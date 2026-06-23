package com.wish.rd.bootstrap.feishu.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 飞书 Helpdesk v1 低层 HTTP 客户端：基于 JDK {@link HttpClient}，不引入新依赖。
 *
 * <p>覆盖 P1 所需接口：
 * <ul>
 *   <li>获取 tenant_access_token：{@code POST /open-apis/auth/v3/tenant_access_token/internal}</li>
 *   <li>查询工单详情：{@code GET /open-apis/helpdesk/v1/tickets/:ticket_id}</li>
 *   <li>查询工单消息：{@code GET /open-apis/helpdesk/v1/tickets/:ticket_id/messages}</li>
 *   <li>创建服务台对话：{@code POST /open-apis/helpdesk/v1/start_service}</li>
 *   <li>发送消息：{@code POST /open-apis/helpdesk/v1/tickets/:ticket_id/messages}</li>
 *   <li>更新工单：{@code PUT /open-apis/helpdesk/v1/tickets/:ticket_id}</li>
 *   <li>查询自定义字段：{@code GET /open-apis/helpdesk/v1/customized_fields}</li>
 * </ul>
 *
 * <p>所有请求附带 {@code Authorization: Bearer <token>} 与 {@code X-Lark-Helpdesk-Authorization}。
 * 错误响应抛 {@link FeishuHelpdeskException}，异常消息仅包含 HTTP 状态与业务 code，不含 token。
 */
@Component
@ConditionalOnProperty(name = "rd.feishu.helpdesk.enabled", havingValue = "true")
public class FeishuHelpdeskClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuHelpdeskClient.class);

    private final FeishuHelpdeskProperties properties;
    private final FeishuHelpdeskAuth auth;
    private final ObjectMapper objectMapper;
    private final HttpTransport transport;
    private final int requestTimeoutMillis;

    /** 缓存的 tenant_access_token 与过期时间。 */
    private volatile String cachedTenantAccessToken = "";
    private volatile long tokenExpiresAtMillis = 0L;

    @Autowired
    public FeishuHelpdeskClient(
            FeishuHelpdeskProperties properties,
            ObjectMapper objectMapper,
            @Value("${rd.feishu.helpdesk.http.connect-timeout-millis:3000}") int connectTimeoutMillis,
            @Value("${rd.feishu.helpdesk.http.request-timeout-millis:10000}") int requestTimeoutMillis
    ) {
        this.properties = properties;
        this.auth = FeishuHelpdeskAuth.from(properties);
        this.objectMapper = objectMapper;
        this.requestTimeoutMillis = Math.max(1000, requestTimeoutMillis);
        int connectTimeout = Math.max(500, connectTimeoutMillis);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();
        this.transport = defaultTransport(httpClient);
    }

    /** 测试可见构造：注入自定义 HttpTransport（用于桩测试）。 */
    FeishuHelpdeskClient(
            FeishuHelpdeskProperties properties,
            ObjectMapper objectMapper,
            HttpTransport transport
    ) {
        this.properties = properties;
        this.auth = FeishuHelpdeskAuth.from(properties);
        this.objectMapper = objectMapper;
        this.transport = transport;
        this.requestTimeoutMillis = 10_000;
    }

    /**
     * 测试/直接装配入口：注入自定义 HTTP 传输。
     *
     * @param properties   配置
     * @param objectMapper JSON 序列化
     * @param transport    HTTP 传输桩
     * @return 客户端实例
     */
    public static FeishuHelpdeskClient forTesting(
            FeishuHelpdeskProperties properties,
            ObjectMapper objectMapper,
            HttpTransport transport
    ) {
        return new FeishuHelpdeskClient(properties, objectMapper, transport);
    }

    /**
     * HTTP 传输抽象：便于在生产用 JDK HttpClient，在测试用桩实现。
     */
    @FunctionalInterface
    public interface HttpTransport {

        /**
         * 发送请求并返回状态码与响应体。
         *
         * @param request 已构造完成的 HTTP 请求
         * @return 状态码与响应体
         * @throws Exception 传输异常
         */
        HttpExchange send(HttpRequest request) throws Exception;
    }

    /** HTTP 交换结果。 */
    public record HttpExchange(int statusCode, String body) {
    }

    private static HttpTransport defaultTransport(HttpClient httpClient) {
        return request -> {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpExchange(response.statusCode(), response.body() == null ? "" : response.body());
        };
    }

    /**
     * 获取 tenant_access_token（带本地缓存）。
     *
     * @return tenant_access_token
     */
    public synchronized String tenantAccessToken() {
        long now = System.currentTimeMillis();
        // 提前 60 秒过期，避免边界问题
        if (!cachedTenantAccessToken.isBlank() && now < tokenExpiresAtMillis - 60_000L) {
            return cachedTenantAccessToken;
        }
        String url = properties.getBaseUrl() + "/open-apis/auth/v3/tenant_access_token/internal";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app_id", properties.getAppId());
        body.put("app_secret", properties.getAppSecret());
        JsonNode response = postJson(url, toJson(body), false);
        String token = response.path("tenant_access_token").asText("");
        int expire = response.path("expire").asInt(7200);
        if (token.isBlank()) {
            throw new FeishuHelpdeskException("failed to fetch tenant_access_token: empty token");
        }
        cachedTenantAccessToken = token;
        tokenExpiresAtMillis = now + expire * 1000L;
        return token;
    }

    /**
     * 查询工单详情。
     *
     * @param ticketId 工单 ID
     * @return 工单原始 JSON 节点；不存在返回 empty
     */
    public Optional<JsonNode> getTicket(String ticketId) {
        String url = properties.getBaseUrl() + "/open-apis/helpdesk/v1/tickets/" + urlEncode(ticketId);
        JsonNode response = getHelpdeskJson(url);
        JsonNode data = response.path("data").path("ticket");
        if (data.isMissingNode() || data.isNull()) {
            return Optional.empty();
        }
        return Optional.of(data);
    }

    /**
     * 查询工单消息列表。
     *
     * @param ticketId 工单 ID
     * @param page     页码（从 1 开始）
     * @param pageSize 页大小
     * @return 消息列表 JSON 节点
     */
    public JsonNode listMessages(String ticketId, int page, int pageSize) {
        String url = properties.getBaseUrl()
                + "/open-apis/helpdesk/v1/tickets/" + urlEncode(ticketId)
                + "/messages?page=" + Math.max(1, page)
                + "&page_size=" + Math.max(1, pageSize);
        JsonNode response = getHelpdeskJson(url);
        return response.path("data").path("items");
    }

    /**
     * 创建服务台对话。
     *
     * <p>飞书仅在进入人工工单时通常返回 {@code ticket_id}；如果只创建机器人对话，
     * 响应可能只有 {@code chat_id}。
     *
     * @param command 创建服务台对话命令
     * @return 创建结果
     */
    public FeishuStartServiceResult startService(FeishuStartServiceCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        String url = properties.getBaseUrl() + "/open-apis/helpdesk/v1/start_service";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("open_id", command.openId());
        body.put("human_service", command.humanService());
        if (!command.appointedAgents().isEmpty()) {
            body.put("appointed_agents", command.appointedAgents());
        }
        if (!command.customizedInfo().isBlank()) {
            body.put("customized_info", command.customizedInfo());
        }
        JsonNode response = postJson(url, toJson(body), true);
        JsonNode data = response.path("data");
        return new FeishuStartServiceResult(
                data.path("chat_id").asText(""),
                data.path("ticket_id").asText(""),
                Map.of("provider", "feishu-helpdesk")
        );
    }

    /**
     * 发送消息到工单会话。
     *
     * @param ticketId    工单 ID
     * @param messageType 消息类型（text/post 等）
     * @param content     消息内容（text 类型为纯文本；post 类型为 JSON 字符串）
     * @return 提供方返回的 message_id
     */
    public String sendMessage(String ticketId, String messageType, String content) {
        String url = properties.getBaseUrl()
                + "/open-apis/helpdesk/v1/tickets/" + urlEncode(ticketId) + "/messages";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msg_type", messageType);
        body.put("content", content);
        JsonNode response = postJson(url, toJson(body), true);
        return response.path("data").path("message_id").asText("");
    }

    /**
     * 更新工单（状态/标签/自定义字段）。
     *
     * @param ticketId 工单 ID
     * @param patch    更新字段 Map
     * @return 是否成功
     */
    public boolean updateTicket(String ticketId, Map<String, Object> patch) {
        String url = properties.getBaseUrl()
                + "/open-apis/helpdesk/v1/tickets/" + urlEncode(ticketId);
        Map<String, Object> body = new LinkedHashMap<>(patch);
        if (!properties.getStaffId().isBlank()) {
            body.put("staff_id", properties.getStaffId());
        }
        JsonNode response = putJson(url, toJson(body));
        int code = response.path("code").asInt(-1);
        return code == 0;
    }

    /**
     * 查询自定义字段定义。
     *
     * @return 自定义字段列表 JSON 节点
     */
    public JsonNode listCustomFields() {
        String url = properties.getBaseUrl() + "/open-apis/helpdesk/v1/customized_fields";
        JsonNode response = getHelpdeskJson(url);
        JsonNode data = response.path("data");
        JsonNode ticketFields = data.path("ticket_customized_fields");
        if (ticketFields.isArray()) {
            return ticketFields;
        }
        JsonNode userFields = data.path("user_customized_fields");
        if (userFields.isArray()) {
            return userFields;
        }
        return data;
    }

    private JsonNode getJson(String url) {
        HttpRequest request = baseRequest(url, "GET", null);
        return sendAndParse(request);
    }

    private JsonNode getHelpdeskJson(String url) {
        HttpRequest request = withHelpdeskAuth(baseRequest(url, "GET", null));
        return sendAndParse(request);
    }

    private JsonNode postJson(String url, String body, boolean withHelpdeskAuth) {
        HttpRequest request = baseRequest(url, "POST", body);
        if (withHelpdeskAuth) {
            request = withHelpdeskAuth(request);
        }
        return sendAndParse(request);
    }

    private JsonNode putJson(String url, String body) {
        HttpRequest request = withHelpdeskAuth(baseRequest(url, "PUT", body));
        return sendAndParse(request);
    }

    private HttpRequest baseRequest(String url, String method, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(requestTimeoutMillis))
                .header("Content-Type", "application/json; charset=UTF-8");
        if (!url.contains("/auth/v3/tenant_access_token")) {
            // token 接口不需要 Bearer
            builder.header("Authorization", "Bearer " + tenantAccessToken());
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        builder.method(method, publisher);
        return builder.build();
    }

    private HttpRequest withHelpdeskAuth(HttpRequest request) {
        return HttpRequest.newBuilder(request, (name, value) -> true)
                .header("X-Lark-Helpdesk-Authorization", auth.helpdeskAuthorizationHeader())
                .build();
    }

    private JsonNode sendAndParse(HttpRequest request) {
        try {
            HttpExchange response = transport.send(request);
            int status = response.statusCode();
            String raw = response.body() == null ? "" : response.body();
            if (status < 200 || status >= 300) {
                throw httpError(status, raw);
            }
            JsonNode node = objectMapper.readTree(raw);
            int code = node.path("code").asInt(0);
            if (code != 0) {
                // 飞书业务错误：只暴露 code + msg，不含 token
                throw new FeishuHelpdeskException("feishu api error: code=" + code + ", msg=" + node.path("msg").asText(""));
            }
            return node;
        } catch (FeishuHelpdeskException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new FeishuHelpdeskException("feishu request failed: " + exception.getClass().getSimpleName(), exception);
        }
    }

    private FeishuHelpdeskException httpError(int status, String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw == null ? "" : raw);
            int code = node.path("code").asInt(-1);
            String msg = node.path("msg").asText("");
            if (code != -1 || !msg.isBlank()) {
                return new FeishuHelpdeskException(
                        "feishu http error: status=" + status + ", code=" + code + ", msg=" + redact(msg)
                );
            }
        } catch (Exception ignored) {
            // Fall back to a redacted body snippet when the provider did not return JSON.
        }
        return new FeishuHelpdeskException("feishu http error: status=" + status + ", body=" + redact(raw));
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            throw new FeishuHelpdeskException("failed to serialize request body", exception);
        }
    }

    /** 简单 URL 参数编码（仅工单 ID 场景）。 */
    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 脱敏响应体：截断长度，剔除可能的 token 字段。 */
    private static String redact(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 256 ? body : body.substring(0, 256);
    }

    FeishuHelpdeskAuth auth() {
        return auth;
    }

    /**
     * 飞书 Helpdesk 异常：消息不含 token，仅含 HTTP/业务错误码。
     */
    public static final class FeishuHelpdeskException extends RuntimeException {
        public FeishuHelpdeskException(String message) {
            super(message);
        }

        public FeishuHelpdeskException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
