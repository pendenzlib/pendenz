# Documentation

| Page | What it answers |
|---|---|
| [`guide.md`](guide.md) | How do I declare a task type, create the table, run tasks, handle failures and the stored error text, set worker options, wrap handlers, name the caller, enqueue in my transaction, use idempotency keys and retention, version payloads, read and steer tasks from code, and remove Pendenz again? |
| [`concepts.md`](concepts.md) | How does it work: status model, claiming, cycles, chaining, recurring tasks, results, approval and permissions, resource limits and rates, decisions a handler reports, fairness, replaceable parts, agents, shutdown? |
| [`sql-recipes.md`](sql-recipes.md) | How do I enqueue, restart, complete, cancel, postpone and inspect tasks with SQL, steer cycles, limits, rates, blocks and schedules, and use the generated views and functions? |
| [`java.md`](java.md) | How does it read from Java, and without kotlinx-serialization? |
| [`spring.md`](spring.md) | How do I use it with Spring Boot or Spring? |
| [`metrics.md`](metrics.md) | How do I observe it, with or without Micrometer? |
| [`agents.md`](agents.md) | How does an AI agent use it through MCP? |
| [`examples.md`](examples.md) | How do I run the examples? |
| [`development.md`](development.md) | How do I build and test it, and how are library versions chosen? |
| [`adr/`](adr) | Why is it built the way it is? |

The API reference is generated from the KDoc with `./gradlew dokkaGenerate`.
