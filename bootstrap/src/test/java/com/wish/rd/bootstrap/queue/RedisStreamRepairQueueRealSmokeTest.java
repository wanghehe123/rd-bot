package com.wish.rd.bootstrap.queue;

import com.wish.rd.bootstrap.queue.impl.RedisStreamRepairQueueAdapter;
import com.wish.rd.bootstrap.queue.impl.InMemoryRepairQueueDeadLetterRepository;
import com.wish.rd.engine.ticket.RepairQueueConsumer;
import com.wish.rd.engine.ticket.RepairQueueDeadLetterRepository;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.RStream;
import org.redisson.api.stream.PendingEntry;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Redis Stream smoke coverage, deliberately opt-in through
 * {@code -Drd.redis.stream.smoke=true} so ordinary unit-test runs stay local.
 */
@EnabledIfSystemProperty(named = "rd.redis.stream.smoke", matches = "true")
class RedisStreamRepairQueueRealSmokeTest {

    @Test
    void shouldPersistTerminalFalseDeadLetterOnceAndPreventAutoClaimRedelivery() throws Exception {
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);
        String streamKey = "rd-bot:smoke:terminal-false:" + suffix;
        String consumerGroup = "rd-bot-smoke-terminal-false-group-" + suffix;
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setRetryAttempts(0);
        RedissonClient client = Redisson.create(config);
        ThreadPoolTaskExecutor executor = singleLoopExecutor("rd-redis-stream-terminal-false-");
        ThreadPoolTaskScheduler leaseScheduler = singleLeaseScheduler("rd-redis-stream-terminal-false-lease-");
        RedisStreamRepairQueueAdapter adapter = null;
        try {
            RedisStreamRepairQueueProperties properties = leaseProperties(
                    streamKey,
                    consumerGroup,
                    "terminal-false"
            );
            properties.setMaxRetryAttempts(3);
            InMemoryRepairQueueDeadLetterRepository deadLetters = new InMemoryRepairQueueDeadLetterRepository();
            AtomicInteger deliveries = new AtomicInteger();
            RepairQueueConsumer consumer = message -> {
                deliveries.incrementAndGet();
                return false;
            };
            adapter = new RedisStreamRepairQueueAdapter(
                    properties,
                    client,
                    provider(consumer),
                    executor,
                    leaseScheduler,
                    provider(deadLetters)
            );

            adapter.start();
            assertTrue(adapter.publish(new RepairTicketMessage(
                    "FS-REDIS-TERMINAL-FALSE-" + suffix,
                    "P1",
                    "trace-terminal-false-" + suffix,
                    4,
                    "smoke",
                    "evt-terminal-false-" + suffix,
                    "helpdesk.ticket.created_v1",
                    Instant.now()
            )).success());

            RStream<String, String> stream = client.getStream(streamKey, StringCodec.INSTANCE);
            assertTrue(awaitUntil(
                    () -> deadLetters.list().size() == 1 && stream.size() == 0L,
                    Duration.ofSeconds(5)
            ), "terminal false must persist a dead letter and owner-fenced ACK/DEL the PEL record");
            Thread.sleep(350L);

            assertAll(
                    () -> assertEquals(1, deliveries.get(), "XAUTOCLAIM must not re-invoke a settled terminal record"),
                    () -> assertEquals(1, deadLetters.list().size(), "terminal dead-letter persistence must be idempotent"),
                    () -> assertEquals(4, deadLetters.list().getFirst().originalAttempt()),
                    () -> assertTrue(deadLetters.list().getFirst().reason().contains("retry attempts exceeded")),
                    () -> assertTrue(stream.listPending(
                            consumerGroup,
                            StreamMessageId.MIN,
                            StreamMessageId.MAX,
                            10
                    ).isEmpty(), "terminal record must not remain in the PEL")
            );
        } finally {
            allowStop(adapter);
            client.getKeys().delete(streamKey);
            executor.shutdown();
            leaseScheduler.shutdown();
            client.shutdown();
        }
    }

    @Test
    void shouldKeepLongRunningHandlerLeasedAgainstAnotherConsumer() throws Exception {
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);
        String streamKey = "rd-bot:smoke:lease:" + suffix;
        String consumerGroup = "rd-bot-smoke-lease-group-" + suffix;
        Config firstConfig = new Config();
        firstConfig.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setRetryAttempts(0);
        Config secondConfig = new Config();
        secondConfig.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setRetryAttempts(0);
        RedissonClient firstClient = Redisson.create(firstConfig);
        RedissonClient secondClient = Redisson.create(secondConfig);
        ThreadPoolTaskExecutor firstExecutor = singleLoopExecutor("rd-redis-stream-lease-first-");
        ThreadPoolTaskExecutor secondExecutor = singleLoopExecutor("rd-redis-stream-lease-second-");
        ThreadPoolTaskScheduler firstLeaseScheduler = singleLeaseScheduler("rd-redis-stream-lease-first-scheduler-");
        ThreadPoolTaskScheduler secondLeaseScheduler = singleLeaseScheduler("rd-redis-stream-lease-second-scheduler-");
        RedisStreamRepairQueueAdapter firstAdapter = null;
        RedisStreamRepairQueueAdapter secondAdapter = null;
        CountDownLatch allowFirstHandlerToFinish = new CountDownLatch(1);
        try {
            RedisStreamRepairQueueProperties firstProperties = leaseProperties(streamKey, consumerGroup, "lease-first");
            RedisStreamRepairQueueProperties secondProperties = leaseProperties(streamKey, consumerGroup, "lease-second");
            CountDownLatch firstHandlerStarted = new CountDownLatch(1);
            CountDownLatch firstSettled = new CountDownLatch(1);
            CountDownLatch secondHandlerRan = new CountDownLatch(1);
            AtomicInteger activeHandlers = new AtomicInteger();
            AtomicInteger concurrentHandlers = new AtomicInteger();
            RepairQueueConsumer firstConsumer = message -> {
                if (activeHandlers.incrementAndGet() > 1) {
                    concurrentHandlers.incrementAndGet();
                }
                firstHandlerStarted.countDown();
                try {
                    if (!allowFirstHandlerToFinish.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release the first handler");
                    }
                    firstSettled.countDown();
                    return true;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("first handler interrupted", exception);
                } finally {
                    activeHandlers.decrementAndGet();
                }
            };
            RepairQueueConsumer secondConsumer = message -> {
                if (activeHandlers.incrementAndGet() > 1) {
                    concurrentHandlers.incrementAndGet();
                }
                try {
                    secondHandlerRan.countDown();
                    return true;
                } finally {
                    activeHandlers.decrementAndGet();
                }
            };
            firstAdapter = new RedisStreamRepairQueueAdapter(
                    firstProperties,
                    firstClient,
                    provider(firstConsumer),
                    firstExecutor,
                    firstLeaseScheduler,
                    provider(RepairQueueDeadLetterRepository.noop())
            );
            secondAdapter = new RedisStreamRepairQueueAdapter(
                    secondProperties,
                    secondClient,
                    provider(secondConsumer),
                    secondExecutor,
                    secondLeaseScheduler,
                    provider(RepairQueueDeadLetterRepository.noop())
            );

            firstAdapter.start();
            assertTrue(firstAdapter.publish(new RepairTicketMessage(
                    "FS-REDIS-LEASE-" + suffix,
                    "P1",
                    "trace-lease-" + suffix,
                    1,
                    "smoke",
                    "evt-lease-" + suffix,
                    "helpdesk.ticket.created_v1",
                    Instant.now()
            )).success());
            assertTrue(firstHandlerStarted.await(3, TimeUnit.SECONDS), "first consumer should own the delivery");

            secondAdapter.start();
            assertFalse(secondHandlerRan.await(500, TimeUnit.MILLISECONDS),
                    "active leased work must not be reclaimed by the second consumer");

            allowFirstHandlerToFinish.countDown();
            assertTrue(firstSettled.await(3, TimeUnit.SECONDS), "first consumer should complete the delivery");
            RStream<String, String> stream = firstClient.getStream(streamKey, StringCodec.INSTANCE);
            assertTrue(awaitUntil(() -> stream.size() == 0L, Duration.ofSeconds(3)),
                    "only the current owner may settle the long-running delivery");
            assertEquals(0, concurrentHandlers.get(), "two consumers must never execute the same delivery concurrently");
        } finally {
            allowFirstHandlerToFinish.countDown();
            allowStop(firstAdapter);
            allowStop(secondAdapter);
            firstClient.getKeys().delete(streamKey);
            firstExecutor.shutdown();
            secondExecutor.shutdown();
            firstLeaseScheduler.shutdown();
            secondLeaseScheduler.shutdown();
            firstClient.shutdown();
            secondClient.shutdown();
        }
    }

    @Test
    void shouldFenceLateStaleOwnerAndAllowOnlyTheClaimingConsumerToSettle() throws Exception {
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);
        String streamKey = "rd-bot:smoke:stale-owner:" + suffix;
        String consumerGroup = "rd-bot-smoke-stale-owner-group-" + suffix;
        Config firstConfig = new Config();
        firstConfig.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setRetryAttempts(0);
        Config secondConfig = new Config();
        secondConfig.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setRetryAttempts(0);
        RedissonClient firstClient = Redisson.create(firstConfig);
        RedissonClient secondClient = Redisson.create(secondConfig);
        ThreadPoolTaskExecutor firstExecutor = singleLoopExecutor("rd-redis-stream-stale-first-");
        ThreadPoolTaskExecutor secondExecutor = singleLoopExecutor("rd-redis-stream-stale-second-");
        ThreadPoolTaskScheduler firstLeaseScheduler = singleLeaseScheduler("rd-redis-stream-stale-first-scheduler-");
        ThreadPoolTaskScheduler secondLeaseScheduler = singleLeaseScheduler("rd-redis-stream-stale-second-scheduler-");
        RedisStreamRepairQueueAdapter firstAdapter = null;
        RedisStreamRepairQueueAdapter secondAdapter = null;
        CountDownLatch releaseFirstHandler = new CountDownLatch(1);
        CountDownLatch releaseSecondHandler = new CountDownLatch(1);
        try {
            RedisStreamRepairQueueProperties firstProperties = leaseProperties(streamKey, consumerGroup, "stale-first");
            RedisStreamRepairQueueProperties secondProperties = leaseProperties(streamKey, consumerGroup, "stale-second");
            CountDownLatch firstHandlerStarted = new CountDownLatch(1);
            CountDownLatch firstHandlerReturned = new CountDownLatch(1);
            CountDownLatch secondHandlerStarted = new CountDownLatch(1);
            CountDownLatch secondHandlerSettled = new CountDownLatch(1);
            AtomicInteger firstHandlers = new AtomicInteger();
            AtomicInteger secondHandlers = new AtomicInteger();
            RepairQueueConsumer firstConsumer = message -> {
                firstHandlers.incrementAndGet();
                firstHandlerStarted.countDown();
                try {
                    if (!releaseFirstHandler.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release stale first handler");
                    }
                    firstHandlerReturned.countDown();
                    return true;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("stale first handler interrupted", exception);
                }
            };
            RepairQueueConsumer secondConsumer = message -> {
                secondHandlers.incrementAndGet();
                secondHandlerStarted.countDown();
                try {
                    if (!firstHandlerReturned.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("stale first handler never returned");
                    }
                    if (!releaseSecondHandler.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release stale second handler");
                    }
                    secondHandlerSettled.countDown();
                    return true;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("stale second handler interrupted", exception);
                }
            };
            firstAdapter = new RedisStreamRepairQueueAdapter(
                    firstProperties,
                    firstClient,
                    provider(firstConsumer),
                    firstExecutor,
                    firstLeaseScheduler,
                    provider(RepairQueueDeadLetterRepository.noop())
            );
            secondAdapter = new RedisStreamRepairQueueAdapter(
                    secondProperties,
                    secondClient,
                    provider(secondConsumer),
                    secondExecutor,
                    secondLeaseScheduler,
                    provider(RepairQueueDeadLetterRepository.noop())
            );

            firstAdapter.start();
            assertTrue(firstAdapter.publish(new RepairTicketMessage(
                    "FS-REDIS-STALE-" + suffix,
                    "P1",
                    "trace-stale-" + suffix,
                    1,
                    "smoke",
                    "evt-stale-" + suffix,
                    "helpdesk.ticket.created_v1",
                    Instant.now()
            )).success());
            assertTrue(firstHandlerStarted.await(3, TimeUnit.SECONDS), "first consumer should own the delivery");

            // Deliberately stop only the first owner's renewal scheduler. Its handler remains running,
            // allowing the second consumer to make the record stale and exercise the late-owner fence.
            firstLeaseScheduler.shutdown();
            Thread.sleep(250L);
            secondAdapter.start();
            assertTrue(secondHandlerStarted.await(3, TimeUnit.SECONDS), "second consumer should claim stale work");

            releaseFirstHandler.countDown();
            assertTrue(firstHandlerReturned.await(3, TimeUnit.SECONDS), "old owner should attempt late completion");
            Thread.sleep(200L);
            RStream<String, String> stream = firstClient.getStream(streamKey, StringCodec.INSTANCE);
            List<PendingEntry> pending = stream.listPending(
                    consumerGroup,
                    StreamMessageId.MIN,
                    StreamMessageId.MAX,
                    1
            );
            assertAll(
                    () -> assertEquals(1, pending.size(), "late owner must not ACK or delete the new owner's PEL entry"),
                    () -> assertTrue(pending.getFirst().getConsumerName().startsWith("stale-second-"))
            );

            releaseSecondHandler.countDown();
            assertTrue(secondHandlerSettled.await(3, TimeUnit.SECONDS), "claiming consumer should settle exactly once");
            assertAll(
                    () -> assertTrue(awaitUntil(() -> stream.size() == 0L, Duration.ofSeconds(3))),
                    () -> assertEquals(1, firstHandlers.get()),
                    () -> assertEquals(1, secondHandlers.get())
            );
        } finally {
            releaseFirstHandler.countDown();
            releaseSecondHandler.countDown();
            allowStop(firstAdapter);
            allowStop(secondAdapter);
            firstClient.getKeys().delete(streamKey);
            firstExecutor.shutdown();
            secondExecutor.shutdown();
            firstLeaseScheduler.shutdown();
            secondLeaseScheduler.shutdown();
            firstClient.shutdown();
            secondClient.shutdown();
        }
    }

    @Test
    void shouldPublishRetryThrownConsumerFailureAndAcknowledgeAgainstLocalRedis() throws Exception {
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);
        String streamKey = "rd-bot:smoke:repair:" + suffix;
        String consumerGroup = "rd-bot-smoke-group-" + suffix;
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        RedissonClient client = Redisson.create(config);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("rd-redis-stream-smoke-");
        executor.initialize();
        ThreadPoolTaskScheduler leaseScheduler = singleLeaseScheduler("rd-redis-stream-smoke-lease-");
        RedisStreamRepairQueueAdapter adapter = null;
        try {
            RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
            properties.setStreamKey(streamKey);
            properties.setConsumerGroup(consumerGroup);
            properties.setConsumerNameBase("rd-bot-smoke");
            properties.setConsumerConcurrency(1);
            properties.setPollTimeoutMillis(50L);
            properties.setPendingClaimIdleMillis(0L);
            properties.setFailureBackoffMillis(25L);
            List<Integer> deliveredAttempts = new CopyOnWriteArrayList<>();
            CountDownLatch settled = new CountDownLatch(1);
            RepairQueueConsumer consumer = message -> {
                deliveredAttempts.add(message.attempt());
                return switch (deliveredAttempts.size()) {
                    case 1 -> false;
                    case 2 -> throw new IllegalStateException("simulated post-retry consumer failure");
                    case 3 -> {
                        settled.countDown();
                        yield true;
                    }
                    default -> throw new IllegalStateException("unexpected delivery after acknowledgement");
                };
            };
            adapter = new RedisStreamRepairQueueAdapter(
                    properties,
                    client,
                    provider(consumer),
                    executor,
                    leaseScheduler,
                    provider(RepairQueueDeadLetterRepository.noop())
            );

            adapter.start();
            assertTrue(adapter.publish(new RepairTicketMessage(
                    "FS-REDIS-SMOKE-" + suffix,
                    "P1",
                    "trace-" + suffix,
                    1,
                    "smoke",
                    "evt-" + suffix,
                    "helpdesk.ticket.created_v1",
                    Instant.now()
            )).success());

            assertTrue(settled.await(10, TimeUnit.SECONDS), "retry transfer and exception recovery should settle");
            RStream<String, String> stream = client.getStream(streamKey, StringCodec.INSTANCE);
            assertTrue(awaitUntil(() -> stream.size() == 0L, Duration.ofSeconds(5)),
                    "successful recovery must atomically acknowledge and delete the final record");
            assertEquals(List.of(1, 2, 3), deliveredAttempts);
        } finally {
            if (adapter != null) {
                adapter.stop();
            }
            client.getKeys().delete(streamKey);
            executor.shutdown();
            leaseScheduler.shutdown();
            client.shutdown();
        }
    }

    private static boolean awaitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25L);
        }
        return condition.getAsBoolean();
    }

    private static RedisStreamRepairQueueProperties leaseProperties(
            String streamKey,
            String consumerGroup,
            String consumerNameBase
    ) {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setStreamKey(streamKey);
        properties.setConsumerGroup(consumerGroup);
        properties.setConsumerNameBase(consumerNameBase);
        properties.setConsumerConcurrency(1);
        properties.setPollTimeoutMillis(25L);
        properties.setPendingClaimIdleMillis(100L);
        properties.setFailureBackoffMillis(5L);
        return properties;
    }

    private static ThreadPoolTaskExecutor singleLoopExecutor(String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.initialize();
        return executor;
    }

    private static ThreadPoolTaskScheduler singleLeaseScheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.initialize();
        return scheduler;
    }

    private static void allowStop(RedisStreamRepairQueueAdapter adapter) {
        if (adapter != null) {
            adapter.stop();
        }
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                if (value == null) {
                    throw new NoSuchElementException();
                }
                return value;
            }

            @Override
            public T getIfAvailable(Supplier<T> defaultSupplier) {
                return value == null ? defaultSupplier.get() : value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? Stream.<T>empty().iterator() : Stream.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }
        };
    }
}
