package com.wish.rd.engine.requirement.audit;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One Host deterministic audit of a command's evidence.
 *
 * @param auditRunId unique run id
 * @param taskId owning task
 * @param subjectStageRunId stage run that produced the evidence, or blank for host-only commands
 * @param subjectRole role or {@code HOST_VERIFY}
 * @param commandId durable command id; unique per task
 * @param completion coverage conclusion
 * @param integrity workspace/evidence integrity
 * @param contractAudit frozen-contract alignment
 * @param verified record ids promoted this run
 * @param missing record ids still pending
 * @param untrusted conflicting or executor-claimed ids
 * @param blockers record ids that block completion
 * @param sourceRefs evidence URIs consulted
 * @param createdAtEpochMillis create time
 */
public record AuditRun(
        String auditRunId,
        String taskId,
        String subjectStageRunId,
        String subjectRole,
        String commandId,
        AuditCompletion completion,
        AuditIntegrity integrity,
        ContractAuditVerdict contractAudit,
        List<String> verified,
        List<String> missing,
        List<String> untrusted,
        List<String> blockers,
        List<String> sourceRefs,
        long createdAtEpochMillis
) {
    public static final int MAX_ID_LIST = 256;
    public static final int MAX_BLOCKERS = 32;

    public AuditRun {
        auditRunId = requireText(auditRunId, "auditRunId");
        taskId = requireText(taskId, "taskId");
        subjectStageRunId = subjectStageRunId == null ? "" : subjectStageRunId.strip();
        subjectRole = requireText(subjectRole, "subjectRole");
        commandId = requireText(commandId, "commandId");
        if (completion == null) {
            throw new IllegalArgumentException("completion must not be null");
        }
        if (integrity == null) {
            throw new IllegalArgumentException("integrity must not be null");
        }
        if (contractAudit == null) {
            throw new IllegalArgumentException("contractAudit must not be null");
        }
        verified = copyBounded(verified, "verified", MAX_ID_LIST);
        missing = copyBounded(missing, "missing", MAX_ID_LIST);
        untrusted = copyBounded(untrusted, "untrusted", MAX_ID_LIST);
        blockers = copyBounded(blockers, "blockers", MAX_BLOCKERS);
        sourceRefs = copyBounded(sourceRefs, "sourceRefs", MAX_ID_LIST);
        if (createdAtEpochMillis < 0L) {
            throw new IllegalArgumentException("createdAtEpochMillis must not be negative");
        }
    }

    /**
     * Rejects duplicate {@code commandId} values in a collected run list.
     *
     * @param runs audit runs
     */
    public static void requireUniqueCommandIds(List<AuditRun> runs) {
        List<AuditRun> safe = runs == null ? List.of() : runs;
        Set<String> seen = new HashSet<>();
        for (AuditRun run : safe) {
            if (run == null) {
                throw new IllegalArgumentException("audit runs must not contain null");
            }
            if (!seen.add(run.commandId())) {
                throw new IllegalStateException("duplicate audit run commandId: " + run.commandId());
            }
        }
    }

    private static List<String> copyBounded(List<String> values, String field, int max) {
        List<String> copied = values == null ? List.of() : List.copyOf(values);
        if (copied.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(field + " must not contain null");
        }
        if (copied.size() > max) {
            throw new IllegalArgumentException(field + " must not exceed " + max);
        }
        return copied;
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
