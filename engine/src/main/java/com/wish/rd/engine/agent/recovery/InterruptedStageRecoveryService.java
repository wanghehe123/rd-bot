package com.wish.rd.engine.agent.recovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.AgentStageTransitions;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.agent.recovery.model.RecoveredWorkspaceExecution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Reconciles interrupted stage attempts from persisted workspace output before opening a fresh attempt.
 */
public final class InterruptedStageRecoveryService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> IMMUTABLE_ARTIFACT_TYPES = Set.of(
            "ROLE_EXECUTION_INPUT_MANIFEST",
            "AGENT_STATE_EVENTS",
            "AGENT_STATE_SNAPSHOT",
            "RUNTIME_CONTEXT_MANIFEST"
    );

    private final InterruptedStageWorkspaceRecoveryPort workspaceRecoveryPort;
    private final AgentStageRunStore stageRunStore;
    private final AgentStageArtifactStore artifactStore;
    private final Supplier<String> idSupplier;

    public InterruptedStageRecoveryService(
            InterruptedStageWorkspaceRecoveryPort workspaceRecoveryPort,
            AgentStageRunStore stageRunStore,
            AgentStageArtifactStore artifactStore,
            Supplier<String> idSupplier
    ) {
        this.workspaceRecoveryPort = workspaceRecoveryPort == null
                ? InterruptedStageWorkspaceRecoveryPort.unavailable()
                : workspaceRecoveryPort;
        this.stageRunStore = Objects.requireNonNull(stageRunStore, "stageRunStore must not be null");
        this.artifactStore = artifactStore == null ? AgentStageArtifactStore.noop() : artifactStore;
        this.idSupplier = idSupplier == null ? () -> "recovered-artifact" : idSupplier;
    }

    /**
     * @return {@code true} when the interrupted attempt was reconciled in place
     */
    public boolean tryRecoverInterruptedStage(AgentStageRun stage, long nowEpochMillis) {
        if (stage == null || !AgentStageTransitions.requiresFreshAttemptOnRecovery(stage.status())) {
            return false;
        }
        Optional<RecoveredWorkspaceExecution> recovered = workspaceRecoveryPort.tryRecover(stage);
        if (recovered.isEmpty()) {
            return false;
        }
        reconcileRecoveredStage(stage, recovered.get(), nowEpochMillis);
        return true;
    }

    private void reconcileRecoveredStage(
            AgentStageRun stage,
            RecoveredWorkspaceExecution recovered,
            long nowEpochMillis
    ) {
        stage = persistRecoveredArtifacts(stage, recovered, nowEpochMillis);
        if (!recovered.providerName().isBlank() || !"[]".equals(recovered.providerAttemptsJson())) {
            stage = stageRunStore.save(stage.withProviderMetadata(
                    recovered.providerName(),
                    recovered.providerAttemptsJson(),
                    nowEpochMillis
            ));
        }
        stage = transition(stage, AgentStageStatus.RESULT_COLLECTING, "", "", nowEpochMillis);
        if (recovered.success()) {
            stage = transition(stage, AgentStageStatus.VERIFYING, "", "", nowEpochMillis);
            transition(stage, AgentStageStatus.SUCCEEDED, "", "", nowEpochMillis);
            return;
        }
        transition(
                stage,
                AgentStageStatus.FAILED_NEEDS_HUMAN,
                recovered.errorCategory().isBlank() ? "AGENT_RESULT_REJECTED" : recovered.errorCategory(),
                recovered.errorMessage().isBlank() ? "recovered workspace result was not successful" : recovered.errorMessage(),
                nowEpochMillis
        );
    }

    private AgentStageRun persistRecoveredArtifacts(
            AgentStageRun stage,
            RecoveredWorkspaceExecution recovered,
            long nowEpochMillis
    ) {
        if (!stage.resultArtifactId().isBlank()) {
            captureExecutorStageArtifacts(stage, recovered.resultJson(), nowEpochMillis);
            return stage;
        }
        String resultJson = recovered.resultJson();
        AgentStageArtifact artifact = artifactStore.save(new AgentStageArtifact(
                idSupplier.get(),
                stage.stageRunId(),
                stage.taskId(),
                stage.role(),
                "RESULT_JSON",
                artifactUri(stage, "result"),
                stage.role().name() + " recovered result json",
                contentPreview(resultJson),
                sha256(resultJson),
                artifactMetadata(stage, "RESULT_JSON", resultJson),
                nowEpochMillis
        ));
        stage = stageRunStore.save(stage.withResultArtifactId(artifact.artifactId(), nowEpochMillis));
        captureExecutorStageArtifacts(stage, resultJson, nowEpochMillis);
        return stage;
    }

    private void captureExecutorStageArtifacts(AgentStageRun stage, String resultJson, long nowEpochMillis) {
        try {
            JsonNode stageArtifacts = OBJECT_MAPPER.readTree(resultJson).path("stageArtifacts");
            if (!stageArtifacts.isArray() || stageArtifacts.isEmpty()) {
                return;
            }
            int index = 0;
            for (JsonNode item : stageArtifacts) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String artifactType = firstNonBlank(text(item.path("type")), text(item.path("artifactType")));
                if (artifactType.isBlank() || "RESULT_JSON".equals(artifactType)) {
                    continue;
                }
                String content = stageArtifactContentPreview(item);
                String uri = firstNonBlank(text(item.path("uri")), text(item.path("artifactUri")));
                if (content.isBlank() && uri.isBlank()) {
                    continue;
                }
                persistStageArtifact(new AgentStageArtifact(
                        idSupplier.get(),
                        stage.stageRunId(),
                        stage.taskId(),
                        stage.role(),
                        artifactType,
                        uri.isBlank() ? artifactUri(stage, artifactType.toLowerCase(Locale.ROOT)) : uri,
                        firstNonBlank(text(item.path("summary")), artifactType + " artifact"),
                        content,
                        stageArtifactContentHash(item, content, uri),
                        stageArtifactMetadata(stage, artifactType, item),
                        nowEpochMillis + (++index)
                ));
            }
        } catch (JsonProcessingException ignored) {
            // RESULT_JSON remains persisted; malformed auxiliary artifact metadata must not hide the primary result.
        }
    }

    private void persistStageArtifact(AgentStageArtifact artifact) {
        if (IMMUTABLE_ARTIFACT_TYPES.contains(artifact.artifactType())) {
            artifactStore.saveImmutable(artifact);
            return;
        }
        artifactStore.save(artifact);
    }

    private AgentStageRun transition(
            AgentStageRun stage,
            AgentStageStatus targetStatus,
            String errorCategory,
            String errorMessage,
            long nowEpochMillis
    ) {
        if (stage.status() == targetStatus) {
            return stage;
        }
        return stageRunStore.transition(
                stage.stageRunId(),
                targetStatus,
                errorCategory,
                errorMessage,
                nowEpochMillis
        );
    }

    private String stageArtifactContentPreview(JsonNode item) throws JsonProcessingException {
        JsonNode contentPreview = item.path("contentPreview");
        if (contentPreview.isMissingNode() || contentPreview.isNull()) {
            return "";
        }
        if (contentPreview.isTextual()) {
            return contentPreview.asText("");
        }
        return OBJECT_MAPPER.writeValueAsString(contentPreview);
    }

    private String stageArtifactMetadata(AgentStageRun stage, String artifactType, JsonNode item)
            throws JsonProcessingException {
        JsonNode metadata = item.path("metadataJson");
        if (metadata.isObject()) {
            return OBJECT_MAPPER.writeValueAsString(metadata);
        }
        return artifactMetadata(stage, artifactType, stageArtifactContentPreview(item));
    }

    private String stageArtifactContentHash(JsonNode item, String content, String uri) {
        String executorSha256 = text(item.path("metadataJson").path("sha256"));
        String normalized = executorSha256.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("sha256:")) {
            normalized = normalized.substring("sha256:".length());
        }
        if (normalized.matches("[0-9a-f]{64}")) {
            return "sha256:" + normalized;
        }
        return sha256(content.isBlank() ? uri : content);
    }

    private String artifactUri(AgentStageRun stage, String name) {
        return "rd-agent-stage://" + stage.taskId() + "/" + stage.stageRunId() + "/" + name;
    }

    private String artifactMetadata(AgentStageRun stage, String artifactType, String content) {
        return """
                {"taskId":%s,"stageRunId":%s,"role":%s,"artifactType":%s,"contentLength":%d}
                """.formatted(
                json(stage.taskId()),
                json(stage.stageRunId()),
                json(stage.role().name()),
                json(artifactType),
                safe(content).length()
        ).strip();
    }

    private String contentPreview(String content) {
        String normalized = safe(content);
        int maxChars = 20_000;
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars);
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safe(content).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String json(String value) {
        return "\"" + safe(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }

    private static String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left.strip();
        }
        return right == null ? "" : right.strip();
    }
}
