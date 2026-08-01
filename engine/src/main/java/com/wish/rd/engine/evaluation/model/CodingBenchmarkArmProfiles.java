package com.wish.rd.engine.evaluation.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Deterministic mapping from {@link CodingBenchmarkArm} to {@link CodingBenchmarkArmProfile}. */
public final class CodingBenchmarkArmProfiles {

    private static final List<String> ROLES_A = List.of("CODING_AGENT");
    private static final List<String> ROLES_B = List.of("REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT");
    private static final List<String> ROLES_C = List.of("REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT");
    private static final List<String> ROLES_D = List.of("REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT");

    private static final Map<CodingBenchmarkArm, CodingBenchmarkArmProfile> BY_ARM =
            Stream.of(
                            new CodingBenchmarkArmProfile(CodingBenchmarkArm.A, ROLES_A, false, false),
                            new CodingBenchmarkArmProfile(CodingBenchmarkArm.B, ROLES_B, false, false),
                            new CodingBenchmarkArmProfile(CodingBenchmarkArm.C, ROLES_C, true, false),
                            new CodingBenchmarkArmProfile(CodingBenchmarkArm.D, ROLES_D, true, true))
                    .collect(Collectors.toUnmodifiableMap(CodingBenchmarkArmProfile::arm, Function.identity()));

    private CodingBenchmarkArmProfiles() {}

    /**
     * Returns the pre-registered profile for the given arm.
     *
     * @throws NullPointerException if arm is null
     * @throws IllegalArgumentException if arm is unknown
     */
    public static CodingBenchmarkArmProfile of(CodingBenchmarkArm arm) {
        Objects.requireNonNull(arm, "arm must not be null");
        CodingBenchmarkArmProfile profile = BY_ARM.get(arm);
        if (profile == null) {
            throw new IllegalArgumentException("unknown arm: " + arm);
        }
        return profile;
    }
}
