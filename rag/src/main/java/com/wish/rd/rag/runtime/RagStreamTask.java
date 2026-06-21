package com.wish.rd.rag.runtime;

public record RagStreamTask(
        String taskId,
        String conversationId,
        String status,
        String messageId,
        String title,
        String errorMessage,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {

    public RagStreamTask {
        taskId = safe(taskId);
        conversationId = safe(conversationId);
        status = safe(status);
        messageId = safe(messageId);
        title = safe(title);
        errorMessage = safe(errorMessage);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
