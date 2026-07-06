package com.wish.rd.adapter.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 工单快照，作为 {@link TicketProviderPort#findTicket(String)} 的返回值。
 *
 * <p>承载工单的核心信息，供后续基于工单的修复流程使用。该 record 是 RD-Bot 的标准工单
 * 形状，承载从各工单提供方（飞书 Helpdesk / 其他）归一化后的字段，不包含任何厂商专属 DTO。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code priority}：归一化优先级，{@code P0}/{@code P1}/{@code P2}，缺省 {@code P2}。</li>
 *   <li>{@code status}/{@code stage}：工单提供方原始状态/阶段字符串，用于生命周期判断（如是否已关闭）。</li>
 *   <li>{@code source}：来源渠道，例如 {@code feishu}，用于路由与回写。</li>
 *   <li>{@code chatId}：会话/聊天 ID，用于向用户回复消息。</li>
 *   <li>{@code customFields}：工单自定义字段映射，key 为字段名、value 为字符串值。</li>
 * </ul>
 *
 * @param ticketId      工单 ID
 * @param title         工单标题
 * @param description   工单描述（修复请求的主要来源）
 * @param labels        工单标签（可用于辅助意图分类）
 * @param createdAt     工单创建时间
 * @param priority      优先级 {@code P0}/{@code P1}/{@code P2}，缺省 {@code P2}
 * @param status        工单提供方原始状态字符串，缺省 {@code ""}
 * @param stage         工单提供方原始阶段字符串，缺省 {@code ""}
 * @param source        来源渠道（如 {@code feishu}），缺省 {@code ""}
 * @param chatId        会话/聊天 ID，缺省 {@code ""}
 * @param customFields  自定义字段映射，缺省空 Map
 * @param updatedAt     最近更新时间，缺省 {@link Instant#EPOCH}
 * @param closedAt      关闭时间，缺省 {@link Instant#EPOCH}
 */
public record TicketSnapshot(
        String ticketId,
        String title,
        String description,
        List<String> labels,
        Instant createdAt,
        String priority,
        String status,
        String stage,
        String source,
        String chatId,
        Map<String, String> customFields,
        Instant updatedAt,
        Instant closedAt
) {

    /** 默认优先级，当提供方未给出明确优先级时使用。 */
    public static final String DEFAULT_PRIORITY = "P2";

    public TicketSnapshot {
        ticketId = safe(ticketId);
        title = safe(title);
        description = safe(description);
        labels = labels == null ? List.of() : List.copyOf(labels);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        priority = normalizePriority(priority);
        status = safe(status);
        stage = safe(stage);
        source = safe(source);
        chatId = safe(chatId);
        customFields = customFields == null ? Map.of() : Map.copyOf(customFields);
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
        closedAt = closedAt == null ? Instant.EPOCH : closedAt;
    }

    /**
     * 兼容旧调用点的五参构造入口，补齐标准字段的默认值。
     *
     * @param ticketId    工单 ID
     * @param title       工单标题
     * @param description 工单描述
     * @param labels      工单标签
     * @param createdAt   工单创建时间
     * @return 带默认标准字段的工单快照
     */
    public TicketSnapshot(String ticketId, String title, String description, List<String> labels, Instant createdAt) {
        this(ticketId, title, description, labels, createdAt, DEFAULT_PRIORITY, "", "", "", "", Map.of(), Instant.EPOCH, Instant.EPOCH);
    }

    /**
     * 判断工单是否已关闭（关闭时间非空 或 状态命中常见关闭枚举）。
     *
     * @return 已关闭返回 true
     */
    public boolean isClosed() {
        if (!Instant.EPOCH.equals(closedAt)) {
            return true;
        }
        String normalized = status == null ? "" : status.trim().toLowerCase();
        return normalized.equals("closed") || normalized.equals("resolved") || normalized.equals("finished");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalizePriority(String priority) {
        if (priority == null) {
            return DEFAULT_PRIORITY;
        }
        String upper = priority.trim().toUpperCase();
        return switch (upper) {
            case "P0", "P1", "P2" -> upper;
            default -> DEFAULT_PRIORITY;
        };
    }
}
