package com.wish.rd.rag.memory;

import com.wish.rd.framework.convention.ChatMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class DefaultConversationMemoryService implements ConversationMemoryService {

    private final ConversationMemoryStore memoryStore;
    private final Executor memoryLoadExecutor;

    public DefaultConversationMemoryService(ConversationMemoryStore memoryStore, Executor memoryLoadExecutor) {
        this.memoryStore = memoryStore == null ? new InMemoryConversationMemoryStore() : memoryStore;
        this.memoryLoadExecutor = memoryLoadExecutor == null
                ? Executors.newVirtualThreadPerTaskExecutor()
                : memoryLoadExecutor;
    }

    public static DefaultConversationMemoryService inMemory() {
        return new DefaultConversationMemoryService(
                new InMemoryConversationMemoryStore(),
                Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    @Override
    public List<ChatMessage> load(String conversationId, String userId) {
        if (isBlank(conversationId) || isBlank(userId)) {
            return List.of();
        }
        return CompletableFuture.supplyAsync(
                () -> memoryStore.loadHistory(conversationId, userId),
                memoryLoadExecutor
        ).exceptionally(ignored -> List.of()).join();
    }

    @Override
    public String append(String conversationId, String userId, ChatMessage message) {
        if (isBlank(conversationId) || isBlank(userId) || message == null) {
            return "";
        }
        return memoryStore.append(conversationId, userId, message);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
