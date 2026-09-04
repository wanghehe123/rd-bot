package com.wish.rd.engine.requirement.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses executor result JSON into bounded {@code UNTRUSTED} claims.
 *
 * <p>Only protocol-declared self-reports are copied: {@code status}, {@code testStatus},
 * {@code environmentNotes}, and {@code facts[]}. The Host never treats these as completion evidence.
 */
public final class RoleClaimExtractor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX = RoleClaimSubject.MAX_CLAIMS_PER_ATTEMPT;

    private RoleClaimExtractor() {
    }

    /**
     * Builds a claim subject from a role result JSON document.
     *
     * @param auditRunId audit run id
     * @param commandId durable command id
     * @param stageRunId originating stage run
     * @param role agent role
     * @param resultJson executor result JSON
     * @param nowEpochMillis audit time
     * @return bounded subject
     */
    public static RoleClaimSubject fromResultJson(
            String auditRunId,
            String commandId,
            String stageRunId,
            String role,
            String resultJson,
            long nowEpochMillis
    ) {
        JsonNode root = parse(resultJson);
        JsonNode payload = payload(root);
        List<RoleClaim> claims = new ArrayList<>();
        List<RoleFact> facts = new ArrayList<>();
        addScalar(claims, "status", text(payload, "status"));
        addScalar(claims, "testStatus", text(payload, "testStatus"));
        JsonNode notes = payload.path("environmentNotes");
        if (notes.isArray()) {
            for (JsonNode note : notes) {
                if (remaining(claims, facts) <= 0) {
                    break;
                }
                String value = note == null || note.isNull() ? "" : note.asText("").strip();
                if (!value.isBlank()) {
                    claims.add(new RoleClaim("environmentNotes", value));
                }
            }
        }
        JsonNode factsNode = payload.path("facts");
        if (factsNode.isArray()) {
            for (JsonNode fact : factsNode) {
                if (remaining(claims, facts) <= 0) {
                    break;
                }
                RoleFact parsed = parseFact(fact);
                if (parsed != null) {
                    facts.add(parsed);
                }
            }
        }
        return new RoleClaimSubject(auditRunId, commandId, stageRunId, role, claims, facts, nowEpochMillis);
    }

    private static JsonNode parse(String resultJson) {
        String raw = resultJson == null || resultJson.isBlank() ? "{}" : resultJson.strip();
        try {
            JsonNode node = JSON.readTree(raw);
            return node == null || node.isNull() || !node.isObject() ? JSON.createObjectNode() : node;
        } catch (Exception ignored) {
            return JSON.createObjectNode();
        }
    }

    private static JsonNode payload(JsonNode root) {
        if (root.hasNonNull("status") || root.hasNonNull("testStatus")
                || root.has("environmentNotes") || root.has("facts")) {
            return root;
        }
        JsonNode nested = root.path("resultJson");
        if (nested.isTextual()) {
            return parse(nested.asText());
        }
        if (nested.isObject()) {
            return nested;
        }
        return root;
    }

    private static void addScalar(List<RoleClaim> claims, String name, String value) {
        if (value.isBlank() || remaining(claims, List.of()) <= 0) {
            return;
        }
        claims.add(new RoleClaim(name, value));
    }

    private static RoleFact parseFact(JsonNode fact) {
        if (fact == null || fact.isNull()) {
            return null;
        }
        if (fact.isTextual()) {
            String statement = fact.asText("").strip();
            return statement.isBlank() ? null : new RoleFact(statement, "DECLARED");
        }
        if (!fact.isObject()) {
            return null;
        }
        String statement = firstNonBlank(
                text(fact, "statement"),
                text(fact, "text"),
                text(fact, "fact"));
        if (statement.isBlank()) {
            return null;
        }
        String kind = firstNonBlank(text(fact, "kind"), "DECLARED");
        return new RoleFact(statement, kind);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.asText("").strip();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private static int remaining(List<RoleClaim> claims, List<RoleFact> facts) {
        return MAX - claims.size() - facts.size();
    }
}
