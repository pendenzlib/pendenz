# MCP module: registered task types as tools for agents

- Status: accepted
- Date: 2026-10-23

## Context

Principle 7 says outside callers only use registered task types, and it
names AI agents among them. v0.4 makes that concrete: an agent that
speaks the Model Context Protocol (MCP) should be able to put work into
a Pendenz queue and follow it, without the application writing a bridge
of its own. What the agent gains is what the queue already has: durable
timers, idempotency keys, approval by a person in the business system
(ADR 0018), results that are validated before they count and carry
their provenance (ADR 0020), and an audit that is a `SELECT`.

MCP has three parts that matter here. A server offers **tools**, each
with a name, a description, a JSON Schema for its input and hints such
as "read-only". A client, the agent's host, lists the tools and calls
them with JSON arguments. Between them sits a **transport**: stdio when
the host starts the server as a child process, or Streamable HTTP when
the server is an endpoint in a running application.

Three rules of this repository constrain the module:

- No server process and no framework in the core (README principle 4,
  CLAUDE.md). An MCP server is a server; it cannot live in `core`.
- No reflection in the core, and payload codecs are opaque
  (`PayloadCodec<P>` turns a `P` into text and back). The library
  therefore cannot derive a JSON Schema for a task type by itself.
- Leaving costs nothing. A type of the MCP SDK must not become part of
  Pendenz's public API, or an SDK upgrade breaks our users.

Two SDKs exist for the JVM, both maintained under the
`modelcontextprotocol` organisation. As of October 2026:

| | Java SDK `io.modelcontextprotocol.sdk:mcp-core` | Kotlin SDK `io.modelcontextprotocol:kotlin-sdk-server` |
|---|---|---|
| Version | 2.1.0, past 1.0 | 0.15.0, before 1.0 |
| Runtime dependencies | Reactor, Jackson (2 or 3 via `mcp-json-jackson*`), SLF4J | Ktor server 3.5, kotlinx-serialization, kotlinx-io, kotlin-logging |
| HTTP transport | Servlet provider, plus Spring WebMVC and WebFlux modules | Ktor only |
| API style | sync and async (Reactor) servers | coroutines |

The planning chose the Kotlin SDK when both were younger. The Java SDK
has since reached a stable major version, and its HTTP transport plugs
into any servlet container, which is where most of our users' HTTP
already runs.

## Decision

A new module `mcp`, artifact `pendenz-mcp`. The core does not know it.

### What the application writes

The application decides which task types an agent may use, and says
for each how its arguments look. Nothing is exposed by default:

```kotlin
val server = PendenzMcp.create(pendenz, actor = "ticket-agent") {
    tool(
        escalateTicket,
        description = "Escalates a support ticket to second level. Needs approval above priority 2.",
        inputSchema = JsonSchema.of<EscalateTicket>(),   // from the kotlinx serializer, no reflection
    )
    tool(refund, description = "...", inputSchema = refundSchemaJson, arguments = JacksonCodec.of(Refund::class.java))
}
```

- `inputSchema` is a JSON Schema string, written by hand or produced by
  `JsonSchema.of<P>()`, which walks the kotlinx-serialization
  descriptor of `P`. The descriptor is generated at compile time, so
  this is not reflection; teams on Jackson write the schema or use a
  generator of their choice.
- `arguments` is the `PayloadCodec<P>` that decodes the tool's JSON
  arguments. It defaults to the type's own codec when the type stores
  one JSON document; a type with payload columns (ADR 0013) must name
  one, since its storage is not JSON.
- `actor` is the identity every call of this server acts as. It goes
  into `createdBy` and into every permission check (ADR 0018), so an
  approval rule like "everything the agent created" holds the agent's
  tasks and the agent can never approve them itself.

### What the agent sees

- **One tool per registered type**, named after the type, that enqueues
  a task. Its arguments are the payload plus three optional fields the
  queue understands: `runAt`, `idempotencyKey` and `correlationId`. The
  answer is the task's id, its status (`PENDING`, `SCHEDULED` or
  `AWAITING_APPROVAL`) and its preview, so the agent learns in the same
  call that a person has to look at it. A repeat with the same
  idempotency key returns the existing task, as `enqueue` does. The
  agent's keys live in a namespace of their own, see the correction
  below.
- **`preview_<type>`** for types that have a preview: computes the
  preview from the arguments, writes nothing, marked read-only.
- **`get_task`**: status, attempts, last error, result, provenance and
  preview of one task.
- **`list_tasks`**: the agent's tasks, filtered by type, status or
  correlation id, newest first, bounded.

An agent sees only tasks whose `createdBy` is its actor, and their
follow-ups (ADR 0019) through the correlation id. Other rows do not
exist for it. This needs no new permission action.

Arguments that do not decode, a refused permission and a type the
server does not offer are tool errors with a readable message, the
form MCP defines for errors the model can correct. Nothing is written
in any of those cases.

### What the agent does not get

No approve, decline, restart, complete, quarantine or cancel tools in
this version. Agents create work, people decide about it; that split is
the reason to route agents through the queue at all. The operator
operations of ADR 0018 remain available to a UI.

### SDK and transport

- **The Java SDK**, `mcp-core` with `mcp-json-jackson3`, through its
  synchronous server API. Reactor stays an implementation detail of the
  SDK.
- **No SDK type in our public API.** `PendenzMcp` builds the server
  from our own types; the SDK is an `implementation` dependency, and
  the binary compatibility check shows it.
- **Two transports, neither a process of ours.**
  `server.serveStdio()` blocks the calling thread on standard input and
  output, for an application `main` that an agent host starts.
  `server.servlet(path)` returns a `jakarta.servlet.Servlet` for
  Streamable HTTP, which the application registers in the container it
  already runs. The module never opens a port.
- A Ktor adapter, should someone need one, is a module of its own.

### Tests

An MCP client from the SDK calls the server in the same JVM against
PostgreSQL: enqueue through a tool, approval held, idempotent repeat,
decoding error, permission refused, another actor's task invisible,
result after a worker ran. One test in Java proves the builder is
usable from Java.

## Rejected alternatives

- **The Kotlin SDK**, as the planning had it. It fits the coroutine
  core, but it is before 1.0, and its only HTTP transport is Ktor: a
  Spring or Quarkus application would run a second HTTP server for the
  agent. The servlet transport of the Java SDK goes into the server
  the application already has.
- **Every registered type as a tool automatically.** Breaks principle
  7 the other way round: registering a handler would publish it to
  every agent. Exposure is a separate decision per type.
- **A JSON Schema derived for every type in the core.** Needs either
  reflection or a schema on every `PayloadCodec`, a burden for the many
  users without agents.
- **One generic `enqueue(type, payload)` tool.** The model gets no
  schema per type and guesses arguments; per-type tools are what MCP
  clients present and models use well.
- **A `VIEW` action in `Permissions`** to govern what an agent may
  read. Possible later; "an agent sees what it created" covers the use
  case without touching the core.
- **Operator tools for agents.** See above; an application that wants
  them can write them on its own MCP server with the public API.
- **A server process of our own**, a `pendenz-mcp` executable. It
  would need its own configuration, database access and handler
  registry, which is the application's.

## Consequences

- A new module with the Java SDK, Jackson 3 and Reactor as transitive
  dependencies; the core is untouched.
- Jackson 3 arrives in the classpath of MCP users even if their task
  types use kotlinx-serialization. That is the SDK's choice; the
  alternative SDK brings Ktor instead.
- `list_tasks` needs a query the public API does not have yet: tasks by
  creator, type, status and correlation id, bounded. It goes into the
  store contract of both stores (ADR 0006) and onto `Pendenz` as an
  ordinary query, useful beyond agents.
- `JsonSchema.of<P>()` lives in the MCP module and covers what the
  kotlinx descriptor knows; constraints such as ranges are added by
  hand.
- The example agent of v0.5 starts the demo's MCP server over stdio.

## Decisions taken with the acceptance (2026-10-23)

1. The Java SDK, `mcp-core` with `mcp-json-jackson3`.
2. Exposure is explicit per task type.
3. Schemas are given by the application; `JsonSchema.of<P>()` derives
   one from a kotlinx serializer.
4. An agent reads the tasks it created and their follow-ups; no `VIEW`
   action.
5. One fixed actor per server; a resolver per request may follow.
6. Separate tools: one enqueue tool per type, `preview_<type>`,
   `get_task` and `list_tasks`.
7. No operator tools for agents.

Noted during implementation:

- The enqueue tool takes the payload under `payload`, beside `runAt`,
  `idempotencyKey` and `correlationId`, so a payload field can never
  clash with a field of the queue.
- `serveStdio` returns at once and serves on the SDK's thread until
  `close`, instead of blocking: the application's `main` usually runs a
  worker as well. `servlet` returns a `jakarta.servlet.http.HttpServlet`;
  the servlet API is a compile-only dependency, provided by the
  container.
- Tool arguments arrive as JSON and go through the SDK's schema check
  before our code sees them; a payload that passes the schema but not
  the codec is our own tool error, naming the type.
- Ids travel as text. `IdType.parse` was added to the core for that, and
  `PendenzMcp.create` takes the id type, because `Pendenz` with a store
  of its own does not know it. `PendenzJava.asKotlin()` gives Java users
  the `Pendenz` to build the server on.
- An agent's follow-ups are found by `createdBy` alone: the worker gives
  follow-ups their parent's creator (ADR 0019), so no correlation id is
  needed to see them. `Pendenz.tasks(TaskQuery)` is the query from the
  consequences, on both stores.
- Tests use Jetty 12 as a test dependency to host the servlet, and the
  SDK's own client, so the HTTP path is exercised end to end.

Corrected on review (2026-10-25):

- **Idempotency keys of the agent are its own.** The key went to
  `enqueue` unchanged, and keys are unique per task type, not per
  creator. An agent that sent the key of a task someone else enqueued
  got that task's id, status and preview back, and an agent could
  occupy a key the business process would use later, so that process
  received the agent's task instead of its own. The server now stores
  the agent's key as `mcp:<actor>:<key>`, and answers only with a task
  the actor created.
- **The servlet checks the `Origin` header.** Without a check, a web
  page in a developer's browser could reach a local endpoint through
  DNS rebinding and act as the agent, which the MCP specification for
  Streamable HTTP forbids. `servlet(endpoint, allowedOrigins)` rejects a
  request whose `Origin` is not listed with status 403; the list is
  empty by default, so only clients that send no `Origin`, which agent
  hosts do not, get through. The servlet has no authentication of its
  own: every caller that reaches it acts as the server's actor, so it
  belongs behind the application's security, which the KDoc and
  `docs/agents.md` now say.

Amended on review (2026-10-25): `get_task` handed the agent `lastError`
as stored, which for a failed attempt is the handler's exception text,
with whatever hosts, queries or data it names. By default the agent now
sees only reasons someone gave on purpose, a validator's for `REJECTED`
and an approver's or operator's for `CANCELLED`; `ErrorText` on the
builder chooses otherwise, `ErrorText.EVERYTHING` shows the column as
it is.

