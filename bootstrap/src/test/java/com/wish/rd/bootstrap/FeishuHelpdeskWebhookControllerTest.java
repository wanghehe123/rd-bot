package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.feishu.ticket.FeishuHelpdeskProperties;
import com.wish.rd.bootstrap.feishu.ticket.FeishuHelpdeskWebhookController;
import com.wish.rd.engine.ticket.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.RepairQueuePublisher;
import com.wish.rd.engine.ticket.RepairTicketMessage;
import com.wish.rd.engine.ticket.TicketEventIngestionEngine;
import com.wish.rd.engine.ticket.TicketEventInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 {@link FeishuHelpdeskWebhookController}：事件解析、URL 校验、不支持的类型忽略、缺失字段 4xx。
 */
class FeishuHelpdeskWebhookControllerTest {

    private MockMvc mockMvc;
    private CapturingPublisher publisher;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        publisher = new CapturingPublisher();
        TicketEventIngestionEngine ingestion = TicketEventIngestionEngine.forTesting(
                publisher,
                new TicketEventIngestionEngine.InMemoryDeduplicationStore(),
                "feishu"
        );
        FeishuHelpdeskProperties properties = new FeishuHelpdeskProperties();
        properties.setEnabled(true);
        FeishuHelpdeskWebhookController controller = new FeishuHelpdeskWebhookController(
                objectMapper, ingestion, properties
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void urlVerificationEchoesChallenge() throws Exception {
        mockMvc.perform(post("/feishu/helpdesk/events")
                        .contentType("application/json")
                        .content("""
                                {"type":"url_verification","challenge":"abc123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("abc123"));
    }

    @Test
    void ticketCreatedEventIsIngested() throws Exception {
        mockMvc.perform(post("/feishu/helpdesk/events")
                        .contentType("application/json")
                        .content("""
                                {
                                  "event_id":"evt-1",
                                  "event_type":"helpdesk.ticket.created_v1",
                                  "create_time":1780000000,
                                  "ticket_key":"FS-2001",
                                  "priority":"P1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.ticketId").value("FS-2001"));

        // 验证发布的消息只有路由元数据，没有工单内容
        RepairTicketMessage message = publisher.published.get(0);
        org.junit.jupiter.api.Assertions.assertEquals("FS-2001", message.ticketId());
        org.junit.jupiter.api.Assertions.assertEquals("P1", message.priority());
    }

    @Test
    void ticketUpdatedEventTriggersIngestion() throws Exception {
        mockMvc.perform(post("/feishu/helpdesk/events")
                        .contentType("application/json")
                        .content("""
                                {
                                  "uuid":"evt-upd-1",
                                  "event_type":"helpdesk.ticket.updated_v1",
                                  "ticket":{"ticket_key":"FS-3001","status":100}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value("FS-3001"));
    }

    @Test
    void ticketMessageCreatedEventFromUserIsIngested() throws Exception {
        mockMvc.perform(post("/feishu/helpdesk/events")
                        .contentType("application/json")
                        .content("""
                                {
                                  "event_id":"evt-msg-1",
                                  "event_type":"helpdesk.ticket_message.created_v1",
                                  "ticket_key":"FS-4001",
                                  "sender":{"sender_type":"2"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value("FS-4001"));
    }

    @Test
    void unsupportedEventTypeIsAcknowledgedButNotPublished() throws Exception {
        mockMvc.perform(post("/feishu/helpdesk/events")
                        .contentType("application/json")
                        .content("""
                                {
                                  "event_id":"evt-x",
                                  "event_type":"helpdesk.faq.updated_v1",
                                  "ticket_key":"FS-5001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ignored").value(true));
        org.junit.jupiter.api.Assertions.assertTrue(publisher.published.isEmpty());
    }

    @Test
    void missingTicketIdReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/feishu/helpdesk/events")
                        .contentType("application/json")
                        .content("""
                                {
                                  "event_id":"evt-y",
                                  "event_type":"helpdesk.ticket.created_v1"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/feishu/helpdesk/events")
                        .contentType("application/json")
                        .content("not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyBodyReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/feishu/helpdesk/events")
                        .contentType("application/json")
                        .content(""))
                .andExpect(status().isBadRequest());
    }

    private static final class CapturingPublisher implements RepairQueuePublisher {
        final List<RepairTicketMessage> published = new ArrayList<>();

        @Override
        public RepairQueuePublishResult publish(RepairTicketMessage message) {
            published.add(message);
            return RepairQueuePublishResult.success("mq-id-" + published.size(), "RD_BOT_REPAIR_TICKET", message.tag());
        }
    }
}
