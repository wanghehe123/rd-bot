package com.wish.rd.bootstrap.queue;

import com.wish.rd.bootstrap.lock.DistributedLockConfiguration;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisStreamRepairQueueConfigurationTest {

    @Test
    void shouldCreateDedicatedQueueLoopExecutorThatStartsEveryConfiguredLoop() {
        contextRunner("local")
                .withPropertyValues("rd.redis-stream.repair.consumer-concurrency=3")
                .run(context -> {
                    assertTrue(context.containsBean(RedisStreamRepairQueueConfiguration.REPAIR_QUEUE_LOOP_EXECUTOR_BEAN));
                    ThreadPoolTaskExecutor executor = context.getBean(
                            RedisStreamRepairQueueConfiguration.REPAIR_QUEUE_LOOP_EXECUTOR_BEAN,
                            ThreadPoolTaskExecutor.class
                    );
                    ThreadPoolTaskScheduler scheduler = context.getBean(
                            RedisStreamRepairQueueConfiguration.REPAIR_QUEUE_LEASE_SCHEDULER_BEAN,
                            ThreadPoolTaskScheduler.class
                    );
                    assertAll(
                            () -> assertEquals(3, executor.getCorePoolSize()),
                            () -> assertEquals(3, executor.getMaxPoolSize()),
                            () -> assertEquals(0, executor.getThreadPoolExecutor().getQueue().remainingCapacity()),
                            () -> assertEquals(3, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                    );
                    CountDownLatch entered = new CountDownLatch(3);
                    CountDownLatch release = new CountDownLatch(1);
                    try {
                        for (int index = 0; index < 3; index++) {
                            executor.submit(() -> {
                                entered.countDown();
                                try {
                                    release.await(2, TimeUnit.SECONDS);
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                }
                            });
                        }
                        assertTrue(entered.await(1, TimeUnit.SECONDS),
                                "every configured long-running loop must receive a worker immediately");
                    } finally {
                        release.countDown();
                    }
                });
    }

    @Test
    void shouldBoundConsumerConcurrencyAndFailureBackoffToSafeMinimums() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setConsumerConcurrency(999);
        properties.setFailureBackoffMillis(0L);
        properties.setPendingClaimIdleMillis(0L);

        assertAll(
                () -> assertEquals(16, properties.getConsumerConcurrency()),
                () -> assertEquals(1L, properties.getFailureBackoffMillis()),
                () -> assertEquals(100L, properties.getPendingClaimIdleMillis()),
                () -> assertEquals(33L, properties.getLeaseRenewalIntervalMillis())
        );
    }

    @Test
    void shouldDefaultToRedisStreamWhenQueueModeIsOmitted() {
        new ApplicationContextRunner()
                .withUserConfiguration(RedisStreamRepairQueueConfiguration.class)
                .withPropertyValues(
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=6379"
                )
                .run(context -> assertTrue(
                        context.containsBean(RedisStreamRepairQueueConfiguration.REPAIR_QUEUE_REDISSON_CLIENT_BEAN)
                ));
    }

    @Test
    void shouldCreateDedicatedQualifiedQueueClientWhenDistributedLocksAreLocal() {
        contextRunner("local")
                .withPropertyValues(
                        "rd.redis-stream.repair.stream-key=rd-bot:test:repair",
                        "rd.redis-stream.repair.consumer-group=repair-test-group",
                        "rd.redis-stream.repair.consumer-concurrency=2"
                )
                .run(context -> {
                    assertTrue(context.containsBean(RedisStreamRepairQueueConfiguration.REPAIR_QUEUE_REDISSON_CLIENT_BEAN));
                    assertEquals(1, context.getBeansOfType(RedissonClient.class).size());
                    RedisStreamRepairQueueProperties properties = context.getBean(RedisStreamRepairQueueProperties.class);
                    assertEquals("rd-bot:test:repair", properties.getStreamKey());
                    assertEquals("repair-test-group", properties.getConsumerGroup());
                    assertEquals(2, properties.getConsumerConcurrency());
                });
    }

    @Test
    void shouldKeepQueueClientDistinctWhenDistributedLocksUseRedisson() {
        contextRunner("redisson")
                .run(context -> {
                    assertTrue(context.containsBean(RedisStreamRepairQueueConfiguration.REPAIR_QUEUE_REDISSON_CLIENT_BEAN));
                    assertTrue(context.containsBean("distributedLockRedissonClient"));
                    assertEquals(2, context.getBeansOfType(RedissonClient.class).size());
                    assertNotNull(context.getBean(RedisStreamRepairQueueConfiguration.REPAIR_QUEUE_REDISSON_CLIENT_BEAN));
                });
    }

    @Test
    void shouldNotCreateQueueClientOutsideRedisStreamMode() {
        contextRunner("local")
                .withPropertyValues("rd.repair.queue.mode=memory")
                .run(context -> assertFalse(
                        context.containsBean(RedisStreamRepairQueueConfiguration.REPAIR_QUEUE_REDISSON_CLIENT_BEAN)
                ));
    }

    private static ApplicationContextRunner contextRunner(String distributedLockMode) {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        RedisStreamRepairQueueConfiguration.class,
                        DistributedLockConfiguration.class
                )
                .withPropertyValues(
                        "rd.repair.queue.mode=redis-stream",
                        "rd.distributed-lock.mode=" + distributedLockMode,
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=6379"
                );
    }
}
