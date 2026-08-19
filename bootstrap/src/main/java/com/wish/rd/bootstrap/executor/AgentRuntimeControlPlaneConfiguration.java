package com.wish.rd.bootstrap.executor;

import com.wish.rd.rag.project.agent.AgentExecutionProfileService;
import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotService;
import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.AgentExecutionProfileStore;
import com.wish.rd.rag.project.agent.AgentStrategyProfileService;
import com.wish.rd.rag.project.agent.AgentStrategyProfileStore;
import com.wish.rd.rag.project.agent.AgentToolPolicyService;
import com.wish.rd.rag.project.agent.AgentToolPolicyStore;
import com.wish.rd.rag.project.agent.ModelProviderProfileService;
import com.wish.rd.rag.project.agent.ModelProviderProfileStore;
import com.wish.rd.rag.project.agent.AgentStageStateProjectionStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentStrategyProfileStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentToolPolicyStore;
import com.wish.rd.rag.project.agent.impl.InMemoryModelProviderProfileStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentStageStateProjectionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Local control-plane beans when the memory store is selected. */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeControlPlaneConfiguration {

    @Bean
    @ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
    @ConditionalOnMissingBean(AgentExecutionProfileStore.class)
    public AgentExecutionProfileStore inMemoryAgentExecutionProfileStore() {
        return new InMemoryAgentExecutionProfileStore();
    }

    @Bean
    @ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
    @ConditionalOnMissingBean(AgentExecutionProfileSnapshotStore.class)
    public AgentExecutionProfileSnapshotStore inMemoryAgentExecutionProfileSnapshotStore() {
        return new InMemoryAgentExecutionProfileSnapshotStore();
    }

    @Bean
    @ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
    @ConditionalOnMissingBean(AgentStageStateProjectionStore.class)
    public AgentStageStateProjectionStore inMemoryAgentStageStateProjectionStore() {
        return new InMemoryAgentStageStateProjectionStore();
    }

    @Bean
    @ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
    @ConditionalOnMissingBean(AgentToolPolicyStore.class)
    public AgentToolPolicyStore inMemoryAgentToolPolicyStore() {
        return new InMemoryAgentToolPolicyStore();
    }

    @Bean
    @ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
    @ConditionalOnMissingBean(ModelProviderProfileStore.class)
    public ModelProviderProfileStore inMemoryModelProviderProfileStore() {
        return new InMemoryModelProviderProfileStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentExecutionProfileService agentExecutionProfileService(AgentExecutionProfileStore store) {
        return new AgentExecutionProfileService(store);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentExecutionProfileSnapshotService agentExecutionProfileSnapshotService(
            AgentExecutionProfileSnapshotStore store
    ) {
        return new AgentExecutionProfileSnapshotService(store);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentToolPolicyService agentToolPolicyService(AgentToolPolicyStore store) {
        return new AgentToolPolicyService(store);
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelProviderProfileService modelProviderProfileService(ModelProviderProfileStore store) {
        return new ModelProviderProfileService(store);
    }

    @Bean
    @ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
    @ConditionalOnMissingBean(AgentStrategyProfileStore.class)
    public AgentStrategyProfileStore inMemoryAgentStrategyProfileStore() {
        return new InMemoryAgentStrategyProfileStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentStrategyProfileService agentStrategyProfileService(
            AgentStrategyProfileStore strategyStore,
            AgentExecutionProfileService profileService
    ) {
        return new AgentStrategyProfileService(strategyStore, profileService);
    }
}
