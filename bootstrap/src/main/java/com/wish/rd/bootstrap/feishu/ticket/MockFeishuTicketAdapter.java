package com.wish.rd.bootstrap.feishu.ticket;

import com.wish.rd.adapter.TicketMessage;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 本地/测试用的 Mock 飞书工单适配器：实现 {@link TicketProviderPort} 与 {@link TicketUpdatePort}。
 *
 * <p>由配置 {@code rd.feishu.helpdesk.enabled=false}（默认）或
 * {@code rd.repair.ticket.provider=mock} 开启。返回确定性工单/消息/自定义字段，
 * 不依赖真实飞书凭据，用于本地端到端联调与单测。
 *
 * <p>支持 {@link #register(TicketSnapshot)} 注入自定义工单用于测试回放。
 */
@Component
@ConditionalOnProperty(
        prefix = "rd.feishu.helpdesk",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class MockFeishuTicketAdapter implements TicketProviderPort, TicketUpdatePort {

    private static final Logger log = LoggerFactory.getLogger(MockFeishuTicketAdapter.class);

    private final Map<String, TicketSnapshot> tickets = new LinkedHashMap<>();
    private final Map<String, List<TicketMessage>> messages = new LinkedHashMap<>();
    private final List<TicketReplyCommand> replies = new ArrayList<>();
    private final List<TicketUpdateCommand> updates = new ArrayList<>();

    public MockFeishuTicketAdapter() {
        seedDefaults();
    }

    private void seedDefaults() {
        // 默认放一条 P1 工单，覆盖最常见联调路径
        Map<String, String> custom = new LinkedHashMap<>();
        custom.put("problemSystem", "payment-service");
        custom.put("symptom", "下单失败");
        custom.put("logs", "ERROR orders.amount is null at OrderService.create");
        custom.put("repository", "github.com/org/payment");
        custom.put("branch", "main");
        register(new TicketSnapshot(
                "FS-MOCK-1",
                "支付系统下单接口 500",
                "金额为空时 OrderService.create 写入订单失败",
                List.of("payment", "orders.amount"),
                Instant.parse("2026-06-21T00:00:00Z"),
                "P1",
                "processing",
                "",
                "feishu",
                "oc-mock-chat-1",
                custom,
                Instant.parse("2026-06-21T00:00:00Z"),
                Instant.EPOCH
        ));
        messages.put("FS-MOCK-1", List.of(
                new TicketMessage(
                        "msg-1", "2", "user-1", "text",
                        "ERROR orders.amount is null at OrderService.create",
                        List.of(), Map.of(), Instant.parse("2026-06-21T00:01:00Z")
                )
        ));
    }

    /**
     * 注册/覆盖一条 Mock 工单。
     *
     * @param snapshot 工单快照
     */
    public synchronized void register(TicketSnapshot snapshot) {
        if (snapshot != null) {
            tickets.put(snapshot.ticketId(), snapshot);
            messages.computeIfAbsent(snapshot.ticketId(), ignored -> List.of());
        }
    }

    /**
     * 注册/覆盖 Mock 工单消息。
     *
     * @param ticketId 工单 ID
     * @param msgs     消息列表
     */
    public synchronized void registerMessages(String ticketId, List<TicketMessage> msgs) {
        messages.put(ticketId, msgs == null ? List.of() : List.copyOf(msgs));
    }

    @Override
    public synchronized Optional<TicketSnapshot> findTicket(String ticketId) {
        return Optional.ofNullable(tickets.get(ticketId));
    }

    @Override
    public synchronized TicketMessages findMessages(String ticketId, TicketMessageQuery query) {
        return new TicketMessages(
                messages.getOrDefault(ticketId, List.of()),
                query.page(),
                query.pageSize(),
                messages.getOrDefault(ticketId, List.of()).size(),
                false
        );
    }

    @Override
    public synchronized TicketUpdateResult sendMessage(TicketReplyCommand command) {
        replies.add(command);
        log.info("mock feishu reply recorded, ticketId={}, contentLen={}",
                command.ticketId(), command.content().length());
        return TicketUpdateResult.success("mock-msg-" + replies.size(), "ok");
    }

    @Override
    public synchronized TicketUpdateResult updateTicket(TicketUpdateCommand command) {
        updates.add(command);
        return TicketUpdateResult.success("mock-update-" + updates.size(), "ok");
    }

    /**
     * 快照已记录的回复（测试用）。
     *
     * @return 不可变回复列表
     */
    public synchronized List<TicketReplyCommand> recordedReplies() {
        return List.copyOf(replies);
    }

    /**
     * 快照已记录的更新（测试用）。
     *
     * @return 不可变更新列表
     */
    public synchronized List<TicketUpdateCommand> recordedUpdates() {
        return List.copyOf(updates);
    }

    /**
     * 清空所有 Mock 数据（测试用）。
     */
    public synchronized void reset() {
        tickets.clear();
        messages.clear();
        replies.clear();
        updates.clear();
        seedDefaults();
    }
}
