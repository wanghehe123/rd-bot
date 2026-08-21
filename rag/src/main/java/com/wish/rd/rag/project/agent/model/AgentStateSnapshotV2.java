package com.wish.rd.rag.project.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

/** Immutable Java-owned initial and projected state for capability-gated Pi attempts. */
public record AgentStateSnapshotV2(
        String protocol,
        long sequence,
        String taskId,
        String stageRunId,
        String role,
        int attemptNo,
        String runtimeType,
        String profileSnapshotId,
        String currentGoal,
        long taskStartedAtEpochMillis,
        long stageStartedAtEpochMillis,
        String phase,
        Budget budget,
        List<Todo> todos,
        long generatedAtEpochMillis
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public AgentStateSnapshotV2 {
        protocol = requireText(protocol, "protocol");
        if (!AgentStateV2Codec.PROTOCOL.equals(protocol)) {
            throw new IllegalArgumentException("protocol must be " + AgentStateV2Codec.PROTOCOL);
        }
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        taskId = requireText(taskId, "taskId");
        stageRunId = requireText(stageRunId, "stageRunId");
        role = requireText(role, "role").toUpperCase(Locale.ROOT);
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        runtimeType = requireText(runtimeType, "runtimeType").toUpperCase(Locale.ROOT);
        profileSnapshotId = requireText(profileSnapshotId, "profileSnapshotId");
        currentGoal = requireText(currentGoal, "currentGoal");
        requirePositiveSafeInteger(taskStartedAtEpochMillis, "taskStartedAtEpochMillis");
        requirePositiveSafeInteger(stageStartedAtEpochMillis, "stageStartedAtEpochMillis");
        phase = requireText(phase, "phase").toUpperCase(Locale.ROOT);
        if (budget == null) {
            throw new IllegalArgumentException("budget must not be null");
        }
        todos = todos == null ? List.of() : List.copyOf(todos);
        requirePositiveSafeInteger(generatedAtEpochMillis, "generatedAtEpochMillis");
    }

    public String canonicalJson() {
        return AgentStateV2Codec.canonicalize(jsonNode());
    }

    public String canonicalHash() {
        return AgentStateV2Codec.hash(jsonNode());
    }

    public boolean hasValidCanonicalHash() {
        try {
            AgentStateV2Codec.decodeAndVerify(canonicalJson(), canonicalHash());
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private JsonNode jsonNode() {
        return OBJECT_MAPPER.valueToTree(this);
    }

    public enum BudgetAvailability {
        UNKNOWN,
        AVAILABLE,
        UNAVAILABLE
    }

    public record Budget(
            BudgetAvailability availability,
            String model,
            Long maxContextTokens,
            Long reservedOutputTokens,
            Long estimatedInputTokens,
            String estimatorVersion
    ) {
        public Budget {
            if (availability == null) {
                throw new IllegalArgumentException("budget availability must not be null");
            }
            model = model == null ? "" : model.strip();
            estimatorVersion = estimatorVersion == null ? "" : estimatorVersion.strip();
            requireNullableSafeInteger(maxContextTokens, "maxContextTokens");
            requireNullableSafeInteger(reservedOutputTokens, "reservedOutputTokens");
            requireNullableSafeInteger(estimatedInputTokens, "estimatedInputTokens");
        }
    }

    public enum TodoOwner {
        HOST,
        AGENT
    }

    public enum TodoKind {
        ACCEPTANCE,
        REMEDIATION,
        PROTOCOL_RETRY,
        AGENT_WORK
    }

    public enum TodoStatus {
        PENDING,
        IN_PROGRESS,
        BLOCKED,
        DONE,
        CANCELLED
    }

    public record Todo(
            String todoId,
            TodoOwner owner,
            TodoKind kind,
            String title,
            TodoStatus status,
            boolean required,
            String acceptanceCriteriaId,
            String acceptanceContentHash,
            AttachmentRef attachment,
            List<String> evidenceArtifactIds
    ) {
        public Todo {
            todoId = requireText(todoId, "todoId");
            if (owner == null || kind == null || status == null) {
                throw new IllegalArgumentException("todo owner, kind, and status must not be null");
            }
            title = requireText(title, "todo title");
            acceptanceCriteriaId = acceptanceCriteriaId == null ? "" : acceptanceCriteriaId.strip();
            acceptanceContentHash = acceptanceContentHash == null ? "" : acceptanceContentHash.strip();
            evidenceArtifactIds = evidenceArtifactIds == null ? List.of() : List.copyOf(evidenceArtifactIds);
        }

        public Todo(
                String todoId,
                TodoOwner owner,
                TodoKind kind,
                String title,
                TodoStatus status,
                boolean required,
                String acceptanceCriteriaId,
                String acceptanceContentHash,
                List<String> evidenceArtifactIds
        ) {
            this(
                    todoId, owner, kind, title, status, required, acceptanceCriteriaId,
                    acceptanceContentHash, null, evidenceArtifactIds
            );
        }
    }

    public record AttachmentRef(String path, String hash, int bytes) {
        public AttachmentRef {
            path = requireText(path, "attachment path");
            hash = requireText(hash, "attachment hash");
            if (bytes <= 0) {
                throw new IllegalArgumentException("attachment bytes must be positive");
            }
        }
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static void requirePositiveSafeInteger(long value, String fieldName) {
        if (value <= 0L || value > AgentStateV2Codec.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(fieldName + " must be a positive JSON safe integer");
        }
    }

    private static void requireNullableSafeInteger(Long value, String fieldName) {
        if (value != null && (value < 0L || value > AgentStateV2Codec.MAX_SAFE_INTEGER)) {
            throw new IllegalArgumentException(fieldName + " must be a non-negative JSON safe integer");
        }
    }
}
