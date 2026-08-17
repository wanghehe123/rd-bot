package com.wish.rd.bootstrap.verify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.bootstrap.executor.impl.RoleHandoffAttachmentResolver;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.exec.repair.execution.model.RepairInputAttachment;
import com.wish.rd.rag.runtime.model.RdRequirementTask;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the coding stage's persisted {@code PATCH_DIFF} / handoff JSON and rematerializes
 * the verified {@code candidate-patch.diff} through {@link RoleHandoffAttachmentResolver}.
 */
public final class ArtifactHostVerificationPatchSource implements HostVerificationPatchSource {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long MAX_CANDIDATE_PATCH_BYTES = 10L * 1024L * 1024L;

    private final AgentStageArtifactStore artifactStore;
    private final RoleHandoffAttachmentResolver handoffResolver;

    /**
     * Creates the production patch source.
     *
     * @param artifactStore   persisted coding-stage artifacts
     * @param handoffResolver verified S3 rematerialization used by local QA
     */
    public ArtifactHostVerificationPatchSource(
            AgentStageArtifactStore artifactStore,
            RoleHandoffAttachmentResolver handoffResolver
    ) {
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore must not be null");
        this.handoffResolver = Objects.requireNonNull(handoffResolver, "handoffResolver must not be null");
    }

    /**
     * Loads the coding stage candidate patch.
     *
     * @param task        requirement task
     * @param codingStage coding stage being verified
     * @return verified patch bytes
     * @throws IllegalStateException when no verified candidate patch exists
     */
    @Override
    public HostVerificationCandidatePatch load(RdRequirementTask task, AgentStageRun codingStage) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        if (codingStage == null) {
            throw new IllegalArgumentException("codingStage must not be null");
        }
        if (codingStage.role() != AgentRole.CODING_AGENT) {
            throw new IllegalStateException(
                    "host verification patch source requires a CODING_AGENT stage, got " + codingStage.role());
        }
        List<AgentStageArtifact> stageArtifacts = artifactStore.listByTask(task.taskId()).stream()
                .filter(artifact -> codingStage.stageRunId().equals(artifact.stageRunId()))
                .toList();
        Map<String, Object> descriptor = persistedCandidatePatch(stageArtifacts);
        if (descriptor.isEmpty()) {
            descriptor = candidatePatchFromResult(codingStage, stageArtifacts);
        }
        if (descriptor.isEmpty()) {
            throw new IllegalStateException(
                    "coding stage " + codingStage.stageRunId() + " has no verified candidate-patch.diff");
        }
        String handoffJson = handoffJson(descriptor);
        List<RepairInputAttachment> attachments = handoffResolver.resolve(AgentRole.QA_AGENT.name(), handoffJson);
        RepairInputAttachment patch = attachments.stream()
                .filter(attachment -> "candidate-patch.diff".equals(attachment.filename()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "coding stage " + codingStage.stageRunId() + " has no verified candidate-patch.diff"));
        return new HostVerificationCandidatePatch(
                patch.content(),
                String.valueOf(descriptor.getOrDefault("sha256", ""))
        );
    }

    private Map<String, Object> persistedCandidatePatch(List<AgentStageArtifact> stageArtifacts) {
        return stageArtifacts.stream()
                .filter(artifact -> "PATCH_DIFF".equals(artifact.artifactType()))
                .max(Comparator.comparingLong(AgentStageArtifact::createdAtEpochMillis))
                .map(this::descriptorFromPatchArtifact)
                .orElseGet(Map::of);
    }

    private Map<String, Object> descriptorFromPatchArtifact(AgentStageArtifact artifact) {
        if (artifact == null || !artifact.artifactUri().startsWith("s3://")) {
            return Map.of();
        }
        try {
            JsonNode metadata = OBJECT_MAPPER.readTree(artifact.metadataJson());
            if (metadata == null || !metadata.isObject()
                    || !"true".equalsIgnoreCase(text(metadata, "candidatePatch"))) {
                return Map.of();
            }
            String sha256 = firstNonBlank(text(metadata, "sha256"), artifact.contentHash());
            long bytes = readBytes(metadata);
            if (!sha256.matches("(?:sha256:)?[0-9a-fA-F]{64}")
                    || bytes <= 0L
                    || bytes > MAX_CANDIDATE_PATCH_BYTES) {
                return Map.of();
            }
            return descriptor(artifact.artifactUri(), sha256, bytes);
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private Map<String, Object> candidatePatchFromResult(
            AgentStageRun codingStage,
            List<AgentStageArtifact> stageArtifacts
    ) {
        AgentStageArtifact result = stageArtifacts.stream()
                .filter(artifact -> artifact.artifactId().equals(codingStage.resultArtifactId())
                        || "RESULT_JSON".equals(artifact.artifactType()))
                .max(Comparator.comparingLong(AgentStageArtifact::createdAtEpochMillis))
                .orElse(null);
        if (result == null || result.contentPreview().isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(result.contentPreview());
            if (root == null || !root.isObject()) {
                return Map.of();
            }
            Map<String, Object> fromManifest = descriptorFromManifest(root.path("candidatePatch"));
            if (!fromManifest.isEmpty()) {
                return fromManifest;
            }
            return descriptorFromStageArtifacts(root.path("stageArtifacts"));
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private Map<String, Object> descriptorFromManifest(JsonNode candidatePatch) {
        if (candidatePatch == null || !candidatePatch.isObject()) {
            return Map.of();
        }
        String artifactUri = text(candidatePatch, "artifactUri");
        String sha256 = text(candidatePatch, "sha256");
        long bytes = readBytes(candidatePatch);
        if (!artifactUri.startsWith("s3://")
                || !sha256.matches("(?:sha256:)?[0-9a-fA-F]{64}")
                || bytes <= 0L
                || bytes > MAX_CANDIDATE_PATCH_BYTES) {
            return Map.of();
        }
        return descriptor(artifactUri, sha256, bytes);
    }

    private Map<String, Object> descriptorFromStageArtifacts(JsonNode stageArtifacts) {
        if (stageArtifacts == null || !stageArtifacts.isArray()) {
            return Map.of();
        }
        for (JsonNode artifact : stageArtifacts) {
            if (artifact == null || !artifact.isObject()
                    || !"PATCH_DIFF".equalsIgnoreCase(text(artifact, "type"))
                    || !"patch.diff".equals(text(artifact, "name"))) {
                continue;
            }
            JsonNode metadata = artifact.path("metadataJson");
            String artifactUri = text(artifact, "uri");
            String sha256 = text(metadata, "sha256");
            long bytes = readBytes(metadata);
            if (!"true".equalsIgnoreCase(text(metadata, "candidatePatch"))
                    || !artifactUri.startsWith("s3://")
                    || !sha256.matches("(?:sha256:)?[0-9a-fA-F]{64}")
                    || bytes <= 0L
                    || bytes > MAX_CANDIDATE_PATCH_BYTES) {
                continue;
            }
            return descriptor(artifactUri, sha256, bytes);
        }
        return Map.of();
    }

    private Map<String, Object> descriptor(String artifactUri, String sha256, long bytes) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("sourceRole", AgentRole.CODING_AGENT.name());
        descriptor.put("targetRole", AgentRole.QA_AGENT.name());
        descriptor.put("artifactName", "patch.diff");
        descriptor.put("artifactUri", artifactUri);
        descriptor.put("sha256", sha256);
        descriptor.put("bytes", bytes);
        return Map.copyOf(descriptor);
    }

    private String handoffJson(Map<String, Object> descriptor) {
        ObjectNode stage = OBJECT_MAPPER.createObjectNode();
        stage.put("role", AgentRole.CODING_AGENT.name());
        stage.set("candidatePatch", OBJECT_MAPPER.valueToTree(descriptor));
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.putArray("stages").add(stage);
        try {
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot build candidate patch handoff", exception);
        }
    }

    private static long readBytes(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return -1L;
        }
        JsonNode bytes = node.path("bytes");
        if (bytes.canConvertToLong()) {
            return bytes.asLong(-1L);
        }
        try {
            return Long.parseLong(bytes.asText("").strip());
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first.isBlank() ? (second == null ? "" : second.strip()) : first;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").strip();
    }
}
