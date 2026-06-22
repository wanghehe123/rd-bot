package com.wish.rd.engine.rag;

import com.wish.rd.framework.convention.RetrievedChunk;

import java.time.Instant;
import java.util.List;

/**
 * 一次 Bug 修复 RAG 检索的可评测日志事件。
 *
 * <p>由 {@link RagBugFixEngine} 在完成检索和上下文打包后生成，外部 sink 可将其写入
 * JSONL、对象存储或评测平台。
 *
 * @param occurredAt            事件发生时间
 * @param taskId                修复任务 ID
 * @param ticketId              工单 ID
 * @param ticketTitle           工单标题
 * @param ticketDescription     工单描述
 * @param ticketLabels          工单标签
 * @param logs                  输入日志
 * @param deepThinking          是否启用深度思考
 * @param primaryIntentSystemId 主意图系统 ID
 * @param primaryIntentName     主意图名称
 * @param guidanceAction        歧义引导动作
 * @param guidancePrompt        歧义引导提示
 * @param searchChannels        命中的检索通道
 * @param retrievedChunks       完整检索证据块
 * @param contextSummary        RAG 上下文摘要
 */
public record RagRetrievalLogEvent(
        Instant occurredAt,
        String taskId,
        String ticketId,
        String ticketTitle,
        String ticketDescription,
        List<String> ticketLabels,
        List<String> logs,
        boolean deepThinking,
        String primaryIntentSystemId,
        String primaryIntentName,
        String guidanceAction,
        String guidancePrompt,
        List<String> searchChannels,
        List<RetrievedChunk> retrievedChunks,
        String contextSummary
) {

    public RagRetrievalLogEvent {
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        taskId = safe(taskId);
        ticketId = safe(ticketId);
        ticketTitle = safe(ticketTitle);
        ticketDescription = safe(ticketDescription);
        ticketLabels = ticketLabels == null ? List.of() : List.copyOf(ticketLabels);
        logs = logs == null ? List.of() : List.copyOf(logs);
        primaryIntentSystemId = safe(primaryIntentSystemId);
        primaryIntentName = safe(primaryIntentName);
        guidanceAction = safe(guidanceAction);
        guidancePrompt = safe(guidancePrompt);
        searchChannels = searchChannels == null ? List.of() : List.copyOf(searchChannels);
        retrievedChunks = retrievedChunks == null ? List.of() : List.copyOf(retrievedChunks);
        contextSummary = safe(contextSummary);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
