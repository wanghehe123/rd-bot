package com.wish.rd.bootstrap.feishu.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.ticket.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.RepairQueuePublisher;
import com.wish.rd.engine.ticket.RepairTicketMessage;
import com.wish.rd.engine.ticket.TicketEventIngestionEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证本地 lark-cli 事件流可以复用现有飞书 IM 入队逻辑。
 */
class FeishuImLocalEventListenerTest {

    @Test
    void shouldConvertFlatLarkCliMessageEventAndPublishRepairTicket() {
        ObjectMapper objectMapper = new ObjectMapper();
        FeishuImProperties properties = new FeishuImProperties();
        properties.setEnabled(true);
        properties.setRequireAtMention(false);
        properties.getLocalListener().setEnabled(true);
        FeishuImTicketStore store = new FeishuImTicketStore();
        CapturingPublisher publisher = new CapturingPublisher();
        TicketEventIngestionEngine ingestionEngine = TicketEventIngestionEngine.forTesting(
                publisher,
                new TicketEventIngestionEngine.InMemoryDeduplicationStore(),
                "feishu-im"
        );
        FeishuImMessageController controller = new FeishuImMessageController(
                objectMapper,
                properties,
                new FeishuImTicketParser(),
                store,
                ingestionEngine
        );
        FeishuImLocalEventListener listener = new FeishuImLocalEventListener(objectMapper, properties, controller);

        listener.handleEventLine("""
                {
                  "type": "im.message.receive_v1",
                  "event_id": "evt-local",
                  "message_id": "om_local",
                  "chat_id": "oc_local",
                  "chat_type": "group",
                  "message_type": "text",
                  "sender_id": "ou_local",
                  "timestamp": "1782298004000",
                  "content": "系统: waimai\\n仓库: github.com/example/waimai\\n分支: main\\n优先级: P1\\n问题: 下单接口 500\\n日志: missing address phone customer_name"
                }
                """);

        assertEquals(1, publisher.published.size());
        RepairTicketMessage message = publisher.published.get(0);
        assertEquals("FI-om-local", message.ticketId());
        assertEquals("P1", message.priority());
        assertEquals("feishu.im.message.created_v1", message.eventType());
        assertTrue(store.findTicket("FI-om-local").isPresent());
        assertEquals("oc_local", store.findTicket("FI-om-local").orElseThrow().chatId());
    }

    @Test
    void shouldConvertFlatLarkCliPostMessageEventAndPublishRepairTicket() {
        ObjectMapper objectMapper = new ObjectMapper();
        FeishuImProperties properties = new FeishuImProperties();
        properties.setEnabled(true);
        properties.setRequireAtMention(false);
        properties.getLocalListener().setEnabled(true);
        FeishuImTicketStore store = new FeishuImTicketStore();
        CapturingPublisher publisher = new CapturingPublisher();
        TicketEventIngestionEngine ingestionEngine = TicketEventIngestionEngine.forTesting(
                publisher,
                new TicketEventIngestionEngine.InMemoryDeduplicationStore(),
                "feishu-im"
        );
        FeishuImMessageController controller = new FeishuImMessageController(
                objectMapper,
                properties,
                new FeishuImTicketParser(),
                store,
                ingestionEngine
        );
        FeishuImLocalEventListener listener = new FeishuImLocalEventListener(objectMapper, properties, controller);

        listener.handleEventLine("""
                {
                  "type": "im.message.receive_v1",
                  "event_id": "evt-local-post",
                  "message_id": "om_local_post",
                  "chat_id": "oc_local",
                  "chat_type": "p2p",
                  "message_type": "post",
                  "sender_id": "ou_local",
                  "timestamp": "1782298004000",
                  "content": "问题: 外卖后端在 Node ESM 环境下启动失败\\n系统: waimai\\n优先级: P0\\n日志: ReferenceError: __dirname is not defined in ES module scope"
                }
                """);

        assertEquals(1, publisher.published.size());
        RepairTicketMessage message = publisher.published.get(0);
        assertEquals("FI-om-local-post", message.ticketId());
        assertEquals("P0", message.priority());
        assertEquals("feishu.im.message.created_v1", message.eventType());
        assertTrue(store.findTicket("FI-om-local-post").isPresent());
    }

    @Test
    void shouldBuildHttpEnvelopeFromLarkCliFlatEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        FeishuImProperties properties = new FeishuImProperties();
        CapturingPublisher publisher = new CapturingPublisher();
        FeishuImMessageController controller = new FeishuImMessageController(
                objectMapper,
                properties,
                new FeishuImTicketParser(),
                new FeishuImTicketStore(),
                TicketEventIngestionEngine.forTesting(
                        publisher,
                        new TicketEventIngestionEngine.InMemoryDeduplicationStore(),
                        "feishu-im"
                )
        );
        FeishuImLocalEventListener listener = new FeishuImLocalEventListener(objectMapper, properties, controller);

        String envelope = listener.toHttpEnvelope("""
                {"type":"im.message.receive_v1","event_id":"evt-1","message_id":"om_1","chat_id":"oc_1","chat_type":"p2p","message_type":"text","sender_id":"ou_1","content":"hello"}
                """);

        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(envelope);
        assertEquals("im.message.receive_v1", node.path("header").path("event_type").asText());
        assertEquals("om_1", node.path("event").path("message").path("message_id").asText());
        assertEquals("oc_1", node.path("event").path("message").path("chat_id").asText());
        assertEquals("{\"text\":\"hello\"}", node.path("event").path("message").path("content").asText());
    }

    @Test
    void shouldUseConfiguredLarkCliProfileWhenConsumingEvents() {
        ObjectMapper objectMapper = new ObjectMapper();
        FeishuImProperties properties = new FeishuImProperties();
        properties.getLocalListener().setProfile("cli_aab17cbab0f85cce");
        FeishuImMessageController controller = new FeishuImMessageController(
                objectMapper,
                properties,
                new FeishuImTicketParser(),
                new FeishuImTicketStore(),
                TicketEventIngestionEngine.forTesting(
                        new CapturingPublisher(),
                        new TicketEventIngestionEngine.InMemoryDeduplicationStore(),
                        "feishu-im"
                )
        );
        FeishuImLocalEventListener listener = new FeishuImLocalEventListener(objectMapper, properties, controller);

        assertEquals(List.of(
                "lark-cli",
                "--profile",
                "cli_aab17cbab0f85cce",
                "event",
                "consume",
                "im.message.receive_v1",
                "--as",
                "bot"
        ), listener.command());
    }

    @Test
    void shouldStopRetryingWhenRemoteEventBusAlreadyOwnsTheApp() {
        ObjectMapper objectMapper = new ObjectMapper();
        FeishuImProperties properties = new FeishuImProperties();
        FeishuImMessageController controller = new FeishuImMessageController(
                objectMapper,
                properties,
                new FeishuImTicketParser(),
                new FeishuImTicketStore(),
                TicketEventIngestionEngine.forTesting(
                        new CapturingPublisher(),
                        new TicketEventIngestionEngine.InMemoryDeduplicationStore(),
                        "feishu-im"
                )
        );
        FeishuImLocalEventListener listener = new FeishuImLocalEventListener(objectMapper, properties, controller);

        assertTrue(listener.shouldStopAfterExit(2, List.of(
                "{",
                "  \"ok\": false,",
                "  \"error\": {",
                "    \"type\": \"validation\",",
                "    \"subtype\": \"failed_precondition\",",
                "    \"message\": \"another event bus is already connected to this app (1 remote event connection(s) detected via API); only one bus should run globally\"",
                "  }",
                "}"
        )));
    }

    private static final class CapturingPublisher implements RepairQueuePublisher {
        private final List<RepairTicketMessage> published = new ArrayList<>();

        @Override
        public RepairQueuePublishResult publish(RepairTicketMessage message) {
            published.add(message);
            return RepairQueuePublishResult.success(message.ticketId(), "msg-" + published.size(), message.priority());
        }
    }
}
