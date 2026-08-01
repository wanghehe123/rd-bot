package com.wish.rd.engine.evaluation.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable, frozen description of the roles, retrieval and QA behaviour activated in a
 * coding benchmark arm.
 *
 * <p>Every arm maps to exactly one profile. The profile is written as a read-only JSON file
 * into the agent container and read by the Pi bridge at startup. The host
 * {@code RequirementDeliveryEngine} never participates in a trial execution.</p>
 *
 * @see CodingBenchmarkArmProfiles
 */
public record CodingBenchmarkArmProfile(
        CodingBenchmarkArm arm,
        List<String> roles,
        boolean retrievalEnabled,
        boolean qaRepairLoopEnabled
) {
    /** Validates the immutable arm profile. */
    public CodingBenchmarkArmProfile {
        Objects.requireNonNull(arm, "arm must not be null");
        roles = roles == null ? List.of() : List.copyOf(roles);
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("at least one role is required");
        }
        for (String role : roles) {
            if (role == null || role.strip().isEmpty()) {
                throw new IllegalArgumentException("role must not be blank");
            }
        }
    }

    /**
     * @return the name of the read-only arm-profile JSON file mounted inside the agent container.
     */
    public String profileFileName() {
        return "arm-profile.json";
    }

    /**
     * @return JSON-serialisable content for this profile, suitable for writing to
     *     {@code /work/input/arm-profile.json} inside the agent container.
     */
    public String toJson() {
        var sb = new StringBuilder(256);
        sb.append("{\"arm\":\"");
        sb.append(arm.name());
        sb.append("\",\"roles\":[");
        for (int i = 0; i < roles.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(roles.get(i)).append('"');
        }
        sb.append("],\"retrievalEnabled\":");
        sb.append(retrievalEnabled);
        sb.append(",\"qaRepairLoopEnabled\":");
        sb.append(qaRepairLoopEnabled);
        sb.append('}');
        return sb.toString();
    }

    @Override
    public String toString() {
        return "CodingBenchmarkArmProfile{arm=" + arm
                + ", roles=" + roles
                + ", retrievalEnabled=" + retrievalEnabled
                + ", qaRepairLoopEnabled=" + qaRepairLoopEnabled
                + "}";
    }
}
