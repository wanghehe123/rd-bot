package com.wish.rd.engine.requirement.job.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

/** Immutable PI QA remediation intent frozen at the outcome-recorded boundary. */
public record PiQaRemediationIntent(
        String protocol,
        String sourceTaskId,
        String sourceStageRunId,
        String sourceCommandId,
        String sourceResultHash,
        long sourceTaskVersion,
        long sourceFencingToken,
        AgentRemediationKind kind,
        int remediationNo,
        String roundId,
        String requestJson,
        String requestHash,
        String targetCodingStageRunId,
        int targetCodingAttemptNo,
        String targetQaStageRunId,
        int targetQaAttemptNo,
        String firstCommandId,
        ExecutionProfileClaim sourceProfile,
        PreparedProfileSnapshot codingProfile,
        PreparedProfileSnapshot qaProfile,
        String protocolFailureReceiptJson,
        String protocolFailureReceiptHash,
        String hostVerificationRunId
) {
    public static final String PROTOCOL = "rd-pi-qa-remediation-intent/v2";
    private static final int MAX_EMBEDDED_JSON_BYTES = 65_536;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * QA-kind constructor that leaves {@code hostVerificationRunId} blank.
     */
    public PiQaRemediationIntent(
            String protocol,
            String sourceTaskId,
            String sourceStageRunId,
            String sourceCommandId,
            String sourceResultHash,
            long sourceTaskVersion,
            long sourceFencingToken,
            AgentRemediationKind kind,
            int remediationNo,
            String roundId,
            String requestJson,
            String requestHash,
            String targetCodingStageRunId,
            int targetCodingAttemptNo,
            String targetQaStageRunId,
            int targetQaAttemptNo,
            String firstCommandId,
            ExecutionProfileClaim sourceProfile,
            PreparedProfileSnapshot codingProfile,
            PreparedProfileSnapshot qaProfile,
            String protocolFailureReceiptJson,
            String protocolFailureReceiptHash
    ) {
        this(protocol, sourceTaskId, sourceStageRunId, sourceCommandId, sourceResultHash,
                sourceTaskVersion, sourceFencingToken, kind, remediationNo, roundId, requestJson, requestHash,
                targetCodingStageRunId, targetCodingAttemptNo, targetQaStageRunId, targetQaAttemptNo,
                firstCommandId, sourceProfile, codingProfile, qaProfile,
                protocolFailureReceiptJson, protocolFailureReceiptHash, "");
    }

    public PiQaRemediationIntent {
        protocol = require(protocol, "protocol");
        if (!PROTOCOL.equals(protocol)) throw new IllegalArgumentException("unsupported remediation intent protocol");
        sourceTaskId = require(sourceTaskId, "sourceTaskId");
        sourceStageRunId = require(sourceStageRunId, "sourceStageRunId");
        sourceCommandId = require(sourceCommandId, "sourceCommandId");
        sourceResultHash = requireDigest(sourceResultHash, "sourceResultHash", true);
        if (sourceTaskVersion < 0 || sourceFencingToken <= 0) {
            throw new IllegalArgumentException("invalid source task concurrency identity");
        }
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (remediationNo < 1 || remediationNo > kind.maximumRounds()) {
            throw new IllegalArgumentException("remediationNo exceeds kind limit");
        }
        roundId = require(roundId, "roundId");
        try {
            Long.parseLong(roundId);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("roundId must be numeric", invalid);
        }
        requestJson = canonicalBoundedJson(requestJson, "requestJson");
        requestHash = requireDigest(requestHash, "requestHash", true);
        requireHash(requestJson, requestHash, "requestHash");
        targetCodingStageRunId = safe(targetCodingStageRunId);
        firstCommandId = require(firstCommandId, "firstCommandId");
        hostVerificationRunId = safe(hostVerificationRunId);
        if (sourceProfile == null) {
            throw new IllegalArgumentException("source profile claim is required");
        }
        sourceProfile.requirePiRemediationCapability();
        if (kind == AgentRemediationKind.HOST_VERIFY_FIX) {
            hostVerificationRunId = require(hostVerificationRunId, "hostVerificationRunId");
            targetCodingStageRunId = require(targetCodingStageRunId, "targetCodingStageRunId");
            if (targetCodingAttemptNo < 1 || targetCodingAttemptNo > 3 || codingProfile == null) {
                throw new IllegalArgumentException("host-verify fix requires a bounded Coding target");
            }
            targetQaStageRunId = safe(targetQaStageRunId);
            if (!targetQaStageRunId.isEmpty() || targetQaAttemptNo != 0 || qaProfile != null) {
                throw new IllegalArgumentException("host-verify fix must not create a QA target");
            }
        } else {
            if (!hostVerificationRunId.isEmpty()) {
                throw new IllegalArgumentException("QA remediation must not carry a host verification run");
            }
            targetQaStageRunId = require(targetQaStageRunId, "targetQaStageRunId");
            if (targetQaAttemptNo < 1 || targetQaAttemptNo > 3) {
                throw new IllegalArgumentException("target QA attempt must be between 1 and 3");
            }
            if (qaProfile == null) {
                throw new IllegalArgumentException("source and QA profile claims are required");
            }
            if (kind == AgentRemediationKind.QA_PRODUCT_FIX) {
                targetCodingStageRunId = require(targetCodingStageRunId, "targetCodingStageRunId");
                if (targetCodingAttemptNo < 1 || targetCodingAttemptNo > 3 || codingProfile == null) {
                    throw new IllegalArgumentException("product fix requires a bounded Coding target");
                }
            } else if (!targetCodingStageRunId.isEmpty() || targetCodingAttemptNo != 0 || codingProfile != null) {
                throw new IllegalArgumentException("protocol retry must not carry a Coding target");
            }
        }
        if (codingProfile != null) {
            codingProfile.requireTarget(sourceTaskId, targetCodingStageRunId, "CODING_AGENT",
                    targetCodingAttemptNo);
        }
        if (qaProfile != null) {
            qaProfile.requireTarget(sourceTaskId, targetQaStageRunId, "QA_AGENT", targetQaAttemptNo);
        }
        protocolFailureReceiptJson = safe(protocolFailureReceiptJson);
        protocolFailureReceiptHash = safe(protocolFailureReceiptHash);
        if (protocolFailureReceiptJson.isEmpty() != protocolFailureReceiptHash.isEmpty()) {
            throw new IllegalArgumentException("protocol failure receipt JSON/hash must be paired");
        }
        if (!protocolFailureReceiptJson.isEmpty()) {
            protocolFailureReceiptJson = canonicalBoundedJson(
                    protocolFailureReceiptJson, "protocolFailureReceiptJson");
            protocolFailureReceiptHash = requireDigest(
                    protocolFailureReceiptHash, "protocolFailureReceiptHash", true);
            requireHash(protocolFailureReceiptJson, protocolFailureReceiptHash, "protocolFailureReceiptHash");
        }
    }

    /**
     * Returns this intent or a copy whose product-fix request JSON/hash match {@code assignedNo}.
     *
     * @param assignedNo unique round number chosen under the task lock
     * @return the same instance when the number is unchanged
     */
    public PiQaRemediationIntent withAssignedRemediationNo(int assignedNo) {
        if (assignedNo == remediationNo) {
            return this;
        }
        if (assignedNo < 1 || assignedNo > kind.maximumRounds()) {
            throw new IllegalArgumentException("remediationNo exceeds kind limit");
        }
        String nextJson = requestJson;
        String nextHash = requestHash;
        if (kind == AgentRemediationKind.QA_PRODUCT_FIX || kind == AgentRemediationKind.HOST_VERIFY_FIX) {
            try {
                var payload = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(requestJson);
                payload.put("remediationNo", assignedNo);
                nextJson = CanonicalJsonSha256.canonicalize(MAPPER.writeValueAsString(payload));
                nextHash = CanonicalJsonSha256.digest(nextJson);
            } catch (java.io.IOException invalid) {
                throw new IllegalArgumentException("invalid product-fix request JSON", invalid);
            }
        }
        return new PiQaRemediationIntent(
                protocol, sourceTaskId, sourceStageRunId, sourceCommandId, sourceResultHash,
                sourceTaskVersion, sourceFencingToken, kind, assignedNo, roundId, nextJson, nextHash,
                targetCodingStageRunId, targetCodingAttemptNo, targetQaStageRunId, targetQaAttemptNo,
                firstCommandId, sourceProfile, codingProfile, qaProfile,
                protocolFailureReceiptJson, protocolFailureReceiptHash, hostVerificationRunId);
    }

    public record ExecutionProfileClaim(
            String profileId,
            long profileVersion,
            String runtimeType,
            List<String> capabilities
    ) {
        public ExecutionProfileClaim {
            profileId = require(profileId, "profileId");
            if (profileVersion < 0) throw new IllegalArgumentException("profileVersion must not be negative");
            runtimeType = require(runtimeType, "runtimeType").toUpperCase(java.util.Locale.ROOT);
            capabilities = capabilities == null ? List.of() : capabilities.stream()
                    .map(value -> require(value, "capability").toUpperCase(java.util.Locale.ROOT))
                    .distinct().sorted(Comparator.naturalOrder()).toList();
        }

        void requirePiRemediationCapability() {
            if (!"PI".equals(runtimeType) || !capabilities.contains("PI_QA_REMEDIATION_V2")) {
                throw new IllegalArgumentException("remediation profile must be PI with PI_QA_REMEDIATION_V2");
            }
        }
    }

    public record PreparedProfileSnapshot(
            String snapshotId,
            String stageRunId,
            String role,
            int attemptNo,
            ExecutionProfileClaim profile,
            String snapshotJson,
            String snapshotHash
    ) {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        public PreparedProfileSnapshot {
            snapshotId = require(snapshotId, "snapshotId");
            stageRunId = require(stageRunId, "stageRunId");
            role = require(role, "role").toUpperCase(java.util.Locale.ROOT);
            if (attemptNo < 1 || attemptNo > 3) throw new IllegalArgumentException("attemptNo must be between 1 and 3");
            if (profile == null) throw new IllegalArgumentException("profile claim is required");
            profile.requirePiRemediationCapability();
            snapshotJson = canonicalBoundedJson(snapshotJson, "snapshotJson");
            snapshotHash = requireDigest(snapshotHash, "snapshotHash", false);
            if (!AgentExecutionProfileSnapshot.sha256(snapshotJson).equals(snapshotHash)) {
                throw new IllegalArgumentException("snapshotHash mismatch");
            }
            verifyPayload(snapshotJson, stageRunId, role, attemptNo, profile);
        }

        void requireTarget(String taskId, String expectedStage, String expectedRole, int expectedAttempt) {
            if (!stageRunId.equals(expectedStage) || !role.equals(expectedRole) || attemptNo != expectedAttempt) {
                throw new IllegalArgumentException("prepared profile target identity mismatch");
            }
            try {
                if (!taskId.equals(MAPPER.readTree(snapshotJson).path("taskId").asText())) {
                    throw new IllegalArgumentException("prepared profile task identity mismatch");
                }
            } catch (java.io.IOException invalid) {
                throw new IllegalArgumentException("invalid prepared profile snapshot", invalid);
            }
        }

        private static void verifyPayload(
                String json,
                String stageRunId,
                String role,
                int attemptNo,
                ExecutionProfileClaim claim
        ) {
            try {
                JsonNode value = MAPPER.readTree(json);
                if (!value.isObject()
                        || !stageRunId.equals(value.path("stageRunId").asText())
                        || !role.equals(value.path("role").asText())
                        || attemptNo != value.path("attemptNo").asInt(-1)
                        || !claim.profileId().equals(value.path("profileId").asText())
                        || claim.profileVersion() != value.path("profileVersion").asLong(-1)
                        || !claim.runtimeType().equals(value.path("runtimeType").asText())) {
                    throw new IllegalArgumentException("prepared profile snapshot claim mismatch");
                }
                List<String> payloadCapabilities = java.util.stream.StreamSupport
                        .stream(value.path("capabilities").spliterator(), false)
                        .map(JsonNode::asText).distinct().sorted().toList();
                if (!payloadCapabilities.equals(claim.capabilities())) {
                    throw new IllegalArgumentException("prepared profile capability claim mismatch");
                }
            } catch (java.io.IOException invalid) {
                throw new IllegalArgumentException("invalid prepared profile snapshot", invalid);
            }
        }
    }

    private static String canonicalBoundedJson(String value, String field) {
        String canonical = CanonicalJsonSha256.canonicalize(require(value, field));
        if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_EMBEDDED_JSON_BYTES) {
            throw new IllegalArgumentException(field + " exceeds protocol limit");
        }
        return canonical;
    }

    private static void requireHash(String canonicalJson, String expected, String field) {
        if (!CanonicalJsonSha256.digest(canonicalJson).equals(expected)) {
            throw new IllegalArgumentException(field + " mismatch");
        }
    }

    private static String requireDigest(String value, String field, boolean prefixed) {
        String normalized = require(value, field).toLowerCase(java.util.Locale.ROOT);
        String expression = prefixed ? "sha256:[0-9a-f]{64}" : "[0-9a-f]{64}";
        if (!normalized.matches(expression)) throw new IllegalArgumentException(field + " is invalid");
        return normalized;
    }

    private static String require(String value, String field) {
        String normalized = safe(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
