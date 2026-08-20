package com.wish.rd.bootstrap.threading;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RD-Bot production thread-pool settings.
 *
 * <p>Each pool maps to one long-running workload family so queue pressure from
 * model execution, document ingestion, and maintenance jobs cannot starve each other.
 */
@ConfigurationProperties(prefix = "rd.thread-pools")
public class RdBotThreadPoolProperties {

    private PoolProperties requirementDelivery = PoolProperties.of(2, 4, 100, "rd-requirement-");
    private PoolProperties executorIo = PoolProperties.of(4, 8, 200, "rd-executor-io-");
    private PoolProperties ingestion = PoolProperties.of(2, 4, 100, "rd-ingestion-");
    private PoolProperties maintenance = PoolProperties.of(1, 2, 50, "rd-maintenance-");
    private SchedulerProperties scheduler = new SchedulerProperties();

    /**
     * Requirement delivery orchestration pool.
     *
     * @return requirement-delivery pool settings
     */
    public PoolProperties getRequirementDelivery() {
        return requirementDelivery;
    }

    /**
     * Updates requirement delivery orchestration pool settings.
     *
     * @param requirementDelivery requirement-delivery pool settings
     */
    public void setRequirementDelivery(PoolProperties requirementDelivery) {
        this.requirementDelivery = PoolProperties.safe(requirementDelivery, this.requirementDelivery);
    }

    /**
     * Blocking model/Docker executor pool.
     *
     * @return executor I/O pool settings
     */
    public PoolProperties getExecutorIo() {
        return executorIo;
    }

    /**
     * Updates blocking model/Docker executor pool settings.
     *
     * @param executorIo executor I/O pool settings
     */
    public void setExecutorIo(PoolProperties executorIo) {
        this.executorIo = PoolProperties.safe(executorIo, this.executorIo);
    }

    /**
     * Document ingestion pool.
     *
     * @return ingestion pool settings
     */
    public PoolProperties getIngestion() {
        return ingestion;
    }

    /**
     * Updates document ingestion pool settings.
     *
     * @param ingestion ingestion pool settings
     */
    public void setIngestion(PoolProperties ingestion) {
        this.ingestion = PoolProperties.safe(ingestion, this.ingestion);
    }

    /**
     * Maintenance and scheduled-job worker pool.
     *
     * @return maintenance pool settings
     */
    public PoolProperties getMaintenance() {
        return maintenance;
    }

    /**
     * Updates maintenance and scheduled-job worker pool settings.
     *
     * @param maintenance maintenance pool settings
     */
    public void setMaintenance(PoolProperties maintenance) {
        this.maintenance = PoolProperties.safe(maintenance, this.maintenance);
    }

    /**
     * Spring scheduler pool settings.
     *
     * @return scheduler settings
     */
    public SchedulerProperties getScheduler() {
        return scheduler;
    }

    /**
     * Updates Spring scheduler pool settings.
     *
     * @param scheduler scheduler settings
     */
    public void setScheduler(SchedulerProperties scheduler) {
        this.scheduler = scheduler == null ? new SchedulerProperties() : scheduler;
    }

    /**
     * Bounded worker-pool settings.
     */
    public static class PoolProperties {
        private int coreSize;
        private int maxSize;
        private int queueCapacity;
        private int keepAliveSeconds = 60;
        private int awaitTerminationSeconds = 30;
        private String threadNamePrefix;

        /**
         * Creates default pool settings for binder use.
         */
        public PoolProperties() {
        }

        static PoolProperties of(int coreSize, int maxSize, int queueCapacity, String threadNamePrefix) {
            PoolProperties properties = new PoolProperties();
            properties.setCoreSize(coreSize);
            properties.setMaxSize(maxSize);
            properties.setQueueCapacity(queueCapacity);
            properties.setThreadNamePrefix(threadNamePrefix);
            return properties;
        }

        static PoolProperties safe(PoolProperties value, PoolProperties fallback) {
            return value == null ? fallback : value;
        }

        /**
         * Core worker count.
         *
         * @return core worker count
         */
        public int getCoreSize() {
            return coreSize;
        }

        /**
         * Updates core worker count.
         *
         * @param coreSize core worker count
         */
        public void setCoreSize(int coreSize) {
            this.coreSize = Math.max(1, coreSize);
        }

        /**
         * Maximum worker count.
         *
         * @return maximum worker count
         */
        public int getMaxSize() {
            return maxSize;
        }

        /**
         * Updates maximum worker count.
         *
         * @param maxSize maximum worker count
         */
        public void setMaxSize(int maxSize) {
            this.maxSize = Math.max(1, maxSize);
        }

        /**
         * Queue capacity before rejecting new work.
         *
         * @return queue capacity
         */
        public int getQueueCapacity() {
            return queueCapacity;
        }

        /**
         * Updates queue capacity before rejecting new work.
         *
         * @param queueCapacity queue capacity
         */
        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = Math.max(0, queueCapacity);
        }

        /**
         * Idle worker keep-alive in seconds.
         *
         * @return keep-alive seconds
         */
        public int getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        /**
         * Updates idle worker keep-alive in seconds.
         *
         * @param keepAliveSeconds keep-alive seconds
         */
        public void setKeepAliveSeconds(int keepAliveSeconds) {
            this.keepAliveSeconds = Math.max(1, keepAliveSeconds);
        }

        /**
         * Shutdown wait in seconds.
         *
         * @return shutdown wait seconds
         */
        public int getAwaitTerminationSeconds() {
            return awaitTerminationSeconds;
        }

        /**
         * Updates shutdown wait in seconds.
         *
         * @param awaitTerminationSeconds shutdown wait seconds
         */
        public void setAwaitTerminationSeconds(int awaitTerminationSeconds) {
            this.awaitTerminationSeconds = Math.max(0, awaitTerminationSeconds);
        }

        /**
         * Thread name prefix.
         *
         * @return thread name prefix
         */
        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        /**
         * Updates thread name prefix.
         *
         * @param threadNamePrefix thread name prefix
         */
        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix == null || threadNamePrefix.isBlank()
                    ? "rd-worker-"
                    : threadNamePrefix;
        }
    }

    /**
     * Settings for Spring's scheduled-method trigger pool.
     */
    public static class SchedulerProperties {
        private int poolSize = 2;
        private String threadNamePrefix = "rd-scheduler-";
        private int awaitTerminationSeconds = 30;

        /**
         * Scheduler trigger pool size.
         *
         * @return scheduler trigger pool size
         */
        public int getPoolSize() {
            return poolSize;
        }

        /**
         * Updates scheduler trigger pool size.
         *
         * @param poolSize scheduler trigger pool size
         */
        public void setPoolSize(int poolSize) {
            this.poolSize = Math.max(1, poolSize);
        }

        /**
         * Scheduler thread name prefix.
         *
         * @return scheduler thread name prefix
         */
        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        /**
         * Updates scheduler thread name prefix.
         *
         * @param threadNamePrefix scheduler thread name prefix
         */
        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix == null || threadNamePrefix.isBlank()
                    ? "rd-scheduler-"
                    : threadNamePrefix;
        }

        /**
         * Scheduler shutdown wait in seconds.
         *
         * @return shutdown wait seconds
         */
        public int getAwaitTerminationSeconds() {
            return awaitTerminationSeconds;
        }

        /**
         * Updates scheduler shutdown wait in seconds.
         *
         * @param awaitTerminationSeconds shutdown wait seconds
         */
        public void setAwaitTerminationSeconds(int awaitTerminationSeconds) {
            this.awaitTerminationSeconds = Math.max(0, awaitTerminationSeconds);
        }
    }
}
