package com.wish.rd.engine.requirement.audit;

/**
 * Compact handoff trust labels. Notes and facts are {@code UNTRUSTED} unless a FACT
 * record was already promoted to {@code COMPLETED} by an {@code AuditRun}.
 */
public final class AuditedHandoffTrust {

    public static final String UNTRUSTED = "UNTRUSTED";

    private AuditedHandoffTrust() {
    }

    /**
     * Returns the compact-fact trust label for one statement.
     *
     * @param head current audited head, or {@code null}
     * @param kind fact kind from executor JSON
     * @param statement fact statement
     * @return {@code UNTRUSTED} or {@code VERIFIED(auditRunId=…)}
     */
    public static String trustForFact(AuditedTaskState head, String kind, String statement) {
        String stmt = statement == null ? "" : statement.strip();
        if (head == null || stmt.isBlank()) {
            return UNTRUSTED;
        }
        String prefixed = prefixed(kind, stmt);
        for (AuditedRecord record : head.records()) {
            if (record.kind() != AuditedRecordKind.FACT
                    || record.status() != AuditedRecordStatus.COMPLETED) {
                continue;
            }
            if (!stmt.equals(record.text()) && !prefixed.equals(record.text())) {
                continue;
            }
            String auditRunId = record.evidenceRefs().isEmpty()
                    ? head.lastAuditRunId()
                    : record.evidenceRefs().getFirst().auditRunId();
            if (auditRunId != null && !auditRunId.isBlank()) {
                return "VERIFIED(auditRunId=" + auditRunId.strip() + ")";
            }
        }
        return UNTRUSTED;
    }

    private static String prefixed(String kind, String statement) {
        String safeKind = kind == null ? "" : kind.strip();
        return safeKind.isBlank() ? statement : safeKind + ":" + statement;
    }
}
