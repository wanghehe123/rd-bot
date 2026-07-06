package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.OpenAiChatCompletionsRepairExecutor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiChatCompletionsRepairExecutorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldCallOpenAiCompatibleEndpointAndReturnCompleteAgentJson() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        AtomicReference<String> authorization = new AtomicReference<>("");
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, """
                    {
                      "id": "chatcmpl-local",
                      "choices": [
                        {
                          "message": {
                            "content": "{\\"decision\\":\\"APPROVED\\",\\"feasibility\\":\\"CAN_DO\\",\\"missingInformation\\":[],\\"risks\\":[\\"low\\"],\\"acceptanceCoverage\\":[\\"测试通过\\"]}"
                          }
                        }
                      ],
                      "usage": {"prompt_tokens": 12, "completion_tokens": 20, "total_tokens": 32}
                    }
                    """);
        });
        OpenAiChatCompletionsRepairExecutor executor = executor();

        RepairExecutionResult result = executor.execute(command("REQUIREMENT_REVIEWER"));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals("Bearer test-token", authorization.get());
        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals("MiniMax-M3", payload.path("model").asText());
        assertTrue(payload.path("messages").toString().contains("review this requirement"));
        assertEquals("openai-chat-completions", result.dockerMetadataJson().get("protocol"));
        assertEquals("minimax", result.dockerMetadataJson().get("provider"));
        assertEquals("32", result.dockerMetadataJson().get("totalTokens"));

        JsonNode agentResult = OBJECT_MAPPER.readTree(result.rawResultJson().get("__agentResultJson"));
        assertEquals("APPROVED", agentResult.path("decision").asText());
        assertTrue(agentResult.path("missingInformation").isArray());
        assertFalse(result.rawResultJson().containsKey("Authorization"));
    }

    @Test
    void shouldCallAnthropicCompatibleEndpointAndReturnRoleJson() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        AtomicReference<String> anthropicVersion = new AtomicReference<>("");
        startServer("/anthropic/v1/messages", exchange -> {
            anthropicVersion.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, """
                    {
                      "id": "msg-local",
                      "content": [
                        {
                          "type": "text",
                          "text": "{\\"summary\\":\\"方案完成\\",\\"affectedFiles\\":[\\"docs/a.md\\"],\\"implementationSteps\\":[\\"write doc\\"],\\"acceptanceMapping\\":[{\\"criteria\\":\\"doc exists\\",\\"validation\\":\\"test -s docs/a.md\\"}],\\"testPlan\\":[{\\"criteria\\":\\"doc exists\\",\\"command\\":\\"test -s docs/a.md\\"}]}"
                        }
                      ],
                      "usage": {"input_tokens": 14, "output_tokens": 18}
                    }
                    """);
        });
        OpenAiChatCompletionsRepairExecutor executor = new OpenAiChatCompletionsRepairExecutor(
                new OpenAiChatCompletionsRepairExecutor.Configuration(
                        "minimax",
                        "MiniMax-M3",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/anthropic",
                        "MINIMAX_API_KEY",
                        Duration.ofSeconds(5),
                        "anthropic-compatible"
                ),
                HttpClient.newHttpClient(),
                () -> "test-token"
        );

        RepairExecutionResult result = executor.execute(command("SOLUTION_ARCHITECT"));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals("2023-06-01", anthropicVersion.get());
        JsonNode payload = OBJECT_MAPPER.readTree(requestBody.get());
        assertEquals("MiniMax-M3", payload.path("model").asText());
        assertEquals("openai-chat-completions", result.dockerMetadataJson().get("protocol"));
        assertEquals("anthropic-compatible", result.dockerMetadataJson().get("modelProtocol"));
        assertEquals("32", result.dockerMetadataJson().get("totalTokens"));
        JsonNode agentResult = OBJECT_MAPPER.readTree(result.rawResultJson().get("__agentResultJson"));
        assertEquals("方案完成", agentResult.path("summary").asText());
    }


    @Test
    void shouldFailValidationWhenRoleResultSchemaIsInvalid() throws Exception {
        startServer(exchange -> writeJson(exchange, 200, """
                {"choices":[{"message":{"content":"{\\"summary\\":\\"missing role fields\\"}"}}]}
                """));
        OpenAiChatCompletionsRepairExecutor executor = executor();

        RepairExecutionResult result = executor.execute(command("REQUIREMENT_REVIEWER"));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertTrue(result.errorMessage().contains("decision must not be blank"));
    }

    @Test
    void shouldRejectCodingAgentWithoutCallingChatEndpoint() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        startServer(exchange -> {
            callCount.incrementAndGet();
            writeJson(exchange, 200, "{\"choices\":[]}");
        });
        OpenAiChatCompletionsRepairExecutor executor = executor();

        RepairExecutionResult result = executor.execute(command("CODING_AGENT"));

        assertEquals(RepairExecutionStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("does not support repository coding"));
        assertEquals(0, callCount.get());
    }

    private OpenAiChatCompletionsRepairExecutor executor() {
        return new OpenAiChatCompletionsRepairExecutor(
                new OpenAiChatCompletionsRepairExecutor.Configuration(
                        "minimax",
                        "MiniMax-M3",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                        "MINIMAX_API_KEY",
                        Duration.ofSeconds(5)
                ),
                HttpClient.newHttpClient(),
                () -> "test-token"
        );
    }

    private RepairJobCommand command(String role) {
        return new RepairJobCommand(
                "repair-1001",
                "task-1001",
                "",
                "需求评审",
                "review this requirement",
                "https://github.com/acme/order.git",
                "acme",
                "order",
                "main",
                "requirement/task-1001",
                Map.of(
                        "agentRole", role,
                        "roleContextJson", "{\"packageId\":\"ctx-1\"}",
                        "upstreamResultJson", "[]"
                ),
                Map.of("bridge", "engine-requirement-executor")
        );
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        startServer("/v1/chat/completions", handler);
    }

    private void startServer(String path, ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception exception) {
                writeJson(exchange, 500, "{\"error\":\"" + exception.getMessage() + "\"}");
            }
        });
        server.start();
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
