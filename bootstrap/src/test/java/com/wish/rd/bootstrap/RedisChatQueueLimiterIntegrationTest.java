package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.rag.ratelimit.FairDistributedRateLimiter;
import com.wish.rd.bootstrap.rag.ratelimit.RedisChatQueueLimiter;
import com.wish.rd.engine.rag.BugFixMessage;
import com.wish.rd.engine.rag.ChatQueueLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "rd.bot.redis.test", matches = "true")
class RedisChatQueueLimiterIntegrationTest {

    @Test
    void queuesSecondCallInRedisUntilPermitIsReleased() throws Exception {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        RedissonClient redissonClient = Redisson.create(config);
        String limiterName = "rd-bot:test:bugfix:" + UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        FairDistributedRateLimiter fairLimiter = new FairDistributedRateLimiter(
                limiterName,
                redissonClient,
                () -> 1,
                () -> 5,
                () -> 50
        );
        RedisChatQueueLimiter limiter = new RedisChatQueueLimiter(fairLimiter, executor, 3_000L, 5_000L);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try {
            CompletableFuture<BugFixMessage> first = CompletableFuture.supplyAsync(() -> limiter.enqueue(
                    request("task-redis-1"),
                    () -> {
                        firstEntered.countDown();
                        await(releaseFirst);
                        return message("ticket-first", false);
                    },
                    () -> message("ticket-first-timeout", true)
            ));
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

            CompletableFuture<BugFixMessage> second = CompletableFuture.supplyAsync(() -> limiter.enqueue(
                    request("task-redis-2"),
                    () -> message("ticket-second", false),
                    () -> message("ticket-second-timeout", true)
            ));

            Thread.sleep(200L);
            assertFalse(second.isDone());
            releaseFirst.countDown();

            assertEquals("ticket-first", first.get(5, TimeUnit.SECONDS).ticketId());
            BugFixMessage secondMessage = second.get(5, TimeUnit.SECONDS);
            assertEquals("ticket-second", secondMessage.ticketId());
            assertFalse(secondMessage.rejected());
        } finally {
            releaseFirst.countDown();
            limiter.close();
            executor.shutdownNow();
            redissonClient.getKeys().deleteByPattern(limiterName + "*");
            redissonClient.shutdown();
        }
    }

    private static ChatQueueLimiter.ChatQueueRequest request(String taskId) {
        return new ChatQueueLimiter.ChatQueueRequest(
                "金额为空时 OrderService.create 写入订单失败",
                taskId
        );
    }

    private static BugFixMessage message(String ticketId, boolean rejected) {
        return new BugFixMessage(
                ticketId,
                "",
                "",
                List.of(),
                "task",
                false,
                "",
                "",
                "",
                "",
                List.of(),
                List.of(),
                "",
                "",
                "",
                List.of(),
                List.of(),
                "",
                rejected
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
