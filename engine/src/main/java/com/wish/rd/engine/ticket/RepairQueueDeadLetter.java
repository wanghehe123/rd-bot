package com.wish.rd.engine.ticket;

import java.util.Map;

/**
 * 修复队列死信记录，保存超过重试上限后需要人工恢复的消息。
 *
 * @param id                   死信 ID
 * @param ticketId             工单 ID
 * @param traceId              链路 ID
 * @param source               消息来源
 * @param eventId              外部事件 ID
 * @param eventType            外部事件类型
 * @param originalAttempt      原始消费次数
 * @param reason               死信原因
 * @param messageJson          原始消息字段
 * @param replayed             是否已重投
 * @param createdAtEpochMillis 创建时间
 * @param replayedAtEpochMillis 重投时间
 */
public record RepairQueueDeadLetter(
        String id,
        String ticketId,
        String traceId,
        String source,
        String eventId,
        String eventType,
        int originalAttempt,
        String reason,
        Map<String, String> messageJson,
        boolean replayed,
        long createdAtEpochMillis,
        long replayedAtEpochMillis
) {

    public RepairQueueDeadLetter {
        id = normalize(id);
        ticketId = normalize(ticketId);
        traceId = normalize(traceId);
        source = normalize(source);
        eventId = normalize(eventId);
        eventType = normalize(eventType);
        originalAttempt = Math.max(0, originalAttempt);
        reason = normalize(reason);
        messageJson = messageJson == null ? Map.of() : Map.copyOf(messageJson);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
        replayedAtEpochMillis = Math.max(0L, replayedAtEpochMillis);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
