package com.wish.rd.bootstrap.feishu.im;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 飞书 IM 工单适配器。
 *
 * <p>读取端委托本地 IM 工单存储；写入端通过飞书 IM API 向原始 chat_id
 * 发送文本消息。该适配器使 RD-Bot 能在无 Helpdesk 商业能力时继续使用标准工单端口。
 */
@Component
@ConditionalOnExpression("'${rd.repair.ticket.provider:mock}' == 'feishu-im' && '${rd.feishu.im.enabled:false}' == 'true'")
public class FeishuImTicketAdapter implements TicketProviderPort, TicketUpdatePort {

    private static final Logger log = LoggerFactory.getLogger(FeishuImTicketAdapter.class);

    private final FeishuImTicketStore store;
    private final FeishuImClient client;
    private final FeishuImProperties properties;

    public FeishuImTicketAdapter(
            FeishuImTicketStore store,
            FeishuImClient client,
            FeishuImProperties properties
    ) {
        this.store = store;
        this.client = client;
        this.properties = properties;
    }

    @Override
    public Optional<TicketSnapshot> findTicket(String ticketId) {
        return store.findTicket(ticketId);
    }

    @Override
    public TicketMessages findMessages(String ticketId, TicketMessageQuery query) {
        return store.findMessages(ticketId, query);
    }

    @Override
    public TicketUpdateResult sendMessage(TicketReplyCommand command) {
        if (!properties.getWriteBack().isEnabled()) {
            return TicketUpdateResult.failure("WRITE_BACK_DISABLED", "feishu im write-back disabled");
        }
        Optional<TicketSnapshot> snapshot = store.findTicket(command.ticketId());
        if (snapshot.isEmpty()) {
            return TicketUpdateResult.failure("TICKET_NOT_FOUND", "ticket not found: " + command.ticketId());
        }
        try {
            FeishuImClient.FeishuImSendResult result = client.sendTextMessage(snapshot.get().chatId(), command.content());
            if (result.success()) {
                return TicketUpdateResult.success(result.messageId(), result.message());
            }
            return TicketUpdateResult.failure(result.code(), result.message());
        } catch (FeishuImClient.FeishuImException exception) {
            log.warn("feishu im write-back failed, ticketId={}, reason={}", command.ticketId(), exception.getMessage());
            return TicketUpdateResult.failure("FEISHU_IM_ERROR", exception.getMessage());
        }
    }

    @Override
    public TicketUpdateResult updateTicket(TicketUpdateCommand command) {
        return TicketUpdateResult.failure("UNSUPPORTED", "feishu im local ticket update is not supported");
    }
}
