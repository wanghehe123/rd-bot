package com.wish.rd.rag.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagStreamTaskRegistryTest {

    @Test
    void tracksRunningCompletedCancelledAndRejectedTasks() {
        RagStreamTaskRegistry registry = RagStreamTaskRegistry.inMemory();

        RagStreamTask running = registry.registerRunning("task-a", "conversation-a");

        assertEquals("RUNNING", running.status());
        assertEquals("conversation-a", running.conversationId());

        RagStreamTask completed = registry.complete("task-a", "message-a", "title-a");

        assertEquals("DONE", completed.status());
        assertEquals("message-a", completed.messageId());
        assertEquals("title-a", completed.title());

        RagStreamTask cancelled = registry.cancel("task-b");

        assertEquals("CANCELLED", cancelled.status());
        assertEquals("task-b", registry.get("task-b").taskId());

        RagStreamTask rejected = registry.reject("task-c", "conversation-c", "message-c", "系统繁忙，请稍后再试");

        assertEquals("REJECTED", rejected.status());
        assertEquals("message-c", rejected.messageId());
        assertEquals("系统繁忙，请稍后再试", rejected.errorMessage());
    }
}
