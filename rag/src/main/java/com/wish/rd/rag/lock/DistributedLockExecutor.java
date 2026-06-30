package com.wish.rd.rag.lock;

import java.util.function.Supplier;

/**
 * 分布式锁执行端口。
 *
 * <p>领域层只依赖该端口表达跨实例互斥语义，Redisson 等具体实现由 bootstrap 适配层提供。
 */
public interface DistributedLockExecutor {

    /**
     * 在指定锁内执行操作。
     *
     * @param lockName 全局唯一锁名
     * @param action   需要受保护的操作
     * @param <T>      返回值类型
     * @return 操作返回值
     */
    <T> T execute(String lockName, Supplier<T> action);

    /**
     * 在指定锁内执行无返回值操作。
     *
     * @param lockName 全局唯一锁名
     * @param action   需要受保护的操作
     */
    default void execute(String lockName, Runnable action) {
        execute(lockName, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 返回本地进程内锁实现，供单测和无 Redis 的本地模式兜底。
     *
     * @return 本地锁执行器
     */
    static DistributedLockExecutor local() {
        return LocalDistributedLockExecutor.INSTANCE;
    }
}
