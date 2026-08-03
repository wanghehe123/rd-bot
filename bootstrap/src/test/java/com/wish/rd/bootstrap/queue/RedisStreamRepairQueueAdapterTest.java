package com.wish.rd.bootstrap.queue;

import com.wish.rd.bootstrap.queue.impl.RedisStreamRepairQueueAdapter;
import com.wish.rd.engine.ticket.RepairQueueConsumer;
import com.wish.rd.engine.ticket.RepairQueueDeadLetterRepository;
import com.wish.rd.engine.ticket.model.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.model.RepairQueueDeadLetter;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.AutoClaimResult;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamAddParams;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisStreamRepairQueueAdapterTest {

    @Test
    void shouldPublishOnlyThinMessageFieldsToConfiguredRedisStream() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setStreamKey("rd-bot:test:repair");
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        when(stream.add(any())).thenReturn(new StreamMessageId(1_780_000_000_000L, 0L));
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(null),
                mock(AsyncTaskExecutor.class),
                leaseScheduler(),
                provider(RepairQueueDeadLetterRepository.noop())
        );
        RepairTicketMessage message = new RepairTicketMessage(
                "FS-9001",
                "P1",
                "trace-9001",
                2,
                "feishu",
                "evt-9001",
                "helpdesk.ticket.created_v1",
                Instant.parse("2026-08-02T00:00:00Z")
        );

        RepairQueuePublishResult result = adapter.publish(message);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<StreamAddArgs<String, String>> arguments = ArgumentCaptor.forClass(StreamAddArgs.class);
        verify(stream).add(arguments.capture());
        StreamAddParams<String, String> entries = (StreamAddParams<String, String>) arguments.getValue();
        assertAll(
                () -> assertTrue(result.success()),
                () -> assertEquals("1780000000000-0", result.messageId()),
                () -> assertEquals(properties.getStreamKey(), result.targetTopic()),
                () -> assertEquals("P1", result.targetTag()),
                () -> assertEquals(
                        Map.of(
                                "ticketId", "FS-9001",
                                "priority", "P1",
                                "traceId", "trace-9001",
                                "attempt", "2",
                                "source", "feishu",
                                "eventId", "evt-9001",
                                "eventType", "helpdesk.ticket.created_v1",
                                "createdAt", "2026-08-02T00:00:00Z"
                        ),
                        entries.getEntries()
                ),
                () -> assertFalse(entries.getEntries().containsKey("title")),
                () -> assertFalse(entries.getEntries().containsKey("description")),
                () -> assertFalse(entries.getEntries().containsKey("logs"))
        );
    }

    @Test
    void shouldAckAndDeleteSucceededDeliveryInOneRedisScript() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setConsumerConcurrency(1);
        properties.setFailureBackoffMillis(0L);
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        AtomicReference<RedisStreamRepairQueueAdapter> adapterRef = new AtomicReference<>();
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(stream.autoClaim(
                eq(properties.getConsumerGroup()),
                any(String.class),
                eq(properties.getPendingClaimIdleMillis()),
                eq(TimeUnit.MILLISECONDS),
                eq(StreamMessageId.MIN),
                eq(properties.getBatchSize())
        )).thenReturn(new AutoClaimResult<>(new StreamMessageId(0L, 0L), Map.of(), java.util.List.of()));
        StreamMessageId recordId = new StreamMessageId(1_780_000_000_001L, 0L);
        Map<String, String> fields = Map.of(
                "ticketId", "FS-9002",
                "priority", "P1",
                "traceId", "trace-9002",
                "attempt", "1",
                "source", "feishu",
                "eventId", "evt-9002",
                "eventType", "helpdesk.ticket.created_v1",
                "createdAt", "2026-08-02T00:00:00Z"
        );
        when(stream.readGroup(
                eq(properties.getConsumerGroup()),
                any(String.class),
                any(StreamReadGroupArgs.class)
        )).thenReturn(Map.of(recordId, fields));
        doAnswer(invocation -> {
            adapterRef.get().stop();
            return 1L;
        }).when(script).eval(
                eq(RScript.Mode.READ_WRITE),
                any(String.class),
                eq(RScript.ReturnType.LONG),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                any(String.class)
        );
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<?> loop = mock(Future.class);
        doReturn(loop).when(executor).submit(any(Runnable.class));
        AtomicBoolean handled = new AtomicBoolean(false);
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> {
                    handled.set(true);
                    return true;
                }),
                executor,
                leaseScheduler(),
                provider(RepairQueueDeadLetterRepository.noop())
        );
        adapterRef.set(adapter);

        adapter.start();
        ArgumentCaptor<Runnable> loopRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).submit(loopRunnable.capture());
        loopRunnable.getValue().run();

        ArgumentCaptor<String> lua = ArgumentCaptor.forClass(String.class);
        verify(script).eval(
                eq(RScript.Mode.READ_WRITE),
                lua.capture(),
                eq(RScript.ReturnType.LONG),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                any(String.class)
        );
        assertAll(
                () -> assertTrue(handled.get()),
                () -> assertTrue(lua.getValue().contains("XACK")),
                () -> assertTrue(lua.getValue().contains("XDEL"))
        );
    }

    @Test
    void shouldAtomicallyTransferFailedDeliveryOnlyWhenOriginalIsStillPending() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setConsumerConcurrency(1);
        properties.setFailureBackoffMillis(0L);
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        AtomicReference<RedisStreamRepairQueueAdapter> adapterRef = new AtomicReference<>();
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(stream.autoClaim(
                eq(properties.getConsumerGroup()),
                any(String.class),
                eq(properties.getPendingClaimIdleMillis()),
                eq(TimeUnit.MILLISECONDS),
                eq(StreamMessageId.MIN),
                eq(properties.getBatchSize())
        )).thenReturn(new AutoClaimResult<>(new StreamMessageId(0L, 0L), Map.of(), java.util.List.of()));
        StreamMessageId recordId = new StreamMessageId(1_780_000_000_002L, 0L);
        Map<String, String> fields = Map.of(
                "ticketId", "FS-9003",
                "priority", "P1",
                "traceId", "trace-9003",
                "attempt", "1",
                "source", "feishu",
                "eventId", "evt-9003",
                "eventType", "helpdesk.ticket.created_v1",
                "createdAt", "2026-08-02T00:00:00Z"
        );
        when(stream.readGroup(
                eq(properties.getConsumerGroup()),
                any(String.class),
                any(StreamReadGroupArgs.class)
        )).thenReturn(Map.of(recordId, fields));
        doAnswer(invocation -> {
            adapterRef.get().stop();
            return java.util.List.of(1L, "1780000000003-0");
        }).when(script).eval(
                eq(RScript.Mode.READ_WRITE),
                any(String.class),
                eq(RScript.ReturnType.LIST),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                any(String.class),
                eq("FS-9003"),
                eq("P1"),
                eq("trace-9003"),
                eq("2"),
                eq("feishu"),
                eq("evt-9003"),
                eq("helpdesk.ticket.created_v1"),
                any(String.class)
        );
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<?> loop = mock(Future.class);
        doReturn(loop).when(executor).submit(any(Runnable.class));
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> {
                    return false;
                }),
                executor,
                leaseScheduler(),
                provider(RepairQueueDeadLetterRepository.noop())
        );
        adapterRef.set(adapter);

        adapter.start();
        ArgumentCaptor<Runnable> loopRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).submit(loopRunnable.capture());
        loopRunnable.getValue().run();

        ArgumentCaptor<String> lua = ArgumentCaptor.forClass(String.class);
        verify(script).eval(
                eq(RScript.Mode.READ_WRITE),
                lua.capture(),
                eq(RScript.ReturnType.LIST),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                any(String.class),
                eq("FS-9003"),
                eq("P1"),
                eq("trace-9003"),
                eq("2"),
                eq("feishu"),
                eq("evt-9003"),
                eq("helpdesk.ticket.created_v1"),
                any(String.class)
        );
        assertAll(
                () -> assertTrue(lua.getValue().contains("XPENDING")),
                () -> assertTrue(lua.getValue().contains("XADD")),
                () -> assertTrue(lua.getValue().contains("XACK")),
                () -> assertTrue(lua.getValue().contains("XDEL"))
        );
    }

    @Test
    void shouldAtomicallyTransferThrownConsumerFailureToNextAttempt() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setConsumerConcurrency(1);
        properties.setMaxRetryAttempts(3);
        properties.setFailureBackoffMillis(1L);
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        AtomicReference<RedisStreamRepairQueueAdapter> adapterRef = new AtomicReference<>();
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(stream.autoClaim(
                eq(properties.getConsumerGroup()),
                any(String.class),
                eq(properties.getPendingClaimIdleMillis()),
                eq(TimeUnit.MILLISECONDS),
                eq(StreamMessageId.MIN),
                eq(properties.getBatchSize())
        )).thenReturn(new AutoClaimResult<>(new StreamMessageId(0L, 0L), Map.of(), java.util.List.of()));
        StreamMessageId recordId = new StreamMessageId(1_780_000_000_003L, 0L);
        Map<String, String> fields = Map.of(
                "ticketId", "FS-9003-THROWN",
                "priority", "P1",
                "traceId", "trace-9003-thrown",
                "attempt", "1",
                "source", "feishu",
                "eventId", "evt-9003-thrown",
                "eventType", "helpdesk.ticket.created_v1",
                "createdAt", "2026-08-02T00:00:00Z"
        );
        when(stream.readGroup(
                eq(properties.getConsumerGroup()),
                any(String.class),
                any(StreamReadGroupArgs.class)
        )).thenReturn(Map.of(recordId, fields));
        doAnswer(invocation -> {
            adapterRef.get().stop();
            return java.util.List.of(1L, "1780000000004-0");
        }).when(script).eval(
                eq(RScript.Mode.READ_WRITE),
                any(String.class),
                eq(RScript.ReturnType.LIST),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                any(String.class),
                eq("FS-9003-THROWN"),
                eq("P1"),
                eq("trace-9003-thrown"),
                eq("2"),
                eq("feishu"),
                eq("evt-9003-thrown"),
                eq("helpdesk.ticket.created_v1"),
                any(String.class)
        );
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<?> loop = mock(Future.class);
        doReturn(loop).when(executor).submit(any(Runnable.class));
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> {
                    throw new IllegalStateException("consumer execution failed");
                }),
                executor,
                leaseScheduler(),
                provider(RepairQueueDeadLetterRepository.noop())
        );
        adapterRef.set(adapter);

        adapter.start();
        ArgumentCaptor<Runnable> loopRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).submit(loopRunnable.capture());
        loopRunnable.getValue().run();

        ArgumentCaptor<String> retryCreatedAt = ArgumentCaptor.forClass(String.class);
        verify(script).eval(
                eq(RScript.Mode.READ_WRITE),
                any(String.class),
                eq(RScript.ReturnType.LIST),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                any(String.class),
                eq("FS-9003-THROWN"),
                eq("P1"),
                eq("trace-9003-thrown"),
                eq("2"),
                eq("feishu"),
                eq("evt-9003-thrown"),
                eq("helpdesk.ticket.created_v1"),
                retryCreatedAt.capture()
        );
        assertTrue(Instant.parse(retryCreatedAt.getValue()).isAfter(Instant.parse("2026-08-02T00:00:00Z")));
    }

    @Test
    void shouldLeaveThrownTerminalDeliveryPendingForDeadLetterRecovery() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setConsumerConcurrency(1);
        properties.setMaxRetryAttempts(3);
        properties.setFailureBackoffMillis(1L);
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(stream.autoClaim(
                eq(properties.getConsumerGroup()),
                any(String.class),
                eq(properties.getPendingClaimIdleMillis()),
                eq(TimeUnit.MILLISECONDS),
                eq(StreamMessageId.MIN),
                eq(properties.getBatchSize())
        )).thenReturn(new AutoClaimResult<>(new StreamMessageId(0L, 0L), Map.of(), java.util.List.of()));
        StreamMessageId recordId = new StreamMessageId(1_780_000_000_004L, 0L);
        when(stream.readGroup(
                eq(properties.getConsumerGroup()),
                any(String.class),
                any(StreamReadGroupArgs.class)
        )).thenReturn(Map.of(recordId, Map.of(
                "ticketId", "FS-9004-TERMINAL",
                "priority", "P1",
                "traceId", "trace-9004-terminal",
                "attempt", "4",
                "source", "feishu",
                "eventId", "evt-9004-terminal",
                "eventType", "helpdesk.ticket.created_v1",
                "createdAt", "2026-08-02T00:00:00Z"
        )));
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<?> loop = mock(Future.class);
        doReturn(loop).when(executor).submit(any(Runnable.class));
        AtomicReference<RedisStreamRepairQueueAdapter> adapterRef = new AtomicReference<>();
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> {
                    adapterRef.get().stop();
                    throw new IllegalStateException("terminal dead-letter persistence unavailable");
                }),
                executor,
                leaseScheduler(),
                provider(RepairQueueDeadLetterRepository.noop())
        );
        adapterRef.set(adapter);

        adapter.start();
        ArgumentCaptor<Runnable> loopRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).submit(loopRunnable.capture());
        loopRunnable.getValue().run();

        verifyNoInteractions(script);
    }

    @Test
    void shouldPersistTerminalFalseDeliveryBeforeOwnerFencedAckAndDelete() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setConsumerConcurrency(1);
        properties.setMaxRetryAttempts(3);
        properties.setFailureBackoffMillis(1L);
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RepairQueueDeadLetterRepository deadLetters = mock(RepairQueueDeadLetterRepository.class);
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(stream.autoClaim(
                eq(properties.getConsumerGroup()),
                any(String.class),
                eq(properties.getPendingClaimIdleMillis()),
                eq(TimeUnit.MILLISECONDS),
                eq(StreamMessageId.MIN),
                eq(properties.getBatchSize())
        )).thenReturn(
                new AutoClaimResult<>(new StreamMessageId(0L, 0L), Map.of(), java.util.List.of()),
                new AutoClaimResult<>(new StreamMessageId(0L, 0L), Map.of(), java.util.List.of())
        );
        StreamMessageId recordId = new StreamMessageId(1_780_000_000_044L, 0L);
        AtomicReference<RedisStreamRepairQueueAdapter> adapterRef = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        when(stream.readGroup(
                eq(properties.getConsumerGroup()),
                any(String.class),
                any(StreamReadGroupArgs.class)
        )).thenAnswer(invocation -> {
            if (reads.getAndIncrement() == 0) {
                return Map.of(recordId, Map.of(
                        "ticketId", "FS-9004-FALSE-TERMINAL",
                        "priority", "P1",
                        "traceId", "trace-9004-false-terminal",
                        "attempt", "4",
                        "source", "feishu",
                        "eventId", "evt-9004-false-terminal",
                        "eventType", "helpdesk.ticket.created_v1",
                        "createdAt", "2026-08-02T00:00:00Z"
                ));
            }
            adapterRef.get().stop();
            return Map.of();
        });
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<?> loop = mock(Future.class);
        doReturn(loop).when(executor).submit(any(Runnable.class));
        RepairQueueDeadLetter persisted = new RepairQueueDeadLetter(
                "dead-letter-9004", "FS-9004-FALSE-TERMINAL", "trace-9004-false-terminal", "feishu",
                "evt-9004-false-terminal", "helpdesk.ticket.created_v1", 4,
                "retry attempts exceeded", Map.of(), false, 1L, 0L
        );
        doReturn(persisted).when(deadLetters).save(any(RepairTicketMessage.class), any(String.class));
        doReturn(1L).when(script).eval(
                eq(RScript.Mode.READ_WRITE),
                any(String.class),
                eq(RScript.ReturnType.LONG),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                any(String.class)
        );
        AtomicInteger deliveries = new AtomicInteger();
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> {
                    deliveries.incrementAndGet();
                    return false;
                }),
                executor,
                leaseScheduler(),
                provider(deadLetters)
        );
        adapterRef.set(adapter);

        adapter.start();
        ArgumentCaptor<Runnable> loopRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).submit(loopRunnable.capture());
        loopRunnable.getValue().run();

        ArgumentCaptor<RepairTicketMessage> deadLetterMessage = ArgumentCaptor.forClass(RepairTicketMessage.class);
        ArgumentCaptor<String> deadLetterReason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> completionLua = ArgumentCaptor.forClass(String.class);
        InOrder completionOrder = inOrder(deadLetters, script);
        completionOrder.verify(deadLetters).save(deadLetterMessage.capture(), deadLetterReason.capture());
        completionOrder.verify(script).eval(
                eq(RScript.Mode.READ_WRITE),
                completionLua.capture(),
                eq(RScript.ReturnType.LONG),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                any(String.class)
        );
        assertAll(
                () -> assertEquals("FS-9004-FALSE-TERMINAL", deadLetterMessage.getValue().ticketId()),
                () -> assertEquals(4, deadLetterMessage.getValue().attempt()),
                () -> assertTrue(deadLetterReason.getValue().contains("retry attempts exceeded")),
                () -> assertTrue(completionLua.getValue().contains("XPENDING")),
                () -> assertTrue(completionLua.getValue().contains("XACK")),
                () -> assertFalse(completionLua.getValue().contains("XADD")),
                () -> assertEquals(2, reads.get()),
                () -> assertEquals(1, deliveries.get())
        );
        verify(stream, times(2)).autoClaim(
                eq(properties.getConsumerGroup()),
                any(String.class),
                eq(properties.getPendingClaimIdleMillis()),
                eq(TimeUnit.MILLISECONDS),
                eq(StreamMessageId.MIN),
                eq(properties.getBatchSize())
        );
        verify(deadLetters, times(1)).save(any(RepairTicketMessage.class), any(String.class));
        verify(script, times(1)).eval(
                eq(RScript.Mode.READ_WRITE),
                any(String.class),
                eq(RScript.ReturnType.LONG),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                any(String.class)
        );
        verify(script, never()).eval(
                eq(RScript.Mode.READ_WRITE),
                any(String.class),
                eq(RScript.ReturnType.LIST),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class)
        );
    }

    @Test
    void shouldLeaveTerminalFalseDeliveryPendingWhenDeadLetterPersistenceFails() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setConsumerConcurrency(1);
        properties.setMaxRetryAttempts(3);
        properties.setFailureBackoffMillis(1L);
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RepairQueueDeadLetterRepository deadLetters = mock(RepairQueueDeadLetterRepository.class);
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(stream.autoClaim(
                eq(properties.getConsumerGroup()),
                any(String.class),
                eq(properties.getPendingClaimIdleMillis()),
                eq(TimeUnit.MILLISECONDS),
                eq(StreamMessageId.MIN),
                eq(properties.getBatchSize())
        )).thenReturn(new AutoClaimResult<>(new StreamMessageId(0L, 0L), Map.of(), java.util.List.of()));
        StreamMessageId recordId = new StreamMessageId(1_780_000_000_045L, 0L);
        AtomicReference<RedisStreamRepairQueueAdapter> adapterRef = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        when(stream.readGroup(
                eq(properties.getConsumerGroup()),
                any(String.class),
                any(StreamReadGroupArgs.class)
        )).thenAnswer(invocation -> {
            if (reads.getAndIncrement() == 0) {
                return Map.of(recordId, Map.of(
                        "ticketId", "FS-9004-FALSE-PERSISTENCE-FAILURE",
                        "priority", "P1",
                        "traceId", "trace-9004-false-persistence-failure",
                        "attempt", "4",
                        "source", "feishu",
                        "eventId", "evt-9004-false-persistence-failure",
                        "eventType", "helpdesk.ticket.created_v1",
                        "createdAt", "2026-08-02T00:00:00Z"
                ));
            }
            adapterRef.get().stop();
            return Map.of();
        });
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<?> loop = mock(Future.class);
        doReturn(loop).when(executor).submit(any(Runnable.class));
        doAnswer(invocation -> {
            adapterRef.get().stop();
            throw new IllegalStateException("terminal dead-letter persistence unavailable");
        }).when(deadLetters).save(any(RepairTicketMessage.class), any(String.class));
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> false),
                executor,
                leaseScheduler(),
                provider(deadLetters)
        );
        adapterRef.set(adapter);

        adapter.start();
        ArgumentCaptor<Runnable> loopRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).submit(loopRunnable.capture());
        loopRunnable.getValue().run();

        verify(deadLetters).save(any(RepairTicketMessage.class), any(String.class));
        verifyNoInteractions(script);
    }

    @Test
    void shouldFenceAckAndDeleteToTheCurrentRedisConsumerOwner() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setConsumerConcurrency(1);
        properties.setFailureBackoffMillis(1L);
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        TaskScheduler leaseScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> renewalFuture = mock(ScheduledFuture.class);
        AtomicReference<Runnable> renewalTask = new AtomicReference<>();
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(stream.autoClaim(
                eq(properties.getConsumerGroup()),
                any(String.class),
                eq(properties.getPendingClaimIdleMillis()),
                eq(TimeUnit.MILLISECONDS),
                eq(StreamMessageId.MIN),
                eq(properties.getBatchSize())
        )).thenReturn(new AutoClaimResult<>(new StreamMessageId(0L, 0L), Map.of(), java.util.List.of()));
        StreamMessageId recordId = new StreamMessageId(1_780_000_000_045L, 0L);
        Map<String, String> fields = validFields("FS-9004-ACK-FENCE", "evt-9004-ack-fence");
        doAnswer(invocation -> {
            renewalTask.set(invocation.getArgument(0));
            return renewalFuture;
        }).when(leaseScheduler).scheduleAtFixedRate(
                any(Runnable.class),
                any(Instant.class),
                any(Duration.class)
        );
        doReturn(1L).when(script).eval(
                eq(RScript.Mode.READ_WRITE),
                any(String.class),
                eq(RScript.ReturnType.LONG),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                any(String.class)
        );
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<RedisStreamRepairQueueAdapter> adapterRef = new AtomicReference<>();
        when(stream.readGroup(
                eq(properties.getConsumerGroup()),
                any(String.class),
                any(StreamReadGroupArgs.class)
        )).thenAnswer(invocation -> {
            if (reads.getAndIncrement() == 0) {
                return Map.of(recordId, fields);
            }
            adapterRef.get().stop();
            return Map.of();
        });
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<?> loop = mock(Future.class);
        doReturn(loop).when(executor).submit(any(Runnable.class));
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> {
                    renewalTask.get().run();
                    return true;
                }),
                executor,
                leaseScheduler,
                provider(RepairQueueDeadLetterRepository.noop())
        );
        adapterRef.set(adapter);

        adapter.start();
        ArgumentCaptor<Runnable> loopRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).submit(loopRunnable.capture());
        loopRunnable.getValue().run();

        ArgumentCaptor<String> lua = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(script, times(2)).eval(
                eq(RScript.Mode.READ_WRITE),
                lua.capture(),
                eq(RScript.ReturnType.LONG),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                owner.capture()
        );
        assertAll(
                () -> assertEquals(2, lua.getAllValues().size()),
                () -> assertTrue(owner.getAllValues().stream().allMatch(value -> !value.isBlank())),
                () -> assertEquals(owner.getAllValues().getFirst(), owner.getAllValues().getLast()),
                () -> assertTrue(lua.getAllValues().stream().anyMatch(value -> value.contains("XCLAIM"))),
                () -> assertTrue(lua.getAllValues().stream().anyMatch(value -> value.contains("XACK"))),
                () -> verify(renewalFuture).cancel(false)
        );
    }

    @Test
    void shouldFenceRetryTransferToTheCurrentRedisConsumerOwner() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setConsumerConcurrency(1);
        properties.setFailureBackoffMillis(1L);
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(stream.autoClaim(
                eq(properties.getConsumerGroup()),
                any(String.class),
                eq(properties.getPendingClaimIdleMillis()),
                eq(TimeUnit.MILLISECONDS),
                eq(StreamMessageId.MIN),
                eq(properties.getBatchSize())
        )).thenReturn(new AutoClaimResult<>(new StreamMessageId(0L, 0L), Map.of(), java.util.List.of()));
        StreamMessageId recordId = new StreamMessageId(1_780_000_000_046L, 0L);
        Map<String, String> fields = validFields("FS-9004-RETRY-FENCE", "evt-9004-retry-fence");
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<RedisStreamRepairQueueAdapter> adapterRef = new AtomicReference<>();
        when(stream.readGroup(
                eq(properties.getConsumerGroup()),
                any(String.class),
                any(StreamReadGroupArgs.class)
        )).thenAnswer(invocation -> {
            if (reads.getAndIncrement() == 0) {
                return Map.of(recordId, fields);
            }
            adapterRef.get().stop();
            return Map.of();
        });
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<?> loop = mock(Future.class);
        doReturn(loop).when(executor).submit(any(Runnable.class));
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> false),
                executor,
                leaseScheduler(),
                provider(RepairQueueDeadLetterRepository.noop())
        );
        adapterRef.set(adapter);

        adapter.start();
        ArgumentCaptor<Runnable> loopRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).submit(loopRunnable.capture());
        loopRunnable.getValue().run();

        ArgumentCaptor<String> lua = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(script).eval(
                eq(RScript.Mode.READ_WRITE),
                lua.capture(),
                eq(RScript.ReturnType.LIST),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                owner.capture(),
                eq("FS-9004-RETRY-FENCE"),
                eq("P1"),
                eq("trace-FS-9004-RETRY-FENCE"),
                eq("2"),
                eq("feishu"),
                eq("evt-9004-retry-fence"),
                eq("helpdesk.ticket.created_v1"),
                any(String.class)
        );
        assertAll(
                () -> assertFalse(owner.getValue().isBlank()),
                () -> assertTrue(lua.getValue().contains("pending[1][2] ~= ARGV[3]"))
        );
    }

    @Test
    void shouldLeaveFailedRetryPendingForTheNextAutoClaimPass() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setConsumerConcurrency(1);
        properties.setFailureBackoffMillis(1L);
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
        StreamMessageId recordId = new StreamMessageId(1_780_000_000_004L, 0L);
        Map<String, String> fields = Map.of(
                "ticketId", "FS-9004",
                "priority", "P1",
                "traceId", "trace-9004",
                "attempt", "1",
                "source", "feishu",
                "eventId", "evt-9004",
                "eventType", "helpdesk.ticket.created_v1",
                "createdAt", "2026-08-02T00:00:00Z"
        );
        when(stream.autoClaim(
                eq(properties.getConsumerGroup()),
                any(String.class),
                eq(properties.getPendingClaimIdleMillis()),
                eq(TimeUnit.MILLISECONDS),
                eq(StreamMessageId.MIN),
                eq(properties.getBatchSize())
        )).thenReturn(
                new AutoClaimResult<>(new StreamMessageId(0L, 0L), Map.of(), java.util.List.of()),
                new AutoClaimResult<>(new StreamMessageId(0L, 0L), Map.of(recordId, fields), java.util.List.of())
        );
        when(stream.readGroup(
                eq(properties.getConsumerGroup()),
                any(String.class),
                any(StreamReadGroupArgs.class)
        )).thenReturn(Map.of(recordId, fields));
        doThrow(new RedisException("transient Redis failure")).when(script).eval(
                eq(RScript.Mode.READ_WRITE),
                any(String.class),
                eq(RScript.ReturnType.LIST),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                any(String.class),
                eq("FS-9004"),
                eq("P1"),
                eq("trace-9004"),
                eq("2"),
                eq("feishu"),
                eq("evt-9004"),
                eq("helpdesk.ticket.created_v1"),
                any(String.class)
        );
        doReturn(1L).when(script).eval(
                eq(RScript.Mode.READ_WRITE),
                any(String.class),
                eq(RScript.ReturnType.LONG),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                any(String.class)
        );
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<?> loop = mock(Future.class);
        doReturn(loop).when(executor).submit(any(Runnable.class));
        AtomicReference<RedisStreamRepairQueueAdapter> adapterRef = new AtomicReference<>();
        AtomicInteger deliveries = new AtomicInteger();
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> {
                    if (deliveries.incrementAndGet() == 2) {
                        adapterRef.get().stop();
                        return true;
                    }
                    return false;
                }),
                executor,
                leaseScheduler(),
                provider(RepairQueueDeadLetterRepository.noop())
        );
        adapterRef.set(adapter);

        adapter.start();
        ArgumentCaptor<Runnable> loopRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).submit(loopRunnable.capture());
        loopRunnable.getValue().run();

        assertEquals(2, deliveries.get());
        verify(stream, times(2)).autoClaim(
                eq(properties.getConsumerGroup()),
                any(String.class),
                eq(properties.getPendingClaimIdleMillis()),
                eq(TimeUnit.MILLISECONDS),
                eq(StreamMessageId.MIN),
                eq(properties.getBatchSize())
        );
        verify(stream, never()).ack(any(String.class), any(StreamMessageId[].class));
        verify(stream, never()).remove(any(StreamMessageId[].class));
    }

    @Test
    void shouldPersistMalformedEntryWithRedisRecordIdBeforeAckAndDelete() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setConsumerConcurrency(1);
        properties.setFailureBackoffMillis(0L);
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RepairQueueDeadLetterRepository deadLetters = mock(RepairQueueDeadLetterRepository.class);
        AtomicReference<RedisStreamRepairQueueAdapter> adapterRef = new AtomicReference<>();
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(stream.autoClaim(
                eq(properties.getConsumerGroup()),
                any(String.class),
                eq(properties.getPendingClaimIdleMillis()),
                eq(TimeUnit.MILLISECONDS),
                eq(StreamMessageId.MIN),
                eq(properties.getBatchSize())
        )).thenReturn(new AutoClaimResult<>(new StreamMessageId(0L, 0L), Map.of(), java.util.List.of()));
        StreamMessageId recordId = new StreamMessageId(1_780_000_000_005L, 0L);
        Map<String, String> rawFields = Map.of(
                "ticketId", "FS-9005",
                "priority", "P1",
                "traceId", "trace-9005",
                "attempt", "not-a-number",
                "source", "feishu",
                "eventId", "evt-9005",
                "eventType", "helpdesk.ticket.created_v1",
                "createdAt", "2026-08-02T00:00:00Z",
                "title", "must-not-be-persisted"
        );
        when(stream.readGroup(
                eq(properties.getConsumerGroup()),
                any(String.class),
                any(StreamReadGroupArgs.class)
        )).thenReturn(Map.of(recordId, rawFields));
        doAnswer(invocation -> {
            adapterRef.get().stop();
            return 1L;
        }).when(script).eval(
                eq(RScript.Mode.READ_WRITE),
                any(String.class),
                eq(RScript.ReturnType.LONG),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                any(String.class)
        );
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<?> loop = mock(Future.class);
        doReturn(loop).when(executor).submit(any(Runnable.class));
        RepairQueueDeadLetter persisted = new RepairQueueDeadLetter(
                "dead-letter-9005", "FS-9005", "trace-9005", "feishu", "evt-9005",
                "helpdesk.ticket.created_v1", 0, "malformed", Map.of(), false, 1L, 0L
        );
        doAnswer(invocation -> {
            return persisted;
        }).when(deadLetters).saveMalformed(eq(recordId.toString()), any(Map.class), any(String.class));
        AtomicBoolean consumerCalled = new AtomicBoolean(false);
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> {
                    consumerCalled.set(true);
                    return true;
                }),
                executor,
                leaseScheduler(),
                provider(deadLetters)
        );
        adapterRef.set(adapter);

        adapter.start();
        ArgumentCaptor<Runnable> loopRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).submit(loopRunnable.capture());
        loopRunnable.getValue().run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> safeFields = ArgumentCaptor.forClass(Map.class);
        verify(deadLetters).saveMalformed(eq(recordId.toString()), safeFields.capture(), any(String.class));
        verify(script).eval(
                eq(RScript.Mode.READ_WRITE),
                any(String.class),
                eq(RScript.ReturnType.LONG),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                eq(recordId.toString()),
                any(String.class)
        );
        assertAll(
                () -> assertFalse(consumerCalled.get()),
                () -> assertEquals(recordId.toString(), safeFields.getValue().get("redisRecordId")),
                () -> assertFalse(safeFields.getValue().containsKey("title"))
        );
    }

    @Test
    void shouldLeaveMalformedDeliveryPendingWhenDeadLetterPersistenceFails() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setConsumerConcurrency(1);
        properties.setFailureBackoffMillis(0L);
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RepairQueueDeadLetterRepository deadLetters = mock(RepairQueueDeadLetterRepository.class);
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(stream.autoClaim(
                eq(properties.getConsumerGroup()),
                any(String.class),
                eq(properties.getPendingClaimIdleMillis()),
                eq(TimeUnit.MILLISECONDS),
                eq(StreamMessageId.MIN),
                eq(properties.getBatchSize())
        )).thenReturn(new AutoClaimResult<>(new StreamMessageId(0L, 0L), Map.of(), java.util.List.of()));
        StreamMessageId recordId = new StreamMessageId(1_780_000_000_055L, 0L);
        when(stream.readGroup(
                eq(properties.getConsumerGroup()),
                any(String.class),
                any(StreamReadGroupArgs.class)
        )).thenReturn(Map.of(recordId, Map.of(
                "ticketId", "FS-9055",
                "attempt", "not-a-number",
                "source", "feishu"
        )));
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<?> loop = mock(Future.class);
        doReturn(loop).when(executor).submit(any(Runnable.class));
        AtomicReference<RedisStreamRepairQueueAdapter> adapterRef = new AtomicReference<>();
        doAnswer(invocation -> {
            adapterRef.get().stop();
            throw new IllegalStateException("dead-letter storage unavailable");
        }).when(deadLetters).saveMalformed(eq(recordId.toString()), any(Map.class), any(String.class));
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> true),
                executor,
                leaseScheduler(),
                provider(deadLetters)
        );
        adapterRef.set(adapter);

        adapter.start();
        ArgumentCaptor<Runnable> loopRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).submit(loopRunnable.capture());
        loopRunnable.getValue().run();

        verify(deadLetters).saveMalformed(eq(recordId.toString()), any(Map.class), any(String.class));
        verifyNoInteractions(script);
    }

    @Test
    void shouldAdvanceAutoClaimCursorUntilThePendingScanIsExhausted() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setConsumerConcurrency(1);
        properties.setFailureBackoffMillis(0L);
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
        StreamMessageId firstRecordId = new StreamMessageId(1_780_000_000_006L, 0L);
        StreamMessageId secondRecordId = new StreamMessageId(1_780_000_000_007L, 0L);
        StreamMessageId nextCursor = new StreamMessageId(1_780_000_000_008L, 0L);
        Map<String, String> first = validFields("FS-9006", "evt-9006");
        Map<String, String> second = validFields("FS-9007", "evt-9007");
        when(stream.autoClaim(
                eq(properties.getConsumerGroup()),
                any(String.class),
                eq(properties.getPendingClaimIdleMillis()),
                eq(TimeUnit.MILLISECONDS),
                any(StreamMessageId.class),
                eq(properties.getBatchSize())
        )).thenReturn(
                new AutoClaimResult<>(nextCursor, Map.of(firstRecordId, first), java.util.List.of()),
                new AutoClaimResult<>(new StreamMessageId(0L, 0L), Map.of(secondRecordId, second), java.util.List.of())
        );
        when(stream.readGroup(
                eq(properties.getConsumerGroup()),
                any(String.class),
                any(StreamReadGroupArgs.class)
        )).thenReturn(Map.of());
        doReturn(1L).when(script).eval(
                eq(RScript.Mode.READ_WRITE),
                any(String.class),
                eq(RScript.ReturnType.LONG),
                eq(java.util.List.of(properties.getStreamKey())),
                eq(properties.getConsumerGroup()),
                any(String.class),
                any(String.class)
        );
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<?> loop = mock(Future.class);
        doReturn(loop).when(executor).submit(any(Runnable.class));
        AtomicReference<RedisStreamRepairQueueAdapter> adapterRef = new AtomicReference<>();
        AtomicInteger processed = new AtomicInteger();
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> {
                    if (processed.incrementAndGet() == 2) {
                        adapterRef.get().stop();
                    }
                    return true;
                }),
                executor,
                leaseScheduler(),
                provider(RepairQueueDeadLetterRepository.noop())
        );
        adapterRef.set(adapter);

        adapter.start();
        ArgumentCaptor<Runnable> loopRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).submit(loopRunnable.capture());
        loopRunnable.getValue().run();

        ArgumentCaptor<StreamMessageId> cursors = ArgumentCaptor.forClass(StreamMessageId.class);
        verify(stream, times(2)).autoClaim(
                eq(properties.getConsumerGroup()),
                any(String.class),
                eq(properties.getPendingClaimIdleMillis()),
                eq(TimeUnit.MILLISECONDS),
                cursors.capture(),
                eq(properties.getBatchSize())
        );
        assertAll(
                () -> assertEquals(2, processed.get()),
                () -> assertEquals(java.util.List.of(StreamMessageId.MIN, nextCursor), cursors.getAllValues())
        );
    }

    private static Map<String, String> validFields(String ticketId, String eventId) {
        return Map.of(
                "ticketId", ticketId,
                "priority", "P1",
                "traceId", "trace-" + ticketId,
                "attempt", "1",
                "source", "feishu",
                "eventId", eventId,
                "eventType", "helpdesk.ticket.created_v1",
                "createdAt", "2026-08-02T00:00:00Z"
        );
    }

    private static TaskScheduler leaseScheduler() {
        return mock(TaskScheduler.class);
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
