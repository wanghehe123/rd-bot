package com.wish.rd.rag.project.agent.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Named project strategy covering all four requirement-delivery roles. */
public record AgentStrategyProfile(
        String strategyId,
        String projectId,
        String name,
        boolean enabled,
        long version,
        List<AgentStrategyRoleSlot> roles
) {

    private static final Pattern STRATEGY_ID = Pattern.compile("[A-Za-z0-9._-]+");
    /** Fits `{projectId}:{strategyId}:{role}` into {@code rd_agent_execution_profiles.profile_id}. */
    public static final int MAX_STRATEGY_ID_LENGTH = 64;

    public AgentStrategyProfile {
        strategyId = text(strategyId);
        projectId = text(projectId);
        name = text(name);
        version = version <= 0L ? 1L : version;
        roles = List.copyOf(roles == null ? List.of() : roles);
    }

    /**
     * Stable per-role projection id used by the existing execution-profile tables.
     *
     * @param projectId project id
     * @param strategyId strategy id unique within the project
     * @param role delivery role
     * @return {@code {projectId}:{strategyId}:{ROLE}}
     */
    public static String projectedProfileId(String projectId, String strategyId, String role) {
        return text(projectId) + ":" + text(strategyId) + ":" + text(role).toUpperCase(Locale.ROOT);
    }

    /**
     * Returns whether {@code strategyId} is a persistable identifier.
     *
     * @param strategyId candidate id
     * @return {@code true} when the id is non-blank, at most 64 characters, and matches {@code [A-Za-z0-9._-]+}
     */
    public static boolean isPersistableStrategyId(String strategyId) {
        String normalized = text(strategyId);
        return !normalized.isBlank()
                && normalized.length() <= MAX_STRATEGY_ID_LENGTH
                && STRATEGY_ID.matcher(normalized).matches();
    }

    /** Finds the slot for a delivery role. */
    public AgentStrategyRoleSlot slot(String role) {
        String normalized = text(role).toUpperCase(Locale.ROOT);
        return roles.stream()
                .filter(item -> item.role().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("strategy is missing role " + normalized));
    }

    /** Returns a copy with one slot replaced. */
    public AgentStrategyProfile withSlot(AgentStrategyRoleSlot slot) {
        Objects.requireNonNull(slot, "slot must not be null");
        List<AgentStrategyRoleSlot> next = new ArrayList<>(roles.size());
        boolean replaced = false;
        for (AgentStrategyRoleSlot existing : roles) {
            if (existing.role().equals(slot.role())) {
                next.add(slot);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) {
            throw new IllegalArgumentException("strategy is missing role " + slot.role());
        }
        return new AgentStrategyProfile(strategyId, projectId, name, enabled, version, next);
    }

    /** Strips Dockerfile bodies from list responses. */
    public AgentStrategyProfile withoutDockerfileText() {
        return new AgentStrategyProfile(
                strategyId,
                projectId,
                name,
                enabled,
                version,
                roles.stream().map(AgentStrategyRoleSlot::withoutDockerfileText).toList()
        );
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
