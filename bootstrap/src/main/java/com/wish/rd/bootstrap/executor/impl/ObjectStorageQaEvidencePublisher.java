package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import com.wish.rd.rag.ingestion.model.StoredIngestionFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Moves browser and command evidence out of the executor workspace into private object storage.
 */
@Component
public final class ObjectStorageQaEvidencePublisher {

    static final String EVIDENCE_BUCKET = "rd-qa-evidence";
    private static final Set<RepairArtifactType> QA_EVIDENCE_TYPES = Set.of(
            RepairArtifactType.QA_COMMAND_LOG,
            RepairArtifactType.QA_SCREENSHOT,
            RepairArtifactType.QA_TRACE,
            RepairArtifactType.QA_CONSOLE_LOG,
            RepairArtifactType.QA_NETWORK_LOG,
            RepairArtifactType.QA_HTTP_TRANSCRIPT,
            RepairArtifactType.QA_VIDEO,
            RepairArtifactType.QA_EVIDENCE_MANIFEST
    );

    private final ObjectStorageService objectStorageService;

    public ObjectStorageQaEvidencePublisher(ObjectStorageService objectStorageService) {
        this.objectStorageService = Objects.requireNonNull(
                objectStorageService, "objectStorageService must not be null");
    }

    /**
     * Uploads every QA evidence artifact and returns a result whose evidence URIs are private object URIs.
     */
    public RepairExecutionResult publish(RepairExecutionResult result) {
        Objects.requireNonNull(result, "result must not be null");
        List<RepairArtifact> publishedArtifacts = new ArrayList<>(result.artifacts().size());
        for (RepairArtifact artifact : result.artifacts()) {
            publishedArtifacts.add(isQaEvidence(artifact) ? publishArtifact(artifact) : artifact);
        }
        return new RepairExecutionResult(
                result.status(),
                result.summary(),
                result.pullRequestUrl(),
                publishedArtifacts,
                result.rawResultJson(),
                result.dockerMetadataJson(),
                result.githubMetadataJson(),
                result.testMetadataJson(),
                result.riskMetadataJson(),
                result.errorMessage()
        );
    }

    private RepairArtifact publishArtifact(RepairArtifact artifact) {
        Path path = localArtifactPath(artifact);
        try {
            long size = Files.size(path);
            validateIntegrity(artifact, path, size);
            String contentType = artifact.metadataJson().getOrDefault(
                    "contentType", "application/octet-stream");
            StoredIngestionFile stored;
            try (InputStream input = Files.newInputStream(path)) {
                stored = objectStorageService.upload(
                        EVIDENCE_BUCKET,
                        input,
                        size,
                        artifact.name(),
                        contentType
                );
            }
            if (stored.size() != size) {
                throw new IllegalStateException("QA evidence upload size mismatch: " + artifact.name());
            }
            return new RepairArtifact(
                    artifact.type(),
                    artifact.name(),
                    stored.url(),
                    artifact.summary(),
                    artifact.metadataJson()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("failed to persist QA evidence: " + artifact.name(), exception);
        }
    }

    private static Path localArtifactPath(RepairArtifact artifact) {
        URI uri;
        try {
            uri = URI.create(artifact.uri());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("invalid QA evidence URI: " + artifact.name(), exception);
        }
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("QA evidence must originate from a local executor file: " + artifact.name());
        }
        Path path = Path.of(uri).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("QA evidence file is missing or not regular: " + artifact.name());
        }
        return path;
    }

    private static void validateIntegrity(RepairArtifact artifact, Path path, long actualSize) throws IOException {
        String expectedBytes = artifact.metadataJson().getOrDefault("bytes", "").strip();
        if (expectedBytes.isBlank() || Long.parseLong(expectedBytes) != actualSize || actualSize <= 0L) {
            throw new IllegalStateException("QA evidence size metadata mismatch: " + artifact.name());
        }
        String expectedSha256 = artifact.metadataJson().getOrDefault("sha256", "").strip();
        if (expectedSha256.startsWith("sha256:")) {
            expectedSha256 = expectedSha256.substring("sha256:".length());
        }
        String actualSha256 = sha256(path);
        if (expectedSha256.isBlank() || !actualSha256.equalsIgnoreCase(expectedSha256)) {
            throw new IllegalStateException("QA evidence sha256 metadata mismatch: " + artifact.name());
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
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean isQaEvidence(RepairArtifact artifact) {
        return artifact != null && QA_EVIDENCE_TYPES.contains(artifact.type());
    }
}
