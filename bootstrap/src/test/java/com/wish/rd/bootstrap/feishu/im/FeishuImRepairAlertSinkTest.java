package com.wish.rd.bootstrap.feishu.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.alert.model.RepairAlert;
import com.wish.rd.exec.repair.alert.model.RepairAlertType;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuImRepairAlertSinkTest {

    @Test
    void shouldSendRedactedRepairAlertToConfiguredFeishuChatAndKeepLocalSnapshot() {
        FeishuImProperties properties = properties();
        StubTransport transport = new StubTransport();
        transport.nextJson(200, "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"tenant-token\",\"expire\":7200}");
        transport.nextJson(200, "{\"code\":0,\"msg\":\"ok\",\"data\":{\"message_id\":\"om-alert-1\"}}");
        FeishuImRepairAlertSink sink = new FeishuImRepairAlertSink(
                new FeishuImClient(properties, new ObjectMapper(), transport),
                properties
        );

        sink.publish(new RepairAlert(
                "stage-1",
                "task-1",
                RepairAlertType.QA_FAILED,
                "QA failed password=secret-value",
                Map.of(
                        "stageRunId", "stage-1",
                        "nextAction", "人工复核 QA 日志",
                        "artifactUrl", "https://example.test/artifacts/qa-report.json",
                        "apiToken", "secret-value"
                ),
                1_788_201_600_000L
        ));

        assertEquals(1, sink.alerts().size());
        assertEquals(1, sink.deliveryAttempts().size());
        assertTrue(sink.deliveryAttempts().getFirst().success());
        assertEquals("om-alert-1", sink.deliveryAttempts().getFirst().messageId());
        assertEquals(2, transport.requests.size());
        String requestBody = transport.bodies.get(1);
        assertTrue(requestBody.contains("\"receive_id\":\"oc-alert\""));
        assertTrue(requestBody.contains("QA_FAILED"));
        assertTrue(requestBody.contains("task-1"));
        assertTrue(requestBody.contains("stage-1"));
        assertTrue(requestBody.contains("人工复核 QA 日志"));
        assertTrue(requestBody.contains("qa-report.json"));
        assertTrue(requestBody.contains("[REDACTED]"));
        assertFalse(requestBody.contains("secret-value"));
        assertFalse(requestBody.contains("app-secret"));
    }

    @Test
    void shouldRecordFailedDeliveryAttemptWithoutThrowingWhenFeishuSendFails() {
        FeishuImProperties properties = properties();
        StubTransport transport = new StubTransport();
        transport.nextJson(200, "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"tenant-token\",\"expire\":7200}");
        transport.nextJson(200, "{\"code\":999,\"msg\":\"permission denied\"}");
        FeishuImRepairAlertSink sink = new FeishuImRepairAlertSink(
                new FeishuImClient(properties, new ObjectMapper(), transport),
                properties
        );

        assertDoesNotThrow(() -> sink.publish(new RepairAlert(
                "stage-2",
                "task-2",
                RepairAlertType.DELIVERY_REVIEW_FAILED,
                "delivery review failed",
                Map.of("nextAction", "人工处理"),
                1_788_201_600_000L
        )));

        assertEquals(1, sink.alerts().size());
        assertEquals(1, sink.deliveryAttempts().size());
        assertFalse(sink.deliveryAttempts().getFirst().success());
        assertEquals("FEISHU_IM_ERROR", sink.deliveryAttempts().getFirst().code());
        assertTrue(sink.deliveryAttempts().getFirst().message().contains("code=999"));
    }

    private static FeishuImProperties properties() {
        FeishuImProperties properties = new FeishuImProperties();
        properties.setEnabled(true);
        properties.setAppId("cli-test");
        properties.setAppSecret("app-secret");
        properties.getAlert().setEnabled(true);
        properties.getAlert().setChatId("oc-alert");
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
