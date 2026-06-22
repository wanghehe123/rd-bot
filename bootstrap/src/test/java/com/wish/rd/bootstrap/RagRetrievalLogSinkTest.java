package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.rag.FileRagRetrievalLogSink;
import com.wish.rd.engine.rag.RagRetrievalLogEvent;
import com.wish.rd.engine.rag.RagRetrievalLogSink;
import com.wish.rd.framework.convention.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRetrievalLogSinkTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void appendsRetrievedChunksAsJsonLines() throws Exception {
        Path logFile = tempDir.resolve("rag-eval").resolve("rag-retrieval.jsonl");
        FileRagRetrievalLogSink sink = new FileRagRetrievalLogSink(OBJECT_MAPPER, logFile);

        sink.append(new RagRetrievalLogEvent(
                Instant.parse("2026-06-22T00:00:00Z"),
                "task-1",
                "ticket-1",
                "支付系统下单接口 500",
                "金额为空时 OrderService.create 写入订单失败",
                List.of("payment"),
                List.of("ERROR orders.amount is null at OrderService.create"),
                false,
                "payment-system",
                "支付系统",
                "ANSWER",
                "已命中支付系统知识",
                List.of("IntentDirectedVectorSearch"),
                List.of(new RetrievedChunk(
                        "chunk-1",
                        "OrderService.create should validate amount before repository.save",
                        "payment-system",
                        "code-snippet",
                        "OrderService.java",
                        9.5d,
                        Map.of("documentId", "doc-1", "chunkIndex", "7")
                )),
                "OrderService.create 缺少 amount 校验"
        ));

        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size());
        JsonNode root = OBJECT_MAPPER.readTree(lines.getFirst());
        assertEquals("2026-06-22T00:00:00Z", root.path("occurredAt").asText());
        assertEquals("task-1", root.path("taskId").asText());
        assertEquals("ticket-1", root.path("ticketId").asText());
        assertEquals("ERROR orders.amount is null at OrderService.create", root.path("logs").get(0).asText());
        assertEquals("IntentDirectedVectorSearch", root.path("searchChannels").get(0).asText());
        assertEquals("chunk-1", root.path("retrievedChunks").get(0).path("chunkId").asText());
        assertEquals(
                "OrderService.create should validate amount before repository.save",
                root.path("retrievedChunks").get(0).path("content").asText()
        );
        assertEquals("doc-1", root.path("retrievedChunks").get(0).path("metadata").path("documentId").asText());
    }

    @Test
    void registersFileSinkOnlyWhenEnabled() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class,
                        JacksonAutoConfiguration.class
                ))
                .withUserConfiguration(com.wish.rd.bootstrap.rag.RagRetrievalLogConfiguration.class);

        runner.run(context -> assertEquals(0, context.getBeanNamesForType(RagRetrievalLogSink.class).length));
        runner.withPropertyValues(
                        "rd.rag.retrieval-log.enabled=true",
                        "rd.rag.retrieval-log.path=" + tempDir.resolve("retrieval.jsonl")
                )
                .run(context -> {
                    assertEquals(1, context.getBeanNamesForType(RagRetrievalLogSink.class).length);
                    assertTrue(context.getBean(RagRetrievalLogSink.class) instanceof FileRagRetrievalLogSink);
                });
    }
}
