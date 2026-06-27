package com.wish.rd.bootstrap.controller.admin.operation;

import com.wish.rd.bootstrap.rocketmq.InMemoryRepairQueueDeadLetterRepository;
import com.wish.rd.engine.ticket.RepairQueueDeadLetter;
import com.wish.rd.engine.ticket.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.RepairQueuePublisher;
import com.wish.rd.engine.ticket.RepairTicketMessage;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairOperationControllerTest {

    @Test
    void shouldNotMarkDeadLetterReplayedWhenPublishFails() {
        InMemoryRepairQueueDeadLetterRepository repository = new InMemoryRepairQueueDeadLetterRepository();
        RepairQueueDeadLetter deadLetter = repository.save(message(), "retry exceeded");
        RepairOperationController controller = controller(repository, message ->
                RepairQueuePublishResult.failure("RD_BOT_REPAIR_TICKET", message.tag(), "broker unavailable"));

        RepairOperationController.DeadLetterReplayView result = controller.replayDeadLetter(deadLetter.id());

        assertFalse(result.publishSuccess());
        assertFalse(repository.findById(deadLetter.id()).orElseThrow().replayed());
    }

    @Test
    void shouldMarkDeadLetterReplayedOnlyOnceAfterSuccessfulPublish() {
        InMemoryRepairQueueDeadLetterRepository repository = new InMemoryRepairQueueDeadLetterRepository();
        RepairQueueDeadLetter deadLetter = repository.save(message(), "retry exceeded");
        RepairOperationController controller = controller(repository, message ->
                RepairQueuePublishResult.success("mq-1", "RD_BOT_REPAIR_TICKET", message.tag()));

        RepairOperationController.DeadLetterReplayView result = controller.replayDeadLetter(deadLetter.id());

        assertTrue(result.publishSuccess());
        assertTrue(repository.findById(deadLetter.id()).orElseThrow().replayed());
        assertThrows(IllegalStateException.class, () -> controller.replayDeadLetter(deadLetter.id()));
    }

    private static RepairOperationController controller(
            InMemoryRepairQueueDeadLetterRepository repository,
            RepairQueuePublisher publisher
    ) {
        return new RepairOperationController(
                RagStreamTaskRegistry.inMemory(),
                provider(null),
                provider(null),
                provider(null),
                provider(repository),
                provider(publisher),
                provider(null),
                provider(null)
        );
    }

    private static RepairTicketMessage message() {
        return new RepairTicketMessage(
                "FS-9001",
                "P1",
                "trace-9001",
                4,
                "feishu",
                "evt-9001",
                "helpdesk.ticket.created_v1",
                Instant.parse("2026-06-21T00:00:00Z")
        );
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
