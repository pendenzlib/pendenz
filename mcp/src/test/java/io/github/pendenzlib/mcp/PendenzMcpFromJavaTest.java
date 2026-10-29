package io.github.pendenzlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.PayloadCodec;
import io.github.pendenzlib.TaskStatus;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.PendenzJava;
import io.github.pendenzlib.mcp.testing.McpHttpClient;
import io.github.pendenzlib.mcp.testing.ToolAnswer;
import io.github.pendenzlib.schema.IdType;
import io.github.pendenzlib.TaskRecord;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** An MCP server built from Java over the Java facade, with a hand-written schema and codec. */
class PendenzMcpFromJavaTest {

    record Greeting(String name) {}

    /** A codec as a Java team without a serialization library would write one. */
    static final PayloadCodec<Greeting> CODEC = new PayloadCodec<>() {
        private final Pattern name = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"");

        @Override
        public String encode(Greeting payload) {
            return "{\"name\":\"" + payload.name() + "\"}";
        }

        @Override
        public Greeting decode(String text) {
            Matcher matcher = name.matcher(text);
            if (!matcher.find()) {
                throw new IllegalArgumentException("not a greeting: " + text);
            }
            return new Greeting(matcher.group(1));
        }
    };

    static final TaskType<Greeting> GREET = new TaskType<>("greet", CODEC);

    static final String SCHEMA = """
            {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}
            """;

    @Test
    void enqueueTool_builtFromJava_storesTheTaskAsTheAgent() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {}).build();
                PendenzMcp<Long> mcp = PendenzMcp.builder(pendenz.asKotlin(), IdType.BIGINT, "ticket-agent")
                        .tool(GREET, "Greets a customer by name.", SCHEMA)
                        .build();
                McpHttpClient client = new McpHttpClient(mcp)) {

            // Act
            ToolAnswer answer = client.call("greet", "{\"payload\": {\"name\": \"Ada\"}}");

            // Assert
            assertThat(answer.isError()).isFalse();
            TaskRecord<Long> task = pendenz.find(Long.parseLong(answer.text("id"))).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
            assertThat(task.getCreatedBy()).isEqualTo("ticket-agent");
        }
    }

    @Test
    void servlet_originNotAllowed_isRefusedWith403() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {}).build();
                PendenzMcp<Long> mcp = PendenzMcp.builder(pendenz.asKotlin(), IdType.BIGINT, "ticket-agent")
                        .tool(GREET, "Greets a customer by name.", SCHEMA)
                        .build();
                McpHttpClient client =
                        new McpHttpClient(mcp.servlet(McpHttpClient.ENDPOINT, List.of("https://ops.example.com")))) {

            // Act
            int status = client.initializeFrom("https://attacker.example");

            // Assert
            assertThat(status).isEqualTo(403);
        }
    }
}
