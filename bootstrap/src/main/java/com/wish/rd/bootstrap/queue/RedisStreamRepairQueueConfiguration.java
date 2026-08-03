package com.wish.rd.bootstrap.queue;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Wires the dedicated Redis client used only by the repair-ticket Stream queue.
 *
 * <p>A distinct qualified client keeps queue availability independent from the
 * distributed-lock mode and prevents lock-client injection ambiguity.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisStreamRepairQueueProperties.class)
public class RedisStreamRepairQueueConfiguration {

    /** Bean name and qualifier for the dedicated repair-queue Redis client. */
    public static final String REPAIR_QUEUE_REDISSON_CLIENT_BEAN = "redisStreamRepairQueueRedissonClient";
    /** Bean name and qualifier for long-running Redis Stream consumer loops. */
    public static final String REPAIR_QUEUE_LOOP_EXECUTOR_BEAN = "rdRedisStreamRepairLoopTaskExecutor";
    /** Bean name and qualifier for in-flight Redis Stream lease renewal. */
    public static final String REPAIR_QUEUE_LEASE_SCHEDULER_BEAN = "rdRedisStreamRepairLeaseTaskScheduler";

    /**
     * Creates the dedicated Redisson client for Redis Stream queue access.
     *
     * @param redisHost Redis host from the shared Spring Redis connection settings
     * @param redisPort Redis port from the shared Spring Redis connection settings
     * @param redisPassword optional Redis password from the shared Spring Redis connection settings
     * @return queue-only Redisson client
     */
    @Bean(name = REPAIR_QUEUE_REDISSON_CLIENT_BEAN, destroyMethod = "shutdown")
    @Qualifier(REPAIR_QUEUE_REDISSON_CLIENT_BEAN)
    @ConditionalOnProperty(name = "rd.repair.queue.mode", havingValue = "redis-stream", matchIfMissing = true)
    public RedissonClient redisStreamRepairQueueRedissonClient(
            @Value("${spring.data.redis.host:127.0.0.1}") String redisHost,
            @Value("${spring.data.redis.port:6379}") int redisPort,
            @Value("${spring.data.redis.password:}") String redisPassword
    ) {
        String host = redisHost == null || redisHost.isBlank() ? "127.0.0.1" : redisHost.trim();
        Config config = new Config();
        var singleServer = config.useSingleServer()
                .setAddress("redis://" + host + ":" + Math.max(1, redisPort));
        if (redisPassword != null && !redisPassword.isBlank()) {
            singleServer.setPassword(redisPassword);
        }
        return Redisson.create(config);
    }

    /**
     * Creates a bounded dedicated executor whose workers exactly match the normalized
     * Redis Stream consumer-loop count. Long-lived queue loops must not occupy the
     * shared repair-dispatch executor.
     *
     * @param properties Redis Stream repair-queue settings
     * @return queue-loop executor with no waiting queue
     */
    @Bean(name = REPAIR_QUEUE_LOOP_EXECUTOR_BEAN, destroyMethod = "shutdown")
    @Qualifier(REPAIR_QUEUE_LOOP_EXECUTOR_BEAN)
    @ConditionalOnProperty(name = "rd.repair.queue.mode", havingValue = "redis-stream", matchIfMissing = true)
    public ThreadPoolTaskExecutor redisStreamRepairQueueLoopExecutor(RedisStreamRepairQueueProperties properties) {
        int concurrency = properties.getConsumerConcurrency();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("rd-redis-stream-repair-loop-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }

    /**
     * Creates a lifecycle-managed scheduler for renewing active Stream PEL leases.
     *
     * @param properties Redis Stream repair-queue settings
     * @return dedicated lease scheduler
     */
    @Bean(name = REPAIR_QUEUE_LEASE_SCHEDULER_BEAN, destroyMethod = "shutdown")
    @Qualifier(REPAIR_QUEUE_LEASE_SCHEDULER_BEAN)
    @ConditionalOnProperty(name = "rd.repair.queue.mode", havingValue = "redis-stream", matchIfMissing = true)
    public TaskScheduler redisStreamRepairQueueLeaseScheduler(RedisStreamRepairQueueProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.getConsumerConcurrency());
        scheduler.setThreadNamePrefix("rd-redis-stream-repair-lease-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.initialize();
        return scheduler;
    }
}
