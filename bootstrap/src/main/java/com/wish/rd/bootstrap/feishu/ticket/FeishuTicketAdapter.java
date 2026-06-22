package com.wish.rd.bootstrap.feishu.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.wish.rd.adapter.TicketMessageQuery;
import com.wish.rd.adapter.TicketMessages;
import com.wish.rd.adapter.TicketProviderPort;
import com.wish.rd.adapter.TicketReplyCommand;
import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.adapter.TicketUpdateCommand;
import com.wish.rd.adapter.TicketUpdatePort;
import com.wish.rd.adapter.TicketUpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 飞书 Helpdesk 工单适配器：实现 {@link TicketProviderPort} 与 {@link TicketUpdatePort}。
 *
 * <p>由配置 {@code rd.feishu.helpdesk.enabled=true} 装配，默认关闭以使用
 * {@link MockFeishuTicketAdapter}。桥接 {@link FeishuHelpdeskClient} 的 HTTP 调用与
 * {@link FeishuTicketMapper} 的 DTO 映射。
 *
 * <p>回写操作（sendMessage/updateTicket）受 {@code writeBack.enabled} 双重开关保护，
 * 默认只读。
 */
@Component
@ConditionalOnProperty(name = "rd.feishu.helpdesk.enabled", havingValue = "true")
public class FeishuTicketAdapter implements TicketProviderPort, TicketUpdatePort {

    private static final Logger log = LoggerFactory.getLogger(FeishuTicketAdapter.class);

    private final FeishuHelpdeskClient client;
    private final FeishuTicketMapper mapper;
    private final boolean writeBackEnabled;

    public FeishuTicketAdapter(
            FeishuHelpdeskClient client,
            FeishuHelpdeskProperties properties,
            FeishuTicketMapper mapper
    ) {
        this.client = client;
        this.mapper = mapper;
        this.writeBackEnabled = properties.getWriteBack() != null && properties.getWriteBack().isEnabled();
    }

    @Override
    public Optional<TicketSnapshot> findTicket(String ticketId) {
        try {
            Optional<JsonNode> node = client.getTicket(ticketId);
            if (node.isEmpty()) {
                log.info("feishu ticket not found, ticketId={}", ticketId);
                return Optional.empty();
            }
            return Optional.of(mapper.toSnapshot(ticketId, node.get()));
        } catch (FeishuHelpdeskClient.FeishuHelpdeskException exception) {
            log.error("feishu getTicket failed, ticketId={}", ticketId, exception);
            return Optional.empty();
        }
    }

    @Override
    public TicketMessages findMessages(String ticketId, TicketMessageQuery query) {
        TicketMessageQuery safe = query == null ? TicketMessageQuery.defaults() : query;
        try {
            JsonNode items = client.listMessages(ticketId, safe.page(), safe.pageSize());
            long total = items.isArray() ? items.size() : 0L;
            return mapper.toMessages(ticketId, items, safe, total);
        } catch (FeishuHelpdeskClient.FeishuHelpdeskException exception) {
            log.error("feishu listMessages failed, ticketId={}", ticketId, exception);
            return TicketMessages.empty();
        }
    }

    @Override
    public TicketUpdateResult sendMessage(TicketReplyCommand command) {
        if (!writeBackEnabled) {
            return TicketUpdateResult.failure("write-back-disabled", "write-back is disabled");
        }
        try {
            String messageId = client.sendMessage(command.ticketId(), command.messageType(), command.content());
            log.info("feishu reply sent, ticketId={}, providerMessageId={}", command.ticketId(), messageId);
            return TicketUpdateResult.success(messageId, "ok");
        } catch (FeishuHelpdeskClient.FeishuHelpdeskException exception) {
            log.error("feishu sendMessage failed, ticketId={}", command.ticketId(), exception);
            return TicketUpdateResult.failure("feishu-error", exception.getMessage());
        }
    }

    @Override
    public TicketUpdateResult updateTicket(TicketUpdateCommand command) {
        if (!writeBackEnabled) {
            return TicketUpdateResult.failure("write-back-disabled", "write-back is disabled");
        }
        try {
            Map<String, Object> patch = new LinkedHashMap<>();
            if (!command.status().isBlank()) {
                patch.put("status_status", mapStandardStatus(command.status()));
            }
            if (!command.tags().isEmpty()) {
                patch.put("tag_ids", command.tags());
            }
            if (!command.customFields().isEmpty()) {
                patch.put("custom_fields", command.customFields());
            }
            if (command.solved() != null && command.solved()) {
                patch.put("status_status", 200);
            }
            boolean success = client.updateTicket(command.ticketId(), patch);
            return success
                    ? TicketUpdateResult.success(command.ticketId(), "ok")
                    : TicketUpdateResult.failure("feishu-update-failed", "update returned non-zero code");
        } catch (FeishuHelpdeskClient.FeishuHelpdeskException exception) {
            log.error("feishu updateTicket failed, ticketId={}", command.ticketId(), exception);
            return TicketUpdateResult.failure("feishu-error", exception.getMessage());
        }
    }

    /** 标准状态字符串映射回飞书 Helpdesk 数字状态码。 */
    private static int mapStandardStatus(String status) {
        return switch (status == null ? "" : status.toLowerCase()) {
            case "pending" -> 99;
            case "processing" -> 100;
            case "solved" -> 200;
            case "closed" -> 300;
            default -> 100;
        };
    }
}
