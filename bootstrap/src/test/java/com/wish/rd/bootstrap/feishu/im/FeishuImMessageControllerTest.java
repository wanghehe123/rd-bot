package com.wish.rd.bootstrap.feishu.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.ticket.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.RepairQueuePublisher;
import com.wish.rd.engine.ticket.RepairTicketMessage;
import com.wish.rd.engine.ticket.TicketEventIngestionEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证飞书 IM 事件入口对 URL 校验、@ 过滤、文本建单和队列发布的处理。
 */
class FeishuImMessageControllerTest {

    private FeishuImTicketStore store;
    private CapturingPublisher publisher;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        FeishuImProperties properties = new FeishuImProperties();
        properties.setEnabled(true);
        properties.setRequireAtMention(true);
        store = new FeishuImTicketStore();
        publisher = new CapturingPublisher();
        TicketEventIngestionEngine ingestionEngine = TicketEventIngestionEngine.forTesting(
                publisher,
                new TicketEventIngestionEngine.InMemoryDeduplicationStore(),
                "feishu-im"
        );
        FeishuImMessageController controller = new FeishuImMessageController(
                new ObjectMapper(),
                properties,
                new FeishuImTicketParser(),
                store,
                ingestionEngine
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldReturnChallengeForUrlVerification() throws Exception {
        mockMvc.perform(post("/feishu/im/events")
                        .contentType("application/json")
                        .content("{\"type\":\"url_verification\",\"challenge\":\"c1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("c1"));
    }

    @Test
    void shouldIgnoreGroupTextWithoutMentionWhenMentionRequired() throws Exception {
        mockMvc.perform(post("/feishu/im/events")
                        .contentType("application/json")
                        .content(eventJson("evt-no-mention", "om-no-mention", "", List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.ignored").value(true));

        assertTrue(publisher.published.isEmpty());
    }

    @Test
    void shouldCreateLocalTicketAndPublishQueueMessageForMentionedText() throws Exception {
        mockMvc.perform(post("/feishu/im/events")
                        .contentType("application/json")
                        .content(eventJson("evt-1", "om_123", "@_user_1 ", List.of("@_user_1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.ticketId").value("FI-om-123"));

        assertEquals(1, publisher.published.size());
        RepairTicketMessage message = publisher.published.get(0);
        assertEquals("FI-om-123", message.ticketId());
        assertEquals("feishu.im.message.created_v1", message.eventType());
        assertEquals("feishu-im", message.source());
        assertEquals("P1", message.priority());
        assertTrue(store.findTicket("FI-om-123").isPresent());
        assertEquals("oc-chat", store.findTicket("FI-om-123").orElseThrow().chatId());
    }

    private static String eventJson(String eventId, String messageId, String prefix, List<String> mentionKeys) {
        String mentions = mentionKeys.stream()
                .map(key -> "{\"key\":\"" + key + "\",\"id\":{\"open_id\":\"ou-bot\"}}")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String text = prefix + "系统: waimai\\n仓库: github.com/example/waimai\\n分支: main\\n优先级: P1\\n问题: 下单接口 500\\n日志: NPE";
        String escapedTextJson = "{\\\"text\\\":\\\"" + text + "\\\"}";
        return """
                {
                  "schema": "2.0",
                  "header": {
                    "event_id": "%s",
                    "event_type": "im.message.receive_v1",
                    "create_time": "1782190000000"
                  },
                  "event": {
                    "sender": {"sender_id": {"open_id": "ou-user"}},
                    "message": {
                      "message_id": "%s",
                      "chat_id": "oc-chat",
                      "chat_type": "group",
                      "message_type": "text",
                      "content": "%s",
                      "mentions": [%s]
                    }
                  }
                }
                """.formatted(eventId, messageId, escapedTextJson, mentions);
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
