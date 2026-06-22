package com.wish.rd.bootstrap.feishu.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.wish.rd.adapter.TicketMessage;
import com.wish.rd.adapter.TicketMessageQuery;
import com.wish.rd.adapter.TicketMessages;
import com.wish.rd.adapter.TicketSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞书 Helpdesk DTO 到标准工单 record 的映射器。
 *
 * <p>把 Helpdesk 原始 JSON 节点映射成 {@link TicketSnapshot}/{@link TicketMessage}，
 * 隔离厂商专属字段名，使 engine 层只依赖标准形状。自定义字段以 {@code field_id -> value}
 * 或 {@code field_name -> value} 两种形式落到 {@code customFields}。
 *
 * <p>飞书 Helpdesk 状态映射：
 * <ul>
 *   <li>{@code status}: {@code 99}=待受理 / {@code 100}=处理中 / {@code 200}=已解决 / {@code 300}=已关闭</li>
 *   <li>映射到标准 {@code status} 字符串：{@code pending}/{@code processing}/{@code solved}/{@code closed}</li>
 * </ul>
 */
public final class FeishuTicketMapper {

    /** 飞书 Helpdesk 状态码到标准状态字符串。 */
    private static final Map<Integer, String> STATUS_BY_CODE = Map.of(
            99, "pending",
            100, "processing",
            200, "solved",
            300, "closed"
    );

    private final Map<String, String> customFieldNameById;

    /**
     * @param customFieldNameById 飞书自定义字段 ID -> 展示名 映射；缺省时退化为 ID
     */
    public FeishuTicketMapper(Map<String, String> customFieldNameById) {
        this.customFieldNameById = customFieldNameById == null ? Map.of() : Map.copyOf(customFieldNameById);
    }

    /**
     * 创建默认映射器（自定义字段按 ID 直出）。
     *
     * @return 默认映射器
     */
    public static FeishuTicketMapper defaults() {
        return new FeishuTicketMapper(Map.of());
    }

    /**
     * 把 Helpdesk ticket JSON 节点映射成 {@link TicketSnapshot}。
     *
     * @param ticketId 工单 ID
     * @param node     Helpdesk ticket JSON 节点
     * @return 标准工单快照
     */
    public TicketSnapshot toSnapshot(String ticketId, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new TicketSnapshot(ticketId, "", "", List.of(), Instant.EPOCH);
        }
        String title = text(node, "title");
        String description = text(node, "desc");
        if (description.isBlank()) {
            description = text(node, "description");
        }
        List<String> labels = toStringList(node.path("tags_info"));
        if (labels.isEmpty()) {
            labels = toStringList(node.path("tags"));
        }
        int statusCode = node.path("status").asInt(0);
        String status = STATUS_BY_CODE.getOrDefault(statusCode, "");
        String stage = text(node, "stage");
        String chatId = text(node, "chat_id");
        if (chatId.isBlank()) {
            chatId = text(node, "external_chat_id");
        }
        Map<String, String> custom = toCustomFields(node.path("custom_fields"));
        String priority = custom.getOrDefault("priority", guessPriority(labels, statusCode));
        Instant createdAt = toInstant(node.path("created_at"));
        Instant updatedAt = toInstant(node.path("updated_at"));
        Instant closedAt = toInstant(node.path("closed_at"));
        if (Instant.EPOCH.equals(closedAt) && ("closed".equals(status) || "solved".equals(status))) {
            // 已关闭但未给 closedAt：用 updatedAt 兜底，使 TicketSnapshot.isClosed() 可判定
            closedAt = updatedAt;
        }
        return new TicketSnapshot(
                ticketId,
                title,
                description,
                labels,
                createdAt,
                priority,
                status,
                stage,
                "feishu",
                chatId,
                custom,
                updatedAt,
                closedAt
        );
    }

    /**
     * 把 Helpdesk messages JSON 数组映射成分页 {@link TicketMessages}。
     *
     * @param ticketId 工单 ID
     * @param items    消息 JSON 数组节点
     * @param query    查询条件
     * @param total    总数
     * @return 消息分页
     */
    public TicketMessages toMessages(String ticketId, JsonNode items, TicketMessageQuery query, long total) {
        List<TicketMessage> messages = new ArrayList<>();
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                TicketMessage message = toMessage(item);
                if (matchesQuery(message, query)) {
                    messages.add(message);
                }
            }
        }
        return new TicketMessages(messages, query.page(), query.pageSize(), total, false);
    }

    /**
     * 把单条 Helpdesk message JSON 映射成 {@link TicketMessage}。
     *
     * @param item 消息 JSON 节点
     * @return 标准消息
     */
    public TicketMessage toMessage(JsonNode item) {
        if (item == null || item.isMissingNode() || item.isNull()) {
            return new TicketMessage("", "", "", "", "", List.of(), Map.of(), Instant.EPOCH);
        }
        String messageId = text(item, "message_id");
        if (messageId.isBlank()) {
            messageId = text(item, "id");
        }
        String senderType = item.path("sender_type").asText("");
        String senderId = text(item, "sender_id");
        if (senderId.isBlank()) {
            senderId = text(item, "from_id");
        }
        String messageType = text(item, "msg_type");
        if (messageType.isBlank()) {
            messageType = text(item, "message_type");
        }
        String content = normalizeMessageContent(item, messageType);
        List<String> attachments = toStringList(item.path("attachments"));
        Instant createdAt = toInstant(item.path("created_at"));
        Map<String, String> metadata = Map.of(
                "feishuMessageId", messageId,
                "senderType", senderType
        );
        return new TicketMessage(messageId, senderType, senderId, messageType, content, attachments, metadata, createdAt);
    }

    private String normalizeMessageContent(JsonNode item, String messageType) {
        // text 类型直接取 content；post 类型扁平化为可读文本
        if ("post".equalsIgnoreCase(messageType)) {
            JsonNode content = item.path("content");
            if (!content.isMissingNode() && !content.isNull()) {
                return flattenPost(content);
            }
        }
        String content = text(item, "content");
        if (content.isBlank()) {
            content = text(item, "text");
        }
        return content;
    }

    private String flattenPost(JsonNode postNode) {
        // post 格式：{"zh_cn":{"title":"...","content":[[{"tag":"text","text":"..."}]]}}
        StringBuilder builder = new StringBuilder();
        JsonNode locale = postNode.path("zh_cn");
        if (locale.isMissingNode()) {
            locale = postNode;
        }
        String title = text(locale, "title");
        if (!title.isBlank()) {
            builder.append(title).append("\n");
        }
        JsonNode content = locale.path("content");
        if (content.isArray()) {
            for (JsonNode paragraph : content) {
                if (paragraph.isArray()) {
                    for (JsonNode node : paragraph) {
                        String text = text(node, "text");
                        if (!text.isBlank()) {
                            builder.append(text);
                        }
                    }
                    builder.append("\n");
                }
            }
        }
        return builder.toString().strip();
    }

    private Map<String, String> toCustomFields(JsonNode customFieldsNode) {
        Map<String, String> result = new LinkedHashMap<>();
        if (customFieldsNode == null || !customFieldsNode.isArray()) {
            return result;
        }
        for (JsonNode field : customFieldsNode) {
            String fieldId = text(field, "field_id");
            if (fieldId.isBlank()) {
                fieldId = text(field, "id");
            }
            String value = text(field, "value");
            String name = customFieldNameById.getOrDefault(fieldId, fieldId);
            result.put(name, value);
        }
        return result;
    }

    private boolean matchesQuery(TicketMessage message, TicketMessageQuery query) {
        if (query == null) {
            return true;
        }
        if (!query.senderType().isBlank() && !query.senderType().equals(message.senderType())) {
            return false;
        }
        if (!Instant.EPOCH.equals(query.fromTime()) && message.createdAt().isBefore(query.fromTime())) {
            return false;
        }
        if (!Instant.EPOCH.equals(query.toTime()) && message.createdAt().isAfter(query.toTime())) {
            return false;
        }
        return true;
    }

    private String guessPriority(List<String> labels, int statusCode) {
        // 标签里含 P0/P1/P2 时优先取用；否则按状态默认 P2
        for (String label : labels) {
            String upper = label == null ? "" : label.toUpperCase();
            if (upper.equals("P0") || upper.equals("P1") || upper.equals("P2")) {
                return upper;
            }
        }
        return "P2";
    }

    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        return node.toString();
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode element : node) {
            if (element.isObject()) {
                String name = text(element, "name");
                if (!name.isBlank()) {
                    result.add(name);
                } else {
                    result.add(text(element, "id"));
                }
            } else if (!element.asText("").isBlank()) {
                result.add(element.asText());
            }
        }
        return result;
    }

    private static Instant toInstant(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Instant.EPOCH;
        }
        if (node.isNumber()) {
            // 飞书时间戳通常是秒级
            long value = node.asLong();
            if (value > 1_000_000_000_000_000L) {
                // 微秒
                return Instant.ofEpochMilli(value / 1_000);
            }
            if (value > 1_000_000_000_000L) {
                // 毫秒
                return Instant.ofEpochMilli(value);
            }
            // 秒
            return Instant.ofEpochSecond(value);
        }
        String text = node.asText("");
        if (text.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
            return Instant.EPOCH;
        }
    }
}
