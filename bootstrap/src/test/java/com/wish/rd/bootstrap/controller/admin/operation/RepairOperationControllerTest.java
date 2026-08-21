package com.wish.rd.bootstrap.controller.admin.operation;

import com.wish.rd.engine.audit.RepairAuditQueryPort;
import com.wish.rd.engine.audit.model.RepairAuditEvent;
import com.wish.rd.engine.audit.model.RepairAuditEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairOperationControllerTest {

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

        List<RepairOperationController.RepairAuditEventView> events =
                controller(queryPort).auditEvents(null, "task-1");

        assertEquals(1, events.size());
        assertEquals("task-1", events.getFirst().taskId());
    }

    @Test
    void shouldRejectCombiningRepairRecordIdAndTaskIdFilters() {
        RepairOperationController controller = controller(new RepairAuditQueryPort() {
            @Override
            public List<RepairAuditEvent> events() {
                return List.of();
            }

            @Override
            public List<RepairAuditEvent> eventsByRepairRecordId(String repairRecordId) {
                return List.of();
            }

            @Override
            public List<RepairAuditEvent> eventsByTaskId(String taskId) {
                return List.of();
            }
        });

        assertThrows(IllegalArgumentException.class, () -> controller.auditEvents("repair-1", "task-1"));
    }

    @Test
    void shouldReturnEmptyViewsWhenOptionalPortsAreMissing() {
        RepairOperationController controller = controller(null);

        assertTrue(controller.alerts().isEmpty());
        assertTrue(controller.auditEvents(null, null).isEmpty());
        assertTrue(controller.runningExecutions().isEmpty());
        assertEquals(0L, controller.knowledgeRefresh().latestDelayMillis());
    }

    private static RepairOperationController controller(RepairAuditQueryPort queryPort) {
        return new RepairOperationController(
                provider(null),
                provider(queryPort),
                provider(null),
                provider(null)
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
