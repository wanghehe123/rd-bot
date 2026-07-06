package com.wish.rd.bootstrap.rocketmq;

import com.wish.rd.bootstrap.rocketmq.impl.RocketMqRepairQueueAdapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RocketMqRepairQueueAdapterListenerTest {

    @Test
    void shouldUseRocketMqReconsumeTimesAsAttemptWhenBodyIsOriginal() throws Exception {
        RocketMqRepairQueueAdapter.RepairMessageListener listener =
                new RocketMqRepairQueueAdapter.RepairMessageListener(message -> true, new ObjectMapper());
        MessageExt message = new MessageExt();
        message.setBody("""
                {
                  "ticketId": "FS-9001",
                  "priority": "P1",
                  "traceId": "trace-9001",
                  "attempt": 1,
                  "source": "feishu",
                  "eventId": "evt-9001",
                  "eventType": "helpdesk.ticket.created_v1",
                  "createdAt": "2026-06-21T00:00:00Z"
                }
                """.getBytes(StandardCharsets.UTF_8));
        message.setReconsumeTimes(3);

        RepairTicketMessage restored = listener.fromMqMessage(message);

        assertEquals(4, restored.attempt());
        assertEquals("trace-9001", restored.traceId());
    }

    @Test
    void shouldRunConsumerOnRepairQueueExecutor() {
        AtomicReference<String> executorThread = new AtomicReference<>();
        AsyncTaskExecutor executor = new CapturingAsyncTaskExecutor("rd-repair-queue-test", executorThread);
        RocketMqRepairQueueAdapter.RepairMessageListener listener =
                new RocketMqRepairQueueAdapter.RepairMessageListener(message -> true, new ObjectMapper(), executor);
        MessageExt message = new MessageExt();
        message.setBody("""
                {
                  "ticketId": "FS-9002",
                  "priority": "P1",
                  "traceId": "trace-9002",
                  "attempt": 1,
                  "source": "feishu",
                  "eventId": "evt-9002",
                  "eventType": "helpdesk.ticket.created_v1",
                  "createdAt": "2026-06-21T00:00:00Z"
                }
                """.getBytes(StandardCharsets.UTF_8));

        listener.consumeMessage(java.util.List.of(message), null);

        assertEquals("rd-repair-queue-test", executorThread.get());
    }

    private record CapturingAsyncTaskExecutor(String threadName, AtomicReference<String> captured)
            implements AsyncTaskExecutor {

        @Override
        public void execute(Runnable task) {
            Thread thread = new Thread(() -> {
                captured.set(Thread.currentThread().getName());
                task.run();
            }, threadName);
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public Future<?> submit(Runnable task) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            execute(() -> {
                try {
                    task.run();
                    future.complete(null);
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            return future;
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            CompletableFuture<T> future = new CompletableFuture<>();
            execute(() -> {
                try {
                    future.complete(task.call());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            return future;
        }
    }
}
