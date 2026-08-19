package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.runtime.AgentExecutionEventStore;
import com.wish.rd.exec.repair.runtime.impl.InMemoryAgentExecutionEventStore;
import com.wish.rd.bootstrap.executor.impl.ProjectionAwareAgentExecutionEventStore;
import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.AgentStageStateProjectionStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentStageStateProjectionStore;
import org.springframework.beans.factory.ObjectProvider;
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
    AgentExecutionEventStore agentExecutionEventStore(
            ObjectProvider<AgentExecutionProfileSnapshotStore> snapshotStoreProvider,
            ObjectProvider<AgentStageStateProjectionStore> projectionStoreProvider
    ) {
        return new ProjectionAwareAgentExecutionEventStore(
                new InMemoryAgentExecutionEventStore(),
                snapshotStoreProvider.getIfAvailable(InMemoryAgentExecutionProfileSnapshotStore::new),
                projectionStoreProvider.getIfAvailable(InMemoryAgentStageStateProjectionStore::new)
        );
    }
}
