package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.adapter.TicketMessageQuery;
import com.wish.rd.adapter.TicketMessages;
import com.wish.rd.adapter.TicketReplyCommand;
import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.adapter.TicketUpdateResult;
import com.wish.rd.bootstrap.feishu.ticket.FeishuHelpdeskAuth;
import com.wish.rd.bootstrap.feishu.ticket.FeishuHelpdeskClient;
import com.wish.rd.bootstrap.feishu.ticket.FeishuHelpdeskProperties;
import com.wish.rd.bootstrap.feishu.ticket.FeishuTicketAdapter;
import com.wish.rd.bootstrap.feishu.ticket.FeishuTicketMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link FeishuTicketAdapter}：用桩 {@link FeishuHelpdeskClient.HttpTransport} 返回固定 JSON，
 * 验证 DTO 映射、helpdesk 鉴权头、回写开关与失败处理，不连真实飞书。
 */
class FeishuTicketAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsTicketDetailWithStatusStageChatIdAndCustomFields() {
        StubTransport transport = new StubTransport();
        transport.enqueue("{\"code\":0,\"data\":{\"ticket\":"
                + "{\"id\":\"FS-1001\",\"title\":\"支付下单 500\",\"desc\":\"金额为空时失败\","
                + "\"status\":100,\"stage\":\"investigating\",\"chat_id\":\"oc-chat-1\","
                + "\"created_at\":1780000000,\"updated_at\":1780000100,"
                + "\"custom_fields\":["
                + "{\"field_id\":\"priority\",\"value\":\"P1\"},"
                + "{\"field_id\":\"logs\",\"value\":\"ERROR orders.amount is null\"}"
                + "],"
                + "\"tags_info\":[{\"id\":\"t1\",\"name\":\"payment\"}]}"
                + "}}");
        FeishuTicketAdapter adapter = newAdapter(enabledWithWriteBack(true), transport);

        Optional<TicketSnapshot> snapshot = adapter.findTicket("FS-1001");

        assertTrue(snapshot.isPresent());
        TicketSnapshot ticket = snapshot.get();
        assertAll(
                () -> assertEquals("FS-1001", ticket.ticketId()),
                () -> assertEquals("支付下单 500", ticket.title()),
                () -> assertEquals("金额为空时失败", ticket.description()),
                () -> assertEquals("processing", ticket.status()),
                () -> assertEquals("investigating", ticket.stage()),
                () -> assertEquals("oc-chat-1", ticket.chatId()),
                () -> assertEquals("feishu", ticket.source()),
                () -> assertEquals("P1", ticket.priority()),
                () -> assertTrue(ticket.labels().contains("payment")),
                () -> assertEquals("ERROR orders.amount is null", ticket.customFields().get("logs")),
                () -> assertFalse(ticket.isClosed())
        );
    }

    @Test
    void findTicketShouldSendHelpdeskAuthorizationHeader() {
        StubTransport transport = new StubTransport();
        transport.enqueue("{\"code\":0,\"data\":{\"ticket\":"
                + "{\"id\":\"FS-AUTH\",\"title\":\"auth\",\"desc\":\"desc\","
                + "\"status\":100,\"custom_fields\":[{\"field_id\":\"logs\",\"value\":\"ERROR\"}]}"
                + "}}");
        FeishuTicketAdapter adapter = newAdapter(enabledWithWriteBack(false), transport);

        Optional<TicketSnapshot> snapshot = adapter.findTicket("FS-AUTH");

        assertTrue(snapshot.isPresent());
        java.net.http.HttpRequest ticketRequest = transport.nonTokenRequests().getFirst();
        assertTrue(
                ticketRequest.headers().firstValue("X-Lark-Helpdesk-Authorization").isPresent(),
                "Helpdesk ticket detail GET must carry X-Lark-Helpdesk-Authorization"
        );
    }

    @Test
    void closedTicketIsDetectedAsClosed() {
        StubTransport transport = new StubTransport();
        transport.enqueue("{\"code\":0,\"data\":{\"ticket\":"
                + "{\"id\":\"FS-2\",\"title\":\"done\",\"desc\":\"\","
                + "\"status\":300,\"updated_at\":1780000200}}}");
        FeishuTicketAdapter adapter = newAdapter(enabledWithWriteBack(false), transport);

        TicketSnapshot ticket = adapter.findTicket("FS-2").orElseThrow();
        assertTrue(ticket.isClosed());
        assertEquals("closed", ticket.status());
    }

    @Test
    void findMessagesMapsUserAndAgentMessages() {
        StubTransport transport = new StubTransport();
        transport.enqueue("{\"code\":0,\"data\":{\"items\":["
                + "{\"message_id\":\"m-1\",\"sender_type\":\"2\",\"sender_id\":\"u-1\","
                + "\"msg_type\":\"text\",\"content\":\"ERROR orders.amount is null\",\"created_at\":1780000000},"
                + "{\"message_id\":\"m-2\",\"sender_type\":\"1\",\"sender_id\":\"agent-1\","
                + "\"msg_type\":\"text\",\"content\":\"已收到\",\"created_at\":1780000010}"
                + "]}}");
        FeishuTicketAdapter adapter = newAdapter(enabledWithWriteBack(false), transport);

        TicketMessages messages = adapter.findMessages("FS-1", TicketMessageQuery.defaults());
        assertEquals(2, messages.messages().size());
        assertEquals("ERROR orders.amount is null", messages.messages().get(0).content());
        assertTrue(messages.messages().get(0).isFromUser());
        assertFalse(messages.messages().get(1).isFromUser());
    }

    @Test
    void sendMessageRespectsWriteBackToggle() {
        // write-back 关闭：失败返回
        FeishuTicketAdapter adapterDisabled = newAdapter(enabledWithWriteBack(false), new StubTransport());
        TicketUpdateResult blocked = adapterDisabled.sendMessage(new TicketReplyCommand(
                "FS-1", "text", "hi", List.of(), Map.of(), "trace", "rec"
        ));
        assertFalse(blocked.success());
        assertEquals("write-back-disabled", blocked.providerCode());

        // write-back 开启：调用 client 成功
        StubTransport transport = new StubTransport();
        transport.enqueue("{\"code\":0,\"data\":{\"message_id\":\"om-xyz\"}}");
        FeishuTicketAdapter adapter = newAdapter(enabledWithWriteBack(true), transport);
        TicketUpdateResult ok = adapter.sendMessage(new TicketReplyCommand(
                "FS-1", "text", "hello", List.of(), Map.of(), "trace", "rec"
        ));
        assertTrue(ok.success());
        assertEquals("om-xyz", ok.providerMessageId());
    }

    @Test
    void apiErrorReturnsEmptySnapshotWithoutLeakingToken() {
        StubTransport transport = new StubTransport();
        transport.enqueue("{\"code\":99991663,\"msg\":\"access token invalid\"}");
        FeishuTicketAdapter adapter = newAdapter(enabledWithWriteBack(false), transport);

        Optional<TicketSnapshot> snapshot = adapter.findTicket("FS-ERR");
        assertTrue(snapshot.isEmpty());
    }

    @Test
    void missingTicketDataReturnsEmpty() {
        StubTransport transport = new StubTransport();
        transport.enqueue("{\"code\":0,\"data\":{}}");
        FeishuTicketAdapter adapter = newAdapter(enabledWithWriteBack(false), transport);

        Optional<TicketSnapshot> snapshot = adapter.findTicket("FS-MISSING");
        assertTrue(snapshot.isEmpty());
    }

    @Test
    void authHeaderIsBase64OfHelpdeskIdTokenAndToStringRedactsToken() {
        FeishuHelpdeskProperties properties = new FeishuHelpdeskProperties();
        properties.setHelpdeskId("hd-1");
        properties.setHelpdeskToken("secret-token");
        FeishuHelpdeskAuth auth = FeishuHelpdeskAuth.from(properties);
        String header = auth.helpdeskAuthorizationHeader();
        String decoded = new String(java.util.Base64.getDecoder().decode(header), StandardCharsets.UTF_8);
        assertEquals("hd-1:secret-token", decoded);
        // toString 不得泄漏 token
        assertFalse(auth.toString().contains("secret-token"));
        assertTrue(auth.toString().contains("hd-1"));
        assertTrue(auth.isConfigured());
    }

    @Test
    void postMessageFlattensToReadableText() {
        StubTransport transport = new StubTransport();
        transport.enqueue("{\"code\":0,\"data\":{\"items\":["
                + "{\"message_id\":\"m-1\",\"sender_type\":\"2\",\"sender_id\":\"u-1\","
                + "\"msg_type\":\"post\","
                + "\"content\":{\"zh_cn\":{\"title\":\"故障报告\",\"content\":[[{\"tag\":\"text\",\"text\":\"订单接口 500\"}]]}},"
                + "\"created_at\":1780000000}"
                + "]}}");
        FeishuTicketAdapter adapter = newAdapter(enabledWithWriteBack(false), transport);

        TicketMessages messages = adapter.findMessages("FS-1", TicketMessageQuery.defaults());
        assertEquals(1, messages.messages().size());
        String content = messages.messages().get(0).content();
        assertTrue(content.contains("故障报告"));
        assertTrue(content.contains("订单接口 500"));
    }

    private FeishuTicketAdapter newAdapter(FeishuHelpdeskProperties properties, StubTransport transport) {
        FeishuHelpdeskClient client = FeishuHelpdeskClient.forTesting(properties, objectMapper, transport);
        return new FeishuTicketAdapter(client, properties, FeishuTicketMapper.defaults());
    }

    private FeishuHelpdeskProperties enabledWithWriteBack(boolean writeBack) {
        FeishuHelpdeskProperties properties = new FeishuHelpdeskProperties();
        properties.setEnabled(true);
        properties.setAppId("cli_test");
        properties.setAppSecret("secret");
        properties.setHelpdeskId("hd-1");
        properties.setHelpdeskToken("hd-token");
        properties.getWriteBack().setEnabled(writeBack);
        return properties;
    }

    /**
     * 桩 HTTP 传输：按 FIFO 顺序返回预置响应体。
     *
     * <p>token 请求（{@code /auth/v3/tenant_access_token}）自动返回固定 token，
     * 其他请求按入队顺序消费。
     */
    private static final class StubTransport implements FeishuHelpdeskClient.HttpTransport {
        private final ArrayDeque<FeishuHelpdeskClient.HttpExchange> responses = new ArrayDeque<>();
        private final List<java.net.http.HttpRequest> nonTokenRequests = new ArrayList<>();

        void enqueue(String body) {
            responses.add(new FeishuHelpdeskClient.HttpExchange(200, body));
        }

        @Override
        public FeishuHelpdeskClient.HttpExchange send(java.net.http.HttpRequest request) {
            String url = request.uri().toString();
            if (url.contains("/auth/v3/tenant_access_token")) {
                // 固定返回有效 token，避免每个测试都要 enqueue
                return new FeishuHelpdeskClient.HttpExchange(
                        200, "{\"code\":0,\"tenant_access_token\":\"stub-token\",\"expire\":7200}"
                );
            }
            nonTokenRequests.add(request);
            FeishuHelpdeskClient.HttpExchange exchange = responses.poll();
            if (exchange == null) {
                throw new IllegalStateException("no stubbed response for " + url);
            }
            return exchange;
        }

        private List<java.net.http.HttpRequest> nonTokenRequests() {
            return List.copyOf(nonTokenRequests);
        }
    }
}
