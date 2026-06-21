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
        assertTrue(Files.exists(engineRoot.resolve("BugFixMessage.java")), "BugFixMessage must package retrieved context for agent");
        assertFalse(Files.exists(engineRoot.resolve("RagV3ChatEngine.java")), "old RagV3ChatEngine must be removed");
        assertFalse(Files.exists(engineRoot.resolve("RagV3ChatStreamResult.java")), "old RagV3 stream result must be removed");
        assertFalse(Files.exists(engineRoot.resolve("RagV3StopResult.java")), "old RagV3 stop result must be removed");
    }
}
