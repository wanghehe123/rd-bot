package com.wish.rd.rag.lock;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 进程内锁执行器。
 *
 * <p>该实现只用于单测和本地无 Redis 启动场景。生产多实例互斥由 bootstrap 层的
 * Redisson 实现提供。
 */
final class LocalDistributedLockExecutor implements DistributedLockExecutor {

    static final LocalDistributedLockExecutor INSTANCE = new LocalDistributedLockExecutor();

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private LocalDistributedLockExecutor() {
    }

    @Override
    public <T> T execute(String lockName, Supplier<T> action) {
        String safeLockName = requireText(lockName, "lockName");
        Objects.requireNonNull(action, "action must not be null");
        ReentrantLock lock = locks.computeIfAbsent(safeLockName, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
