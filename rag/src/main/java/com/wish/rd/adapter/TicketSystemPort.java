package com.wish.rd.adapter;

import java.util.Optional;

/**
 * 工单系统访问端口（旧版整合接口）。
 *
 * <p>已废弃：P1 起被拆分为读取端口 {@link TicketProviderPort} 与写入端口
 * {@link TicketUpdatePort}，以避免单一胖接口在实现端被部分实现。
 *
 * <p>保留此接口仅为历史代码兼容；新代码请使用拆分后的端口。
 *
 * @deprecated 使用 {@link TicketProviderPort} 与 {@link TicketUpdatePort}
 */
@Deprecated(forRemoval = true)
public interface TicketSystemPort {

    /**
     * 查询工单。
     *
     * @param ticketId 工单 ID
     * @return 工单快照；不存在时返回 {@link Optional#empty()}
     */
    Optional<TicketSnapshot> findTicket(String ticketId);
}
