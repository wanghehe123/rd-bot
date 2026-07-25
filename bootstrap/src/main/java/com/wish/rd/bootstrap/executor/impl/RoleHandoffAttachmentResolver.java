package com.wish.rd.bootstrap.executor.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.execution.model.RepairInputAttachment;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves private RustFS handoffs into read-only files in the next isolated executor workspace. */
@Component
public final class RoleHandoffAttachmentResolver {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CANDIDATE_PATCH_NAME = "patch.diff";
    private static final long MAX_CANDIDATE_PATCH_BYTES = 10L * 1024L * 1024L;

    private final ObjectStorageService objectStorageService;
    private final RoleHandoffProperties properties;

    public RoleHandoffAttachmentResolver(
            ObjectStorageService objectStorageService,
            RoleHandoffProperties properties
    ) {
        this.objectStorageService = Objects.requireNonNull(
                objectStorageService, "objectStorageService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /** Returns only documents explicitly addressed to the current role. */
    public List<RepairInputAttachment> resolve(String targetRole, String upstreamHandoffJson) {
        String safeTargetRole = normalizeRole(targetRole);
        if (safeTargetRole.isBlank() || upstreamHandoffJson == null || upstreamHandoffJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(upstreamHandoffJson);
        } catch (Exception exception) {
            throw new IllegalStateException("upstream handoff manifest is not valid JSON", exception);
        }
        if (root == null || !root.isObject()) {
            return List.of();
        }
        Map<String, RepairInputAttachment> resolved = new LinkedHashMap<>();
        if (root.has("roleHandoff")) {
            resolveOne(root.path("roleHandoff"), root.path("sourceRole").asText(""), safeTargetRole, resolved);
        }
        JsonNode stages = root.path("stages");
        if (stages.isArray()) {
            for (JsonNode stage : stages) {
                if (stage != null && stage.isObject()) {
                    resolveOne(
                            stage.has("handoff") ? stage.path("handoff") : stage.path("roleHandoff"),
                            stage.path("role").asText(""),
                            safeTargetRole,
                            resolved
                    );
                    resolveCandidatePatch(
                            stage.path("candidatePatch"),
                            stage.path("role").asText(""),
                            safeTargetRole,
                            resolved
                    );
                }
            }
        }
        return List.copyOf(resolved.values());
    }

    private void resolveOne(
            JsonNode handoff,
            String fallbackSourceRole,
            String targetRole,
            Map<String, RepairInputAttachment> resolved
    ) {
        if (handoff == null || !handoff.isObject()
                || !targetRole.equals(normalizeRole(handoff.path("targetRole").asText("")))) {
            return;
        }
        String sourceRole = normalizeRole(firstNonBlank(
                handoff.path("sourceRole").asText(""), fallbackSourceRole
        ));
        String artifactName = handoff.path("artifactName").asText("").strip();
        String uri = handoff.path("artifactUri").asText("").strip();
        String expectedSha256 = normalizeSha256(handoff.path("sha256").asText(""));
        long expectedBytes = handoff.path("bytes").asLong(-1L);
        if (sourceRole.isBlank() || !"handoff/next.md".equals(artifactName)
                || !uri.startsWith("s3://") || expectedSha256.isBlank() || expectedBytes <= 0L) {
            throw new IllegalStateException("upstream handoff metadata is incomplete");
        }
        if (expectedBytes > properties.maxMarkdownBytes()) {
            throw new IllegalStateException("upstream handoff exceeds configured token budget");
        }
        byte[] bytes;
        try (InputStream input = objectStorageService.openStream(uri)) {
            bytes = input.readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("upstream handoff object cannot be read", exception);
        }
        if (bytes.length != expectedBytes || bytes.length > properties.maxMarkdownBytes()) {
            throw new IllegalStateException("upstream handoff byte size mismatch");
        }
        if (!expectedSha256.equalsIgnoreCase(sha256(bytes))) {
            throw new IllegalStateException("upstream handoff sha256 mismatch");
        }
        String filename = "handoff-" + sourceRole.toLowerCase(java.util.Locale.ROOT) + ".md";
        resolved.put("handoff:" + sourceRole, new RepairInputAttachment(filename, "text/markdown", bytes));
    }

    private void resolveCandidatePatch(
            JsonNode candidatePatch,
            String fallbackSourceRole,
            String targetRole,
            Map<String, RepairInputAttachment> resolved
    ) {
        if (!"QA_AGENT".equals(targetRole)
                || candidatePatch == null
                || !candidatePatch.isObject()
                || !targetRole.equals(normalizeRole(candidatePatch.path("targetRole").asText("")))) {
            return;
        }
        String sourceRole = normalizeRole(firstNonBlank(
                candidatePatch.path("sourceRole").asText(""), fallbackSourceRole
        ));
        String artifactName = candidatePatch.path("artifactName").asText("").strip();
        String uri = candidatePatch.path("artifactUri").asText("").strip();
        String expectedSha256 = normalizeSha256(candidatePatch.path("sha256").asText(""));
        long expectedBytes = candidatePatch.path("bytes").asLong(-1L);
        if (!"CODING_AGENT".equals(sourceRole)
                || !CANDIDATE_PATCH_NAME.equals(artifactName)
                || !uri.startsWith("s3://")
                || expectedSha256.isBlank()
                || expectedBytes <= 0L
                || expectedBytes > MAX_CANDIDATE_PATCH_BYTES) {
            throw new IllegalStateException("candidate patch metadata is incomplete");
        }
        byte[] bytes = readVerifiedObject(uri, expectedBytes, expectedSha256, MAX_CANDIDATE_PATCH_BYTES, "candidate patch");
        resolved.put("patch:" + sourceRole,
                new RepairInputAttachment("candidate-patch.diff", "text/x-diff", bytes));
    }

    private byte[] readVerifiedObject(
            String uri,
            long expectedBytes,
            String expectedSha256,
            long maxBytes,
            String subject
    ) {
        byte[] bytes;
        try (InputStream input = objectStorageService.openStream(uri)) {
            bytes = input.readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException(subject + " object cannot be read", exception);
        }
        if (bytes.length != expectedBytes || bytes.length > maxBytes) {
            throw new IllegalStateException(subject + " byte size mismatch");
        }
        if (!expectedSha256.equalsIgnoreCase(sha256(bytes))) {
            throw new IllegalStateException(subject + " sha256 mismatch");
        }
        return bytes;
    }

    private static String firstNonBlank(String first, String second) {
        String normalizedFirst = first == null ? "" : first.strip();
        return normalizedFirst.isBlank() ? (second == null ? "" : second.strip()) : normalizedFirst;
    }

    private static String normalizeRole(String value) {
        return value == null ? "" : value.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private static String normalizeSha256(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.startsWith("sha256:")) {
            normalized = normalized.substring("sha256:".length());
        }
        return normalized.matches("[0-9a-fA-F]{64}") ? normalized : "";
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
