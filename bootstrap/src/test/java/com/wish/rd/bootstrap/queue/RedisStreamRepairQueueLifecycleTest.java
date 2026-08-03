package com.wish.rd.bootstrap.queue;

import com.wish.rd.bootstrap.queue.impl.RedisStreamRepairQueueAdapter;
import com.wish.rd.engine.ticket.RepairQueueConsumer;
import com.wish.rd.engine.ticket.RepairQueueDeadLetterRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamCreateGroupParams;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.RedisBusyException;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStreamRepairQueueLifecycleTest {

    @Test
    void shouldCreateGroupWithMkstreamAtAllAndStartBoundedNamedExecutorLoops() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setConsumerConcurrency(2);
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<?> loop = mock(Future.class);
        doReturn(loop).when(executor).submit(any(Runnable.class));
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> true),
                executor,
                leaseScheduler(),
                provider(RepairQueueDeadLetterRepository.noop())
        );

        adapter.start();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<StreamCreateGroupArgs> groupArgs = ArgumentCaptor.forClass(StreamCreateGroupArgs.class);
        verify(stream).createGroup(groupArgs.capture());
        StreamCreateGroupParams group = (StreamCreateGroupParams) groupArgs.getValue();
        assertAll(
                () -> assertTrue(adapter.isRunning()),
                () -> assertEquals(properties.getConsumerGroup(), group.getName()),
                () -> assertTrue(group.isMakeStream()),
                () -> assertEquals(StreamMessageId.ALL, group.getId()),
                () -> verify(executor, times(2)).submit(any(Runnable.class))
        );

        adapter.stop();

        assertAll(
                () -> assertFalse(adapter.isRunning()),
                () -> verify(loop, times(2)).cancel(true)
        );
    }

    @Test
    void shouldTreatOnlyBusygroupAsExistingConsumerGroup() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        doThrow(new RedisBusyException("BUSYGROUP Consumer Group name already exists"))
                .when(stream).createGroup(any(StreamCreateGroupArgs.class));
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        doReturn(mock(Future.class)).when(executor).submit(any(Runnable.class));
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> true),
                executor,
                leaseScheduler(),
                provider(RepairQueueDeadLetterRepository.noop())
        );

        adapter.start();

        assertTrue(adapter.isRunning());
        adapter.stop();
    }

    @Test
    void shouldPropagateNonBusygroupGroupCreationFailure() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        doThrow(new RedisBusyException("LOADING Redis is loading"))
                .when(stream).createGroup(any(StreamCreateGroupArgs.class));
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> true),
                mock(AsyncTaskExecutor.class),
                leaseScheduler(),
                provider(RepairQueueDeadLetterRepository.noop())
        );

        assertThrows(RedisBusyException.class, adapter::start);
        assertFalse(adapter.isRunning());
    }

    @Test
    void shouldCancelAlreadySubmittedLoopsWhenTheNamedExecutorRejectsAnotherConsumer() {
        RedisStreamRepairQueueProperties properties = new RedisStreamRepairQueueProperties();
        properties.setConsumerConcurrency(2);
        RStream<String, String> stream = mock(RStream.class);
        RedissonClient client = mock(RedissonClient.class);
        when(client.<String, String>getStream(eq(properties.getStreamKey()), eq(StringCodec.INSTANCE))).thenReturn(stream);
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<?> firstLoop = mock(Future.class);
        doReturn(firstLoop).doThrow(new TaskRejectedException("repair queue executor is full"))
                .when(executor).submit(any(Runnable.class));
        RedisStreamRepairQueueAdapter adapter = new RedisStreamRepairQueueAdapter(
                properties,
                client,
                provider(message -> true),
                executor,
                leaseScheduler(),
                provider(RepairQueueDeadLetterRepository.noop())
        );

        assertThrows(TaskRejectedException.class, adapter::start);

        assertAll(
                () -> assertFalse(adapter.isRunning()),
                () -> verify(firstLoop).cancel(true)
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
