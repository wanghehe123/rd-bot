package com.wish.rd.bootstrap.feishu.im.model;

import java.util.Map;

/**
 * 飞书 IM 文本解析后的内部工单草稿。
 *
 * <p>该值对象只在 bootstrap 的飞书 IM 适配层使用，用于把用户消息转换成
 * {@code TicketSnapshot} 前暂存标题、描述、优先级与标准自定义字段。
 *
 * @param title        工单标题，缺省空串
 * @param description  工单描述，缺省空串
 * @param priority     归一化优先级，缺省 {@code P2}
 * @param customFields 标准自定义字段，缺省空 Map
 * @param rawText      原始文本，缺省空串
 */
public record FeishuImTicketDraft(
        String title,
        String description,
        String priority,
        Map<String, String> customFields,
        String rawText
) {

    public FeishuImTicketDraft {
        title = safe(title);
        description = safe(description);
        priority = normalizePriority(priority);
        customFields = customFields == null ? Map.of() : Map.copyOf(customFields);
        rawText = safe(rawText);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizePriority(String priority) {
        String value = safe(priority).toUpperCase();
        return switch (value) {
            case "P0", "P1", "P2" -> value;
            default -> "P2";
        };
    }
}
