package com.wish.rd.bootstrap.threading;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Centralized RD-Bot thread-pool wiring.
 *
 * <p>The pools are intentionally separated by workload class instead of sharing
 * a global executor: delivery orchestration, blocking executor I/O, ingestion,
 * and maintenance each have independent capacity and back-pressure.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RdBotThreadPoolProperties.class)
public class RdBotThreadPoolConfiguration {

    /** Requirement delivery orchestration executor bean name. */
    public static final String REQUIREMENT_DELIVERY_EXECUTOR_BEAN = "rdRequirementDeliveryTaskExecutor";

    /** Blocking model/Docker executor bean name. */
    public static final String EXECUTOR_IO_EXECUTOR_BEAN = "rdExecutorIoTaskExecutor";

    /** Document ingestion executor bean name. */
    public static final String INGESTION_EXECUTOR_BEAN = "rdIngestionTaskExecutor";

    /** Maintenance worker executor bean name. */
    public static final String MAINTENANCE_EXECUTOR_BEAN = "rdMaintenanceTaskExecutor";

    /**
     * Creates the requirement delivery orchestration executor.
     *
     * @param properties thread-pool settings
     * @return bounded task executor
     */
    @Bean(REQUIREMENT_DELIVERY_EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor rdRequirementDeliveryTaskExecutor(RdBotThreadPoolProperties properties) {
        return executor(properties.getRequirementDelivery());
    }

    /**
     * Creates the blocking executor I/O pool for Docker and model calls.
     *
     * @param properties thread-pool settings
     * @return bounded task executor
     */
    @Bean(EXECUTOR_IO_EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor rdExecutorIoTaskExecutor(RdBotThreadPoolProperties properties) {
        return executor(properties.getExecutorIo());
    }

    /**
     * Creates the document ingestion executor.
     *
     * @param properties thread-pool settings
     * @return bounded task executor
     */
    @Bean(INGESTION_EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor rdIngestionTaskExecutor(RdBotThreadPoolProperties properties) {
        return executor(properties.getIngestion());
    }

    /**
     * Creates the maintenance worker executor.
     *
     * @param properties thread-pool settings
     * @return bounded task executor
     */
    @Bean(MAINTENANCE_EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor rdMaintenanceTaskExecutor(RdBotThreadPoolProperties properties) {
        return executor(properties.getMaintenance());
    }

    /**
     * Creates Spring's scheduled-method trigger scheduler.
     *
     * @param properties thread-pool settings
     * @return scheduler for {@code @Scheduled} methods
     */
    @Bean("taskScheduler")
    public TaskScheduler taskScheduler(RdBotThreadPoolProperties properties) {
        RdBotThreadPoolProperties.SchedulerProperties scheduler = properties.getScheduler();
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(scheduler.getPoolSize());
        taskScheduler.setThreadNamePrefix(scheduler.getThreadNamePrefix());
        taskScheduler.setWaitForTasksToCompleteOnShutdown(true);
        taskScheduler.setAwaitTerminationSeconds(scheduler.getAwaitTerminationSeconds());
        taskScheduler.initialize();
        return taskScheduler;
    }

    private ThreadPoolTaskExecutor executor(RdBotThreadPoolProperties.PoolProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCoreSize());
        executor.setMaxPoolSize(Math.max(properties.getCoreSize(), properties.getMaxSize()));
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getKeepAliveSeconds());
        executor.setThreadNamePrefix(properties.getThreadNamePrefix());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());
        executor.initialize();
        return executor;
    }
}
