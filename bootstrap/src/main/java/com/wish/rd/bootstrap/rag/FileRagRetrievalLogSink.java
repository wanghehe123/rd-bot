package com.wish.rd.bootstrap.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.rag.RagRetrievalLogEvent;
import com.wish.rd.engine.rag.RagRetrievalLogSink;
import com.wish.rd.framework.convention.RetrievedChunk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

/**
 * 将 RAG 检索评测事件追加写入本地 JSONL 文件。
 *
 * <p>每一行是一轮检索事件，便于后续用脚本或评测系统逐行读取。
 */
public final class FileRagRetrievalLogSink implements RagRetrievalLogSink {

    private final ObjectMapper objectMapper;
    private final Path logFile;

    /**
     * 创建文件日志 sink。
     *
     * @param objectMapper Jackson 序列化器
     * @param logFile      JSONL 日志文件路径
     */
    public FileRagRetrievalLogSink(ObjectMapper objectMapper, Path logFile) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.logFile = (logFile == null ? RagRetrievalLogProperties.DEFAULT_PATH : logFile)
                .toAbsolutePath()
                .normalize();
    }

    @Override
    public synchronized void append(RagRetrievalLogEvent event) {
        try {
            Path parent = logFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(logFile, toJsonLine(event), StandardCharsets.UTF_8, CREATE, APPEND);
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to append RAG retrieval log: " + logFile, exception);
        }
    }

    private String toJsonLine(RagRetrievalLogEvent event) throws JsonProcessingException {
        return objectMapper.writeValueAsString(toPayload(event)) + System.lineSeparator();
    }

    private Map<String, Object> toPayload(RagRetrievalLogEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("occurredAt", event.occurredAt().toString());
        payload.put("taskId", event.taskId());
        payload.put("ticketId", event.ticketId());
        payload.put("ticketTitle", event.ticketTitle());
        payload.put("ticketDescription", event.ticketDescription());
        payload.put("ticketLabels", event.ticketLabels());
        payload.put("logs", event.logs());
        payload.put("deepThinking", event.deepThinking());
        payload.put("primaryIntentSystemId", event.primaryIntentSystemId());
        payload.put("primaryIntentName", event.primaryIntentName());
        payload.put("guidanceAction", event.guidanceAction());
        payload.put("guidancePrompt", event.guidancePrompt());
        payload.put("searchChannels", event.searchChannels());
        payload.put("retrievedChunks", event.retrievedChunks().stream()
                .map(FileRagRetrievalLogSink::toChunkPayload)
                .toList());
        payload.put("contextSummary", event.contextSummary());
        return payload;
    }

    private static Map<String, Object> toChunkPayload(RetrievedChunk chunk) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunkId", chunk.chunkId());
        payload.put("content", chunk.content());
        payload.put("knowledgeBaseId", chunk.knowledgeBaseId());
        payload.put("knowledgeType", chunk.knowledgeType());
        payload.put("sourceName", chunk.sourceName());
        payload.put("score", chunk.score());
        payload.put("metadata", chunk.metadata());
        return payload;
    }
}
