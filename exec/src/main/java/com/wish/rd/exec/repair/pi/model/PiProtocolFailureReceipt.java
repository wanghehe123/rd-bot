package com.wish.rd.exec.repair.pi.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.rag.project.agent.model.AgentStateV2Codec;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Host-validated, canonical facts for the bounded Pi QA protocol retry. */
public record PiProtocolFailureReceipt(
        String protocol,
        Kind kind,
        List<String> missingFacts,
        boolean resultSubmitted,
        ResultSubmissionSource resultSubmissionSource,
        boolean roleSchemaAccepted,
        String acceptedResultDigest,
        boolean agentSettled,
        boolean eventStreamTrusted,
        boolean containerTerminated,
        boolean recoveryApplicable,
        boolean recoveryIssued,
        boolean recoveryExhausted,
        RejectionKind lastRejectionKind,
        String lastRejectionDigest,
        List<String> diagnosticArtifactIds,
        String taskId,
        String stageRunId,
        String role,
        int attemptNo,
        Instant generatedAt,
        String canonicalJson,
        String canonicalHash
) {

    public static final String PROTOCOL = "PiProtocolFailureReceipt/v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Set<String> EXACT_FIELDS = Set.of(
            "acceptedResultDigest", "agentSettled", "attemptNo", "containerTerminated",
            "diagnosticArtifactIds", "eventStreamTrusted", "generatedAt", "kind",
            "lastRejectionDigest", "lastRejectionKind", "missingFacts", "protocol",
            "recoveryApplicable", "recoveryExhausted", "recoveryIssued", "resultSubmissionSource",
            "resultSubmitted", "role", "roleSchemaAccepted", "stageRunId", "taskId"
    );

    public PiProtocolFailureReceipt {
        missingFacts = List.copyOf(missingFacts);
        diagnosticArtifactIds = List.copyOf(diagnosticArtifactIds);
    }

    public static PiProtocolFailureReceipt decodeAndVerify(String json, String expectedHash) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("receipt JSON must not be blank");
        }
        JsonNode value;
        try {
            value = OBJECT_MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid receipt JSON", exception);
        }
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("receipt JSON must be an object");
        }
        String canonical = AgentStateV2Codec.canonicalize(value);
        String actualHash = AgentStateV2Codec.hash(value);
        if (!actualHash.equals(expectedHash == null ? "" : expectedHash.toLowerCase())) {
            throw new IllegalArgumentException("receipt hash mismatch");
        }
        if (!canonical.equals(json)) {
            throw new IllegalArgumentException("receipt JSON must use canonical bytes");
        }
        if (!EXACT_FIELDS.equals(fieldNames(value))) {
            throw new IllegalArgumentException("receipt fields must exactly match v1 contract");
        }
        if (!PROTOCOL.equals(value.path("protocol").asText())) {
            throw new IllegalArgumentException("receipt protocol must be " + PROTOCOL);
        }
        Kind kind = parseEnum(Kind.class, value.path("kind").asText(), "unsupported receipt kind");
        ResultSubmissionSource source = parseEnum(
                ResultSubmissionSource.class,
                value.path("resultSubmissionSource").asText(),
                "unsupported result submission source"
        );
        RejectionKind rejectionKind = parseEnum(
                RejectionKind.class,
                value.path("lastRejectionKind").asText(),
                "unsupported last rejection kind"
        );
        List<String> missingFacts = requiredStringList(value.get("missingFacts"), "missingFacts", false);
        List<String> artifactIds = requiredStringList(
                value.get("diagnosticArtifactIds"), "diagnosticArtifactIds", true
        );
        int attemptNo = value.path("attemptNo").asInt(0);
        if (!value.path("attemptNo").isIntegralNumber() || attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be a positive safe integer");
        }
        Instant generatedAt;
        try {
            generatedAt = Instant.parse(requiredText(value, "generatedAt"));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("generatedAt must be an instant", exception);
        }
        String acceptedDigest = optionalDigest(value, "acceptedResultDigest");
        String rejectionDigest = optionalDigest(value, "lastRejectionDigest");
        PiProtocolFailureReceipt receipt = new PiProtocolFailureReceipt(
                PROTOCOL,
                kind,
                missingFacts,
                requiredBoolean(value, "resultSubmitted"),
                source,
                requiredBoolean(value, "roleSchemaAccepted"),
                acceptedDigest,
                requiredBoolean(value, "agentSettled"),
                requiredBoolean(value, "eventStreamTrusted"),
                requiredBoolean(value, "containerTerminated"),
                requiredBoolean(value, "recoveryApplicable"),
                requiredBoolean(value, "recoveryIssued"),
                requiredBoolean(value, "recoveryExhausted"),
                rejectionKind,
                rejectionDigest,
                artifactIds,
                requiredText(value, "taskId"),
                requiredText(value, "stageRunId"),
                requiredText(value, "role"),
                attemptNo,
                generatedAt,
                json,
                expectedHash.toLowerCase()
        );
        validateExactKindFacts(receipt);
        return receipt;
    }

    public void requireIdentity(String expectedTaskId, String expectedStageRunId, String expectedRole, int expectedAttemptNo) {
        if (!taskId.equals(expectedTaskId)
                || !stageRunId.equals(expectedStageRunId)
                || !role.equals(expectedRole)
                || attemptNo != expectedAttemptNo) {
            throw new IllegalArgumentException("protocol failure receipt identity mismatch");
        }
    }

    public enum Kind {
        RESULT_MISSING_AFTER_RECOVERY,
        AGENT_SETTLED_MISSING,
        ROLE_SCHEMA_REJECTED_AFTER_RECOVERY
    }

    public enum ResultSubmissionSource {
        NONE,
        AGENT_RD_SUBMIT_RESULT,
        BRIDGE_SYNTHETIC
    }

    public enum RejectionKind {
        NONE,
        ROLE_SCHEMA
    }

    private static void validateExactKindFacts(PiProtocolFailureReceipt receipt) {
        boolean recoveryAll = receipt.recoveryApplicable && receipt.recoveryIssued && receipt.recoveryExhausted;
        boolean noAcceptedAgentResult = receipt.resultSubmissionSource != ResultSubmissionSource.AGENT_RD_SUBMIT_RESULT
                && !receipt.roleSchemaAccepted
                && receipt.acceptedResultDigest.isEmpty();
        switch (receipt.kind) {
            case RESULT_MISSING_AFTER_RECOVERY -> {
                if (!receipt.missingFacts.equals(List.of("AGENT_RESULT_SUBMITTED"))
                        || !receipt.agentSettled || !recoveryAll || !noAcceptedAgentResult
                        || receipt.lastRejectionKind != RejectionKind.NONE
                        || !receipt.lastRejectionDigest.isEmpty()) {
                    throw new IllegalArgumentException("receipt contradicts RESULT_MISSING_AFTER_RECOVERY facts");
                }
            }
            case AGENT_SETTLED_MISSING -> {
                if (!receipt.missingFacts.equals(List.of("AGENT_SETTLED"))
                        || receipt.agentSettled || !receipt.resultSubmitted
                        || receipt.resultSubmissionSource != ResultSubmissionSource.AGENT_RD_SUBMIT_RESULT
                        || !receipt.roleSchemaAccepted || !SHA256.matcher(receipt.acceptedResultDigest).matches()
                        || receipt.recoveryApplicable || receipt.recoveryIssued || receipt.recoveryExhausted
                        || receipt.lastRejectionKind != RejectionKind.NONE
                        || !receipt.lastRejectionDigest.isEmpty()) {
                    throw new IllegalArgumentException("receipt contradicts AGENT_SETTLED_MISSING facts");
                }
            }
            case ROLE_SCHEMA_REJECTED_AFTER_RECOVERY -> {
                if (!receipt.missingFacts.equals(List.of(
                        "AGENT_RESULT_SUBMITTED", "ROLE_SCHEMA_ACCEPTED_RESULT"
                )) || !receipt.agentSettled || !recoveryAll || !noAcceptedAgentResult
                        || receipt.lastRejectionKind != RejectionKind.ROLE_SCHEMA
                        || !SHA256.matcher(receipt.lastRejectionDigest).matches()) {
                    throw new IllegalArgumentException(
                            "receipt contradicts ROLE_SCHEMA_REJECTED_AFTER_RECOVERY facts"
                    );
                }
            }
        }
    }

    private static Set<String> fieldNames(JsonNode value) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private static String requiredText(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank text");
        }
        return node.textValue();
    }

    private static boolean requiredBoolean(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isBoolean()) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return node.booleanValue();
    }

    private static String optionalDigest(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException(field + " must be blank or sha256 digest");
        }
        String digest = node.textValue();
        if (!digest.isEmpty() && !SHA256.matcher(digest).matches()) {
            throw new IllegalArgumentException(field + " must be blank or sha256 digest");
        }
        return digest;
    }

    private static List<String> requiredStringList(JsonNode value, String field, boolean nonEmpty) {
        if (value == null || !value.isArray() || (nonEmpty && value.isEmpty())) {
            throw new IllegalArgumentException(field + " must be " + (nonEmpty ? "a non-empty " : "a ") + "string array");
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(field + " must be a string array");
            }
            values.add(item.textValue());
        }
        return List.copyOf(values);
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String value, String message) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }
}
