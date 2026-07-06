package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagBugFixEnginePolicyTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void ragV3ChatEngineIsReplacedByBugFixEngine() {
        Path engineRoot = PROJECT_ROOT.resolve("engine/src/main/java/com/wish/rd/engine/rag");

        assertTrue(Files.exists(engineRoot.resolve("RagBugFixEngine.java")), "RagBugFixEngine must own bug-fix RAG flow");
        assertTrue(Files.exists(engineRoot.resolve("model/BugFixMessage.java")), "BugFixMessage must package retrieved context for agent");
        assertFalse(Files.exists(engineRoot.resolve("RagV3ChatEngine.java")), "old RagV3ChatEngine must be removed");
        assertFalse(Files.exists(engineRoot.resolve("RagV3ChatStreamResult.java")), "old RagV3 stream result must be removed");
        assertFalse(Files.exists(engineRoot.resolve("RagV3StopResult.java")), "old RagV3 stop result must be removed");
    }

    @Test
    void bugFixRagEngineOnlyBuildsTaskScopedRagResult() throws Exception {
        Path engineRoot = PROJECT_ROOT.resolve("engine/src/main/java/com/wish/rd/engine/rag");
        String engine = Files.readString(engineRoot.resolve("RagBugFixEngine.java"));
        String message = Files.readString(engineRoot.resolve("model/BugFixMessage.java"));
        String limiter = Files.readString(engineRoot.resolve("ChatQueueLimiter.java"));

        assertFalse(engine.contains("ConversationMemoryService"), "bug-fix RAG must not depend on conversation memory");
        assertFalse(engine.contains("DefaultConversationMemoryService"), "bug-fix RAG must not create conversation memory");
        assertFalse(engine.contains("memoryService"), "bug-fix RAG must not load or append conversation messages");
        assertFalse(engine.contains("BugFixAgentEngine"), "bug-fix RAG must return results to upstream orchestration");
        assertFalse(engine.contains("submitToAgent"), "bug-fix RAG must not submit to agent by itself");
        assertFalse(message.contains("conversationId"), "bug-fix result is task scoped, not conversation scoped");
        assertFalse(limiter.contains("conversationId"), "bug-fix queue request only needs taskId");
    }
}
