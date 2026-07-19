package com.wish.rd.engine.requirement.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.review.model.AiReviewDecision;
import com.wish.rd.engine.requirement.review.model.AiReviewDimension;
import com.wish.rd.engine.requirement.review.model.AiReviewFinding;
import com.wish.rd.engine.requirement.review.model.AiReviewResult;
import com.wish.rd.engine.requirement.review.model.AiReviewSeverity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strictly validates AI delivery-review JSON and rejects invented evidence references. */
public final class AiReviewResultValidator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<AgentRole> REQUIREMENT_ROLES = Set.copyOf(AgentRole.requirementDeliveryOrder());

    /**
     * Parses and validates one model response.
     *
     * @param rawJson model response JSON
     * @param allowedSourceIds immutable package manifest IDs
     * @return validated AI review result
     * @throws IllegalArgumentException when the response violates the contract
     */
    public AiReviewResult validate(String rawJson, Set<String> allowedSourceIds) {
        String normalized = rawJson == null ? "" : rawJson.strip();
        JsonNode root;
        try {
            root = OBJECT_MAPPER.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(normalized);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid AI review JSON", exception);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("AI review root must be an object");
        }

        AiReviewDecision decision = enumValue(root, "decision", AiReviewDecision.class);
        int score = boundedScore(root, "score");
        String summary = requiredText(root, "summary");
        AgentRole retryFromRole = retryFromRole(root, decision);
        Set<String> allowed = allowedSourceIds == null ? Set.of() : Set.copyOf(allowedSourceIds);
        List<AiReviewDimension> dimensions = dimensions(root.path("dimensions"), allowed);
        List<AiReviewFinding> findings = findings(root.path("findings"), allowed);

        if (decision == AiReviewDecision.NOT_OK && findings.stream().noneMatch(AiReviewFinding::isBlocking)) {
            throw new IllegalArgumentException("NOT_OK requires a HIGH or CRITICAL finding");
        }
        return new AiReviewResult(decision, score, summary, retryFromRole, dimensions, findings, normalized);
    }

    private static AgentRole retryFromRole(JsonNode root, AiReviewDecision decision) {
        String value = text(root.path("retryFromRole"));
        if (decision == AiReviewDecision.OK) {
            if (!value.isBlank()) {
                throw new IllegalArgumentException("OK must not contain retryFromRole");
            }
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(decision + " requires retryFromRole");
        }
        try {
            AgentRole role = AgentRole.valueOf(value);
            if (!REQUIREMENT_ROLES.contains(role)) {
                throw new IllegalArgumentException("retryFromRole is not a requirement role: " + value);
            }
            return role;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid retryFromRole: " + value, exception);
        }
    }

    private static List<AiReviewDimension> dimensions(JsonNode values, Set<String> allowed) {
        requireArray(values, "dimensions");
        List<AiReviewDimension> result = new ArrayList<>();
        for (JsonNode value : values) {
            requireObject(value, "dimension");
            result.add(new AiReviewDimension(
                    requiredText(value, "name"),
                    boundedScore(value, "score"),
                    requiredText(value, "reason"),
                    sourceIds(value.path("sourceIds"), allowed)
            ));
        }
        return List.copyOf(result);
    }

    private static List<AiReviewFinding> findings(JsonNode values, Set<String> allowed) {
        requireArray(values, "findings");
        List<AiReviewFinding> result = new ArrayList<>();
        for (JsonNode value : values) {
            requireObject(value, "finding");
            result.add(new AiReviewFinding(
                    enumValue(value, "severity", AiReviewSeverity.class),
                    requiredText(value, "title"),
                    requiredText(value, "detail"),
                    sourceIds(value.path("sourceIds"), allowed),
                    requiredText(value, "suggestion")
            ));
        }
        return List.copyOf(result);
    }

    private static List<String> sourceIds(JsonNode values, Set<String> allowed) {
        requireArray(values, "sourceIds");
        Set<String> unique = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            String sourceId = text(value);
            if (sourceId.isBlank()) {
                throw new IllegalArgumentException("sourceIds must contain non-blank strings");
            }
            if (!allowed.contains(sourceId)) {
                throw new IllegalArgumentException("AI review referenced unknown source: " + sourceId);
            }
            if (unique.add(sourceId)) {
                result.add(sourceId);
            }
        }
        return List.copyOf(result);
    }

    private static int boundedScore(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        int score = value.asInt();
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
        return score;
    }

    private static String requiredText(JsonNode root, String field) {
        String value = text(root.path(field));
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static <E extends Enum<E>> E enumValue(JsonNode root, String field, Class<E> type) {
        String value = requiredText(root, field);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid " + field + ": " + value, exception);
        }
    }

    private static void requireArray(JsonNode value, String field) {
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
    }

    private static String text(JsonNode value) {
        return value == null || !value.isTextual() ? "" : value.asText().strip();
    }
}
