package com.wish.rd.adapter;

import com.wish.rd.adapter.model.TicketReplyCommand;
import com.wish.rd.adapter.model.TicketUpdateCommand;
import com.wish.rd.adapter.model.TicketUpdateResult;


/**
 * 工单系统写入端口：向工单会话回复消息、更新工单状态/字段。
 *
 * <p>具体实现由 {@code bootstrap} 提供方适配器注入；RAG/Engine 只依赖此契约。
 * 写回默认关闭，由配置 {@code rd.ticket.write-back.enabled=true} 显式开启；
 * 使用具体飞书入口时，也可由 {@code rd.feishu.im.write-back.enabled=true}
 * 或 {@code rd.feishu.helpdesk.write-back.enabled=true} 打开。
 */
public interface TicketUpdatePort {

    /**
     * 向工单会话发送一条消息。
     *
     * @param command 回复命令
     * @return 更新结果
     */
    TicketUpdateResult sendMessage(TicketReplyCommand command);

    /**
     * 更新工单状态/标签/自定义字段。
     *
     * @param command 更新命令
     * @return 更新结果
     */
    TicketUpdateResult updateTicket(TicketUpdateCommand command);
}
