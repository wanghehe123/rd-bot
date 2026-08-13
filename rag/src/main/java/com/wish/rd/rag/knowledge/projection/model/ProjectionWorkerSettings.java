package com.wish.rd.rag.knowledge.projection.model;

/**
 * 投影 Worker/Poller 的运行参数。
 *
 * <p>退避不是可选优化：每一次 poll 都会重写行版本，没有退避时积压会把写放大打到
 * 本地 mutation 事务提交的同一张表上。
 *
 * @param batchSize                批量领取上限
 * @param workerLeaseMillis        提交侧租约时长
 * @param pollLeaseMillis          单轮轮询的短租约时长，必须远小于提交侧租约
 * @param pollIntervalMillis       基础轮询间隔
 * @param maxPollIntervalMillis    退避上限
 * @param retryBackoffMillis       提交失败的基础退避
 * @param maxRetryBackoffMillis    提交失败的退避上限
 * @param unknownOutcomeTimeoutMillis 越过发送边界后仍未收敛的墙钟截止，锚在发送时刻
 */
public record ProjectionWorkerSettings(
        int batchSize,
        long workerLeaseMillis,
        long pollLeaseMillis,
        long pollIntervalMillis,
        long maxPollIntervalMillis,
        long retryBackoffMillis,
        long maxRetryBackoffMillis,
        long unknownOutcomeTimeoutMillis
) {

    public ProjectionWorkerSettings {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        if (pollLeaseMillis >= workerLeaseMillis) {
            throw new IllegalArgumentException("the poll lease must be much shorter than the worker lease");
        }
    }

    public static ProjectionWorkerSettings defaults() {
        return new ProjectionWorkerSettings(
                10, 120_000L, 15_000L, 3_000L, 60_000L, 2_000L, 300_000L, 1_800_000L);
    }

    /**
     * 指数退避并封顶。第一次尝试就退避 base，之后每次翻倍。
     *
     * @param attemptCount 已用尝试次数
     * @param base         基础退避
     * @param cap          上限
     * @return 退避毫秒数
     */
    public static long backoffMillis(int attemptCount, long base, long cap) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 20);
        long scaled = base << exponent;
        return scaled <= 0L || scaled > cap ? cap : scaled;
    }

    public long submitBackoffMillis(int attemptCount) {
        return backoffMillis(attemptCount, retryBackoffMillis, maxRetryBackoffMillis);
    }

    public long pollBackoffMillis(int pollCount) {
        return backoffMillis(pollCount, pollIntervalMillis, maxPollIntervalMillis);
    }

    /**
     * 按远端任务已存活的时长决定下次轮询间隔。
     *
     * <p>刻意不依赖"已轮询次数"这样的计数列：那需要每轮再写一次行，
     * 而轮询本身已经在写 {@code row_version}。用年龄推导是无状态的，
     * 崩溃恢复或换实例接手都会得到同一个节奏。
     *
     * @param ageMillis 距首次发送的时长
     * @return 下次轮询间隔
     */
    public long pollIntervalForAge(long ageMillis) {
        long proportional = Math.max(0L, ageMillis) / 4L;
        return Math.min(maxPollIntervalMillis, Math.max(pollIntervalMillis, proportional));
    }
}
