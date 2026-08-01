package com.wish.rd.rag.project.agent.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Canonical hashing and redaction helpers for role execution facts. */
public final class AgentFactCanonical {

    private static final Pattern CREDENTIAL_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|token|password|secret|authorization)\\s*[:=]\\s*\\S+"
    );

    private AgentFactCanonical() {
    }

    public static String redactStatement(String statement) {
        if (statement == null || statement.isBlank()) {
            return "";
        }
        return CREDENTIAL_PATTERN.matcher(statement.strip()).replaceAll("$1=[REDACTED]");
    }

    public static String canonicalFactHash(RoleExecutionFact fact) {
        return sha256(canonicalFactJson(fact));
    }

    public static String canonicalFactJson(RoleExecutionFact fact) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("factId", fact.factId());
        canonical.put("kind", fact.kind().name());
        canonical.put("statement", redactStatement(fact.statement()));
        putIfPresent(canonical, "sourceArtifactId", fact.sourceArtifactId());
        putIfPresent(canonical, "sourceStageRunId", fact.sourceStageRunId());
        putIfPresent(canonical, "sourceToolCallId", fact.sourceToolCallId());
        putIfPresent(canonical, "repoRevision", fact.repoRevision());
        putIfPresent(canonical, "workspaceFingerprint", fact.workspaceFingerprint());
        putIfPresent(canonical, "observedAt", fact.observedAt());
        putIfPresent(canonical, "commandHash", fact.commandHash());
        canonical.put("freshnessPolicy", fact.freshnessPolicy().name());
        putIfPresent(canonical, "expiresAt", fact.expiresAt());
        if (fact.confidence() != null) {
            canonical.put("confidence", fact.confidence());
        }
        return toSortedJson(canonical);
    }

    public static List<String> deriveEnvironmentNotes(
            List<RoleExecutionFact> facts,
            FactFreshnessEvaluator.FreshnessContext context
    ) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        List<RoleExecutionFact> sorted = new ArrayList<>(facts);
        sorted.sort(Comparator.comparing(RoleExecutionFact::factId));
        List<String> notes = new ArrayList<>();
        for (RoleExecutionFact fact : sorted) {
            if (FactFreshnessEvaluator.isPromptEligible(fact, context)) {
                notes.add(redactStatement(fact.statement()));
            }
        }
        return List.copyOf(notes);
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String toSortedJson(Map<String, Object> canonical) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : canonical.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value instanceof Number number) {
                builder.append(number);
            } else {
                builder.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        builder.append('}');
        return builder.toString();
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
