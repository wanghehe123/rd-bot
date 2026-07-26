package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.bootstrap.executor.PiAgentArtifactArchiveProperties;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.pi.AgentPrivateArtifactIndex;
import com.wish.rd.exec.repair.pi.AgentPrivateArtifactPublisher;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import com.wish.rd.rag.ingestion.model.StoredIngestionFile;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Stores Pi raw events and session files in separate private S3/RustFS buckets.
 * It is deliberately opt-in because retention and access values are operator decisions.
 */
@Component
@ConditionalOnProperty(
        name = "rd.executor.pi.artifact-archive.enabled",
        havingValue = "true"
)
public final class ObjectStoragePiArtifactPublisher implements AgentPrivateArtifactPublisher {

    private static final String RAW_FILE = "private/pi-raw-events.jsonl";
    private static final String SESSION_DIRECTORY = "private/session";

    private final ObjectStorageService objectStorageService;
    private final PiAgentArtifactArchiveProperties properties;
    private final AgentPrivateArtifactIndex privateArtifactIndex;

    public ObjectStoragePiArtifactPublisher(
            ObjectStorageService objectStorageService,
            PiAgentArtifactArchiveProperties properties
    ) {
        this(objectStorageService, properties, AgentPrivateArtifactIndex.noop());
    }

    @Autowired
    public ObjectStoragePiArtifactPublisher(
            ObjectStorageService objectStorageService,
            PiAgentArtifactArchiveProperties properties,
            AgentPrivateArtifactIndex privateArtifactIndex
    ) {
        this.objectStorageService = Objects.requireNonNull(
                objectStorageService, "objectStorageService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.privateArtifactIndex = Objects.requireNonNull(
                privateArtifactIndex, "privateArtifactIndex must not be null");
        properties.validateForPublishing();
    }

    @Override
    public List<RepairArtifact> publish(
            RepairJobCommand command,
            AgentExecutionProfileSnapshot snapshot,
            RepairWorkspace workspace
    ) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(workspace, "workspace must not be null");
        if (!properties.isEnabled()) {
            return List.of();
        }
        properties.validateForPublishing();
        Path output = requireOutputDirectory(workspace.outputDirectory());
        List<RepairArtifact> artifacts = new ArrayList<>();
        Path raw = output.resolve(RAW_FILE).normalize();
        if (raw.startsWith(output) && Files.exists(raw, LinkOption.NOFOLLOW_LINKS)) {
            RepairArtifact artifact = uploadFile(
                    raw,
                    RAW_FILE,
                    RepairArtifactType.PI_RAW_EVENTS,
                    properties.getRawBucket(),
                    properties.getRawRetentionMillis(),
                    "RAW_EVENTS",
                    snapshot
            );
            privateArtifactIndex.record(snapshot, artifact);
            artifacts.add(artifact);
        }

        Path sessionRoot = output.resolve(SESSION_DIRECTORY).normalize();
        if (sessionRoot.startsWith(output) && Files.isDirectory(sessionRoot, LinkOption.NOFOLLOW_LINKS)) {
            List<Path> sessionFiles;
            try (Stream<Path> paths = Files.walk(sessionRoot)) {
                sessionFiles = paths
                        .peek(path -> rejectSymbolicLink(path, "Pi session archive"))
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .sorted(Comparator.comparing(path -> sessionRoot.relativize(path).toString()))
                        .toList();
            }
            if (sessionFiles.size() > properties.getMaxSessionFiles()) {
                throw new IOException("Pi session archive exceeds the configured file count limit");
            }
            for (Path file : sessionFiles) {
                String relative = SESSION_DIRECTORY + "/" + sessionRoot.relativize(file)
                        .toString().replace('\\', '/');
                RepairArtifact artifact = uploadFile(
                        file,
                        relative,
                        RepairArtifactType.PI_SESSION,
                        properties.getSessionBucket(),
                        properties.getSessionRetentionMillis(),
                        "SESSION",
                        snapshot
                );
                privateArtifactIndex.record(snapshot, artifact);
                artifacts.add(artifact);
            }
        }
        return List.copyOf(artifacts);
    }

    private RepairArtifact uploadFile(
            Path path,
            String artifactName,
            RepairArtifactType type,
            String bucket,
            long retentionMillis,
            String retentionClass,
            AgentExecutionProfileSnapshot snapshot
    ) throws IOException {
        rejectSymbolicLink(path, "Pi private artifact");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Pi private artifact is not a regular file: " + artifactName);
        }
        long size = Files.size(path);
        long maxBytes = type == RepairArtifactType.PI_RAW_EVENTS
                ? properties.getMaxRawBytes()
                : properties.getMaxSessionFileBytes();
        if (size <= 0L || size > maxBytes) {
            throw new IOException("Pi private artifact exceeds the configured size limit: " + artifactName);
        }
        String sha256 = sha256(path);
        StoredIngestionFile stored;
        try (InputStream input = Files.newInputStream(path)) {
            stored = objectStorageService.upload(
                    bucket,
                    input,
                    size,
                    artifactName,
                    contentType(artifactName)
            );
        }
        if (stored == null
                || stored.size() != size
                || stored.url() == null
                || !stored.url().startsWith("s3://")) {
            throw new IOException("Pi private artifact upload returned an invalid object reference");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("taskId", snapshot.taskId());
        metadata.put("stageRunId", snapshot.stageRunId());
        metadata.put("snapshotId", snapshot.snapshotId());
        metadata.put("snapshotHash", snapshot.snapshotHash());
        metadata.put("role", snapshot.role());
        metadata.put("artifactName", artifactName);
        metadata.put("bytes", String.valueOf(size));
        metadata.put("sha256", sha256);
        metadata.put("contentType", contentType(artifactName));
        metadata.put("restricted", "true");
        metadata.put("retentionClass", retentionClass);
        metadata.put("retentionMillis", String.valueOf(retentionMillis));
        metadata.put("expiresAtEpochMillis", String.valueOf(expiry(System.currentTimeMillis(), retentionMillis)));
        return new RepairArtifact(
                type,
                artifactName,
                stored.url(),
                "Restricted Pi " + retentionClass.toLowerCase() + " archive",
                Map.copyOf(metadata)
        );
    }

    private static Path requireOutputDirectory(Path outputDirectory) throws IOException {
        if (outputDirectory == null || !Files.isDirectory(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Pi output directory is missing");
        }
        rejectSymbolicLink(outputDirectory, "Pi output directory");
        return outputDirectory.toAbsolutePath().normalize();
    }

    private static void rejectSymbolicLink(Path path, String description) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalStateException(description + " must not be a symbolic link: " + path);
        }
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static long expiry(long now, long retentionMillis) {
        try {
            return Math.addExact(now, retentionMillis);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static String contentType(String name) {
        return name.endsWith(".jsonl") ? "application/x-ndjson" : "application/octet-stream";
    }
}
