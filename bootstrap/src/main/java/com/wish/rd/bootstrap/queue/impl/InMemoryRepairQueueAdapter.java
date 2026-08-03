package com.wish.rd.bootstrap.queue.impl;

import com.wish.rd.engine.ticket.RepairQueueConsumer;
import com.wish.rd.engine.ticket.RepairQueuePublisher;
import com.wish.rd.engine.ticket.model.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory repair-queue adapter for explicit local and test mode.
 *
 * <p>It preserves the existing synchronous test-channel behavior without requiring Redis;
 * production uses {@code rd.repair.queue.mode=redis-stream} instead.
 */
@Component
@ConditionalOnProperty(name = "rd.repair.queue.mode", havingValue = "memory")
public class InMemoryRepairQueueAdapter implements RepairQueuePublisher {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRepairQueueAdapter.class);
    private static final String STREAM_TARGET = "in-memory://rd-bot:repair:tickets";

    private final List<QueuedMessage> published = new ArrayList<>();
    private final AtomicLong idSequence = new AtomicLong(0);
    private volatile RepairQueueConsumer consumer;

    /**
     * Binds the queue consumer used by the local synchronous adapter.
     *
     * @param consumer consumer callback, or {@code null} to defer consumption
     */
    public void bindConsumer(RepairQueueConsumer consumer) {
        this.consumer = consumer;
    }

    /**
     * Publishes a local message and immediately invokes the bound consumer when present.
     *
     * @param message thin repair-ticket message
     * @return local publish result
     */
    @Override
    public synchronized RepairQueuePublishResult publish(RepairTicketMessage message) {
        QueuedMessage queued = publishInternal(message);
        dispatch(queued);
        return RepairQueuePublishResult.success("mem-" + queued.id(), STREAM_TARGET, message.tag());
    }

    /**
     * Drains the latest unconsumed local message for a ticket.
     *
     * @param ticketId ticket identifier
     * @return consumption result or empty when no matching message remains
     */
    public synchronized Optional<Boolean> drainOne(String ticketId) {
        Optional<QueuedMessage> found = published.stream()
                .filter(message -> !message.consumed())
                .filter(message -> message.message().ticketId().equals(ticketId))
                .reduce((first, second) -> second);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return dispatch(found.get());
    }

    /**
     * Publishes and synchronously drains one local message.
     *
     * @param message thin repair-ticket message
     * @return whether the bound consumer completed successfully
     */
    public synchronized boolean publishAndDrain(RepairTicketMessage message) {
        QueuedMessage queued = publishInternal(message);
        return dispatch(queued).orElse(false);
    }

    /**
     * Returns a snapshot of locally published messages for the test channel.
     *
     * @return immutable message snapshot
     */
    public synchronized List<RepairTicketMessage> snapshot() {
        return published.stream().map(QueuedMessage::message).toList();
    }

    private QueuedMessage publishInternal(RepairTicketMessage message) {
        long id = idSequence.incrementAndGet();
        QueuedMessage queued = new QueuedMessage(id, message);
        published.add(queued);
        log.info("in-memory queue published, ticketId={}, tag={}, seq={}",
                message.ticketId(), message.tag(), id);
        return queued;
    }

    private Optional<Boolean> dispatch(QueuedMessage queued) {
        if (queued.consumed()) {
            return Optional.empty();
        }
        RepairQueueConsumer currentConsumer = consumer;
        if (currentConsumer == null) {
            log.warn("in-memory queue has no consumer bound, ticketId={}", queued.message().ticketId());
            return Optional.empty();
        }
        try {
            boolean handled = currentConsumer.handle(queued.message());
            queued.markConsumed();
            if (!handled) {
                log.warn("in-memory queue consumer returned failure, ticketId={}, seq={}",
                        queued.message().ticketId(), queued.id());
            }
            return Optional.of(handled);
        } catch (RuntimeException exception) {
            log.error("in-memory queue consumer threw exception, ticketId={}, seq={}",
                    queued.message().ticketId(), queued.id(), exception);
            return Optional.of(false);
        }
    }

    private static final class QueuedMessage {
        private final long id;
        private final RepairTicketMessage message;
        private boolean consumed;

        private QueuedMessage(long id, RepairTicketMessage message) {
            this.id = id;
            this.message = message;
        }

        private long id() {
            return id;
        }

        private RepairTicketMessage message() {
            return message;
        }

        private boolean consumed() {
            return consumed;
        }

        private void markConsumed() {
            this.consumed = true;
        }
    }
}
