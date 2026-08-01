package com.wish.rd.rag.project.agent.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable, versioned tool-policy data resolved into an execution snapshot. */
public record AgentToolPolicy(
        String policyId,
        long version,
        Set<String> hostAllow,
        Set<String> allow,
        Set<String> deny,
        boolean enabled
) {

    public AgentToolPolicy {
        policyId = requireText(policyId, "policyId");
        if (version <= 0L) {
            throw new IllegalArgumentException("version must be positive");
        }
        hostAllow = normalize(hostAllow);
        allow = normalize(allow);
        deny = normalize(deny);
    }

    public List<String> effectiveAllow() {
        return hostAllow.stream()
                .filter(allow::contains)
                .filter(tool -> !deny.contains(tool))
                .sorted()
                .toList();
    }

    public static AgentToolPolicy defaultCodingPolicy() {
        return new AgentToolPolicy(
                "legacy-host-bound",
                1L,
                Set.of(
                        "read",
                        "bash",
                        "edit",
                        "write",
                        "rd_submit_result",
                        "rd_todo_rewrite",
                        "rd_todo_update_status",
                        "rd_record_fact"
                ),
                Set.of(
                        "read",
                        "bash",
                        "edit",
                        "write",
                        "rd_submit_result",
                        "rd_todo_rewrite",
                        "rd_todo_update_status",
                        "rd_record_fact"
                ),
                Set.of(),
                true
        );
    }

    /** Read-only QA policy: no repository mutation tools. */
    public static AgentToolPolicy defaultQaPolicy() {
        return new AgentToolPolicy(
                "default-qa",
                1L,
                Set.of("read", "bash", "rd_submit_result"),
                Set.of("read", "bash", "rd_submit_result"),
                Set.of("edit", "write"),
                true
        );
    }

    private static Set<String> normalize(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = value == null ? "" : value.strip();
            if (!item.isBlank()) {
                normalized.add(item);
            }
        }
        return Set.copyOf(normalized);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
