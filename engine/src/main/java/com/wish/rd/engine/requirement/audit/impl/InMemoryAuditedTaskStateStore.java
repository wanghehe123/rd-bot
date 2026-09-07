package com.wish.rd.engine.requirement.audit.impl;

import com.wish.rd.engine.requirement.audit.AuditRun;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateStore;
import com.wish.rd.engine.requirement.audit.CompletionBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only in-memory audited-state store with revision CAS.
 *
 * <p>Not a production truth source.
 */
public final class InMemoryAuditedTaskStateStore implements AuditedTaskStateStore {

    private final Map<String, AuditedTaskState> heads = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, AuditedTaskState>> revisions = new ConcurrentHashMap<>();
    private final Map<String, List<AuditRun>> runs = new ConcurrentHashMap<>();
    private final Map<String, String> runsByCommand = new ConcurrentHashMap<>();
    private final Map<String, CompletionBinding> bindings = new ConcurrentHashMap<>();

    @Override
    public synchronized Optional<AuditedTaskState> head(String taskId) {
        return Optional.ofNullable(heads.get(normalize(taskId)));
    }

    @Override
    public synchronized Optional<AuditedTaskState> findRevision(String taskId, long stateVersion) {
        Map<Long, AuditedTaskState> taskRevisions = revisions.get(normalize(taskId));
        if (taskRevisions == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(taskRevisions.get(stateVersion));
    }

    @Override
    public synchronized AuditedTaskState initializeIfAbsent(AuditedTaskState initial) {
        if (initial == null) {
            throw new IllegalArgumentException("initial state must not be null");
        }
        if (initial.stateVersion() != 1L) {
            throw new IllegalArgumentException("initializeIfAbsent requires state_version=1");
        }
        String taskId = initial.taskId();
        AuditedTaskState head = heads.get(taskId);
        if (head != null) {
            if (!head.contractRef().acceptanceCriteriaHash()
                    .equals(initial.contractRef().acceptanceCriteriaHash())) {
                throw new IllegalStateException("audited contractRef mismatch: " + taskId);
            }
            return head;
        }
        Map<Long, AuditedTaskState> taskRevisions = revisions.computeIfAbsent(taskId, ignored -> new LinkedHashMap<>());
        taskRevisions.put(1L, initial);
        heads.put(taskId, initial);
        return initial;
    }

    @Override
    public synchronized AuditedTaskState appendRevision(
            long expectedStateVersion,
            AuditedTaskState next,
            AuditRun auditRun
    ) {
        if (next == null) {
            throw new IllegalArgumentException("next state must not be null");
        }
        if (auditRun == null) {
            throw new IllegalArgumentException("audit run must not be null");
        }
        if (!next.taskId().equals(auditRun.taskId())) {
            throw new IllegalArgumentException("audit run taskId must match state taskId");
        }
        if (expectedStateVersion != next.stateVersion()) {
            throw new IllegalStateException(
                    "expectedStateVersion mismatch: expected " + expectedStateVersion
                            + " but next was " + next.stateVersion());
        }
        String taskId = next.taskId();
        Map<Long, AuditedTaskState> taskRevisions = revisions.computeIfAbsent(taskId, ignored -> new LinkedHashMap<>());
        AuditedTaskState existingRevision = taskRevisions.get(next.stateVersion());
        if (existingRevision != null) {
            if (existingRevision.stateHash().equals(next.stateHash())) {
                rememberRun(auditRun);
                return heads.get(taskId);
            }
            throw new IllegalStateException(
                    "same version has a different hash: " + taskId + "#" + next.stateVersion());
        }
        AuditedTaskState head = heads.get(taskId);
        if (next.stateVersion() == 1L) {
            if (head != null) {
                throw new IllegalStateException("expectedStateVersion mismatch: head already exists for " + taskId);
            }
        } else if (head == null || head.stateVersion() != next.stateVersion() - 1L) {
            throw new IllegalStateException(
                    "expectedStateVersion mismatch: cannot write " + next.stateVersion()
                            + " when head is " + (head == null ? "absent" : head.stateVersion()));
        }
        rememberRun(auditRun);
        taskRevisions.put(next.stateVersion(), next);
        heads.put(taskId, next);
        return next;
    }

    @Override
    public synchronized List<AuditRun> listAuditRuns(String taskId) {
        return List.copyOf(runs.getOrDefault(normalize(taskId), List.of()));
    }

    @Override
    public synchronized void bindCompletion(String taskId, String auditRunId, long stateVersion, String stateHash) {
        CompletionBinding binding = new CompletionBinding(taskId, auditRunId, stateVersion, stateHash);
        AuditedTaskState head = heads.get(binding.taskId());
        if (head == null
                || head.stateVersion() != binding.stateVersion()
                || !head.stateHash().equals(binding.stateHash())) {
            throw new IllegalStateException("completion binding does not match audited head: " + binding.taskId());
        }
        boolean knownRun = listAuditRuns(binding.taskId()).stream()
                .anyMatch(run -> run.auditRunId().equals(binding.auditRunId()));
        if (!knownRun) {
            throw new IllegalStateException("completion binding audit run is unknown: " + binding.auditRunId());
        }
        CompletionBinding existing = bindings.get(binding.taskId());
        if (existing != null && !existing.equals(binding)) {
            throw new IllegalStateException("completion binding already exists for " + binding.taskId());
        }
        bindings.put(binding.taskId(), binding);
    }

    @Override
    public synchronized Optional<CompletionBinding> completionBinding(String taskId) {
        return Optional.ofNullable(bindings.get(normalize(taskId)));
    }

    private void rememberRun(AuditRun auditRun) {
        String existingId = runsByCommand.putIfAbsent(auditRun.commandId(), auditRun.auditRunId());
        if (existingId != null && !existingId.equals(auditRun.auditRunId())) {
            throw new IllegalStateException("duplicate audit run commandId: " + auditRun.commandId());
        }
        if (existingId != null) {
            return;
        }
        runs.computeIfAbsent(auditRun.taskId(), ignored -> new ArrayList<>()).add(auditRun);
        AuditRun.requireUniqueCommandIds(runs.get(auditRun.taskId()));
    }

    private static String normalize(String taskId) {
        String normalized = taskId == null ? "" : taskId.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return normalized;
    }
}
