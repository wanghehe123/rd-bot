package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagBugFixRateLimitPolicyTest {

    @Test
    void ragBugFixEngineUsesChatQueueLimiterInsteadOfLocalAtomicCounter() throws Exception {
        Path engine = Path.of("..", "engine", "src", "main", "java", "com", "wish", "rd", "engine", "rag", "RagBugFixEngine.java");
        String source = Files.readString(engine);

        assertTrue(source.contains("ChatQueueLimiter"));
        assertTrue(source.contains("chatQueueLimiter.enqueue"));
        assertFalse(source.contains("AtomicInteger"));
        assertFalse(source.contains("activeChats"));
        assertFalse(source.contains("releaseChatSlot"));
        assertFalse(source.contains("tryAcquireChatSlot"));
    }
}
