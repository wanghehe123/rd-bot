package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.engine.agent.model.AgentRole;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Moves a bounded, direct-downstream Markdown handoff into private RustFS storage. */
@Component
public final class ObjectStorageRoleHandoffPublisher {

    static final String HANDOFF_BUCKET = "rd-role-handoffs";
    static final String HANDOFF_NAME = "handoff/next.md";
    static final String CANDIDATE_PATCH_NAME = "patch.diff";
    private static final long MAX_CANDIDATE_PATCH_BYTES = 10L * 1024L * 1024L;

    private final ObjectStorageService objectStorageService;
    private final RoleHandoffProperties properties;

    public ObjectStorageRoleHandoffPublisher(
            ObjectStorageService objectStorageService,
            RoleHandoffProperties properties
    ) {
        this.objectStorageService = Objects.requireNonNull(
                objectStorageService, "objectStorageService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /** Exposes the configured approximate token budget to the isolated workspace context. */
    public int maxTokens() {
        return properties.getMaxTokens();
    }

    /** Publishes only non-QA handoffs; QA has no direct downstream document contract. */
    public RepairExecutionResult publish(AgentRole role, RepairExecutionResult result) {
        Objects.requireNonNull(result, "result must not be null");
        if (role == null || role == AgentRole.QA_AGENT) {
            return result;
        }
        List<RepairArtifact> published = new ArrayList<>(result.artifacts().size());
        int handoffCount = 0;
        for (RepairArtifact artifact : result.artifacts()) {
            if (role == AgentRole.CODING_AGENT && artifact.type() == RepairArtifactType.PATCH_DIFF) {
                published.add(publishCandidatePatch(artifact));
                continue;
            }
            if (artifact.type() != RepairArtifactType.HANDOFF_MARKDOWN) {
                published.add(artifact);
                continue;
            }
            handoffCount++;
            if (handoffCount > 1) {
                throw new IllegalStateException("a role may produce only one Markdown handoff");
            }
            published.add(publishArtifact(artifact));
        }
        return new RepairExecutionResult(
                result.status(),
                result.summary(),
                result.pullRequestUrl(),
                published,
                result.rawResultJson(),
                result.dockerMetadataJson(),
                result.githubMetadataJson(),
                result.testMetadataJson(),
                result.riskMetadataJson(),
                result.errorMessage()
        );
    }

    private RepairArtifact publishArtifact(RepairArtifact artifact) {
        if (!HANDOFF_NAME.equals(artifact.name())) {
            throw new IllegalStateException("handoff artifact must be written to " + HANDOFF_NAME);
        }
        Path path = localArtifactPath(artifact);
        try {
            long size = Files.size(path);
            if (size <= 0L || size > properties.maxMarkdownBytes()) {
                throw new IllegalStateException(
                        "handoff Markdown exceeds configured " + properties.getMaxTokens() + " token budget"
                );
            }
            validateIntegrity(artifact, path, size);
            StoredIngestionFile stored;
            try (InputStream input = Files.newInputStream(path)) {
                stored = objectStorageService.upload(
                        HANDOFF_BUCKET,
                        input,
                        size,
                        "next.md",
                        "text/markdown"
                );
            }
            if (stored == null || stored.size() != size || stored.url() == null || !stored.url().startsWith("s3://")) {
                throw new IllegalStateException("handoff object storage upload size or URI mismatch");
            }
            Map<String, String> metadata = new LinkedHashMap<>(artifact.metadataJson());
            metadata.put("contentType", "text/markdown");
            metadata.put("handoffMaxTokens", String.valueOf(properties.getMaxTokens()));
            return new RepairArtifact(
                    artifact.type(),
                    artifact.name(),
                    stored.url(),
                    artifact.summary(),
                    Map.copyOf(metadata)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("failed to persist Markdown handoff", exception);
        }
    }

    private RepairArtifact publishCandidatePatch(RepairArtifact artifact) {
        if (!CANDIDATE_PATCH_NAME.equals(artifact.name())) {
            throw new IllegalStateException("candidate patch must be written to " + CANDIDATE_PATCH_NAME);
        }
        Path path = localArtifactPath(artifact);
        try {
            long size = Files.size(path);
            if (size <= 0L || size > MAX_CANDIDATE_PATCH_BYTES) {
                throw new IllegalStateException("candidate patch exceeds 10 MiB limit");
            }
            validateIntegrity(artifact, path, size);
            StoredIngestionFile stored;
            try (InputStream input = Files.newInputStream(path)) {
                stored = objectStorageService.upload(
                        HANDOFF_BUCKET,
                        input,
                        size,
                        CANDIDATE_PATCH_NAME,
                        "text/x-diff"
                );
            }
            if (stored == null || stored.size() != size || stored.url() == null || !stored.url().startsWith("s3://")) {
                throw new IllegalStateException("candidate patch object storage upload size or URI mismatch");
            }
            Map<String, String> metadata = new LinkedHashMap<>(artifact.metadataJson());
            metadata.put("contentType", "text/x-diff");
            metadata.put("candidatePatch", "true");
            return new RepairArtifact(
                    artifact.type(), artifact.name(), stored.url(), artifact.summary(), Map.copyOf(metadata)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("failed to persist candidate patch", exception);
        }
    }

    private static Path localArtifactPath(RepairArtifact artifact) {
        URI uri;
        try {
            uri = URI.create(artifact.uri());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("invalid handoff artifact URI", exception);
        }
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("Markdown handoff must originate from a local executor file");
        }
        Path path = Path.of(uri).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Markdown handoff file is missing or not regular");
        }
        return path;
    }

    private static void validateIntegrity(RepairArtifact artifact, Path path, long actualSize) throws IOException {
        String expectedBytes = artifact.metadataJson().getOrDefault("bytes", "").strip();
        if (expectedBytes.isBlank() || Long.parseLong(expectedBytes) != actualSize) {
            throw new IllegalStateException("handoff size metadata mismatch");
        }
        String expectedSha256 = artifact.metadataJson().getOrDefault("sha256", "").strip();
        if (expectedSha256.startsWith("sha256:")) {
            expectedSha256 = expectedSha256.substring("sha256:".length());
        }
        String actualSha256 = sha256(path);
        if (expectedSha256.isBlank() || !actualSha256.equalsIgnoreCase(expectedSha256)) {
            throw new IllegalStateException("handoff sha256 metadata mismatch");
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
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
