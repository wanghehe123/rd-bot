package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.ObjectStoragePiArtifactPublisher;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.docker.model.RepairWorkspaceFiles;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import com.wish.rd.rag.ingestion.model.StoredIngestionFile;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectStoragePiArtifactPublisherTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldArchiveRawEventsAndSessionFilesAsRestrictedObjects() throws Exception {
        Path output = temporaryDirectory.resolve("output");
        Path privateRoot = output.resolve("private");
        Path session = privateRoot.resolve("session");
        Files.createDirectories(session);
        Files.writeString(privateRoot.resolve("pi-raw-events.jsonl"), "raw-event\n", StandardCharsets.UTF_8);
        Files.writeString(session.resolve("session-1.jsonl"), "session-event\n", StandardCharsets.UTF_8);
        RepairWorkspace workspace = workspace(output);
        RecordingObjectStorage storage = new RecordingObjectStorage();
        PiAgentArtifactArchiveProperties properties = new PiAgentArtifactArchiveProperties();
        properties.setEnabled(true);
        properties.setRawBucket("rd-pi-raw-test");
        properties.setSessionBucket("rd-pi-session-test");
        properties.setRawRetentionMillis(60_000L);
        properties.setSessionRetentionMillis(120_000L);

        List<RepairArtifact> artifacts = new ObjectStoragePiArtifactPublisher(storage, properties)
                .publish(command(), snapshot(), workspace);

        assertEquals(2, artifacts.size());
        assertTrue(artifacts.stream().anyMatch(artifact ->
                artifact.type() == RepairArtifactType.PI_RAW_EVENTS
                        && "true".equals(artifact.metadataJson().get("restricted"))
                        && "RAW_EVENTS".equals(artifact.metadataJson().get("retentionClass"))));
        assertTrue(artifacts.stream().anyMatch(artifact ->
                artifact.type() == RepairArtifactType.PI_SESSION
                        && artifact.name().endsWith("session-1.jsonl")
                        && "SESSION".equals(artifact.metadataJson().get("retentionClass"))));
        assertEquals(List.of("rd-pi-raw-test", "rd-pi-session-test"), storage.buckets());
    }

    @Test
    void shouldReturnNoArtifactsWhenPrivateArchiveIsNotEnabled() throws Exception {
        Path output = temporaryDirectory.resolve("output");
        Files.createDirectories(output.resolve("private/session"));
        Files.writeString(output.resolve("private/pi-raw-events.jsonl"), "raw-event\n", StandardCharsets.UTF_8);

        PiAgentArtifactArchiveProperties properties = new PiAgentArtifactArchiveProperties();
        List<RepairArtifact> artifacts = new ObjectStoragePiArtifactPublisher(
                new RecordingObjectStorage(), properties
        ).publish(command(), snapshot(), workspace(output));

        assertTrue(artifacts.isEmpty());
    }

    private RepairWorkspace workspace(Path output) {
        return new RepairWorkspace(
                temporaryDirectory,
                temporaryDirectory.resolve("input"),
                temporaryDirectory.resolve("repo"),
                output,
                temporaryDirectory.resolve("cache"),
                new RepairWorkspaceFiles(
                        temporaryDirectory.resolve("prompt"),
                        temporaryDirectory.resolve("context"),
                        temporaryDirectory.resolve("schema"),
                        output.resolve("result.json"),
                        output.resolve("patch.diff"),
                        output.resolve("test.log"),
                        output.resolve("events"),
                        output.resolve("docker-meta")
                )
        );
    }

    private static RepairJobCommand command() {
        return new RepairJobCommand(
                "repair-task-1", "task-1", "ticket-1", "Pi archive", "prompt",
                "https://github.com/acme/repo.git", "acme", "repo", "main", "repair/task-1",
                Map.of("agentRole", "CODING_AGENT"), Map.of(), List.of()
        );
    }

    private static AgentExecutionProfileSnapshot snapshot() {
        String json = "{\"runtimeType\":\"PI\"}";
        return new AgentExecutionProfileSnapshot(
                "snapshot-1", "stage-1", "task-1", "CODING_AGENT", 1, AgentRuntimeType.PI,
                json, AgentExecutionProfileSnapshot.sha256(json), 1L
        );
    }

    private static final class RecordingObjectStorage implements ObjectStorageService {
        private final List<String> buckets = new ArrayList<>();

        @Override
        public StoredIngestionFile upload(
                String bucketName,
                InputStream content,
                long size,
                String originalFilename,
                String contentType
        ) {
            try {
                content.readAllBytes();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            buckets.add(bucketName);
            return new StoredIngestionFile(
                    "s3://" + bucketName + "/" + originalFilename,
                    contentType,
                    size,
                    originalFilename
            );
        }

        @Override
        public InputStream openStream(String url) {
            throw new UnsupportedOperationException();
        }

        private List<String> buckets() {
            return List.copyOf(buckets);
        }
    }
}
