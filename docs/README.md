# Documentation

| Page | What it answers |
|---|---|
| [`guide.md`](guide.md) | How do I declare a task type, create the table, enqueue and run tasks? |
| [`concepts.md`](concepts.md) | How does it work: status model, claiming, cycles, chaining, recurring tasks, results, approval, limits, fairness, shutdown? |
| [`sql-recipes.md`](sql-recipes.md) | How do I restart, complete, cancel, quarantine and inspect tasks with SQL? |
| [`java.md`](java.md) | How does it read from Java, and without kotlinx-serialization? |
| [`spring.md`](spring.md) | How do I use it with Spring Boot or Spring? |
| [`metrics.md`](metrics.md) | How do I observe it, with or without Micrometer? |
| [`agents.md`](agents.md) | How does an AI agent use it through MCP? |
| [`examples.md`](examples.md) | How do I run the examples? |
| [`development.md`](development.md) | How do I build and test it, and how are library versions chosen? |
| [`adr/`](adr) | Why is it built the way it is? |

The API reference is generated from the KDoc with `./gradlew dokkaGenerate`.
