# Examples

Four runnable examples, none of them published. Each runs against an
embedded PostgreSQL unless `PENDENZ_JDBC_URL`, `PENDENZ_DB_USER` and
`PENDENZ_DB_PASSWORD` point it at yours, and each runs in the build as a
test, so CI needs neither a database server nor a model.

## Billing demo

A billing service in miniature: Flyway, HikariCP, a flaky handler, an SQL
restart, retention into an archive table.

```bash
./gradlew :examples:demo:run
```

## Spring shop

A shop on Spring Boot 4 with the starter: an invoice and its mail in one
`@Transactional` method behind a REST endpoint, the handler as a bean,
Flyway for the tables, a retry for one flaky customer. Built with Boot's
Gradle plugin and dependency management, as Spring Initializr sets a
service up. It listens on port 8080:

```bash
./gradlew :examples:spring:bootRun
curl -X POST localhost:8080/invoices -H 'Content-Type: application/json' -d '{"customer":"ACME","amount":99.90}'
curl localhost:8080/invoices/1
```

## Helpdesk

A ticket agent and the helpdesk it works for. The agent starts the
helpdesk as an MCP server over stdio, reads open tickets with SQL and acts
only through tools; escalations wait for a person, and a reply that
mentions a refund is refused, a keyword check standing in for a real
validation. The agent, not the model, decides which ticket a call is about
and sets its idempotency key, since a ticket's text can tell the model
anything.

The agent needs a model with tool calling behind an OpenAI-compatible API,
a local [Ollama](https://ollama.com) by default (`ollama pull qwen3:8b`).
`AGENT_MODEL_URL`, `AGENT_MODEL` and `AGENT_API_KEY` point it at another,
such as LM Studio's server on `http://<host>:1234/v1`, and
`AGENT_MODEL_TIMEOUT` (ISO-8601, `PT5M`) gives a slow reasoning model more
than the default two minutes per answer. Its tests replay prepared model
answers instead.

```bash
./gradlew :examples:helpdesk:run
```

## Catalogue cycle

A cycle that has a language model categorise a whole product catalogue,
one request at a time behind a resource limit. Injected faults cause
retries and refused answers; an operator pauses and resumes the cycle,
repairs broken products and restarts or quarantines their tasks with SQL.

It talks to a model the same way, without tools: `CATALOG_MODEL_URL`,
`CATALOG_MODEL`, `CATALOG_API_KEY` and `CATALOG_MODEL_TIMEOUT`.
`CATALOG_PRODUCTS` sets how many products it generates, 60 by default; at
a local model's pace of one product every fifteen seconds, retries and
restarts included, that is about twenty minutes. Its tests use a model
that knows the catalogue's keywords.

```bash
./gradlew :examples:catalog:run
```
