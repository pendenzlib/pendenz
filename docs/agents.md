# Agents

An AI agent uses the queue through MCP, with `pendenz-mcp`. The
application names the task types an agent may use; each becomes a tool
that enqueues, beside `preview_<type>`, `get_task` and `list_tasks`.

```kotlin
val mcp = PendenzMcp.create(pendenz, IdType.BIGINT, actor = "ticket-agent") {
    tool(escalateTicket, "Escalates a ticket to second level.", JsonSchema.of<Escalation>())
}
mcp.serveStdio()                         // an agent host starts this application
servletContext.addServlet("mcp", mcp.servlet("/mcp")).addMapping("/mcp")   // or Streamable HTTP
```

- Every call acts as one actor. An approval rule on the task type can
  hold what the agent creates, and the agent can never approve it: a
  task's creator may not approve it.
- The agent sees only its own tasks.
- What a tool may do is what the task type allows; the handler, written
  by the developer, decides what happens. Pendenz has no model client and
  no agent framework (principle 7, ADR 0021).
- The server opens no port of its own: stdio for an agent host that
  starts the application, a servlet for Streamable HTTP in the
  application's own server.

[`examples/helpdesk`](../examples/helpdesk) is a ticket agent that works
for a helpdesk this way, see [`examples.md`](examples.md#helpdesk).
