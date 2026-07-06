package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.wish.rd.bootstrap.feishu.ticket.FeishuHelpdeskAuth;
import com.wish.rd.bootstrap.feishu.ticket.FeishuHelpdeskClient;
import com.wish.rd.bootstrap.feishu.ticket.FeishuHelpdeskProperties;
import com.wish.rd.bootstrap.feishu.ticket.model.FeishuStartServiceCommand;
import com.wish.rd.bootstrap.feishu.ticket.model.FeishuStartServiceResult;
import com.wish.rd.adapter.model.TicketMessageQuery;
import com.wish.rd.adapter.model.TicketMessages;
import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.bootstrap.feishu.ticket.impl.FeishuTicketAdapter;
import com.wish.rd.bootstrap.feishu.ticket.FeishuTicketMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 飞书 Helpdesk 真实连通性冒烟测试：默认跳过，显式开启才执行。
 *
 * <p>读操作（查询工单详情 / 消息 / 自定义字段）默认开启；
 * 写操作（发消息/更新工单）需额外 {@code rd.feishu.helpdesk.write-back.enabled=true}。
 *
 * <p>执行命令：
 * <pre>
 * ./mvnw -pl bootstrap -am -Dtest=FeishuHelpdeskRealSmokeTest \
 *   -Drd.integration.feishu.enabled=true \
 *   -Drd.feishu.helpdesk.enabled=true \
 *   -Drd.feishu.helpdesk.app-id=$APP_ID \
 *   -Drd.feishu.helpdesk.app-secret=$APP_SECRET \
 *   -Drd.feishu.helpdesk.helpdesk-id=$HELPDESK_ID \
 *   -Drd.feishu.helpdesk.helpdesk-token=$HELPDESK_TOKEN \
 *   -Drd.feishu.smoke.ticket-id=$TICKET_ID \
 *   test
 * </pre>
 *
 * <p>真实创建服务台对话默认关闭；如需验证机器人创建工单，额外传入：
 * <pre>
 *   -Drd.feishu.smoke.create-ticket.enabled=true \
 *   -Drd.feishu.smoke.open-id=$USER_OPEN_ID
 * </pre>
 *
 * <p>缺凭据时给出清晰假设失败，而不是随机连接错误。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "rd.integration.feishu.enabled", matches = "true")
class FeishuHelpdeskRealSmokeTest {

    @Autowired
    private FeishuHelpdeskProperties properties;

    @DynamicPropertySource
    static void registerFeishuProperties(DynamicPropertyRegistry registry) {
        registry.add("rd.feishu.helpdesk.enabled", () -> "true");
    }

    @Test
    void readsTicketDetailMessagesAndCustomFields() {
        // 假设校验：必须有完整凭据
        assumeTrue(!properties.getAppId().isBlank(), "rd.feishu.helpdesk.app-id must be set");
        assumeTrue(!properties.getAppSecret().isBlank(), "rd.feishu.helpdesk.app-secret must be set");
        assumeTrue(!properties.getHelpdeskId().isBlank(), "rd.feishu.helpdesk.helpdesk-id must be set");
        assumeTrue(!properties.getHelpdeskToken().isBlank(), "rd.feishu.helpdesk.helpdesk-token must be set");

        String ticketId = System.getProperty("rd.feishu.smoke.ticket-id", "");
        assumeTrue(!ticketId.isBlank(), "rd.feishu.smoke.ticket-id must be set to an existing ticket");

        FeishuHelpdeskClient client = new FeishuHelpdeskClient(
                properties, new com.fasterxml.jackson.databind.ObjectMapper(), 3000, 10_000
        );
        FeishuTicketAdapter adapter = new FeishuTicketAdapter(
                client, properties, FeishuTicketMapper.defaults()
        );

        Optional<TicketSnapshot> snapshot = adapter.findTicket(ticketId);
        assertTrue(snapshot.isPresent(), "ticket should exist: " + ticketId);
        TicketSnapshot ticket = snapshot.get();
        assertNotNull(ticket.status());
        System.out.println("[smoke] ticket " + ticketId + " status=" + ticket.status()
                + " priority=" + ticket.priority() + " chatId=" + ticket.chatId());

        TicketMessages messages = adapter.findMessages(ticketId, TicketMessageQuery.defaults());
        System.out.println("[smoke] ticket " + ticketId + " messages=" + messages.messages().size());

        // 自定义字段列表（只读）
        JsonNode customFields = client.listCustomFields();
        assertNotNull(customFields);
        System.out.println("[smoke] custom fields count=" + (customFields.isArray() ? customFields.size() : 0));
    }

    @Test
    void createsHelpdeskConversationWhenExplicitlyEnabled() {
        assumeTrue(Boolean.getBoolean("rd.feishu.smoke.create-ticket.enabled"),
                "set rd.feishu.smoke.create-ticket.enabled=true to create a real Helpdesk conversation");
        assumeTrue(!properties.getAppId().isBlank(), "rd.feishu.helpdesk.app-id must be set");
        assumeTrue(!properties.getAppSecret().isBlank(), "rd.feishu.helpdesk.app-secret must be set");
        assumeTrue(!properties.getHelpdeskId().isBlank(), "rd.feishu.helpdesk.helpdesk-id must be set");
        assumeTrue(!properties.getHelpdeskToken().isBlank(), "rd.feishu.helpdesk.helpdesk-token must be set");

        String openId = System.getProperty("rd.feishu.smoke.open-id", "");
        assumeTrue(!openId.isBlank(), "rd.feishu.smoke.open-id must be set to an existing user open_id");

        FeishuHelpdeskClient client = new FeishuHelpdeskClient(
                properties, new com.fasterxml.jackson.databind.ObjectMapper(), 3000, 10_000
        );
        FeishuStartServiceResult result = client.startService(new FeishuStartServiceCommand(
                openId,
                true,
                java.util.List.of(),
                "RD-Bot real start_service smoke"
        ));

        assertTrue(!result.chatId().isBlank(), "start_service should return chat_id");
        System.out.println("[smoke] start_service chatId=" + result.chatId()
                + " ticketId=" + result.ticketId());
    }

    @Test
    void authHelperMasksTokenInToString() {
        // 即便真实凭据场景，toString 也不应泄漏 helpdesk token
        FeishuHelpdeskAuth auth = FeishuHelpdeskAuth.from(properties);
        if (auth.isConfigured()) {
            String repr = auth.toString();
            assertTrue(!repr.contains(properties.getHelpdeskToken()), "toString must not leak helpdesk token");
        }
    }
}
