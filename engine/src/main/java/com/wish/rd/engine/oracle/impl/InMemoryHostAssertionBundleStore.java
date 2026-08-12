package com.wish.rd.engine.oracle.impl;

import com.wish.rd.engine.oracle.HostAssertionBundleStore;
import com.wish.rd.engine.oracle.model.FrozenAssertionBundle;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory Host assertion store for isolated tests and explicit memory-mode demos.
 */
public final class InMemoryHostAssertionBundleStore implements HostAssertionBundleStore {

    private final ConcurrentHashMap<Key, FrozenAssertionBundle> bundles = new ConcurrentHashMap<>();

    @Override
    public FrozenAssertionBundle saveIfAbsent(FrozenAssertionBundle bundle) {
        if (bundle == null) {
            throw new IllegalArgumentException("bundle must not be null");
        }
        Key key = Key.of(bundle.taskId(), bundle.stageRunId(), bundle.scope());
        return bundles.computeIfAbsent(key, ignored -> bundle);
    }

    @Override
    public Optional<FrozenAssertionBundle> find(String taskId, String stageRunId, String scope) {
        return Optional.ofNullable(bundles.get(Key.of(taskId, stageRunId, scope)));
    }

    @Override
    public List<FrozenAssertionBundle> listByTaskAndStage(String taskId, String stageRunId) {
        String normalizedTaskId = requireText(taskId, "taskId");
        String normalizedStageRunId = requireText(stageRunId, "stageRunId");
        return bundles.entrySet().stream()
                .filter(entry -> entry.getKey().taskId().equals(normalizedTaskId))
                .filter(entry -> entry.getKey().stageRunId().equals(normalizedStageRunId))
                .map(java.util.Map.Entry::getValue)
                .sorted(Comparator.comparing(FrozenAssertionBundle::scope))
                .toList();
    }

    private record Key(String taskId, String stageRunId, String scope) {
        private static Key of(String taskId, String stageRunId, String scope) {
            return new Key(
                    requireText(taskId, "taskId"),
                    requireText(stageRunId, "stageRunId"),
                    FrozenAssertionBundle.normalizeScope(scope)
            );
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
