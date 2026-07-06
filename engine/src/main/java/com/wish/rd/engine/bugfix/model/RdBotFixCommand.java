package com.wish.rd.engine.bugfix.model;

import com.wish.rd.adapter.model.TicketSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * RD 机器人修复流程命令。
 *
 * <p>由工单入口或测试入口构造，供 {@link RdBotFixEngine#runBugFix(RdBotFixCommand)} 编排全流程。
 *
 * @param ticket       工单快照
 * @param logs         相关日志
 * @param deepThinking 是否启用深度思考
 * @param priority     优先级
 */
public record RdBotFixCommand(
        TicketSnapshot ticket,
        List<String> logs,
        boolean deepThinking,
        String priority
) {

    public RdBotFixCommand {
        ticket = ticket == null
                ? new TicketSnapshot("", "", "", List.of(), Instant.now())
                : ticket;
        logs = logs == null ? List.of() : List.copyOf(logs);
        priority = priority == null || priority.isBlank() ? "P2" : priority.strip().toUpperCase();
    }
}
