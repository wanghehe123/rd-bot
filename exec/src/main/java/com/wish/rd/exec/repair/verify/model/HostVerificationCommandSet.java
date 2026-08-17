package com.wish.rd.exec.repair.verify.model;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Resolved host BUILD and STATIC commands for one verification attempt.
 *
 * <p>Produced by {@link com.wish.rd.exec.repair.verify.HostVerificationCommandDetector}.
 * {@code buildCommandsDeclared} / {@code staticCommandsDeclared} are true only when an
 * explicit QA profile declared that list (including an empty skip). Auto-detected
 * commands leave the flags false even when the lists are non-empty.
 *
 * @param buildCommands            BUILD command lines, possibly empty
 * @param staticCommands           STATIC command lines, possibly empty
 * @param buildCommandsDeclared    whether BUILD came from an explicit profile
 * @param staticCommandsDeclared   whether STATIC came from an explicit profile
 * @param docsOnly                 whether the candidate change-set is documentation-only
 * @param ambiguous                whether BUILD could not be resolved safely
 * @param reason                   operator-facing explanation
 */
public record HostVerificationCommandSet(
        List<String> buildCommands,
        List<String> staticCommands,
        boolean buildCommandsDeclared,
        boolean staticCommandsDeclared,
        boolean docsOnly,
        boolean ambiguous,
        String reason
) {

    public HostVerificationCommandSet {
        buildCommands = copy(buildCommands);
        staticCommands = copy(staticCommands);
        reason = reason == null ? "" : reason.strip();
    }

    private static List<String> copy(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(values.stream()
                .map(value -> value == null ? "" : value.strip())
                .filter(value -> !value.isBlank())
                .toList()));
    }
}
