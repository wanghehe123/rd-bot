package com.wish.rd.engine.requirement.policy.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;

/**
 * Canonical, externally-evaluated policy decision proposed for one PLAN_READY ledger generation.
 *
 * <p>The evaluator runs before the host transaction. This value carries only its canonical
 * decision; it never gives the evaluator access to task, command, or ledger mutation.
 */
public record RecordRequirementPolicyDecisionCommand(
        String policyRunId,
        String taskId,
        long expectedTaskVersion,
        long expectedFencingToken,
        String planDigest,
        String policyJson,
        String policyDigest,
        String policyAction,
        RequirementPolicyRetryContext retryContext
) {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "ALLOWED", "WAITING_APPROVAL", "NEED_INFO", "UNSAFE");

    /** Validates the exact task snapshot and RFC 8785 canonical decision document. */
    public RecordRequirementPolicyDecisionCommand {
        retryContext = java.util.Objects.requireNonNull(retryContext, "retryContext must not be null");
        policyRunId = require(policyRunId, "policyRunId");
        taskId = require(taskId, "taskId");
        if (expectedTaskVersion < 0L) {
            throw new IllegalArgumentException("expectedTaskVersion must not be negative");
        }
        if (expectedFencingToken <= 0L) {
            throw new IllegalArgumentException("expectedFencingToken must be positive");
        }
        planDigest = require(planDigest, "planDigest");
        policyJson = requireJson(policyJson, "policyJson");
        policyDigest = require(policyDigest, "policyDigest");
        policyAction = require(policyAction, "policyAction");
        if (!SUPPORTED_ACTIONS.contains(policyAction)) {
            throw new IllegalArgumentException("policyAction is not a supported host policy decision");
        }
        String canonical = RequirementPolicyRun.canonicalizeJson(policyJson);
        if (!canonical.equals(policyJson)) {
            throw new IllegalArgumentException("policyJson must already be RFC 8785 canonical");
        }
        if (!RequirementPolicyRun.canonicalJsonDigest(policyJson).equals(policyDigest)) {
            throw new IllegalArgumentException("policyDigest does not match RFC 8785 canonical policyJson");
        }
        if (!policyAction.equals(policyActionIn(policyJson))) {
            throw new IllegalArgumentException("policyAction must match canonical policyJson.policyAction");
        }
        if (!planDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("planDigest must be sha256: followed by 64 lowercase hexadecimal characters");
        }
        retryContext.requireSourcePlanDigest(planDigest);
    }

    /** Compatibility constructor for normal policy operations, with explicit empty retry context. */
    public RecordRequirementPolicyDecisionCommand(
            String policyRunId, String taskId, long expectedTaskVersion, long expectedFencingToken,
            String planDigest, String policyJson, String policyDigest, String policyAction
    ) {
        this(policyRunId, taskId, expectedTaskVersion, expectedFencingToken, planDigest, policyJson,
                policyDigest, policyAction, RequirementPolicyRetryContext.empty());
    }

    private static String policyActionIn(String policyJson) {
        try {
            JsonNode root = JSON.readTree(policyJson);
            JsonNode action = root == null ? null : root.get("policyAction");
            return action != null && action.isTextual() ? action.textValue() : "";
        } catch (JsonProcessingException impossibleAfterCanonicalValidation) {
            throw new IllegalArgumentException("policyJson must contain a textual policyAction",
                    impossibleAfterCanonicalValidation);
        }
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String requireJson(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
