package com.wish.rd.bootstrap.ticket.impl;

import com.wish.rd.exec.repair.ticket.model.TicketUpdateCommand;
import com.wish.rd.exec.repair.ticket.TicketWriteBackPort;
import com.wish.rd.exec.repair.ticket.model.TicketWriteBackResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 默认工单回写 mock 适配器，用于本地启动和测试。
 */
@Component
@ConditionalOnProperty(prefix = "rd.ticket.write-back", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockTicketWriteBackAdapter implements TicketWriteBackPort {

    private final List<TicketUpdateCommand> commands = new ArrayList<>();

    @Override
    public synchronized TicketWriteBackResult writeBack(TicketUpdateCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        commands.add(command);
        return TicketWriteBackResult.success(
                "mock",
                "mock ticket write-back recorded",
                Map.of(
                        "provider", "mock",
                        "recordedCount", String.valueOf(commands.size())
                )
        );
    }

    /**
     * 返回已记录的回写命令快照。
     *
     * @return 回写命令列表
     */
    public synchronized List<TicketUpdateCommand> recordedCommands() {
        return List.copyOf(commands);
    }
}
