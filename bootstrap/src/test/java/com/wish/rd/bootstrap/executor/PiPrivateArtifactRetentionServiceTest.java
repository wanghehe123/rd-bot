package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.PiPrivateArtifactRetentionService;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.pi.AgentPrivateArtifactIndex;
import com.wish.rd.exec.repair.pi.model.AgentPrivateArtifactRecord;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import com.wish.rd.rag.ingestion.model.StoredIngestionFile;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiPrivateArtifactRetentionServiceTest {

    @Test
    void shouldDeleteExpiredObjectsBeforeRemovingTheirMetadata() {
        AgentPrivateArtifactRecord record = record("expired");
        RecordingIndex index = new RecordingIndex(record);
        RecordingStorage storage = new RecordingStorage();
        PiAgentArtifactArchiveProperties properties = properties();

        new PiPrivateArtifactRetentionService(storage, index, properties).cleanupExpired();

        assertEquals(List.of(record.artifactUri()), storage.deleted);
        assertEquals(List.of(record.artifactId()), index.removed);
    }

    @Test
    void shouldRetainMetadataWhenObjectDeletionFails() {
        AgentPrivateArtifactRecord record = record("failed");
        RecordingIndex index = new RecordingIndex(record);
        RecordingStorage storage = new RecordingStorage();
        storage.failDelete = true;

        new PiPrivateArtifactRetentionService(storage, index, properties()).cleanupExpired();

        assertEquals(List.of(record.artifactUri()), storage.deleted);
        assertEquals(List.of(), index.removed);
    }

    private static PiAgentArtifactArchiveProperties properties() {
        PiAgentArtifactArchiveProperties properties = new PiAgentArtifactArchiveProperties();
        properties.setEnabled(true);
        properties.setRawBucket("raw");
        properties.setSessionBucket("session");
        properties.setRawRetentionMillis(1000L);
        properties.setSessionRetentionMillis(1000L);
        return properties;
    }

    private static AgentPrivateArtifactRecord record(String id) {
        long now = System.currentTimeMillis();
        return new AgentPrivateArtifactRecord(
                id,
                "1",
                "2",
                "snapshot-1",
                RepairArtifactType.PI_SESSION,
                "private/session/" + id,
                "s3://session/" + id,
                10L,
                "sha256:" + "a".repeat(64),
                "application/x-ndjson",
                "SESSION",
                now - 2000L,
                now - 1000L
        );
    }

    private static final class RecordingIndex implements AgentPrivateArtifactIndex {
        private final List<AgentPrivateArtifactRecord> expired;
        private final List<String> removed = new ArrayList<>();

        private RecordingIndex(AgentPrivateArtifactRecord record) {
            expired = List.of(record);
        }

        @Override
        public void record(com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot snapshot,
                           com.wish.rd.exec.repair.execution.model.RepairArtifact artifact) {
        }

        @Override
        public List<AgentPrivateArtifactRecord> findExpired(long nowEpochMillis, int limit) {
            return expired;
        }

        @Override
        public void remove(String artifactId) {
            removed.add(artifactId);
        }
    }

    private static final class RecordingStorage implements ObjectStorageService {
        private final List<String> deleted = new ArrayList<>();
        private boolean failDelete;

        @Override
        public StoredIngestionFile upload(String bucketName, InputStream content, long size,
                                          String originalFilename, String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream openStream(String url) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean delete(String url) {
            deleted.add(url);
            if (failDelete) throw new IllegalStateException("delete failed");
            return true;
        }
    }
}
