package com.wish.rd.bootstrap.rocketmq;

import com.wish.rd.engine.ticket.RepairQueueConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 在启动期把 {@link RepairQueueConsumer} 绑定到 {@link InMemoryRepairQueueAdapter}。
 *
 * <p>内存模式下，发布方持有消费回调引用，使端到端流程可在单进程内完成。
 */
@Component
@ConditionalOnProperty(name = "rd.repair.queue.mode", havingValue = "memory", matchIfMissing = true)
public class RepairQueueWiring {

    private static final Logger log = LoggerFactory.getLogger(RepairQueueWiring.class);

    public RepairQueueWiring(
            InMemoryRepairQueueAdapter adapter,
            ObjectProvider<RepairQueueConsumer> consumerProvider
    ) {
        RepairQueueConsumer consumer = consumerProvider.getIfAvailable();
        if (consumer != null) {
            adapter.bindConsumer(consumer);
            log.info("bound repair queue consumer to in-memory adapter");
        } else {
            log.warn("no RepairQueueConsumer bean available; in-memory queue will not auto-drain");
        }
    }
}
