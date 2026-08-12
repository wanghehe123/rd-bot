package com.wish.rd.engine.scheduling.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable Host scheduling policy supplied by production configuration or an authoritative
 * project policy before requirement stage commands are admitted.
 *
 * <p>{@code RequirementDeliveryDispatchService} passes this value through the fair planner and
 * durable claim store so every scheduler instance evaluates the same limits and project weights.
 */
public record RequirementDeliverySchedulingPolicy(
        FairScheduleLimits limits,
        Map<String, Integer> projectWeights,
        long commandDeadlineMillis,
        String defaultProviderId
) {

    private static final long DEFAULT_COMMAND_DEADLINE_MILLIS = 60L * 60L * 1000L;
    private static final String DEFAULT_PROVIDER_ID = "local-provider";

    public RequirementDeliverySchedulingPolicy {
        limits = limits == null ? FairScheduleLimits.defaults() : limits;
        projectWeights = normalizeProjectWeights(projectWeights);
        commandDeadlineMillis = Math.max(1L, commandDeadlineMillis);
        defaultProviderId = textOrDefault(defaultProviderId, DEFAULT_PROVIDER_ID);
    }

    /** Returns the conservative local/test policy used only when no production policy is wired. */
    public static RequirementDeliverySchedulingPolicy defaults() {
        return new RequirementDeliverySchedulingPolicy(
                FairScheduleLimits.defaults(),
                Map.of(),
                DEFAULT_COMMAND_DEADLINE_MILLIS,
                DEFAULT_PROVIDER_ID
        );
    }

    private static Map<String, Integer> normalizeProjectWeights(Map<String, Integer> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> normalized = new LinkedHashMap<>();
        values.forEach((projectId, weight) -> {
            String key = projectId == null ? "" : projectId.strip();
            if (!key.isBlank()) {
                normalized.put(key, Math.max(1, weight == null ? 1 : weight));
            }
        });
        return Map.copyOf(normalized);
    }

    private static String textOrDefault(String value, String fallback) {
        String normalized = value == null ? "" : value.strip();
        return normalized.isBlank() ? fallback : normalized;
    }
}
