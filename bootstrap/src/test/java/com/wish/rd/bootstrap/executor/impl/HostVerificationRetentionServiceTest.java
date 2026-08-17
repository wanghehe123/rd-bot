package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.engine.requirement.verify.impl.InMemoryHostVerificationStore;
import com.wish.rd.engine.requirement.verify.model.HostVerificationArtifact;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.rag.ingestion.impl.InMemoryObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task deletion must drop host-verification objects before store rows.
 */
class HostVerificationRetentionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDeleteS3AndFileObjectsThenArtifactRows() throws Exception {
        InMemoryObjectStorageService objectStorage = new InMemoryObjectStorageService();
        byte[] log = "build-log".getBytes(StandardCharsets.UTF_8);
        String s3Uri = objectStorage.upload(
                "rd-qa-evidence",
                new ByteArrayInputStream(log),
                log.length,
                "build.log",
                "text/plain"
        ).url();
        Path file = tempDir.resolve("static.log");
        Files.writeString(file, "static-log");
        String fileUri = file.toUri().toString();
        assertTrue(fileUri.startsWith("file:"));

        InMemoryHostVerificationStore store = new InMemoryHostVerificationStore();
        store.create(run("8001", "9001"));
        store.create(run("8011", "9002"));
        store.appendArtifact(new HostVerificationArtifact(
                "6001", "9001", "8001", "VERIFY_BUILD_LOG",
                "verify-evidence/build/build.log", s3Uri, "text/plain", log.length, "aaa", 1L));
        store.appendArtifact(new HostVerificationArtifact(
                "6002", "9001", "8001", "VERIFY_STATIC_LOG",
                "verify-evidence/static/static.log", fileUri, "text/plain", 10L, "bbb", 2L));
        store.appendArtifact(new HostVerificationArtifact(
                "6011", "9002", "8011", "VERIFY_BUILD_LOG",
                "verify-evidence/build/other.log",
                "s3://rd-qa-evidence/keep-me",
                "text/plain",
                1L,
                "ccc",
                3L
        ));

        HostVerificationRetentionService service = new HostVerificationRetentionService(store, objectStorage);

        assertEquals(2, service.deleteForTask("9001"));
        assertTrue(store.listArtifacts("8001").isEmpty());
        assertEquals(1, store.listArtifacts("8011").size());
        assertThrows(IllegalArgumentException.class, () -> objectStorage.openStream(s3Uri));
        assertFalse(Files.exists(file));
    }

    private static HostVerificationRun run(String runId, String taskId) {
        return new HostVerificationRun(
                runId,
                taskId,
                "7001",
                "",
                1,
                HostVerificationStatus.SUCCEEDED,
                false,
                "",
                "",
                0,
                1L,
                1L,
                1L
        );
    }
}
