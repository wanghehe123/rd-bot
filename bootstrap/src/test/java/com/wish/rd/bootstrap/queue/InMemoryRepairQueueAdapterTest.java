package com.wish.rd.bootstrap.queue;

import com.wish.rd.bootstrap.queue.impl.InMemoryRepairQueueAdapter;
import com.wish.rd.engine.ticket.model.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRepairQueueAdapterTest {

    @Test
    void publishShouldTriggerBoundConsumerOnce() {
        InMemoryRepairQueueAdapter adapter = new InMemoryRepairQueueAdapter();
        AtomicInteger consumed = new AtomicInteger();
        adapter.bindConsumer(message -> {
            consumed.incrementAndGet();
            return true;
        });

        RepairQueuePublishResult result = adapter.publish(message("FI-MEM-1"));

        assertTrue(result.success());
        assertEquals(1, consumed.get());
        assertEquals(1, adapter.snapshot().size());
    }

    @Test
    void drainOneShouldNotConsumeAlreadyDispatchedMessageAgain() {
        InMemoryRepairQueueAdapter adapter = new InMemoryRepairQueueAdapter();
        AtomicInteger consumed = new AtomicInteger();
        adapter.bindConsumer(message -> {
            consumed.incrementAndGet();
            return true;
        });

        adapter.publish(message("FI-MEM-2"));
        Optional<Boolean> drained = adapter.drainOne("FI-MEM-2");

        assertTrue(drained.isEmpty());
        assertEquals(1, consumed.get());
    }

    private RepairTicketMessage message(String ticketId) {
        return new RepairTicketMessage(
                ticketId,
                "P0",
                "trace-" + ticketId,
                RepairTicketMessage.FIRST_ATTEMPT,
                "test",
                "event-" + ticketId,
                "ticket.created",
                Instant.EPOCH
        );
    }
}
