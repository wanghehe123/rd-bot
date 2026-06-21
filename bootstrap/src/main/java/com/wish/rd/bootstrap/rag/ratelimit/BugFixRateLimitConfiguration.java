package com.wish.rd.bootstrap.rag.ratelimit;

import com.wish.rd.engine.rag.BugFixMessage;
import com.wish.rd.engine.rag.ChatQueueLimiter;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Bug 修复聊天队列限流装配。
 */
@Configuration
public class BugFixRateLimitConfiguration {

    private static final String CHAT_LIMITER_NAME = "rd-bot:bugfix:chat";

    @Bean(destroyMethod = "shutdown")
    public ExecutorService bugFixChatEntryExecutor(
            @Value("${rag.rate-limit.global.executor-size:4}") int executorSize
    ) {
        int size = Math.max(1, executorSize);
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("rd_bot_bugfix_chat_entry_" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(size, factory);
    }

    @Bean(destroyMethod = "close")
    public ChatQueueLimiter chatQueueLimiter(
            ExecutorService bugFixChatEntryExecutor,
            @Value("${rag.rate-limit.global.enabled:false}") boolean enabled,
            @Value("${rag.rate-limit.global.max-concurrent:4}") int maxConcurrent,
            @Value("${rag.rate-limit.global.max-wait-seconds:20}") int maxWaitSeconds,
            @Value("${rag.rate-limit.global.lease-seconds:600}") int leaseSeconds,
            @Value("${rag.rate-limit.global.poll-interval-ms:200}") int pollIntervalMs,
            @Value("${spring.data.redis.host:localhost}") String redisHost,
            @Value("${spring.data.redis.port:6379}") int redisPort,
            @Value("${spring.data.redis.password:}") String redisPassword
    ) {
        if (!enabled) {
            return new CloseableChatQueueLimiter(ChatQueueLimiter.passThrough());
        }
        if (maxConcurrent <= 0) {
            return new CloseableChatQueueLimiter(ChatQueueLimiter.alwaysReject());
        }
        AtomicReference<RedissonClient> redissonClientRef = new AtomicReference<>();
        RedisChatQueueLimiter redisLimiter = new RedisChatQueueLimiter(
                () -> {
                    RedissonClient redissonClient = createRedissonClient(redisHost, redisPort, redisPassword);
                    redissonClientRef.set(redissonClient);
                    return new FairDistributedRateLimiter(
                            CHAT_LIMITER_NAME,
                            redissonClient,
                            () -> maxConcurrent,
                            () -> Math.max(1, leaseSeconds),
                            () -> Math.max(50, pollIntervalMs)
                    );
                },
                bugFixChatEntryExecutor,
                TimeUnitSeconds.toMillis(Math.max(1, maxWaitSeconds)),
                TimeUnitSeconds.toMillis(Math.max(1, leaseSeconds)),
                () -> {
                    RedissonClient redissonClient = redissonClientRef.get();
                    if (redissonClient != null) {
                        redissonClient.shutdown();
                    }
                }
        );
        return new CloseableChatQueueLimiter(redisLimiter);
    }

    private RedissonClient createRedissonClient(String host, int port, String password) {
        Config config = new Config();
        String actualHost = host == null || host.isBlank() ? "localhost" : host;
        config.useSingleServer()
                .setAddress("redis://" + actualHost + ":" + port)
                .setPassword(password == null || password.isBlank() ? null : password);
        return Redisson.create(config);
    }

    private static final class CloseableChatQueueLimiter implements ChatQueueLimiter, AutoCloseable {

        private final ChatQueueLimiter delegate;
        private final Runnable closeHook;

        private CloseableChatQueueLimiter(ChatQueueLimiter delegate) {
            this(delegate, () -> { });
        }

        private CloseableChatQueueLimiter(ChatQueueLimiter delegate, Runnable closeHook) {
            this.delegate = delegate;
            this.closeHook = closeHook;
        }

        @Override
        public BugFixMessage enqueue(
                ChatQueueRequest request,
                Supplier<BugFixMessage> onAcquire,
                Supplier<BugFixMessage> onTimeout
        ) {
            return delegate.enqueue(request, onAcquire, onTimeout);
        }

        @Override
        public void close() throws Exception {
            if (delegate instanceof AutoCloseable closeable) {
                closeable.close();
            }
            closeHook.run();
        }
    }

    private static final class TimeUnitSeconds {

        private static long toMillis(int seconds) {
            return seconds * 1_000L;
        }
    }
}
