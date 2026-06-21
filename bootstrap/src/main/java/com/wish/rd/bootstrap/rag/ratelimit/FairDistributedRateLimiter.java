package com.wish.rd.bootstrap.rag.ratelimit;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Redis backed fair distributed rate limiter migrated from ragent.
 *
 * <p>It uses a Redisson expirable semaphore for concurrency, a Redis ZSET for FIFO queue order,
 * a Lua script for atomic head-window claims, and a topic to wake local pollers across instances.
 */
public final class FairDistributedRateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(FairDistributedRateLimiter.class);
    private static final String LUA_PATH = "lua/queue_claim_atomic.lua";
    private static final long ENTRY_TTL_BUFFER_MILLIS = 5_000L;

    private final String name;
    private final RedissonClient redissonClient;
    private final IntSupplier maxPermitsSupplier;
    private final IntSupplier leaseSecondsSupplier;
    private final IntSupplier pollIntervalMsSupplier;

    private final String semaphoreKey;
    private final String queueKey;
    private final String queueSeqKey;
    private final String notifyTopicKey;
    private final String entryKeyPrefix;
    private final String claimLua;

    private final ScheduledExecutorService scheduler;
    private final PollNotifier pollNotifier;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile int notifyListenerId = -1;

    public FairDistributedRateLimiter(
            String name,
            RedissonClient redissonClient,
            IntSupplier maxPermitsSupplier,
            IntSupplier leaseSecondsSupplier,
            IntSupplier pollIntervalMsSupplier
    ) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient must not be null");
        this.maxPermitsSupplier = Objects.requireNonNull(maxPermitsSupplier, "maxPermitsSupplier must not be null");
        this.leaseSecondsSupplier = Objects.requireNonNull(leaseSecondsSupplier, "leaseSecondsSupplier must not be null");
        this.pollIntervalMsSupplier = Objects.requireNonNull(pollIntervalMsSupplier, "pollIntervalMsSupplier must not be null");

        this.semaphoreKey = name + ":semaphore";
        this.queueKey = name + ":queue";
        this.queueSeqKey = name + ":queue:seq";
        this.notifyTopicKey = name + ":queue:notify";
        this.entryKeyPrefix = name + ":entry:";
        this.claimLua = loadLuaScript();

        String threadPrefix = name.replace(':', '_');
        int schedulerSize = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        AtomicInteger threadCounter = new AtomicInteger();
        this.scheduler = new ScheduledThreadPoolExecutor(schedulerSize, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(threadPrefix + "_scheduler_" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.pollNotifier = new PollNotifier(this::availablePermits, scheduler);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        redissonClient.getPermitExpirableSemaphore(semaphoreKey).trySetPermits(maxPermitsSupplier.getAsInt());
        RTopic topic = redissonClient.getTopic(notifyTopicKey);
        notifyListenerId = topic.addListener(String.class, (channel, message) -> pollNotifier.fire());
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        if (notifyListenerId != -1) {
            redissonClient.getTopic(notifyTopicKey).removeListener(notifyListenerId);
            notifyListenerId = -1;
        }
        scheduler.shutdown();
        awaitShutdown(scheduler);
        pollNotifier.clear();
    }

    public void acquire(AcquireRequest request) {
        Ticket ticket = new Ticket(request);
        if (request.cancelBinder() != null) {
            request.cancelBinder().accept(ticket::cancel);
        }
        setEntryMarker(ticket.requestId, request.maxWaitMillis());
        RScoredSortedSet<String> queue = redissonClient.getScoredSortedSet(queueKey, StringCodec.INSTANCE);
        queue.add(nextQueueSeq(), ticket.requestId);
        if (tryAcquireIfReady(ticket)) {
            return;
        }
        scheduleQueuePoll(ticket);
    }

    private enum State {
        PENDING,
        GRANTED,
        TIMED_OUT,
        CANCELLED
    }

    private final class Ticket {
        final String requestId = UUID.randomUUID().toString();
        final long deadline;
        final AcquireRequest request;
        final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
        final AtomicReference<String> permitRef = new AtomicReference<>();
        volatile ScheduledFuture<?> future;

        Ticket(AcquireRequest request) {
            this.request = request;
            this.deadline = System.currentTimeMillis() + request.maxWaitMillis();
        }

        boolean isPending() {
            return state.get() == State.PENDING;
        }

        void cancel() {
            state.compareAndSet(State.PENDING, State.CANCELLED);
            cleanup();
        }

        void timeout() {
            if (!state.compareAndSet(State.PENDING, State.TIMED_OUT)) {
                return;
            }
            cleanup();
            submitSafely(request.onTimeout(), "onTimeout");
        }

        boolean grant(String permitId) {
            permitRef.set(permitId);
            if (!state.compareAndSet(State.PENDING, State.GRANTED)) {
                if (permitRef.compareAndSet(permitId, null)) {
                    releasePermitQuietly(permitId);
                    publishQueueNotify();
                }
                return false;
            }
            unregisterFromNotifier();
            cancelFutureQuietly();
            Runnable wrapped = () -> {
                try {
                    request.onAcquired().run();
                } finally {
                    releaseHeldPermit();
                }
            };
            try {
                request.onAcquiredExecutor().execute(wrapped);
                return true;
            } catch (RejectedExecutionException ex) {
                LOG.warn("[{}] onAcquired submission rejected, falling back to timeout", name, ex);
                releaseHeldPermit();
                cleanup();
                submitSafely(request.onTimeout(), "onTimeout(fallback)");
                return false;
            }
        }

        void releaseHeldPermit() {
            String permitId = permitRef.getAndSet(null);
            if (permitId != null) {
                releasePermitQuietly(permitId);
                publishQueueNotify();
            }
        }

        void cleanup() {
            boolean removed = false;
            try {
                removed = redissonClient.getScoredSortedSet(queueKey, StringCodec.INSTANCE).remove(requestId);
            } catch (Exception ex) {
                LOG.debug("[{}] failed to remove queue item requestId={}", name, requestId, ex);
            }
            deleteEntryMarker(requestId);

            boolean releasedPermit = false;
            if (state.get() != State.GRANTED) {
                String permitId = permitRef.getAndSet(null);
                if (permitId != null) {
                    releasePermitQuietly(permitId);
                    releasedPermit = true;
                }
            }
            if (removed || releasedPermit) {
                publishQueueNotify();
            }
            unregisterFromNotifier();
            cancelFutureQuietly();
        }

        void unregisterFromNotifier() {
            pollNotifier.unregister(requestId);
        }

        void cancelFutureQuietly() {
            ScheduledFuture<?> current = future;
            if (current != null && !current.isCancelled()) {
                current.cancel(false);
            }
        }

        private void submitSafely(Runnable runnable, String label) {
            try {
                request.onAcquiredExecutor().execute(runnable);
            } catch (Exception ex) {
                LOG.warn("[{}] {} submission failed; callback dropped", name, label, ex);
            }
        }
    }

    private boolean tryAcquireIfReady(Ticket ticket) {
        if (!ticket.isPending()) {
            return false;
        }
        int available = availablePermits();
        if (available <= 0) {
            return false;
        }
        long claimedScore = claimIfReady(ticket.requestId, available);
        if (claimedScore < 0L) {
            return false;
        }
        String permitId = tryAcquirePermit();
        if (permitId == null) {
            setEntryMarker(ticket.requestId, Math.max(1L, ticket.deadline - System.currentTimeMillis()));
            RScoredSortedSet<String> queue = redissonClient.getScoredSortedSet(queueKey, StringCodec.INSTANCE);
            queue.add(claimedScore, ticket.requestId);
            publishQueueNotify();
            if (!ticket.isPending()) {
                queue.remove(ticket.requestId);
                deleteEntryMarker(ticket.requestId);
            }
            return false;
        }
        if (!ticket.isPending()) {
            releasePermitQuietly(permitId);
            publishQueueNotify();
            return false;
        }
        publishQueueNotify();
        return ticket.grant(permitId);
    }

    private void scheduleQueuePoll(Ticket ticket) {
        int interval = Math.max(50, pollIntervalMsSupplier.getAsInt());
        Runnable poller = () -> {
            if (!ticket.isPending()) {
                ticket.unregisterFromNotifier();
                ticket.cancelFutureQuietly();
                return;
            }
            if (System.currentTimeMillis() > ticket.deadline) {
                ticket.timeout();
                return;
            }
            tryAcquireIfReady(ticket);
        };
        ticket.future = scheduler.scheduleAtFixedRate(poller, interval, interval, TimeUnit.MILLISECONDS);
        pollNotifier.register(ticket.requestId, poller);
    }

    private String tryAcquirePermit() {
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(semaphoreKey);
        try {
            return semaphore.tryAcquire(0, leaseSecondsSupplier.getAsInt(), TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private int availablePermits() {
        return redissonClient.getPermitExpirableSemaphore(semaphoreKey).availablePermits();
    }

    private void releasePermitQuietly(String permitId) {
        try {
            redissonClient.getPermitExpirableSemaphore(semaphoreKey).release(permitId);
        } catch (Exception ex) {
            LOG.debug("[{}] failed to release permit: {}", name, ex.getMessage());
        }
    }

    private void setEntryMarker(String requestId, long remainingMillis) {
        long ttlMillis = Math.max(remainingMillis, 1L) + ENTRY_TTL_BUFFER_MILLIS;
        try {
            RBucket<String> bucket = redissonClient.getBucket(entryKeyPrefix + requestId, StringCodec.INSTANCE);
            bucket.set("1", Duration.ofMillis(ttlMillis));
        } catch (Exception ex) {
            LOG.debug("[{}] failed to set entry marker requestId={}", name, requestId, ex);
        }
    }

    private void deleteEntryMarker(String requestId) {
        try {
            redissonClient.getBucket(entryKeyPrefix + requestId, StringCodec.INSTANCE).delete();
        } catch (Exception ex) {
            LOG.debug("[{}] failed to delete entry marker requestId={}", name, requestId, ex);
        }
    }

    private long claimIfReady(String requestId, int availablePermits) {
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        List<Object> result = script.eval(
                RScript.Mode.READ_WRITE,
                claimLua,
                RScript.ReturnType.LIST,
                List.of(queueKey),
                requestId,
                String.valueOf(availablePermits),
                entryKeyPrefix
        );
        if (result == null || result.isEmpty() || parseLong(result.getFirst()) != 1L) {
            return -1L;
        }
        return result.size() >= 2 ? parseLong(result.get(1)) : nextQueueSeq();
    }

    private long nextQueueSeq() {
        RAtomicLong sequence = redissonClient.getAtomicLong(queueSeqKey);
        return sequence.incrementAndGet();
    }

    private void publishQueueNotify() {
        redissonClient.getTopic(notifyTopicKey).publish("permit_changed");
    }

    private static long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static String loadLuaScript() {
        try {
            ClassPathResource resource = new ClassPathResource(LUA_PATH);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load Lua script: " + LUA_PATH, ex);
        }
    }

    private static void awaitShutdown(ScheduledExecutorService executor) {
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public record AcquireRequest(
            long maxWaitMillis,
            Runnable onAcquired,
            Runnable onTimeout,
            Executor onAcquiredExecutor,
            Consumer<Runnable> cancelBinder
    ) {

        public AcquireRequest {
            Objects.requireNonNull(onAcquired, "onAcquired must not be null");
            Objects.requireNonNull(onTimeout, "onTimeout must not be null");
            Objects.requireNonNull(onAcquiredExecutor, "onAcquiredExecutor must not be null");
            if (maxWaitMillis <= 0L) {
                throw new IllegalArgumentException("maxWaitMillis must be > 0");
            }
        }
    }

    private static final class PollNotifier {

        private final IntSupplier permitSupplier;
        private final Executor executor;
        private final ConcurrentHashMap<String, Runnable> pollers = new ConcurrentHashMap<>();
        private final AtomicBoolean firing = new AtomicBoolean(false);
        private final AtomicInteger pendingNotifications = new AtomicInteger(0);

        PollNotifier(IntSupplier permitSupplier, Executor executor) {
            this.permitSupplier = permitSupplier;
            this.executor = executor;
        }

        void register(String requestId, Runnable poller) {
            pollers.put(requestId, poller);
        }

        void unregister(String requestId) {
            pollers.remove(requestId);
        }

        void fire() {
            pendingNotifications.incrementAndGet();
            if (!firing.compareAndSet(false, true)) {
                return;
            }
            executor.execute(() -> {
                do {
                    pendingNotifications.set(0);
                    try {
                        if (permitSupplier.getAsInt() <= 0) {
                            break;
                        }
                        for (Runnable poller : pollers.values()) {
                            try {
                                poller.run();
                            } catch (Exception ex) {
                                LOG.debug("poller failed", ex);
                            }
                        }
                    } finally {
                        firing.set(false);
                    }
                } while (pendingNotifications.get() > 0 && firing.compareAndSet(false, true));
            });
        }

        void clear() {
            pollers.clear();
        }
    }
}
