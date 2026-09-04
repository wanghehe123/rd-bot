package com.wish.rd.engine.requirement.audit;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for audited task state. Production uses PostgreSQL;
 * {@code InMemoryAuditedTaskStateStore} is test-only.
 */
public interface AuditedTaskStateStore {

    /**
     * Loads the current head, if any.
     *
     * @param taskId task id
     * @return head snapshot
     */
    Optional<AuditedTaskState> head(String taskId);

    /**
     * Writes {@code state_version=1} when the head is absent.
     *
     * <p>Same contract hash is idempotent. A different frozen-criteria hash fails closed.
     * This is not an audit of a command and does not consume {@code UNIQUE(command_id)}.
     *
     * @param initial sealed version-1 state
     * @return stored head
     */
    AuditedTaskState initializeIfAbsent(AuditedTaskState initial);

    /**
     * Appends a revision and audit run with CAS on {@code expectedStateVersion}.
     *
     * <p>{@code expectedStateVersion} must equal {@code next.stateVersion()}. Same version and
     * hash is idempotent; same version with a different hash is rejected; a mismatched expected
     * version is rejected.
     *
     * @param expectedStateVersion version the caller intends to write
     * @param next sealed next state
     * @param auditRun audit run for this command
     * @return stored head
     */
    AuditedTaskState appendRevision(long expectedStateVersion, AuditedTaskState next, AuditRun auditRun);

    /**
     * Lists audit runs for a task in insertion order.
     *
     * @param taskId task id
     * @return immutable runs
     */
    List<AuditRun> listAuditRuns(String taskId);

    /**
     * Binds task completion to a specific audit run and state head.
     *
     * @param taskId task id
     * @param auditRunId audit run id
     * @param stateVersion head version
     * @param stateHash head hash
     */
    void bindCompletion(String taskId, String auditRunId, long stateVersion, String stateHash);

    /**
     * Loads the completion binding when present.
     *
     * @param taskId task id
     * @return binding
     */
    Optional<CompletionBinding> completionBinding(String taskId);
}
