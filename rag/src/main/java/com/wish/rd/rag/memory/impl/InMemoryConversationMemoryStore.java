package com.wish.rd.rag.memory.impl;

import com.wish.rd.rag.memory.ConversationMemoryStore;

import com.wish.rd.framework.convention.model.ChatMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryConversationMemoryStore implements ConversationMemoryStore {

    private final Map<String, List<ChatMessage>> histories = new LinkedHashMap<>();

    @Override
    public synchronized List<ChatMessage> loadHistory(String conversationId, String userId) {
        return List.copyOf(histories.getOrDefault(key(conversationId, userId), List.of()));
    }

    @Override
    public synchronized String append(String conversationId, String userId, ChatMessage message) {
        String key = key(conversationId, userId);
        List<ChatMessage> messages = histories.computeIfAbsent(key, ignored -> new ArrayList<>());
        messages.add(message);
        return conversationId + "#" + messages.size();
    }

    private String key(String conversationId, String userId) {
        return userId + "::" + conversationId;
    }
}
