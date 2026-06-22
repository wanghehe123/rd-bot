package com.wish.rd.adapter;

import java.util.Optional;

/**
 * 工单系统读取端口：按工单 ID 查询工单快照与消息。
 *
 * <p>RAG/Engine 能力层只依赖此契约，具体实现由 {@code bootstrap} 提供方适配器注入。
 * 通过拆分读取/写入端口，避免单一胖接口在实现端被部分实现的尴尬。
 */
public interface TicketProviderPort {

    /**
     * 查询工单快照。
     *
     * @param ticketId 工单 ID
     * @return 工单快照；不存在时返回 {@link Optional#empty()}
     */
    Optional<TicketSnapshot> findTicket(String ticketId);

    /**
     * 查询工单消息列表（分页）。
     *
     * @param ticketId 工单 ID
     * @param query    查询条件
     * @return 消息分页结果；工单不存在时返回 {@link TicketMessages#empty()}
     */
    TicketMessages findMessages(String ticketId, TicketMessageQuery query);
}
