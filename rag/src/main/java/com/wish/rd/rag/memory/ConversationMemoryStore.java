package com.wish.rd.rag.memory;

import com.wish.rd.framework.convention.model.ChatMessage;

import java.util.List;

public interface ConversationMemoryStore {

    List<ChatMessage> loadHistory(String conversationId, String userId);

    String append(String conversationId, String userId, ChatMessage message);
}
