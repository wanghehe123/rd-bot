package com.wish.rd.rag.runtime.impl;

import com.wish.rd.rag.runtime.TaskMaterialStore;

import com.wish.rd.rag.lock.DistributedLockExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.wish.rd.rag.runtime.model.TaskMaterial;

/**
 * 内存任务材料存储。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryTaskMaterialStore implements TaskMaterialStore {

    private static final String LOCK_NAME = "rd-bot:lock:task-material-store";

    private final LinkedHashMap<String, TaskMaterial> materials = new LinkedHashMap<>();
    private final DistributedLockExecutor lockExecutor;

    public InMemoryTaskMaterialStore() {
        this(DistributedLockExecutor.local());
    }

    @Autowired
    public InMemoryTaskMaterialStore(ObjectProvider<DistributedLockExecutor> lockExecutorProvider) {
        this(lockExecutorProvider == null
                ? DistributedLockExecutor.local()
                : lockExecutorProvider.getIfAvailable(DistributedLockExecutor::local));
    }

    public InMemoryTaskMaterialStore(DistributedLockExecutor lockExecutor) {
        this.lockExecutor = lockExecutor == null ? DistributedLockExecutor.local() : lockExecutor;
    }

    @Override
    public TaskMaterial save(TaskMaterial material) {
        return withLock(() -> {
            if (material == null || material.materialId().isBlank()) {
                throw new IllegalArgumentException("materialId must not be blank");
            }
            if (material.taskId().isBlank()) {
                throw new IllegalArgumentException("taskId must not be blank");
            }
            materials.put(material.materialId(), material);
            return material;
        });
    }

    @Override
    public Optional<TaskMaterial> findById(String materialId) {
        return withLock(() -> {
            String safeMaterialId = materialId == null ? "" : materialId.strip();
            return Optional.ofNullable(materials.get(safeMaterialId));
        });
    }

    @Override
    public List<TaskMaterial> listByTask(String taskId) {
        return withLock(() -> {
            String safeTaskId = taskId == null ? "" : taskId.strip();
            return materials.values().stream()
                    .filter(material -> material.taskId().equals(safeTaskId))
                    .toList();
        });
    }

    @Override
    public int deleteByTask(String taskId) {
        return withLock(() -> {
            String safeTaskId = taskId == null ? "" : taskId.strip();
            List<String> materialIds = materials.values().stream()
                    .filter(material -> material.taskId().equals(safeTaskId))
                    .map(TaskMaterial::materialId)
                    .toList();
            materialIds.forEach(materials::remove);
            return materialIds.size();
        });
    }

    private <T> T withLock(Supplier<T> action) {
        return lockExecutor.execute(LOCK_NAME, action);
    }
}
