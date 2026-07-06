package com.wish.rd.engine;

import com.wish.rd.engine.ticket.model.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.RepairQueuePublisher;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import com.wish.rd.engine.ticket.TicketEventIngestionEngine;
import com.wish.rd.engine.ticket.model.TicketEventInput;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link TicketEventIngestionEngine} 把事件转成瘦队列消息、派生路由字段并做幂等。
 */
class TicketEventIngestionEngineTest {

    @Test
    void ingestsEventAndPublishesRoutingOnlyMessage() {
        CapturingPublisher publisher = new CapturingPublisher(true);
        TicketEventIngestionEngine engine = TicketEventIngestionEngine.forTesting(
                publisher,
                new TicketEventIngestionEngine.InMemoryDeduplicationStore(),
                "feishu"
        );

        RepairQueuePublishResult result = engine.ingest(new TicketEventInput(
                "T-1001",
                "evt-1",
                TicketEventInput.TYPE_TICKET_CREATED,
                "feishu",
                "P0",
                "trace-abc",
                Instant.parse("2026-06-21T00:00:00Z"),
                Map.of()
        ));

        assertAll(
                () -> assertTrue(result.success()),
                () -> assertEquals(1, publisher.published.size())
        );
        RepairTicketMessage message = publisher.published.get(0);
        assertAll(
                () -> assertEquals("T-1001", message.ticketId()),
                () -> assertEquals("P0", message.priority()),
                () -> assertEquals("P0", message.tag()),
                () -> assertEquals("trace-abc", message.traceId()),
                () -> assertEquals(1, message.attempt()),
                () -> assertEquals("feishu", message.source()),
                () -> assertEquals("evt-1", message.eventId()),
                () -> assertEquals(TicketEventInput.TYPE_TICKET_CREATED, message.eventType()),
                () -> assertEquals("evt-1|T-1001|" + TicketEventInput.TYPE_TICKET_CREATED, message.deduplicationKey())
        );
    }

    @Test
    void messageBodyCarriesNoTicketContentOrSecrets() {
        CapturingPublisher publisher = new CapturingPublisher(true);
        TicketEventIngestionEngine engine = TicketEventIngestionEngine.forTesting(
                publisher,
                new TicketEventIngestionEngine.InMemoryDeduplicationStore(),
                "feishu"
        );

        // 带敏感 metadata 的入参，验证不会进入消息
        engine.ingest(new TicketEventInput(
                "T-1002",
                "evt-2",
                TicketEventInput.TYPE_TICKET_UPDATED,
                "",
                "",
                "",
                Instant.EPOCH,
                Map.of("access_token", "secret", "title", "leak")
        ));

        RepairTicketMessage message = publisher.published.get(0);
        String body = message.toString();
        assertAll(
                () -> assertFalse(body.contains("secret"), "message body must not contain access token"),
                () -> assertFalse(body.contains("leak"), "message body must not contain ticket content"),
                () -> assertEquals("feishu", message.source(), "source defaults to feishu"),
                () -> assertEquals("P2", message.priority(), "priority defaults to P2"),
                () -> assertFalse(message.traceId().isBlank(), "traceId is generated when missing")
        );
    }

    @Test
    void duplicateEventIdTicketIdTypeIsSkipped() {
        CapturingPublisher publisher = new CapturingPublisher(true);
        TicketEventIngestionEngine engine = TicketEventIngestionEngine.forTesting(
                publisher,
                new TicketEventIngestionEngine.InMemoryDeduplicationStore(),
                "feishu"
        );
        TicketEventInput event = new TicketEventInput(
                "T-1003",
                "evt-dup",
                TicketEventInput.TYPE_TICKET_CREATED,
                "feishu",
                "P1",
                "trace-x",
                Instant.EPOCH,
                Map.of()
        );

        assertTrue(engine.ingest(event).success());
        RepairQueuePublishResult second = engine.ingest(event);
        assertAll(
                () -> assertFalse(second.success()),
                () -> assertTrue(second.errorMessage().contains("duplicate event")),
                () -> assertEquals(1, publisher.published.size(), "only first event should publish")
        );
    }

    @Test
    void blankTicketIdRejectsBeforePublish() {
        assertThrows(IllegalArgumentException.class, () -> new TicketEventInput(
                "  ", "evt", "type", "feishu", "P0", "trace", Instant.EPOCH, Map.of()
        ));
    }

    @Test
    void publishFailureIsReturnedAsFailureResult() {
        CapturingPublisher publisher = new CapturingPublisher(false);
        TicketEventIngestionEngine engine = TicketEventIngestionEngine.forTesting(
                publisher,
                new TicketEventIngestionEngine.InMemoryDeduplicationStore(),
                "feishu"
        );
        RepairQueuePublishResult result = engine.ingest(new TicketEventInput(
                "T-1004",
                "evt-fail",
                TicketEventInput.TYPE_TICKET_MESSAGE_CREATED,
                "feishu",
                "",
                "",
                Instant.EPOCH,
                Map.of()
        ));
        assertFalse(result.success());
    }

    private static final class CapturingPublisher implements RepairQueuePublisher {
        private final boolean succeed;
        private final List<RepairTicketMessage> published = new ArrayList<>();

        CapturingPublisher(boolean succeed) {
            this.succeed = succeed;
        }

        @Override
        public RepairQueuePublishResult publish(RepairTicketMessage message) {
            published.add(message);
            return succeed
                    ? RepairQueuePublishResult.success("mq-id-" + published.size(), "RD_BOT_REPAIR_TICKET", message.tag())
                    : RepairQueuePublishResult.failure("RD_BOT_REPAIR_TICKET", message.tag(), "broker unavailable");
        }
    }
}
