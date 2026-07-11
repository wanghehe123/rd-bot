package com.wish.rd.bootstrap.executor;

import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;

/** Shared in-memory stage stores for local mode so execution and overview see the same records. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryAgentObservabilityConfiguration {
    @Bean
    @ConditionalOnMissingBean(AgentStageRunStore.class)
    AgentStageRunStore agentStageRunStore() {
        return new InMemoryAgentStageRunStore();
    }

    @Bean
    @ConditionalOnMissingBean(AgentStageArtifactStore.class)
    AgentStageArtifactStore agentStageArtifactStore() {
        return new InMemoryAgentStageArtifactStore();
    }

    @Bean
    @ConditionalOnMissingBean(RoleContextPackageStore.class)
    RoleContextPackageStore roleContextPackageStore() {
        return new InMemoryRoleContextPackageStore();
    }
}
