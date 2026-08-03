package com.wish.rd.bootstrap.queue;

import com.wish.rd.bootstrap.queue.impl.InMemoryRepairQueueAdapter;
import com.wish.rd.engine.ticket.RepairQueueConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Binds the local in-memory repair queue to the engine consumer when memory mode is selected.
 *
 * <p>Production Redis Stream mode owns its consumer lifecycle directly; this component exists
 * solely to preserve deterministic single-process HTTP QA and unit-test behavior.
 */
@Component
@ConditionalOnProperty(name = "rd.repair.queue.mode", havingValue = "memory")
public class RepairQueueWiring {

    private static final Logger log = LoggerFactory.getLogger(RepairQueueWiring.class);

    /**
     * Connects the optional engine consumer to the local adapter.
     *
     * @param adapter local in-memory queue adapter
     * @param consumerProvider provider for the engine consumer
     */
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
