package com.wish.rd.engine.ticket;

import com.wish.rd.engine.ticket.impl.TicketRepairEngine;

import com.wish.rd.engine.ticket.model.RepairTicketMessage;


/**
 * 修复队列消费端口：由 bootstrap 的 MQ 适配器在收到消息时回调触发。
 *
 * <p>Engine 内部由 {@code TicketRepairEngine} 实现此接口；bootstrap 的
 * {@code RocketMqRepairQueueAdapter} / {@code InMemoryRepairQueueAdapter}
 * 持有此端口引用并在 MQ 回调中调用 {@link #handle(RepairTicketMessage)}。
 */
public interface RepairQueueConsumer {

    /**
     * 处理一条修复队列消息。
     *
     * @param message 队列消息
     * @return 处理是否成功（失败将由 MQ 适配器决定是否重试）
     */
    boolean handle(RepairTicketMessage message);
}
