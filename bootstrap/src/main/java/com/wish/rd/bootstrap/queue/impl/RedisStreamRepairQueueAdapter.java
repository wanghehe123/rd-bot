package com.wish.rd.bootstrap.queue.impl;

import com.wish.rd.bootstrap.queue.RedisStreamRepairQueueProperties;
import com.wish.rd.bootstrap.queue.RedisStreamRepairQueueConfiguration;
import com.wish.rd.engine.ticket.RepairQueueConsumer;
import com.wish.rd.engine.ticket.RepairQueueDeadLetterRepository;
import com.wish.rd.engine.ticket.RepairQueuePublisher;
import com.wish.rd.engine.ticket.model.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import org.redisson.api.RStream;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.AutoClaimResult;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.client.RedisBusyException;

/**
 * Redis Stream implementation of the repair-queue publishing port.
 *
 * <p>This bootstrap adapter stores only {@link RepairTicketMessage} routing metadata in
 * {@code RStream<String, String>} with {@link StringCodec}; the engine receives work through
 * {@link RepairQueueConsumer} in a later lifecycle phase.
 */
@Component
@ConditionalOnProperty(name = "rd.repair.queue.mode", havingValue = "redis-stream", matchIfMissing = true)
public final class RedisStreamRepairQueueAdapter implements RepairQueuePublisher, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamRepairQueueAdapter.class);

    private static final String ACK_AND_DELETE_LUA = """
            local pending = redis.call('XPENDING', KEYS[1], ARGV[1], ARGV[2], ARGV[2], 1)
            if #pending == 0 or pending[1][2] ~= ARGV[3] then
                return 0
            end
            local acknowledged = redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
            if acknowledged == 1 then
                redis.call('XDEL', KEYS[1], ARGV[2])
            end
            return acknowledged
            """;

    private static final String RETRY_TRANSFER_LUA = """
            local pending = redis.call('XPENDING', KEYS[1], ARGV[1], ARGV[2], ARGV[2], 1)
            if #pending == 0 or pending[1][2] ~= ARGV[3] then
                return {0, ''}
            end
            local retryId = redis.call(
                'XADD', KEYS[1], '*',
                'ticketId', ARGV[4],
                'priority', ARGV[5],
                'traceId', ARGV[6],
                'attempt', ARGV[7],
                'source', ARGV[8],
                'eventId', ARGV[9],
                'eventType', ARGV[10],
                'createdAt', ARGV[11]
            )
            redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
            redis.call('XDEL', KEYS[1], ARGV[2])
            return {1, retryId}
            """;

    private static final String RENEW_LEASE_LUA = """
            local pending = redis.call('XPENDING', KEYS[1], ARGV[1], ARGV[2], ARGV[2], 1)
            if #pending == 0 or pending[1][2] ~= ARGV[3] then
                return 0
            end
            local renewed = redis.call('XCLAIM', KEYS[1], ARGV[1], ARGV[3], 0, ARGV[2], 'JUSTID')
            if #renewed == 0 then
                return 0
            end
            return 1
            """;

    private static final Set<String> THIN_MESSAGE_FIELDS = Set.of(
            "ticketId",
            "priority",
            "traceId",
            "attempt",
            "source",
            "eventId",
            "eventType",
            "createdAt"
    );

    private final RedisStreamRepairQueueProperties properties;
    private final RStream<String, String> stream;
    private final RScript script;
    private final RepairQueueConsumer consumer;
    private final RepairQueueDeadLetterRepository deadLetterRepository;
    private final AsyncTaskExecutor loopExecutor;
    private final TaskScheduler leaseScheduler;
    private final List<Future<?>> loopFutures = new CopyOnWriteArrayList<>();
    private final Set<InFlightLease> inFlightLeases = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lifecycleMonitor = new Object();
    private final String consumerInstanceId = UUID.randomUUID().toString();

    /**
     * Creates the Redis Stream repair-queue adapter.
     *
     * @param properties queue configuration
     * @param redissonClient dedicated queue Redis client
     * @param consumerProvider engine consumer provider reserved for lifecycle consumption
     * @param loopExecutor dedicated Redis Stream loop executor reserved for lifecycle consumption
     * @param leaseScheduler dedicated scheduler that renews active PEL ownership leases
     * @param deadLetterRepository dead-letter repository reserved for malformed entry handling
     */
    public RedisStreamRepairQueueAdapter(
            RedisStreamRepairQueueProperties properties,
            @Qualifier(RedisStreamRepairQueueConfiguration.REPAIR_QUEUE_REDISSON_CLIENT_BEAN)
            RedissonClient redissonClient,
            ObjectProvider<RepairQueueConsumer> consumerProvider,
            @Qualifier(RedisStreamRepairQueueConfiguration.REPAIR_QUEUE_LOOP_EXECUTOR_BEAN)
            AsyncTaskExecutor loopExecutor,
            @Qualifier(RedisStreamRepairQueueConfiguration.REPAIR_QUEUE_LEASE_SCHEDULER_BEAN)
            TaskScheduler leaseScheduler,
            ObjectProvider<RepairQueueDeadLetterRepository> deadLetterRepository
    ) {
        this.properties = properties == null ? new RedisStreamRepairQueueProperties() : properties;
        this.stream = redissonClient.getStream(this.properties.getStreamKey(), StringCodec.INSTANCE);
        this.script = redissonClient.getScript(StringCodec.INSTANCE);
        this.consumer = consumerProvider == null ? null : consumerProvider.getIfAvailable();
        this.loopExecutor = loopExecutor;
        this.leaseScheduler = leaseScheduler;
        this.deadLetterRepository = deadLetterRepository == null
                ? RepairQueueDeadLetterRepository.noop()
                : deadLetterRepository.getIfAvailable(RepairQueueDeadLetterRepository::noop);
    }

    /**
     * Starts the configured number of long-running Redis Stream consumer loops.
     *
     * <p>Group creation is deliberately completed before worker submission so every
     * loop observes the same persistent consumer-group boundary.
     */
    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (running.get()) {
                return;
            }
            createConsumerGroup();
            if (consumer == null || loopExecutor == null || leaseScheduler == null) {
                log.warn("redis stream repair queue has no consumer, loop executor, or lease scheduler; loops will not start");
                return;
            }
            running.set(true);
            try {
                for (int index = 0; index < properties.getConsumerConcurrency(); index++) {
                    String consumerName = properties.getConsumerNameBase() + "-" + consumerInstanceId + "-" + index;
                    Future<?> future = loopExecutor.submit(() -> consumeLoop(consumerName));
                    if (future != null) {
                        loopFutures.add(future);
                    }
                }
            } catch (RuntimeException exception) {
                stop();
                throw exception;
            }
        }
    }

    /**
     * Stops worker loops and interrupts any blocking Redis read safely.
     */
    @Override
    public void stop() {
        synchronized (lifecycleMonitor) {
            if (!running.getAndSet(false)) {
                return;
            }
            for (InFlightLease lease : inFlightLeases) {
                lease.close();
            }
            for (Future<?> future : loopFutures) {
                future.cancel(true);
            }
            loopFutures.clear();
        }
    }

    /**
     * Stops worker loops and then invokes Spring's lifecycle callback.
     *
     * @param callback callback supplied by the container
     */
    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    /**
     * Returns whether Redis Stream consumer loops are active.
     *
     * @return {@code true} after a successful start and before stop
     */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Requests automatic startup after the Spring context is refreshed.
     *
     * @return always {@code true}
     */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * Publishes a thin repair-ticket message to the configured Redis Stream.
     *
     * @param message routing-only repair-ticket message
     * @return stable queue result using the Redis record ID and Stream key
     */
    @Override
    public RepairQueuePublishResult publish(RepairTicketMessage message) {
        if (message == null) {
            return RepairQueuePublishResult.failure(properties.getStreamKey(), "", "message must not be null");
        }
        try {
            StreamMessageId id = stream.add(StreamAddArgs.entries(toFields(message)));
            log.info("redis stream published, ticketId={}, tag={}, recordId={}",
                    message.ticketId(), message.tag(), id);
            return RepairQueuePublishResult.success(id.toString(), properties.getStreamKey(), message.tag());
        } catch (RuntimeException exception) {
            log.error("redis stream publish failed, ticketId={}", message.ticketId(), exception);
            return RepairQueuePublishResult.failure(
                    properties.getStreamKey(),
                    message.tag(),
                    exception.getClass().getSimpleName()
            );
        }
    }

    private static Map<String, String> toFields(RepairTicketMessage message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ticketId", message.ticketId());
        fields.put("priority", message.priority());
        fields.put("traceId", message.traceId());
        fields.put("attempt", Integer.toString(message.attempt()));
        fields.put("source", message.source());
        fields.put("eventId", message.eventId());
        fields.put("eventType", message.eventType());
        fields.put("createdAt", message.createdAt().toString());
        return Map.copyOf(fields);
    }

    private void createConsumerGroup() {
        try {
            stream.createGroup(StreamCreateGroupArgs.name(properties.getConsumerGroup())
                    .makeStream()
                    .id(StreamMessageId.ALL));
        } catch (RedisBusyException exception) {
            if (!isBusyGroup(exception)) {
                throw exception;
            }
            log.debug("redis stream repair consumer group already exists, group={}", properties.getConsumerGroup());
        }
    }

    private void consumeLoop(String consumerName) {
        boolean failureLogged = false;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                reclaimPending(consumerName);
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                Map<StreamMessageId, Map<String, String>> messages = stream.readGroup(
                        properties.getConsumerGroup(),
                        consumerName,
                        StreamReadGroupArgs.neverDelivered()
                                .count(properties.getBatchSize())
                                .timeout(Duration.ofMillis(properties.getPollTimeoutMillis()))
                );
                if (messages == null || messages.isEmpty()) {
                    failureLogged = false;
                    continue;
                }
                for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
                    if (!running.get() || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    handleEntry(entry.getKey(), entry.getValue(), consumerName);
                }
                failureLogged = false;
            } catch (RuntimeException exception) {
                if (!running.get() || isInterrupted(exception)) {
                    return;
                }
                if (!failureLogged) {
                    log.warn("redis stream repair queue loop failed, consumer={}", consumerName, exception);
                    failureLogged = true;
                }
                if (!awaitFailureBackoff()) {
                    return;
                }
            }
        }
    }

    private void reclaimPending(String consumerName) {
        StreamMessageId cursor = StreamMessageId.MIN;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            AutoClaimResult<String, String> claimed = stream.autoClaim(
                    properties.getConsumerGroup(),
                    consumerName,
                    properties.getPendingClaimIdleMillis(),
                    TimeUnit.MILLISECONDS,
                    cursor,
                    properties.getBatchSize()
            );
            if (claimed == null) {
                return;
            }
            Map<StreamMessageId, Map<String, String>> messages = claimed.getMessages();
            if (messages != null) {
                for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
                    if (!running.get() || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    handleEntry(entry.getKey(), entry.getValue(), consumerName);
                }
            }
            StreamMessageId nextCursor = claimed.getNextId();
            if (isClaimScanExhausted(nextCursor) || nextCursor.equals(cursor)) {
                return;
            }
            // XAUTOCLAIM can page through a large PEL; advancing prevents the first page from starving later records.
            cursor = nextCursor;
        }
    }

    private void handleEntry(
            StreamMessageId recordId,
            Map<String, String> fields,
            String consumerName
    ) {
        InFlightLease lease = beginLease(recordId, consumerName);
        try {
            RepairTicketMessage message;
            try {
                message = toMessage(fields);
            } catch (RuntimeException exception) {
                persistMalformedAndAcknowledge(recordId, fields, consumerName, lease, exception);
                return;
            }
            boolean handled;
            try {
                handled = consumer.handle(message);
            } catch (RuntimeException exception) {
                if (!lease.isActive()) {
                    log.warn("redis stream repair consumer lost its lease before exception completion, recordId={}", recordId);
                    return;
                }
                if (message.attempt() > properties.getMaxRetryAttempts()) {
                    // A terminal consumer failure (for example unavailable dead-letter persistence) must remain
                    // pending. Retrying it as another Stream record would bypass the bounded dead-letter path.
                    throw exception;
                }
                log.warn(
                        "redis stream repair consumer threw; atomically transferring retry, recordId={}, ticketId={}, attempt={}",
                        recordId,
                        message.ticketId(),
                        message.attempt(),
                        exception
                );
                transferRetry(recordId, consumerName, message);
                return;
            }
            if (!lease.isActive()) {
                log.warn("redis stream repair consumer lost its lease before completion, recordId={}", recordId);
                return;
            }
            if (!handled) {
                if (message.attempt() > properties.getMaxRetryAttempts()) {
                    persistTerminalFalseAndAcknowledge(recordId, consumerName, message, lease);
                    return;
                }
                transferRetry(recordId, consumerName, message);
                return;
            }
            acknowledgeAndDelete(recordId, consumerName);
        } finally {
            lease.close();
        }
    }

    private void persistMalformedAndAcknowledge(
            StreamMessageId recordId,
            Map<String, String> fields,
            String consumerName,
            InFlightLease lease,
            RuntimeException exception
    ) {
        // Dead-letter persistence precedes acknowledgement so an unavailable store keeps the PEL record recoverable.
        deadLetterRepository.saveMalformed(
                recordId.toString(),
                safeRawFields(recordId, fields),
                "redis stream entry is malformed: " + exception.getClass().getSimpleName()
        );
        if (!lease.isActive()) {
            log.warn("redis stream malformed record lost its lease before acknowledgement, recordId={}", recordId);
            return;
        }
        acknowledgeAndDelete(recordId, consumerName);
    }

    private void persistTerminalFalseAndAcknowledge(
            StreamMessageId recordId,
            String consumerName,
            RepairTicketMessage message,
            InFlightLease lease
    ) {
        // 先持久化有效消息的终态死信；仓储失败时异常向上冒泡，PEL 因而保留给后续 XAUTOCLAIM 恢复。
        deadLetterRepository.save(
                message,
                "redis stream repair consumer returned false after retry attempts exceeded"
        );
        if (!lease.isActive()) {
            log.warn("redis stream repair terminal false record lost its lease before acknowledgement, recordId={}",
                    recordId);
            return;
        }
        acknowledgeAndDelete(recordId, consumerName);
    }

    private void acknowledgeAndDelete(StreamMessageId recordId, String consumerName) {
        Long acknowledged = script.eval(
                RScript.Mode.READ_WRITE,
                ACK_AND_DELETE_LUA,
                RScript.ReturnType.LONG,
                List.of(properties.getStreamKey()),
                properties.getConsumerGroup(),
                recordId.toString(),
                consumerName
        );
        if (acknowledged == null || acknowledged != 1L) {
            log.warn("redis stream repair queue completion lost ownership or was no longer pending, recordId={}", recordId);
        }
    }

    private void transferRetry(StreamMessageId recordId, String consumerName, RepairTicketMessage message) {
        RepairTicketMessage retry = message.nextAttempt();
        List<Object> result = script.eval(
                RScript.Mode.READ_WRITE,
                RETRY_TRANSFER_LUA,
                RScript.ReturnType.LIST,
                List.of(properties.getStreamKey()),
                properties.getConsumerGroup(),
                recordId.toString(),
                consumerName,
                retry.ticketId(),
                retry.priority(),
                retry.traceId(),
                Integer.toString(retry.attempt()),
                retry.source(),
                retry.eventId(),
                retry.eventType(),
                retry.createdAt().toString()
        );
        if (result == null || result.isEmpty() || parseLong(result.getFirst()) != 1L) {
            log.warn("redis stream repair retry transfer lost ownership or was no longer pending, recordId={}", recordId);
        }
    }

    private InFlightLease beginLease(StreamMessageId recordId, String consumerName) {
        InFlightLease lease = new InFlightLease(recordId, consumerName);
        inFlightLeases.add(lease);
        try {
            lease.schedule();
            return lease;
        } catch (RuntimeException exception) {
            lease.close();
            throw exception;
        }
    }

    private boolean renewLease(StreamMessageId recordId, String consumerName) {
        Long renewed = script.eval(
                RScript.Mode.READ_WRITE,
                RENEW_LEASE_LUA,
                RScript.ReturnType.LONG,
                List.of(properties.getStreamKey()),
                properties.getConsumerGroup(),
                recordId.toString(),
                consumerName
        );
        return renewed != null && renewed == 1L;
    }

    /**
     * Owns the periodic lease-renewal task for one entry currently being handled.
     *
     * <p>The renewal Lua script verifies the current PEL owner and resets its idle
     * time in the same Redis operation. A failure to observe ownership is terminal
     * for this local handler: its later acknowledgement or retry transfer is fenced
     * by the same expected consumer name.
     */
    private final class InFlightLease implements AutoCloseable {

        private final StreamMessageId recordId;
        private final String consumerName;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean renewalFailureLogged = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> renewalFuture;

        private InFlightLease(StreamMessageId recordId, String consumerName) {
            this.recordId = recordId;
            this.consumerName = consumerName;
        }

        private void schedule() {
            long intervalMillis = properties.getLeaseRenewalIntervalMillis();
            renewalFuture = leaseScheduler.scheduleAtFixedRate(
                    this::renew,
                    Instant.now().plusMillis(intervalMillis),
                    Duration.ofMillis(intervalMillis)
            );
        }

        private void renew() {
            if (!isActive()) {
                return;
            }
            try {
                if (!renewLease(recordId, consumerName) && active.compareAndSet(true, false)) {
                    log.warn("redis stream repair queue lease ownership was lost, recordId={}", recordId);
                }
            } catch (RuntimeException exception) {
                // A transient Redis failure must not make this handler believe another consumer owns the
                // entry. The completion scripts still fence ownership, and the next scheduled renewal
                // can restore the idle timer once Redis is available again.
                if (renewalFailureLogged.compareAndSet(false, true)) {
                    log.warn("redis stream repair queue lease renewal failed, recordId={}", recordId, exception);
                }
            }
        }

        private boolean isActive() {
            return active.get() && running.get();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            active.set(false);
            ScheduledFuture<?> future = renewalFuture;
            if (future != null) {
                future.cancel(false);
            }
            inFlightLeases.remove(this);
        }
    }

    private static RepairTicketMessage toMessage(Map<String, String> fields) {
        Map<String, String> safeFields = fields == null ? Map.of() : fields;
        return new RepairTicketMessage(
                safeFields.getOrDefault("ticketId", ""),
                safeFields.getOrDefault("priority", RepairTicketMessage.DEFAULT_PRIORITY),
                safeFields.getOrDefault("traceId", ""),
                parseAttempt(safeFields.get("attempt")),
                safeFields.getOrDefault("source", ""),
                safeFields.getOrDefault("eventId", ""),
                safeFields.getOrDefault("eventType", ""),
                parseInstant(safeFields.get("createdAt"))
        );
    }

    private static int parseAttempt(String value) {
        try {
            int attempt = Integer.parseInt(value);
            if (attempt <= 0) {
                throw new IllegalArgumentException("redis stream attempt must be positive");
            }
            return attempt;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("redis stream attempt is malformed", exception);
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("redis stream createdAt is malformed", exception);
        }
    }

    private static Map<String, String> safeRawFields(StreamMessageId recordId, Map<String, String> fields) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (fields != null) {
            for (String fieldName : THIN_MESSAGE_FIELDS) {
                String value = fields.get(fieldName);
                if (value != null) {
                    safe.put(fieldName, value);
                }
            }
        }
        safe.put("redisRecordId", recordId.toString());
        return Map.copyOf(safe);
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

    private static boolean isClaimScanExhausted(StreamMessageId cursor) {
        return cursor == null || (cursor.getId0() == 0L && cursor.getId1() == 0L);
    }

    private boolean awaitFailureBackoff() {
        try {
            Thread.sleep(properties.getFailureBackoffMillis());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isInterrupted(Throwable exception) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Throwable current = exception;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isBusyGroup(RedisBusyException exception) {
        return exception.getMessage() != null && exception.getMessage().contains("BUSYGROUP");
    }
}
