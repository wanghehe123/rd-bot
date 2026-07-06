package com.wish.rd.rag.memory.model;

public record ManagedConversation(
        String conversationId,
        String userId,
        String title,
        long lastTime
) {
}
