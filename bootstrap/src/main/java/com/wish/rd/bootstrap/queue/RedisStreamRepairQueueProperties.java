package com.wish.rd.bootstrap.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis Stream repair-queue configuration bound from {@code rd.redis-stream.repair}.
 *
 * <p>The bootstrap queue adapter consumes these values while the engine remains
 * vendor-neutral through {@code RepairQueuePublisher} and {@code RepairQueueConsumer}.
 */
@ConfigurationProperties(prefix = "rd.redis-stream.repair")
public class RedisStreamRepairQueueProperties {

    /** Default Redis Stream key used for repair-ticket routing metadata. */
    public static final String DEFAULT_STREAM_KEY = "rd-bot:repair:tickets";
    /** Default consumer group shared by repair workers. */
    public static final String DEFAULT_CONSUMER_GROUP = "rd-bot-repair-workers";
    /** Default prefix for process-unique Redis consumer names. */
    public static final String DEFAULT_CONSUMER_NAME_BASE = "rd-bot-repair";
    /** Hard upper bound for long-running repair consumer loops in one process. */
    public static final int MAX_CONSUMER_CONCURRENCY = 16;
    /** Smallest loop-failure delay, preventing a zero-delay error hot loop. */
    public static final long MIN_FAILURE_BACKOFF_MILLIS = 1L;
    /** Smallest PEL idle threshold that still leaves time for an active-handler lease renewal. */
    public static final long MIN_PENDING_CLAIM_IDLE_MILLIS = 100L;

    private String streamKey = DEFAULT_STREAM_KEY;
    private String consumerGroup = DEFAULT_CONSUMER_GROUP;
    private String consumerNameBase = DEFAULT_CONSUMER_NAME_BASE;
    private int consumerConcurrency = 2;
    private long pollTimeoutMillis = 1_000L;
    private int batchSize = 10;
    private long pendingClaimIdleMillis = 60_000L;
    private long failureBackoffMillis = 1_000L;
    private int maxRetryAttempts = 3;

    /**
     * Returns the Redis Stream key.
     *
     * @return nonblank Stream key
     */
    public String getStreamKey() {
        return normalize(streamKey, DEFAULT_STREAM_KEY);
    }

    /**
     * Sets the Redis Stream key.
     *
     * @param streamKey Stream key supplied by configuration
     */
    public void setStreamKey(String streamKey) {
        this.streamKey = streamKey;
    }

    /**
     * Returns the consumer group name.
     *
     * @return nonblank consumer group
     */
    public String getConsumerGroup() {
        return normalize(consumerGroup, DEFAULT_CONSUMER_GROUP);
    }

    /**
     * Sets the consumer group name.
     *
     * @param consumerGroup group name supplied by configuration
     */
    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    /**
     * Returns the base portion of each unique consumer name.
     *
     * @return nonblank consumer-name base
     */
    public String getConsumerNameBase() {
        return normalize(consumerNameBase, DEFAULT_CONSUMER_NAME_BASE);
    }

    /**
     * Sets the base portion of each unique consumer name.
     *
     * @param consumerNameBase consumer-name base supplied by configuration
     */
    public void setConsumerNameBase(String consumerNameBase) {
        this.consumerNameBase = consumerNameBase;
    }

    /**
     * Returns the number of long-running consumer loops.
     *
     * @return loop count in the inclusive range {@code [1, 16]}
     */
    public int getConsumerConcurrency() {
        return Math.min(MAX_CONSUMER_CONCURRENCY, Math.max(1, consumerConcurrency));
    }

    /**
     * Sets the number of long-running consumer loops.
     *
     * @param consumerConcurrency requested loop count
     */
    public void setConsumerConcurrency(int consumerConcurrency) {
        this.consumerConcurrency = consumerConcurrency;
    }

    /**
     * Returns the blocking read timeout in milliseconds.
     *
     * @return positive polling timeout
     */
    public long getPollTimeoutMillis() {
        return Math.max(1L, pollTimeoutMillis);
    }

    /**
     * Sets the blocking read timeout in milliseconds.
     *
     * @param pollTimeoutMillis requested timeout
     */
    public void setPollTimeoutMillis(long pollTimeoutMillis) {
        this.pollTimeoutMillis = pollTimeoutMillis;
    }

    /**
     * Returns the maximum number of entries fetched in one Redis call.
     *
     * @return positive batch size
     */
    public int getBatchSize() {
        return Math.max(1, batchSize);
    }

    /**
     * Sets the maximum number of entries fetched in one Redis call.
     *
     * @param batchSize requested batch size
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    /**
     * Returns the idle threshold used before stale PEL entries are reclaimed.
     *
     * @return idle threshold of at least {@value #MIN_PENDING_CLAIM_IDLE_MILLIS} milliseconds
     */
    public long getPendingClaimIdleMillis() {
        return Math.max(MIN_PENDING_CLAIM_IDLE_MILLIS, pendingClaimIdleMillis);
    }

    /**
     * Sets the idle threshold used before stale PEL entries are reclaimed.
     *
     * @param pendingClaimIdleMillis requested idle threshold
     */
    public void setPendingClaimIdleMillis(long pendingClaimIdleMillis) {
        this.pendingClaimIdleMillis = pendingClaimIdleMillis;
    }

    /**
     * Returns the cadence for renewing a currently handled PEL record.
     *
     * <p>One third of the claim-idle window leaves two renewal opportunities before another
     * consumer may reclaim the record, while still bounding scheduler activity.
     *
     * @return positive lease-renewal period in milliseconds
     */
    public long getLeaseRenewalIntervalMillis() {
        return Math.max(1L, getPendingClaimIdleMillis() / 3L);
    }

    /**
     * Returns the backoff after a Redis loop failure.
     *
     * @return at least one millisecond of backoff
     */
    public long getFailureBackoffMillis() {
        return Math.max(MIN_FAILURE_BACKOFF_MILLIS, failureBackoffMillis);
    }

    /**
     * Sets the backoff after a Redis loop failure.
     *
     * @param failureBackoffMillis requested backoff
     */
    public void setFailureBackoffMillis(long failureBackoffMillis) {
        this.failureBackoffMillis = failureBackoffMillis;
    }

    /**
     * Returns the maximum business retry attempt accepted by the engine consumer.
     *
     * @return positive maximum attempt count
     */
    public int getMaxRetryAttempts() {
        return Math.max(1, maxRetryAttempts);
    }

    /**
     * Sets the maximum business retry attempt accepted by the engine consumer.
     *
     * @param maxRetryAttempts requested maximum attempt count
     */
    public void setMaxRetryAttempts(int maxRetryAttempts) {
        this.maxRetryAttempts = maxRetryAttempts;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
