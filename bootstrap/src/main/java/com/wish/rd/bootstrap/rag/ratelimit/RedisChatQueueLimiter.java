package com.wish.rd.bootstrap.rag.ratelimit;

import com.wish.rd.engine.rag.BugFixMessage;
import com.wish.rd.engine.rag.ChatQueueLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Redis backed implementation of the BugFix chat queue limiter.
 */
public final class RedisChatQueueLimiter implements ChatQueueLimiter, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RedisChatQueueLimiter.class);

    private final Supplier<FairDistributedRateLimiter> rateLimiterSupplier;
    private final Executor executor;
    private final long maxWaitMillis;
    private final long resultWaitMillis;
    private final Runnable closeHook;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile FairDistributedRateLimiter rateLimiter;

    public RedisChatQueueLimiter(
            FairDistributedRateLimiter rateLimiter,
            Executor executor,
            long maxWaitMillis,
            long leaseMillis
    ) {
        this(() -> rateLimiter, executor, maxWaitMillis, leaseMillis, () -> { });
    }

    public RedisChatQueueLimiter(
            Supplier<FairDistributedRateLimiter> rateLimiterSupplier,
            Executor executor,
            long maxWaitMillis,
            long leaseMillis,
            Runnable closeHook
    ) {
        this.rateLimiterSupplier = Objects.requireNonNull(rateLimiterSupplier, "rateLimiterSupplier must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.maxWaitMillis = Math.max(1L, maxWaitMillis);
        this.resultWaitMillis = this.maxWaitMillis + Math.max(1L, leaseMillis) + 5_000L;
        this.closeHook = closeHook == null ? () -> { } : closeHook;
    }

    @Override
    public BugFixMessage enqueue(
            ChatQueueRequest request,
            Supplier<BugFixMessage> onAcquire,
            Supplier<BugFixMessage> onTimeout
    ) {
        FairDistributedRateLimiter actualRateLimiter = ensureStarted();
        CompletableFuture<BugFixMessage> result = new CompletableFuture<>();
        AtomicReference<Runnable> cancelRef = new AtomicReference<>();
        actualRateLimiter.acquire(new FairDistributedRateLimiter.AcquireRequest(
                maxWaitMillis,
                () -> complete(result, onAcquire),
                () -> complete(result, onTimeout),
                executor,
                cancelRef::set
        ));
        try {
            return result.get(resultWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            cancel(cancelRef);
            return onTimeout.get();
        } catch (TimeoutException ex) {
            LOG.warn("Redis chat queue wait timed out for task={}", request.taskId());
            cancel(cancelRef);
            return onTimeout.get();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Redis chat queue callback failed", cause);
        }
    }

    @Override
    public void close() {
        if (started.compareAndSet(true, false)) {
            FairDistributedRateLimiter actualRateLimiter = rateLimiter;
            if (actualRateLimiter != null) {
                actualRateLimiter.stop();
            }
        }
        closeHook.run();
    }

    private FairDistributedRateLimiter ensureStarted() {
        FairDistributedRateLimiter actualRateLimiter = rateLimiter;
        if (actualRateLimiter != null) {
            return actualRateLimiter;
        }
        synchronized (this) {
            actualRateLimiter = rateLimiter;
            if (actualRateLimiter == null) {
                actualRateLimiter = rateLimiterSupplier.get();
                rateLimiter = actualRateLimiter;
                actualRateLimiter.start();
                started.set(true);
            }
            return actualRateLimiter;
        }
    }

    private void complete(CompletableFuture<BugFixMessage> result, Supplier<BugFixMessage> supplier) {
        try {
            result.complete(supplier.get());
        } catch (Throwable ex) {
            result.completeExceptionally(ex);
        }
    }

    private void cancel(AtomicReference<Runnable> cancelRef) {
        Runnable cancel = cancelRef.get();
        if (cancel != null) {
            cancel.run();
        }
    }
}
