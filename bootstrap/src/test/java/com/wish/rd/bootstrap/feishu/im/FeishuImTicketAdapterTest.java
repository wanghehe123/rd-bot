package com.wish.rd.bootstrap.feishu.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.adapter.TicketReplyCommand;
import com.wish.rd.adapter.TicketUpdateResult;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证飞书 IM 工单适配器的读取代理与消息回写行为。
 */
class FeishuImTicketAdapterTest {

    @Test
    void shouldNotCallFeishuWhenWriteBackDisabled() {
        FeishuImTicketStore store = seedStore();
        FeishuImProperties properties = properties(false);
        StubTransport transport = new StubTransport();
        FeishuImTicketAdapter adapter = new FeishuImTicketAdapter(
                store,
                new FeishuImClient(properties, new ObjectMapper(), transport),
                properties
        );

        TicketUpdateResult result = adapter.sendMessage(new TicketReplyCommand(
                "FI-om-1", "text", "RAG ready", List.of(), Map.of(), "trace-1", "rr-1"
        ));

        assertFalse(result.success());
        assertEquals("WRITE_BACK_DISABLED", result.providerCode());
        assertEquals(0, transport.requests.size());
    }

    @Test
    void shouldSendTextMessageToOriginalChatWhenWriteBackEnabled() {
        FeishuImTicketStore store = seedStore();
        FeishuImProperties properties = properties(true);
        StubTransport transport = new StubTransport();
        transport.nextJson(200, "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"tenant-token\",\"expire\":7200}");
        transport.nextJson(200, "{\"code\":0,\"msg\":\"ok\",\"data\":{\"message_id\":\"om-reply\"}}");
        FeishuImTicketAdapter adapter = new FeishuImTicketAdapter(
                store,
                new FeishuImClient(properties, new ObjectMapper(), transport),
                properties
        );

        TicketUpdateResult result = adapter.sendMessage(new TicketReplyCommand(
                "FI-om-1", "text", "RAG ready", List.of(), Map.of(), "trace-1", "rr-1"
        ));

        assertTrue(result.success());
        assertEquals("om-reply", result.providerMessageId());
        assertEquals(2, transport.requests.size());
        HttpRequest sendRequest = transport.requests.get(1);
        assertEquals("/open-apis/im/v1/messages?receive_id_type=chat_id", sendRequest.uri().getRawPath()
                + "?" + sendRequest.uri().getRawQuery());
        assertEquals("Bearer tenant-token", sendRequest.headers().firstValue("Authorization").orElse(""));
        String body = transport.bodies.get(1);
        assertTrue(body.contains("\"receive_id\":\"oc-chat\""));
        assertTrue(body.contains("\"msg_type\":\"text\""));
        assertTrue(body.contains("RAG ready"));
        assertFalse(body.contains("app-secret"));
    }

    private static FeishuImTicketStore seedStore() {
        FeishuImTicketStore store = new FeishuImTicketStore();
        FeishuImTicketDraft draft = new FeishuImTicketParser().parse("""
                系统: waimai
                问题: 下单接口 500
                日志: NPE
                仓库: github.com/example/waimai
                """);
        store.registerFromMessage(
                "FI-om-1", "oc-chat", "ou-user", "om-1", draft.rawText(), draft,
                Instant.parse("2026-06-23T10:15:30Z")
        );
        return store;
    }

    private static FeishuImProperties properties(boolean writeBackEnabled) {
        FeishuImProperties properties = new FeishuImProperties();
        properties.setEnabled(true);
        properties.setAppId("cli-test");
        properties.setAppSecret("app-secret");
        properties.getWriteBack().setEnabled(writeBackEnabled);
        return properties;
    }

    private static final class StubTransport implements FeishuImClient.HttpTransport {
        private final ArrayDeque<FeishuImClient.HttpExchange> responses = new ArrayDeque<>();
        private final List<HttpRequest> requests = new java.util.ArrayList<>();
        private final List<String> bodies = new java.util.ArrayList<>();

        private void nextJson(int status, String body) {
            responses.add(new FeishuImClient.HttpExchange(status, body));
        }

        @Override
        public FeishuImClient.HttpExchange send(HttpRequest request, String body) {
            requests.add(request);
            bodies.add(body == null ? "" : body);
            return responses.removeFirst();
        }
    }
}
