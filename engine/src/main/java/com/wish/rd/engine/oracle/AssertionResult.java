package com.wish.rd.engine.oracle;

import com.wish.rd.engine.oracle.model.AssertionType;

import java.util.List;

/**
 * Result of one Host-owned assertion execution.
 *
 * @param assertionId     assertion id
 * @param assertionType   assertion type
 * @param outcome         outcome
 * @param message         diagnostic message
 * @param evidenceRefs    referenced evidence artifact paths
 */
public record AssertionResult(
        String assertionId,
        AssertionType assertionType,
        AssertionOutcome outcome,
        String message,
        List<String> evidenceRefs
) {

    public AssertionResult {
        assertionId = assertionId == null ? "" : assertionId.strip();
        if (assertionType == null) {
            throw new IllegalArgumentException("assertionType must not be null");
        }
        outcome = outcome == null ? AssertionOutcome.ERROR : outcome;
        message = message == null ? "" : message.strip();
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }

    public static AssertionResult passed(String id, AssertionType type, String message, List<String> evidence) {
        return new AssertionResult(id, type, AssertionOutcome.PASSED, message, evidence);
    }

    public static AssertionResult failed(String id, AssertionType type, String message, List<String> evidence) {
        return new AssertionResult(id, type, AssertionOutcome.FAILED, message, evidence);
    }

    public static AssertionResult unsupported(String id, AssertionType type) {
        return new AssertionResult(
                id,
                type,
                AssertionOutcome.UNSUPPORTED,
                "no host runner registered for " + type,
                List.of()
        );
    }

    public static AssertionResult error(String id, AssertionType type, String message) {
        return new AssertionResult(id, type, AssertionOutcome.ERROR, message, List.of());
    }
}
