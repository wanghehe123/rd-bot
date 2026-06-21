package com.wish.rd.rag.memory;

public record ManagedConversation(
        String conversationId,
        String userId,
        String title,
        long lastTime
) {
}
