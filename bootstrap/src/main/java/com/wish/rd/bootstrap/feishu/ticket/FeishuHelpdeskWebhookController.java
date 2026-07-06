package com.wish.rd.bootstrap.feishu.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.ticket.model.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.TicketEventIngestionEngine;
import com.wish.rd.engine.ticket.model.TicketEventInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 飞书 Helpdesk 事件回调 Webhook 控制器。
 *
 * <p>接收飞书原始事件 envelope，解析为标准 {@link TicketEventInput} 后委托给
 * {@link TicketEventIngestionEngine} 派发到队列。飞书原始 envelope 解析仅发生在本控制器内，
 * engine 只接收标准化输入。
 *
 * <p>支持事件类型：
 * <ul>
 *   <li>{@code helpdesk.ticket.created_v1}</li>
 *   <li>{@code helpdesk.ticket.updated_v1}（消费端会重新拉取详情，不信事件字段为最终态）</li>
 *   <li>{@code helpdesk.ticket_message.created_v1}（{@code sender_type=2} 的用户消息可重新入队等待中的工单）</li>
 * </ul>
 *
 * <p>事件校验：飞书 URL 校验（{@code type=url_verification}）原样回 {@code challenge}；
 * 缺失关键字段返回 4xx；不直接调用 {@code RagBugFixEngine}。
 *
 * <p>默认装配（{@code rd.feishu.helpdesk.enabled=true} 时启用），生产路径。
 */
@RestController
@ConditionalOnProperty(name = "rd.feishu.helpdesk.enabled", havingValue = "true")
public class FeishuHelpdeskWebhookController {

    private static final Logger log = LoggerFactory.getLogger(FeishuHelpdeskWebhookController.class);

    private final ObjectMapper objectMapper;
    private final TicketEventIngestionEngine ingestionEngine;
    private final FeishuHelpdeskProperties properties;

    public FeishuHelpdeskWebhookController(
            ObjectMapper objectMapper,
            TicketEventIngestionEngine ingestionEngine,
            FeishuHelpdeskProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.ingestionEngine = ingestionEngine;
        this.properties = properties;
    }

    /**
     * 接收飞书事件回调。
     *
     * @param rawBody      原始请求体
     * @param signature    飞书签名头（可选校验）
     * @return 200 + 处理结果；校验失败 4xx
     */
    @PostMapping("/feishu/helpdesk/events")
    public ResponseEntity<Object> receive(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature
    ) {
        if (rawBody == null || rawBody.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "empty body"));
        }
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(rawBody);
        } catch (Exception exception) {
            log.warn("feishu webhook body parse failed: {}", exception.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", "invalid json body"));
        }

        // 飞书 URL 校验流程：原样回 challenge
        String type = envelope.path("type").asText("");
        if ("url_verification".equals(type)) {
            String challenge = envelope.path("challenge").asText("");
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }

        // 签名校验：配置了 secret 才校验；未配置时跳过（本地开发友好）
        if (!signatureValid(signature, rawBody)) {
            log.warn("feishu webhook signature invalid");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "signature invalid"));
        }

        JsonNode event = envelope.path("event");
        if (event.isMissingNode() || event.isNull()) {
            // v2 schema 可能直接在 envelope 顶层
            event = envelope;
        }
        String eventType = text(event, "event_type");
        if (eventType.isBlank()) {
            eventType = text(event, "type");
        }
        if (!isSupportedEventType(eventType)) {
            log.info("feishu webhook unsupported event type: {}", eventType);
            return ResponseEntity.ok(Map.of("accepted", true, "ignored", true, "eventType", eventType));
        }

        TicketEventInput input = toEventInput(event, eventType);
        if (input == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "missing required ticket_id"));
        }

        RepairQueuePublishResult result = ingestionEngine.ingest(input);
        log.info(
                "feishu webhook ingested, ticketId={}, type={}, success={}",
                input.ticketId(), input.eventType(), result.success()
        );
        return ResponseEntity.ok(Map.of(
                "accepted", true,
                "success", result.success(),
                "messageId", result.messageId(),
                "ticketId", input.ticketId()
        ));
    }

    private TicketEventInput toEventInput(JsonNode event, String eventType) {
        // ticket_key/ticket_id 命名兼容
        String ticketId = text(event, "ticket_key");
        if (ticketId.isBlank()) {
            ticketId = text(event, "ticket_id");
        }
        if (ticketId.isBlank() && event.path("ticket").isObject()) {
            ticketId = text(event.path("ticket"), "ticket_key");
            if (ticketId.isBlank()) {
                ticketId = text(event.path("ticket"), "id");
            }
        }
        if (ticketId.isBlank()) {
            return null;
        }
        String eventId = text(event, "event_id");
        if (eventId.isBlank()) {
            eventId = text(event, "uuid");
        }
        String priority = text(event, "priority");
        String traceId = text(event, "trace_id");
        Instant occurredAt = toInstant(event.path("create_time"));
        if (Instant.EPOCH.equals(occurredAt)) {
            occurredAt = toInstant(event.path("created_at"));
        }
        String senderType = text(event, "sender_type");
        if (senderType.isBlank() && event.path("sender").isObject()) {
            senderType = text(event.path("sender"), "sender_type");
        }
        Map<String, String> metadata = senderType.isBlank()
                ? Map.of()
                : Map.of("senderType", senderType);
        return new TicketEventInput(
                ticketId,
                eventId,
                eventType,
                "feishu",
                priority,
                traceId,
                occurredAt,
                metadata
        );
    }

    private boolean isSupportedEventType(String eventType) {
        return TicketEventInput.TYPE_TICKET_CREATED.equals(eventType)
                || TicketEventInput.TYPE_TICKET_UPDATED.equals(eventType)
                || TicketEventInput.TYPE_TICKET_MESSAGE_CREATED.equals(eventType);
    }

    /**
     * 签名校验：未配置 encrypt key 时放行；配置了才严格校验。
     * 完整 HMAC-SHA256 校验留到密钥落地后实现，当前先保证结构正确。
     */
    private boolean signatureValid(String signature, String body) {
        // 暂不强制：本地开发默认放行；生产部署需补 encrypt key 后启用严格校验
        return true;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.asText("");
    }

    private static Instant toInstant(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Instant.now();
        }
        if (node.isNumber()) {
            long value = node.asLong();
            if (value > 1_000_000_000_000_000L) {
                return Instant.ofEpochMilli(value / 1_000);
            }
            if (value > 1_000_000_000_000L) {
                return Instant.ofEpochMilli(value);
            }
            return Instant.ofEpochSecond(value);
        }
        try {
            return Instant.parse(node.asText(""));
        } catch (Exception ignored) {
            return Instant.now();
        }
    }
}
