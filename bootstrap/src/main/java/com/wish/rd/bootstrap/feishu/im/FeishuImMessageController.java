package com.wish.rd.bootstrap.feishu.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.engine.ticket.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.TicketEventIngestionEngine;
import com.wish.rd.engine.ticket.TicketEventInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 飞书 IM 消息事件控制器。
 *
 * <p>接收飞书 IM 事件回调，将文本消息转换成本地工单并交给
 * {@link TicketEventIngestionEngine} 入队。控制器只做外部事件适配，不直接调用 RAG 或执行器。
 */
@RestController
@ConditionalOnExpression("'${rd.repair.ticket.provider:mock}' == 'feishu-im' && '${rd.feishu.im.enabled:false}' == 'true'")
public class FeishuImMessageController {

    private static final Logger log = LoggerFactory.getLogger(FeishuImMessageController.class);
    private static final String FEISHU_MESSAGE_RECEIVE_EVENT = "im.message.receive_v1";

    private final ObjectMapper objectMapper;
    private final FeishuImProperties properties;
    private final FeishuImTicketParser parser;
    private final FeishuImTicketStore store;
    private final TicketEventIngestionEngine ingestionEngine;

    public FeishuImMessageController(
            ObjectMapper objectMapper,
            FeishuImProperties properties,
            FeishuImTicketParser parser,
            FeishuImTicketStore store,
            TicketEventIngestionEngine ingestionEngine
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.parser = parser;
        this.store = store;
        this.ingestionEngine = ingestionEngine;
    }

    /**
     * 接收飞书 IM 事件回调。
     *
     * @param rawBody 飞书原始事件体
     * @return URL 校验、忽略结果或入队结果
     */
    @PostMapping("/feishu/im/events")
    public ResponseEntity<Object> receive(@RequestBody String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "empty body"));
        }
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(rawBody);
        } catch (Exception exception) {
            log.warn("feishu im event parse failed: {}", exception.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", "invalid json body"));
        }

        if ("url_verification".equals(envelope.path("type").asText(""))) {
            return ResponseEntity.ok(Map.of("challenge", envelope.path("challenge").asText("")));
        }

        String eventType = envelope.path("header").path("event_type").asText("");
        if (!FEISHU_MESSAGE_RECEIVE_EVENT.equals(eventType)) {
            return ResponseEntity.ok(Map.of("accepted", true, "ignored", true, "eventType", eventType));
        }

        JsonNode event = envelope.path("event");
        JsonNode message = event.path("message");
        if (!"text".equals(message.path("message_type").asText(""))) {
            return ResponseEntity.ok(Map.of("accepted", true, "ignored", true, "reason", "non-text message"));
        }

        boolean groupChat = "group".equals(message.path("chat_type").asText(""));
        JsonNode mentions = message.path("mentions");
        if (properties.isRequireAtMention() && groupChat && (!mentions.isArray() || mentions.isEmpty())) {
            return ResponseEntity.ok(Map.of("accepted", true, "ignored", true, "reason", "bot not mentioned"));
        }

        String text = cleanText(extractText(message.path("content")), mentions);
        if (text.isBlank()) {
            return ResponseEntity.ok(Map.of("accepted", true, "ignored", true, "reason", "empty text"));
        }

        String messageId = message.path("message_id").asText("");
        String ticketId = toTicketId(messageId);
        String chatId = message.path("chat_id").asText("");
        String senderOpenId = event.path("sender").path("sender_id").path("open_id").asText("");
        Instant createdAt = toInstant(envelope.path("header").path("create_time"));
        FeishuImTicketDraft draft = parser.parse(text);
        TicketSnapshot snapshot = store.registerFromMessage(ticketId, chatId, senderOpenId, messageId, text, draft, createdAt);

        TicketEventInput input = new TicketEventInput(
                snapshot.ticketId(),
                envelope.path("header").path("event_id").asText(""),
                TicketEventInput.TYPE_FEISHU_IM_MESSAGE_CREATED,
                FeishuImTicketStore.SOURCE,
                snapshot.priority(),
                "",
                createdAt,
                metadata(chatId, messageId)
        );
        RepairQueuePublishResult result = ingestionEngine.ingest(input);
        return ResponseEntity.ok(Map.of(
                "accepted", true,
                "success", result.success(),
                "messageId", result.messageId(),
                "ticketId", snapshot.ticketId()
        ));
    }

    private String extractText(JsonNode contentNode) {
        String content = contentNode.asText("");
        if (content.isBlank()) {
            return "";
        }
        try {
            return objectMapper.readTree(content).path("text").asText("");
        } catch (Exception exception) {
            return content;
        }
    }

    private static String cleanText(String text, JsonNode mentions) {
        String result = text == null ? "" : text;
        if (mentions != null && mentions.isArray()) {
            for (JsonNode mention : mentions) {
                String key = mention.path("key").asText("");
                if (!key.isBlank()) {
                    result = result.replace(key, "");
                }
            }
        }
        return result.strip();
    }

    private static String toTicketId(String messageId) {
        String safe = messageId == null ? "" : messageId.trim().replaceAll("[^A-Za-z0-9]+", "-");
        safe = safe.replaceAll("^-+", "").replaceAll("-+$", "");
        if (safe.isBlank()) {
            safe = UUID.randomUUID().toString().substring(0, 8);
        }
        return "FI-" + safe;
    }

    private static Instant toInstant(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Instant.now();
        }
        String text = node.asText("");
        try {
            long value = Long.parseLong(text);
            if (value > 1_000_000_000_000L) {
                return Instant.ofEpochMilli(value);
            }
            return Instant.ofEpochSecond(value);
        } catch (Exception ignored) {
            return Instant.now();
        }
    }

    private static Map<String, String> metadata(String chatId, String messageId) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("chatId", chatId == null ? "" : chatId);
        metadata.put("messageId", messageId == null ? "" : messageId);
        return Map.copyOf(metadata);
    }
}
