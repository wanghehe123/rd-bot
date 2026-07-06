package com.wish.rd.bootstrap.lock.impl;

import com.wish.rd.rag.lock.DistributedLockExecutor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 基于 Redisson 的分布式锁执行器。
 */
public final class RedissonDistributedLockExecutor implements DistributedLockExecutor {

    private final RedissonClient redissonClient;

    public RedissonDistributedLockExecutor(RedissonClient redissonClient) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient must not be null");
    }

    @Override
    public <T> T execute(String lockName, Supplier<T> action) {
        String safeLockName = requireText(lockName, "lockName");
        Objects.requireNonNull(action, "action must not be null");
        RLock lock = redissonClient.getLock(safeLockName);
        lock.lock();
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String requireText(String value, String name) {
        String safeValue = value == null ? "" : value.strip();
        if (safeValue.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return safeValue;
    }
}
