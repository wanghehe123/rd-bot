package com.wish.rd.bootstrap.controller.admin.operation;

import com.wish.rd.bootstrap.rocketmq.impl.InMemoryRepairQueueDeadLetterRepository;
import com.wish.rd.engine.audit.RepairAuditQueryPort;
import com.wish.rd.engine.audit.model.RepairAuditEvent;
import com.wish.rd.engine.audit.model.RepairAuditEventType;
import com.wish.rd.engine.ticket.model.RepairQueueDeadLetter;
import com.wish.rd.engine.ticket.model.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.RepairQueuePublisher;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void shouldQueryAuditEventsByTaskIdWithoutLoadingOtherTasks() {
        RepairAuditEvent matching = RepairAuditEvent.now(
                "repair-1", "task-1", "ticket-1", RepairAuditEventType.EXECUTION_FINISHED,
                "Docker", "task one finished", java.util.Map.of());
        RepairAuditQueryPort queryPort = new RepairAuditQueryPort() {
            @Override
            public List<RepairAuditEvent> events() {
                throw new AssertionError("task-scoped query must not load all audit events");
            }

            @Override
            public List<RepairAuditEvent> eventsByRepairRecordId(String repairRecordId) {
                return List.of();
            }

            @Override
            public List<RepairAuditEvent> eventsByTaskId(String taskId) {
                return "task-1".equals(taskId) ? List.of(matching) : List.of();
            }
        };
        RepairOperationController controller = controller(
                new InMemoryRepairQueueDeadLetterRepository(),
                message -> RepairQueuePublishResult.success("mq-1", "RD_BOT_REPAIR_TICKET", message.tag()),
                queryPort);

        List<RepairOperationController.RepairAuditEventView> events = controller.auditEvents(null, "task-1");

        assertEquals(1, events.size());
        assertEquals("task-1", events.getFirst().taskId());
    }

    private static RepairOperationController controller(
            InMemoryRepairQueueDeadLetterRepository repository,
            RepairQueuePublisher publisher
    ) {
        return controller(repository, publisher, null);
    }

    private static RepairOperationController controller(
            InMemoryRepairQueueDeadLetterRepository repository,
            RepairQueuePublisher publisher,
            RepairAuditQueryPort queryPort
    ) {
        return new RepairOperationController(
                RagStreamTaskRegistry.inMemory(),
                provider(null),
                provider(queryPort),
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
