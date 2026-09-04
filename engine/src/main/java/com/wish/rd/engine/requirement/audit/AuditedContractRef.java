package com.wish.rd.engine.requirement.audit;

/**
 * Frozen contract identity captured when the audited state is initialized.
 *
 * @param acceptanceCriteriaHash canonical hash of the frozen acceptance-criteria JSON
 * @param taskVersionAtFreeze task version at initialization
 * @param fencingTokenAtFreeze fencing token at initialization
 */
public record AuditedContractRef(
        String acceptanceCriteriaHash,
        long taskVersionAtFreeze,
        long fencingTokenAtFreeze
) {
    public AuditedContractRef {
        acceptanceCriteriaHash = requireText(acceptanceCriteriaHash, "acceptanceCriteriaHash");
        if (taskVersionAtFreeze < 0L) {
            throw new IllegalArgumentException("taskVersionAtFreeze must not be negative");
        }
        if (fencingTokenAtFreeze <= 0L) {
            throw new IllegalArgumentException("fencingTokenAtFreeze must be positive");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
