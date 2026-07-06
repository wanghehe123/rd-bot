package com.wish.rd.engine.ticket;

import com.wish.rd.engine.ticket.model.RepairQueuePublishResult;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;


/**
 * 修复队列发布端口。
 *
 * <p>Engine 只依赖此契约；具体实现由 {@code bootstrap} 注入（内存 / RocketMQ）。
 * 发布方只接收 {@link RepairTicketMessage} 瘦消息，不知道工单详情。
 */
public interface RepairQueuePublisher {

    /**
     * 发布一条修复消息到队列。
     *
     * @param message 队列消息
     * @return 发布结果
     */
    RepairQueuePublishResult publish(RepairTicketMessage message);
}
