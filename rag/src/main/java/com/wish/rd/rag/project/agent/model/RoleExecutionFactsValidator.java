package com.wish.rd.rag.project.agent.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Pure validation for facts[] and legacy environmentNotes derivation. */
public final class RoleExecutionFactsValidator {

    private RoleExecutionFactsValidator() {
    }

    public static List<String> validateFactsProtocol(
            JsonNode root,
            ContextProtocolVersion protocolVersion,
            FactFreshnessEvaluator.FreshnessContext freshnessContext
    ) {
        Objects.requireNonNull(protocolVersion, "protocolVersion must not be null");
        if (protocolVersion != ContextProtocolVersion.FACTS_V1) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        if (root == null || !root.isObject()) {
            errors.add("result json root must be an object");
            return List.copyOf(errors);
        }
        JsonNode factsNode = root.get("facts");
        if (factsNode == null) {
            errors.add("facts must be present when contextProtocolVersion is FACTS_V1");
            return List.copyOf(errors);
        }
        if (!factsNode.isArray()) {
            errors.add("facts must be an array");
            return List.copyOf(errors);
        }
        List<RoleExecutionFact> facts = new ArrayList<>();
        int index = 0;
        for (JsonNode factNode : factsNode) {
            errors.addAll(validateFactNode(factNode, "facts[" + index + "]"));
            parseFactNode(factNode).ifPresent(facts::add);
            index++;
        }
        if (root.has("environmentNotes")) {
            errors.addAll(validateEnvironmentNotesConsistency(root.get("environmentNotes"), facts, freshnessContext));
        }
        return List.copyOf(errors);
    }

    public static List<String> validateFactNode(JsonNode factNode, String prefix) {
        List<String> errors = new ArrayList<>();
        if (factNode == null || !factNode.isObject()) {
            errors.add(prefix + " must be an object");
            return errors;
        }
        requireText(factNode, "factId", prefix + ".factId", errors);
        FactKind kind = parseEnum(factNode, "kind", FactKind.class, prefix + ".kind", errors);
        String statement = requireBoundedStatement(factNode, "statement", prefix + ".statement", errors);
        FactFreshnessPolicy freshnessPolicy = parseEnum(
                factNode,
                "freshnessPolicy",
                FactFreshnessPolicy.class,
                prefix + ".freshnessPolicy",
                errors
        );
        if (kind == FactKind.OBSERVED) {
            requireText(factNode, "sourceArtifactId", prefix + ".sourceArtifactId", errors);
            requireText(factNode, "sourceStageRunId", prefix + ".sourceStageRunId", errors);
            requireText(factNode, "observedAt", prefix + ".observedAt", errors);
            if (factNode.has("sourceToolCallId") && factNode.get("sourceToolCallId").isTextual()) {
                requireText(factNode, "sourceToolCallId", prefix + ".sourceToolCallId", errors);
            }
        }
        if (freshnessPolicy == FactFreshnessPolicy.SAME_REVISION) {
            requireText(factNode, "repoRevision", prefix + ".repoRevision", errors);
        }
        if (freshnessPolicy == FactFreshnessPolicy.SAME_WORKSPACE) {
            requireText(factNode, "workspaceFingerprint", prefix + ".workspaceFingerprint", errors);
        }
        if (freshnessPolicy == FactFreshnessPolicy.TTL) {
            requireText(factNode, "expiresAt", prefix + ".expiresAt", errors);
        }
        if ((kind == FactKind.INFERRED || kind == FactKind.HISTORICAL) && factNode.has("confidence")) {
            JsonNode confidence = factNode.get("confidence");
            if (!confidence.isNumber() || confidence.doubleValue() < 0.0d || confidence.doubleValue() > 1.0d) {
                errors.add(prefix + ".confidence must be between 0 and 1");
            }
        }
        if (kind == FactKind.OBSERVED && statement != null && statement.isBlank()) {
            errors.add(prefix + ".statement must not be blank");
        }
        return errors;
    }

    public static List<String> validateEnvironmentNotesConsistency(
            JsonNode environmentNotesNode,
            List<RoleExecutionFact> facts,
            FactFreshnessEvaluator.FreshnessContext freshnessContext
    ) {
        if (environmentNotesNode == null || environmentNotesNode.isNull()) {
            return List.of();
        }
        if (!environmentNotesNode.isArray()) {
            return List.of("environmentNotes must be an array");
        }
        List<String> submitted = new ArrayList<>();
        for (JsonNode note : environmentNotesNode) {
            if (!note.isTextual() || note.asText("").isBlank()) {
                return List.of("environmentNotes entries must be non-blank strings");
            }
            submitted.add(note.asText("").strip());
        }
        List<String> derived = AgentFactCanonical.deriveEnvironmentNotes(facts, freshnessContext);
        if (!submitted.equals(derived)) {
            return List.of("environmentNotes must match fresh OBSERVED facts derivation");
        }
        return List.of();
    }

    private static java.util.Optional<RoleExecutionFact> parseFactNode(JsonNode factNode) {
        if (factNode == null || !factNode.isObject()) {
            return java.util.Optional.empty();
        }
        try {
            FactKind kind = FactKind.valueOf(text(factNode, "kind").toUpperCase(Locale.ROOT));
            FactFreshnessPolicy policy = FactFreshnessPolicy.valueOf(
                    text(factNode, "freshnessPolicy").toUpperCase(Locale.ROOT)
            );
            Double confidence = factNode.has("confidence") && factNode.get("confidence").isNumber()
                    ? factNode.get("confidence").doubleValue()
                    : null;
            FactFreshnessStatus status = factNode.has("freshnessStatus")
                    ? FactFreshnessStatus.valueOf(text(factNode, "freshnessStatus").toUpperCase(Locale.ROOT))
                    : FactFreshnessStatus.FRESH;
            return java.util.Optional.of(new RoleExecutionFact(
                    text(factNode, "factId"),
                    kind,
                    text(factNode, "statement"),
                    optionalText(factNode, "sourceArtifactId"),
                    optionalText(factNode, "sourceStageRunId"),
                    optionalText(factNode, "sourceToolCallId"),
                    optionalText(factNode, "repoRevision"),
                    optionalText(factNode, "workspaceFingerprint"),
                    optionalText(factNode, "observedAt"),
                    optionalText(factNode, "commandHash"),
                    policy,
                    optionalText(factNode, "expiresAt"),
                    confidence,
                    status
            ));
        } catch (RuntimeException exception) {
            return java.util.Optional.empty();
        }
    }

    private static <E extends Enum<E>> E parseEnum(
            JsonNode node,
            String field,
            Class<E> enumType,
            String label,
            List<String> errors
    ) {
        String value = text(node, field);
        if (value.isBlank()) {
            errors.add(label + " must not be blank");
            return null;
        }
        try {
            return Enum.valueOf(enumType, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            errors.add(label + " is invalid");
            return null;
        }
    }

    private static String requireBoundedStatement(
            JsonNode node,
            String field,
            String label,
            List<String> errors
    ) {
        String value = text(node, field);
        if (value.isBlank()) {
            errors.add(label + " must not be blank");
            return null;
        }
        if (value.length() > RoleExecutionFact.MAX_STATEMENT_LENGTH) {
            errors.add(label + " exceeds max length " + RoleExecutionFact.MAX_STATEMENT_LENGTH);
        }
        return value;
    }

    private static void requireText(JsonNode node, String field, String label, List<String> errors) {
        if (!node.has(field) || !node.get(field).isTextual() || node.get(field).asText("").isBlank()) {
            errors.add(label + " must not be blank");
        }
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").strip();
    }

    private static String optionalText(JsonNode node, String field) {
        String value = text(node, field);
        return value.isBlank() ? null : value;
    }
}
