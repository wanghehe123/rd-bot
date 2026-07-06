package com.wish.rd.rag.feedback.model;

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
