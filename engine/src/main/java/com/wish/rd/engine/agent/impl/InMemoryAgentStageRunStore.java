package com.wish.rd.engine.agent.impl;

import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.AgentStageTransitions;

import com.wish.rd.rag.lock.DistributedLockExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;

/**
 * 内存 Agent 阶段运行存储。
 *
 * <p>只用于开发和测试；生产多实例必须替换为数据库实现。
 */
public final class InMemoryAgentStageRunStore implements AgentStageRunStore {

    private static final String LOCK_NAME = "rd-bot:lock:agent-stage-run-store";

    private final LinkedHashMap<String, AgentStageRun> runs = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> idempotencyIndex = new LinkedHashMap<>();
    private final DistributedLockExecutor lockExecutor;

    public InMemoryAgentStageRunStore() {
        this(DistributedLockExecutor.local());
    }

    public InMemoryAgentStageRunStore(DistributedLockExecutor lockExecutor) {
        this.lockExecutor = lockExecutor == null ? DistributedLockExecutor.local() : lockExecutor;
    }

    @Override
    public AgentStageRun save(AgentStageRun stageRun) {
        return withLock(() -> {
            if (stageRun == null) {
                throw new IllegalArgumentException("stageRun must not be null");
            }
            String existingRunId = idempotencyIndex.get(indexKey(stageRun));
            if (existingRunId != null && !existingRunId.equals(stageRun.stageRunId())) {
                throw new IllegalStateException("duplicate agent stage idempotency key: "
                        + stageRun.idempotencyKey());
            }
            runs.put(stageRun.stageRunId(), stageRun);
            idempotencyIndex.put(indexKey(stageRun), stageRun.stageRunId());
            return stageRun;
        });
    }

    @Override
    public Optional<AgentStageRun> findById(String stageRunId) {
        return withLock(() -> Optional.ofNullable(runs.get(safe(stageRunId))));
    }

    @Override
    public List<AgentStageRun> listByTask(String taskId) {
        return withLock(() -> {
            String safeTaskId = safe(taskId);
            return runs.values().stream()
                    .filter(run -> run.taskId().equals(safeTaskId))
                    .toList();
        });
    }

    @Override
    public AgentStageRun transition(
            String stageRunId,
            AgentStageStatus targetStatus,
            String errorCategory,
            String errorMessage,
            long updateTimeEpochMillis
    ) {
        return withLock(() -> {
            AgentStageRun existing = runs.get(safe(stageRunId));
            if (existing == null) {
                throw new IllegalArgumentException("agent stage run not found: " + safe(stageRunId));
            }
            AgentStageTransitions.ensureTransition(existing.status(), targetStatus);
            AgentStageRun next = existing.withStatus(targetStatus, errorCategory, errorMessage, updateTimeEpochMillis);
            runs.put(next.stageRunId(), next);
            return next;
        });
    }

    private static String indexKey(AgentStageRun stageRun) {
        return stageRun.taskId() + ":" + stageRun.role().name() + ":" + stageRun.idempotencyKey();
    }

    private <T> T withLock(Supplier<T> action) {
        return lockExecutor.execute(LOCK_NAME, action);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
