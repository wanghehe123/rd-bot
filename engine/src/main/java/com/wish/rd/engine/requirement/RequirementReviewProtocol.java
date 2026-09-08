package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * Host classification for a requirement-reviewer protocol result.
 *
 * <p>An explicit {@code decision} of {@code NEED_INFO} always asks the operator, even
 * when the execution {@code status} reports {@code SUCCESS}/{@code OK}. Otherwise,
 * approval (an approved {@code decision}, or a {@code SUCCESS}/{@code OK} status with
 * no NEED_INFO decision) proceeds even with advisory {@code missingInformation};
 * {@code NEED_INFO} on {@code status}/{@code feasibility} or non-empty
 * {@code missingInformation} without approval are operator-ASK outcomes.
 * {@code REJECTED}/{@code UNSAFE}/{@code FAILED} remain fail-closed.
 */
public final class RequirementReviewProtocol {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RequirementReviewProtocol() {
    }

    /** How Host should continue after a reviewer protocol object. */
    public enum Disposition {
        PROCEED,
        ASK_OPERATOR,
        FAIL_CLOSED
    }

    /**
     * Classifies a reviewer result JSON object.
     *
     * @param resultJson reviewer or aggregated execution JSON
     * @return disposition
     */
    public static Disposition disposition(String resultJson) {
        JsonNode root = parse(resultJson);
        if (root == null || !root.isObject()) {
            return Disposition.FAIL_CLOSED;
        }
        String status = normalized(root.path("status"));
        String decision = normalized(root.path("decision"));
        String feasibility = normalized(root.path("feasibility"));
        if (hardFail(status) || hardFail(decision) || hardFail(feasibility)) {
            return Disposition.FAIL_CLOSED;
        }
        // An explicit NEED_INFO decision asks the operator first: an execution status
        // like SUCCESS/OK describes the run, not reviewer approval, and must not mask
        // a real missing-input request.
        if (needInfo(decision)) {
            return Disposition.ASK_OPERATOR;
        }
        // Explicit approval proceeds even when the agent lists advisory gaps under
        // missingInformation (common with APPROVED/CAN_DO). Without approval,
        // NEED_INFO on status/feasibility or missingInformation forces an operator ASK.
        if (isApproved(decision) || isApproved(status)) {
            return Disposition.PROCEED;
        }
        if (needInfo(status) || needInfo(feasibility) || hasMissingInformation(root)) {
            return Disposition.ASK_OPERATOR;
        }
        if (status.isBlank() && decision.isBlank() && feasibility.isBlank()) {
            return Disposition.FAIL_CLOSED;
        }
        return Disposition.PROCEED;
    }

    /**
     * Returns whether Manager should ASK.
     *
     * @param resultJson reviewer or aggregated execution JSON
     * @return true when operator material is required
     */
    public static boolean asksOperator(String resultJson) {
        return disposition(resultJson) == Disposition.ASK_OPERATOR;
    }

    /**
     * Returns whether the reviewer result must fail-close the task.
     *
     * @param resultJson reviewer or aggregated execution JSON
     * @return true when Host must stop as FAILED_NEEDS_HUMAN
     */
    public static boolean failsClosed(String resultJson) {
        return disposition(resultJson) == Disposition.FAIL_CLOSED;
    }

    /**
     * Prefers the reviewer summary, then common reason fields.
     *
     * @param resultJson reviewer JSON
     * @param fallback summary or default reason
     * @return operator-visible reason
     */
    public static String askReason(String resultJson, String fallback) {
        JsonNode root = parse(resultJson);
        if (root == null || !root.isObject()) {
            return firstNonBlank(fallback, "requirement review needs operator input");
        }
        return firstNonBlank(
                fallback,
                text(root.path("reason")),
                text(root.path("errorMessage")),
                text(root.path("summary")),
                "requirement review needs operator input"
        );
    }

    private static boolean hardFail(String value) {
        return switch (value) {
            case "UNSAFE", "REJECTED", "REJECT", "FAILED", "FAILURE", "BLOCKED", "NEEDS_HUMAN",
                    "FAILED_NEEDS_HUMAN" -> true;
            default -> false;
        };
    }

    private static boolean needInfo(String value) {
        return "NEED_INFO".equals(value);
    }

    private static boolean isApproved(String value) {
        return switch (value) {
            case "APPROVED", "PASS", "PASSED", "GO", "SUCCESS", "OK" -> true;
            default -> false;
        };
    }

    private static boolean hasMissingInformation(JsonNode root) {
        JsonNode missing = root.path("missingInformation");
        return missing.isArray() && missing.size() > 0;
    }

    private static JsonNode parse(String resultJson) {
        try {
            return OBJECT_MAPPER.readTree(resultJson == null || resultJson.isBlank() ? "{}" : resultJson);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static String normalized(JsonNode node) {
        return text(node).toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static String text(JsonNode node) {
        return node == null || !node.isTextual() ? "" : node.asText().strip();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }
}
