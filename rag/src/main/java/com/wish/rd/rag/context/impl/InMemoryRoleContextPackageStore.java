package com.wish.rd.rag.context.impl;

import com.wish.rd.rag.context.RoleContextPackageStore;

import com.wish.rd.rag.lock.DistributedLockExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import com.wish.rd.rag.context.model.RoleContextPackage;

/**
 * 内存角色上下文包存储。
 *
 * <p>只适合单机开发和单元测试；生产多实例必须替换成数据库实现。
 */
public final class InMemoryRoleContextPackageStore implements RoleContextPackageStore {

    private static final String LOCK_NAME = "rd-bot:lock:role-context-package-store";

    private final LinkedHashMap<String, RoleContextPackage> packages = new LinkedHashMap<>();
    private final DistributedLockExecutor lockExecutor;

    public InMemoryRoleContextPackageStore() {
        this(DistributedLockExecutor.local());
    }

    public InMemoryRoleContextPackageStore(DistributedLockExecutor lockExecutor) {
        this.lockExecutor = lockExecutor == null ? DistributedLockExecutor.local() : lockExecutor;
    }

    @Override
    public RoleContextPackage save(RoleContextPackage contextPackage) {
        return withLock(() -> {
            if (contextPackage == null || contextPackage.packageId().isBlank()) {
                throw new IllegalArgumentException("packageId must not be blank");
            }
            if (contextPackage.taskId().isBlank()) {
                throw new IllegalArgumentException("taskId must not be blank");
            }
            packages.put(contextPackage.packageId(), contextPackage);
            return contextPackage;
        });
    }

    @Override
    public Optional<RoleContextPackage> findById(String packageId) {
        return withLock(() -> Optional.ofNullable(packages.get(safe(packageId))));
    }

    @Override
    public List<RoleContextPackage> listByTask(String taskId) {
        return withLock(() -> {
            String safeTaskId = safe(taskId);
            return packages.values().stream()
                    .filter(contextPackage -> contextPackage.taskId().equals(safeTaskId))
                    .toList();
        });
    }

    @Override
    public List<RoleContextPackage> listByTaskAndRole(String taskId, String role) {
        return withLock(() -> {
            String safeTaskId = safe(taskId);
            String safeRole = safe(role).toUpperCase();
            return packages.values().stream()
                    .filter(contextPackage -> contextPackage.taskId().equals(safeTaskId))
                    .filter(contextPackage -> contextPackage.role().equals(safeRole))
                    .toList();
        });
    }

    private <T> T withLock(Supplier<T> action) {
        return lockExecutor.execute(LOCK_NAME, action);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
