package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.runtime.AgentExecutionEventStore;
import com.wish.rd.exec.repair.runtime.impl.InMemoryAgentExecutionEventStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the bounded live event ring in every storage mode. Completed
 * normalized events are persisted as stage artifacts; this ring is only the
 * low-latency observation surface while a container is running.
 */
@Configuration(proxyBeanMethods = false)
public class AgentExecutionEventStoreConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentExecutionEventStore.class)
    AgentExecutionEventStore agentExecutionEventStore() {
        return new InMemoryAgentExecutionEventStore();
    }
}
