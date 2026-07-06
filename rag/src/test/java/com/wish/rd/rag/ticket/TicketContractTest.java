package com.wish.rd.rag.ticket;

import com.wish.rd.adapter.model.TicketMessage;
import com.wish.rd.adapter.model.TicketMessageQuery;
import com.wish.rd.adapter.model.TicketMessages;
import com.wish.rd.adapter.TicketProviderPort;
import com.wish.rd.adapter.model.TicketReplyCommand;
import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.adapter.model.TicketUpdateCommand;
import com.wish.rd.adapter.TicketUpdatePort;
import com.wish.rd.adapter.model.TicketUpdateResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单契约测试：验证 {@code rag} 层的标准 record 端口能正确归一 null 入参，
 * 且不依赖任何 Feishu / RocketMQ / Spring web 客户端。
 */
class TicketContractTest {

    @Test
    void ticketSnapshotNormalizesNullsAndLegacyFiveArgConstructor() {
        TicketSnapshot snapshot = new TicketSnapshot(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        assertAll(
                () -> assertEquals("", snapshot.ticketId()),
                () -> assertEquals("", snapshot.title()),
                () -> assertEquals("", snapshot.description()),
                () -> assertTrue(snapshot.labels().isEmpty()),
                () -> assertEquals(Instant.EPOCH, snapshot.createdAt()),
                () -> assertEquals("P2", snapshot.priority()),
                () -> assertEquals("", snapshot.status()),
                () -> assertEquals("", snapshot.source()),
                () -> assertEquals("", snapshot.chatId()),
                () -> assertTrue(snapshot.customFields().isEmpty()),
                () -> assertEquals(Instant.EPOCH, snapshot.updatedAt()),
                () -> assertEquals(Instant.EPOCH, snapshot.closedAt()),
                () -> assertFalse(snapshot.isClosed())
        );

        // 兼容旧调用点：五参构造仍可工作
        TicketSnapshot legacy = new TicketSnapshot("T-1", "title", "desc", List.of("a"), Instant.EPOCH);
        assertEquals("P2", legacy.priority());
        assertEquals("", legacy.source());
        assertTrue(legacy.customFields().isEmpty());

        // closed 判定
        TicketSnapshot closed = new TicketSnapshot(
                "T-2", "t", "d", List.of(), Instant.EPOCH, "P1", "closed", "", "feishu", "",
                Map.of(), Instant.EPOCH, Instant.now()
        );
        assertTrue(closed.isClosed());

        // priority 归一
        TicketSnapshot weird = new TicketSnapshot(
                "T-3", "t", "d", List.of(), Instant.EPOCH, "weird", "", "", "", "",
                Map.of(), Instant.EPOCH, Instant.EPOCH
        );
        assertEquals("P2", weird.priority());

        TicketSnapshot p0 = new TicketSnapshot(
                "T-4", "t", "d", List.of(), Instant.EPOCH, "p0", "", "", "", "",
                Map.of(), Instant.EPOCH, Instant.EPOCH
        );
        assertEquals("P0", p0.priority());
    }

    @Test
    void ticketMessageAndQueryNormalizeNulls() {
        TicketMessage message = new TicketMessage(null, null, null, null, null, null, null, null);
        assertAll(
                () -> assertEquals("", message.messageId()),
                () -> assertEquals("", message.senderType()),
                () -> assertEquals("", message.senderId()),
                () -> assertEquals("", message.messageType()),
                () -> assertEquals("", message.content()),
                () -> assertTrue(message.attachments().isEmpty()),
                () -> assertTrue(message.metadata().isEmpty()),
                () -> assertEquals(Instant.EPOCH, message.createdAt()),
                () -> assertFalse(message.isFromUser())
        );
        TicketMessage userMessage = new TicketMessage(
                "m-1", "2", "u-1", "text", "hello", List.of(), Map.of(), Instant.EPOCH
        );
        assertTrue(userMessage.isFromUser());

        TicketMessages messages = new TicketMessages(null, 0, 0, -1L, true);
        assertTrue(messages.messages().isEmpty());
        assertEquals(1, TicketMessages.empty().page());

        TicketMessageQuery query = new TicketMessageQuery(null, null, null, -1, -1);
        assertEquals("", query.senderType());
        assertEquals(Instant.EPOCH, query.fromTime());
        assertEquals(TicketMessageQuery.DEFAULT_PAGE_SIZE, query.pageSize());
        assertEquals(1, query.page());
        assertEquals(1, TicketMessageQuery.defaults().page());
    }

    @Test
    void ticketCommandsAndResultNormalizeNulls() {
        TicketReplyCommand reply = new TicketReplyCommand(null, null, null, null, null, null, null);
        assertAll(
                () -> assertEquals("", reply.ticketId()),
                () -> assertEquals("text", reply.messageType()),
                () -> assertEquals("", reply.content()),
                () -> assertTrue(reply.attachments().isEmpty()),
                () -> assertTrue(reply.metadata().isEmpty()),
                () -> assertEquals("", reply.traceId()),
                () -> assertEquals("", reply.repairRecordId())
        );

        TicketUpdateCommand update = new TicketUpdateCommand(null, null, null, null, null, null, null);
        assertAll(
                () -> assertEquals("", update.ticketId()),
                () -> assertEquals("", update.status()),
                () -> assertTrue(update.tags().isEmpty()),
                () -> assertEquals("", update.comment()),
                () -> assertTrue(update.customFields().isEmpty()),
                () -> assertEquals("", update.traceId())
        );

        TicketUpdateResult result = new TicketUpdateResult(true, null, null, null);
        assertAll(
                () -> assertEquals("", result.providerMessageId()),
                () -> assertEquals("", result.providerCode()),
                () -> assertEquals("", result.message()),
                () -> assertTrue(result.success())
        );
        assertTrue(TicketUpdateResult.success("mid-1", "ok").success());
        assertFalse(TicketUpdateResult.failure("999", "bad").success());
    }

    @Test
    void portsCanBeImplementedAsRecordsAndPureJavaWithoutExternalDeps() {
        TicketProviderPort provider = new StubProvider();
        TicketUpdatePort updater = new StubUpdater();

        assertTrue(provider.findTicket("T-1").isPresent());
        assertEquals(1, provider.findMessages("T-1", TicketMessageQuery.defaults()).messages().size());
        assertTrue(updater.sendMessage(new TicketReplyCommand("T-1", "text", "hi", List.of(), Map.of(), "trace", "rec")).success());
        assertTrue(updater.updateTicket(new TicketUpdateCommand("T-1", "processing", List.of(), "", Map.of(), false, "trace")).success());
    }

    private static final class StubProvider implements TicketProviderPort {
        @Override
        public Optional<TicketSnapshot> findTicket(String ticketId) {
            return Optional.of(new TicketSnapshot(ticketId, "t", "d", List.of(), Instant.EPOCH));
        }

        @Override
        public TicketMessages findMessages(String ticketId, TicketMessageQuery query) {
            return new TicketMessages(
                    List.of(new TicketMessage("m-1", "2", "u", "text", "hello", List.of(), Map.of(), Instant.EPOCH)),
                    1,
                    query.pageSize(),
                    1L,
                    false
            );
        }
    }

    private static final class StubUpdater implements TicketUpdatePort {
        @Override
        public TicketUpdateResult sendMessage(TicketReplyCommand command) {
            return TicketUpdateResult.success("mid-" + command.ticketId(), "ok");
        }

        @Override
        public TicketUpdateResult updateTicket(TicketUpdateCommand command) {
            return TicketUpdateResult.success("tid-" + command.ticketId(), "ok");
        }
    }
}
