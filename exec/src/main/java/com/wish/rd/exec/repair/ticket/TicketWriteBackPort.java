package com.wish.rd.exec.repair.ticket;

/**
 * 修复结果写回外部工单系统的端口。
 */
public interface TicketWriteBackPort {

    /**
     * 写回修复结果。
     *
     * @param command 通用回写命令
     * @return 回写结果
     */
    TicketWriteBackResult writeBack(TicketUpdateCommand command);
}
