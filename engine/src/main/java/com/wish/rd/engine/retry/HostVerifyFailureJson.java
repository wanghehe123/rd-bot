package com.wish.rd.engine.retry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.retry.model.TaskFailurePhase;

/**
 * Structured HOST_VERIFY identity carried on delivery result JSON.
 *
 * <p>Writers and resolvers must use these fields. Free-text {@code errorMessage}
 * is never a retry-point signal.
 */
public final class HostVerifyFailureJson {

    /** Canonical failed-stage identity for host BUILD/STATIC failures. */
    public static final String STAGE = "HOST_VERIFY";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HostVerifyFailureJson() {
    }

    /**
     * Returns whether {@code resultJson} names HOST_VERIFY and a verification run id.
     *
     * @param resultJson delivery or mutation result payload
     * @return {@code true} when both structured fields are present
     */
    public static boolean isStructuredHostVerify(String resultJson) {
        return TaskFailurePhase.HOST_VERIFY.name().equals(failurePhase(resultJson))
                && !verificationRunId(resultJson).isBlank();
    }

    /**
     * Returns the structured failure phase, or blank.
     *
     * @param resultJson delivery or mutation result payload
     * @return explicit {@code failurePhase} text
     */
    public static String failurePhase(String resultJson) {
        return text(resultJson, "failurePhase");
    }

    /**
     * Returns the structured verification run id, or blank.
     *
     * @param resultJson delivery or mutation result payload
     * @return explicit {@code failedVerificationRunId}
     */
    public static String verificationRunId(String resultJson) {
        return text(resultJson, "failedVerificationRunId");
    }

    /**
     * Returns the last non-blank mutation result JSON on a plan.
     *
     * @param resultJsons mutation payloads from newest-last order
     * @return last non-blank JSON, or empty
     */
    public static String lastNonBlank(Iterable<String> resultJsons) {
        String found = "";
        if (resultJsons == null) {
            return found;
        }
        for (String resultJson : resultJsons) {
            String normalized = resultJson == null ? "" : resultJson.strip();
            if (!normalized.isBlank()) {
                found = normalized;
            }
        }
        return found;
    }

    private static String text(String resultJson, String field) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(resultJson == null ? "{}" : resultJson);
            if (root == null || !root.isObject()) {
                return "";
            }
            return root.path(field).asText("").strip();
        } catch (Exception ignored) {
            return "";
        }
    }
}
