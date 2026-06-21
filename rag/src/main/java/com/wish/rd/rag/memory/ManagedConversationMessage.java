package com.wish.rd.rag.memory;

public record ManagedConversationMessage(
        String id,
        String conversationId,
        String userId,
        String role,
        String content,
        String thinkingContent,
        Integer thinkingDuration,
        Integer vote,
        long createTime
) {
}
