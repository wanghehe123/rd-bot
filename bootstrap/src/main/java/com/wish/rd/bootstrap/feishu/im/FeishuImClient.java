package com.wish.rd.bootstrap.feishu.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 飞书 IM 低层 HTTP 客户端。
 *
 * <p>负责获取 tenant_access_token 并调用 IM 发消息接口。该类只在 bootstrap
 * 适配层使用，错误消息不包含 app secret 或 tenant token。
 */
@Component
@ConditionalOnProperty(prefix = "rd.feishu.im", name = "enabled", havingValue = "true")
public class FeishuImClient {

    private final FeishuImProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpTransport transport;

    @Autowired
    public FeishuImClient(FeishuImProperties properties, ObjectMapper objectMapper) {
        this(
                properties,
                objectMapper,
                new JdkHttpTransport(properties == null ? new FeishuImProperties() : properties)
        );
    }

    FeishuImClient(FeishuImProperties properties, ObjectMapper objectMapper, HttpTransport transport) {
        this.properties = properties == null ? new FeishuImProperties() : properties;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.transport = transport;
    }

    /**
     * 向飞书会话发送文本消息。
     *
     * @param chatId 飞书 chat_id
     * @param text   文本内容
     * @return 飞书消息发送结果
     */
    public FeishuImSendResult sendTextMessage(String chatId, String text) {
        return sendTextMessage("chat_id", chatId, text);
    }

    /**
     * Sends text to a supported Feishu recipient identity.
     *
     * @param receiveIdType {@code chat_id} or {@code open_id}
     * @param receiveId     recipient id
     * @param text          text body
     * @return provider result
     */
    public FeishuImSendResult sendTextMessage(String receiveIdType, String receiveId, String text) {
        return sendTextMessage(receiveIdType, receiveId, text, "");
    }

    /** Sends text with an optional provider idempotency UUID. */
    public FeishuImSendResult sendTextMessage(
            String receiveIdType,
            String receiveId,
            String text,
            String idempotencyUuid
    ) {
        String safeType = receiveIdType == null ? "" : receiveIdType.strip().toLowerCase(java.util.Locale.ROOT);
        if (!safeType.equals("chat_id") && !safeType.equals("open_id")) {
            return FeishuImSendResult.failure("INVALID_RECEIVE_ID_TYPE", "receiveIdType must be chat_id or open_id");
        }
        if (receiveId == null || receiveId.isBlank()) {
            return FeishuImSendResult.failure("INVALID_RECEIVE_ID", "receiveId must not be blank");
        }
        String token = tenantAccessToken();
        String url = properties.getBaseUrl()
                + "/open-apis/im/v1/messages?receive_id_type="
                + URLEncoder.encode(safeType, StandardCharsets.UTF_8);
        String contentJson = toJson(Map.of("text", text == null ? "" : text));
        Map<String, String> payload = new java.util.LinkedHashMap<>();
        payload.put("receive_id", receiveId);
        payload.put("msg_type", "text");
        payload.put("content", contentJson);
        if (idempotencyUuid != null && !idempotencyUuid.isBlank()) {
            payload.put("uuid", idempotencyUuid.strip());
        }
        String body = toJson(payload);
        HttpRequest request = baseRequest(url, "POST", body)
                .header("Authorization", "Bearer " + token)
                .build();
        JsonNode response = sendJson(request, body);
        String messageId = response.path("data").path("message_id").asText("");
        return FeishuImSendResult.success(messageId);
    }

    private String tenantAccessToken() {
        String url = properties.getBaseUrl() + "/open-apis/auth/v3/tenant_access_token/internal";
        String body = toJson(Map.of(
                "app_id", properties.getAppId(),
                "app_secret", properties.getAppSecret()
        ));
        HttpRequest request = baseRequest(url, "POST", body).build();
        JsonNode response = sendJson(request, body);
        String token = response.path("tenant_access_token").asText("");
        if (token.isBlank()) {
            throw new FeishuImException("failed to fetch tenant_access_token: empty token");
        }
        return token;
    }

    private HttpRequest.Builder baseRequest(String url, String method, String body) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(properties.getHttp().getRequestTimeoutMillis()))
                .header("Content-Type", "application/json; charset=utf-8")
                .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
    }

    private JsonNode sendJson(HttpRequest request, String body) {
        try {
            HttpExchange exchange = transport.send(request, body);
            if (exchange.statusCode() < 200 || exchange.statusCode() >= 300) {
                throw new FeishuImException("feishu im http error: status=" + exchange.statusCode());
            }
            JsonNode node = objectMapper.readTree(exchange.body());
            int code = node.path("code").asInt(0);
            if (code != 0) {
                throw new FeishuImException("feishu im api error: code=" + code + ", msg=" + node.path("msg").asText(""));
            }
            return node;
        } catch (FeishuImException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new FeishuImException("feishu im request failed: " + exception.getClass().getSimpleName(), exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new FeishuImException("failed to serialize feishu im request", exception);
        }
    }

    /**
     * HTTP 传输抽象，测试中用于捕获请求。
     */
    @FunctionalInterface
    interface HttpTransport {
        HttpExchange send(HttpRequest request, String body) throws Exception;
    }

    /**
     * HTTP 响应。
     *
     * @param statusCode HTTP 状态码
     * @param body       响应体
     */
    record HttpExchange(int statusCode, String body) {
        HttpExchange {
            body = body == null ? "" : body;
        }
    }

    /**
     * 飞书 IM 发消息结果。
     *
     * @param success   是否成功
     * @param messageId 飞书消息 ID
     * @param code      错误码
     * @param message   错误摘要
     */
    public record FeishuImSendResult(boolean success, String messageId, String code, String message) {

        public FeishuImSendResult {
            messageId = messageId == null ? "" : messageId;
            code = code == null ? "" : code;
            message = message == null ? "" : message;
        }

        public static FeishuImSendResult success(String messageId) {
            return new FeishuImSendResult(true, messageId, "0", "ok");
        }

        public static FeishuImSendResult failure(String code, String message) {
            return new FeishuImSendResult(false, "", code, message);
        }
    }

    /**
     * 飞书 IM 客户端异常。
     */
    public static final class FeishuImException extends RuntimeException {
        FeishuImException(String message) {
            super(message);
        }

        FeishuImException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class JdkHttpTransport implements HttpTransport {
        private final HttpClient client;

        private JdkHttpTransport(FeishuImProperties properties) {
            this.client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(properties.getHttp().getConnectTimeoutMillis()))
                    .build();
        }

        @Override
        public HttpExchange send(HttpRequest request, String body) throws Exception {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpExchange(response.statusCode(), response.body());
        }
    }
}
