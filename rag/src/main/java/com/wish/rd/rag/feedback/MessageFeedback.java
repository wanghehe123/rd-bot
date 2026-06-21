package com.wish.rd.rag.feedback;

public record MessageFeedback(
        String messageId,
        String conversationId,
        String userId,
        int vote,
        String reason,
        String comment,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {
}
