package com.wish.rd.engine.retry.impl;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retry.TaskRetryAttemptBindingStore;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.retry.model.TaskRetryAttemptKind;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Synchronized test/local implementation preserving retry-binding immutability rules. */
public final class InMemoryTaskRetryAttemptBindingStore implements TaskRetryAttemptBindingStore {

    private final LinkedHashMap<String, TaskRetryAttemptBinding> bindings = new LinkedHashMap<>();

    @Override
    public synchronized TaskRetryAttemptBinding save(TaskRetryAttemptBinding binding) {
        if (binding == null) {
            throw new IllegalArgumentException("binding must not be null");
        }
        TaskRetryAttemptBinding existing = bindings.get(binding.bindingId());
        if (existing != null) {
            requireSame(existing, binding, "binding id");
            return existing;
        }
        if (!binding.parentBindingId().isBlank()) {
            TaskRetryAttemptBinding parent = bindings.get(binding.parentBindingId());
            if (parent == null || !parent.checkpointId().equals(binding.checkpointId())) {
                throw new IllegalStateException("retry binding parent is missing or from another checkpoint");
            }
            if (parent.kind() != TaskRetryAttemptKind.AGENT_STAGE || parent.role() != binding.role()) {
                throw new IllegalStateException("retry child binding must match its agent-stage parent role");
            }
        }
        bindings.values().stream()
                .filter(candidate -> candidate.kind() == binding.kind())
                .filter(candidate -> candidate.attemptId().equals(binding.attemptId()))
                .findFirst()
                .ifPresent(candidate -> conflict("attempt target", candidate, binding));
        Optional<TaskRetryAttemptBinding> slot = binding.parentBindingId().isBlank()
                ? findTopLevelSlot(binding.checkpointId(), binding.kind(), binding.role(), binding.ordinal())
                : findChild(binding.checkpointId(), binding.parentBindingId(), binding.kind(), binding.ordinal());
        slot.ifPresent(candidate -> conflict("binding slot", candidate, binding));
        bindings.put(binding.bindingId(), binding);
        return binding;
    }

    @Override
    public synchronized Optional<TaskRetryAttemptBinding> findById(String bindingId) {
        return Optional.ofNullable(bindings.get(safe(bindingId)));
    }

    @Override
    public synchronized List<TaskRetryAttemptBinding> listByCheckpoint(String checkpointId) {
        String expected = safe(checkpointId);
        return bindings.values().stream()
                .filter(binding -> binding.checkpointId().equals(expected))
                .sorted(Comparator.comparingInt(TaskRetryAttemptBinding::ordinal)
                        .thenComparing(TaskRetryAttemptBinding::bindingId))
                .toList();
    }

    @Override
    public synchronized Optional<TaskRetryAttemptBinding> findPrimary(
            String checkpointId, TaskRetryAttemptKind kind, AgentRole role
    ) {
        List<TaskRetryAttemptBinding> matches = bindings.values().stream()
                .filter(binding -> binding.checkpointId().equals(safe(checkpointId)))
                .filter(binding -> binding.parentBindingId().isBlank())
                .filter(binding -> binding.kind() == kind && binding.role() == role)
                .sorted(Comparator.comparingInt(TaskRetryAttemptBinding::ordinal)
                        .thenComparing(TaskRetryAttemptBinding::bindingId))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("retry primary binding is ambiguous for checkpoint/kind/role");
        }
        return matches.stream().findFirst();
    }

    @Override
    public synchronized Optional<TaskRetryAttemptBinding> findChild(
            String checkpointId,
            String parentBindingId,
            TaskRetryAttemptKind kind,
            int ordinal
    ) {
        return bindings.values().stream()
                .filter(binding -> binding.checkpointId().equals(safe(checkpointId)))
                .filter(binding -> binding.parentBindingId().equals(safe(parentBindingId)))
                .filter(binding -> binding.kind() == kind && binding.ordinal() == ordinal)
                .findFirst();
    }

    private static void conflict(String identity, TaskRetryAttemptBinding existing, TaskRetryAttemptBinding requested) {
        if (!existing.equals(requested)) {
            throw new IllegalStateException("retry " + identity + " already has a different immutable binding");
        }
    }

    private Optional<TaskRetryAttemptBinding> findTopLevelSlot(
            String checkpointId,
            TaskRetryAttemptKind kind,
            AgentRole role,
            int ordinal
    ) {
        return bindings.values().stream()
                .filter(binding -> binding.checkpointId().equals(safe(checkpointId)))
                .filter(binding -> binding.parentBindingId().isBlank())
                .filter(binding -> binding.kind() == kind && binding.role() == role)
                .filter(binding -> binding.ordinal() == ordinal)
                .findFirst();
    }

    private static void requireSame(TaskRetryAttemptBinding existing, TaskRetryAttemptBinding requested, String identity) {
        if (!existing.equals(requested)) {
            throw new IllegalStateException("retry " + identity + " already has a different immutable binding");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
