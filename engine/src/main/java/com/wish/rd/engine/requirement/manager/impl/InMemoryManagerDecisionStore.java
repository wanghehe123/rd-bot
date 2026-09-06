package com.wish.rd.engine.requirement.manager.impl;

import com.wish.rd.engine.requirement.manager.ManagerDecision;
import com.wish.rd.engine.requirement.manager.ManagerDecisionStore;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only Manager decision store. */
public final class InMemoryManagerDecisionStore implements ManagerDecisionStore {
    private final Map<String, ManagerDecision> bySource = new ConcurrentHashMap<>();

    @Override
    public ManagerDecision insertIfAbsent(ManagerDecision decision) {
        if (decision == null) {
            throw new IllegalArgumentException("decision is required");
        }
        String key = key(decision.taskId(), decision.sourceCommandId());
        return bySource.compute(key, (ignored, existing) -> {
            if (existing == null) {
                return decision;
            }
            if (!existing.decisionHash().equals(decision.decisionHash())
                    && existing.stateVersion() == decision.stateVersion()) {
                throw new IllegalStateException("manager decision replay hash mismatch for " + key);
            }
            if (existing.stateVersion() == decision.stateVersion()) {
                return existing;
            }
            throw new IllegalStateException("manager decision already exists for " + key);
        });
    }

    @Override
    public Optional<ManagerDecision> findLatest(String taskId) {
        String id = taskId == null ? "" : taskId.strip();
        return bySource.values().stream()
                .filter(decision -> decision.taskId().equals(id))
                .max(Comparator.comparingInt(ManagerDecision::roundNo));
    }

    @Override
    public Optional<ManagerDecision> findBySourceCommand(String taskId, String sourceCommandId) {
        return Optional.ofNullable(bySource.get(key(taskId, sourceCommandId)));
    }

    @Override
    public List<ManagerDecision> listByTask(String taskId) {
        String id = taskId == null ? "" : taskId.strip();
        return bySource.values().stream()
                .filter(decision -> decision.taskId().equals(id))
                .sorted(Comparator.comparingInt(ManagerDecision::roundNo))
                .toList();
    }

    @Override
    public int maxRound(String taskId) {
        return findLatest(taskId).map(ManagerDecision::roundNo).orElse(0);
    }

    private static String key(String taskId, String sourceCommandId) {
        return (taskId == null ? "" : taskId.strip()) + ":" + (sourceCommandId == null ? "" : sourceCommandId.strip());
    }
}
