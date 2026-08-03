package com.wish.rd.engine.ticket;

import com.wish.rd.engine.ticket.impl.TicketRepairEngine;

import com.wish.rd.engine.ticket.model.RepairTicketMessage;


/**
 * 修复队列消费端口：由 bootstrap 的队列适配器在收到消息时调用。
 *
 * <p>Engine 内部由 {@code TicketRepairEngine} 实现此接口；bootstrap 的
 * {@code RedisStreamRepairQueueAdapter} / {@code InMemoryRepairQueueAdapter}
 * 持有此端口引用并在队列消费循环中调用 {@link #handle(RepairTicketMessage)}。
 */
public interface RepairQueueConsumer {

    /**
     * 处理一条修复队列消息。
     *
     * @param message 队列消息
     * @return 处理是否成功（失败将由队列适配器决定是否重试）
     */
    boolean handle(RepairTicketMessage message);
}
