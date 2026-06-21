package com.wish.rd.adapter;

import java.util.Optional;

/**
 * 工单系统访问端口：按工单 ID 查询工单快照。
 *
 * <p>RAG 能力层只定义契约，具体实现由外部适配器提供。当前主流程未直接调用，
 * 作为后续"工单驱动修复"的预留边界。
 */
public interface TicketSystemPort {

    /**
     * 查询工单。
     *
     * @param ticketId 工单 ID
     * @return 工单快照；不存在时返回 {@link Optional#empty()}
     */
    Optional<TicketSnapshot> findTicket(String ticketId);
}
