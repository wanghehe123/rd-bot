package com.wish.rd.bootstrap.rag;

import com.wish.rd.bootstrap.rag.impl.ProjectScopedRequirementKnowledgeSearchAdapter;
import com.wish.rd.engine.retrieval.DeepRetrievalOrchestrator;
import com.wish.rd.engine.retrieval.RequirementKnowledgeSearchPort;
import com.wish.rd.engine.retrieval.impl.KnowledgeRetrievalModeRouter;
import com.wish.rd.engine.retrieval.model.KnowledgeProviderMode;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.retrieval.navigator.ThreeTierNavigationEngine;
import com.wish.rd.rag.retrieval.navigator.model.NavigatorSettings;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.RetrievalRunStore;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/** Registers the durable store when PostgreSQL is enabled and a local fallback otherwise. */
@Configuration(proxyBeanMethods = false)
public class RetrievalRunConfiguration {

    @Bean
    @ConditionalOnMissingBean(RetrievalRunStore.class)
    public RetrievalRunStore inMemoryRetrievalRunStore() {
        return new InMemoryRetrievalRunStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public RetrievalRunLifecycle retrievalRunLifecycle(RetrievalRunStore store, SnowflakeIdGenerator idGenerator) {
        SnowflakeIdGenerator safeIdGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
        return new RetrievalRunLifecycle(store, safeIdGenerator::nextIdString, System::currentTimeMillis);
    }

    @Bean
    public KnowledgeProviderMode knowledgeProviderMode(
            @Value("${rd.rag.knowledge-provider-mode:LOCAL}") String raw
    ) {
        return KnowledgeProviderMode.parse(raw);
    }

    @Bean
    public NavigatorSettings navigatorSettings(
            @Value("${rd.rag.navigator.max-rounds:3}") int maxRounds,
            @Value("${rd.rag.navigator.l0-candidates:20}") int l0Candidates,
            @Value("${rd.rag.navigator.l1-expansions:6}") int l1Expansions,
            @Value("${rd.rag.navigator.l2-expansions:3}") int l2Expansions,
            @Value("${rd.rag.navigator.max-remote-calls:15}") int maxRemoteCalls,
            @Value("${rd.rag.navigator.token-budget:8000}") int tokenBudget,
            @Value("${rd.rag.navigator.time-budget:20s}") Duration timeBudget
    ) {
        return new NavigatorSettings(
                maxRounds, l0Candidates, l1Expansions, l2Expansions,
                maxRemoteCalls, tokenBudget, timeBudget);
    }

    @Bean
    @Primary
    @ConditionalOnBean(ProjectScopedRequirementKnowledgeSearchAdapter.class)
    public RequirementKnowledgeSearchPort knowledgeRetrievalModeRouter(
            ProjectScopedRequirementKnowledgeSearchAdapter localSearch,
            KnowledgeProviderMode mode,
            NavigatorSettings navigatorSettings,
            ThreeTierNavigationEngine navigationEngine,
            RetrievalRunLifecycle lifecycle
    ) {
        return new KnowledgeRetrievalModeRouter(
                localSearch, mode, navigatorSettings.timeBudget(), navigationEngine, lifecycle);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeepRetrievalOrchestrator deepRetrievalOrchestrator(
            RetrievalRunLifecycle lifecycle,
            ObjectProvider<RequirementKnowledgeSearchPort> searchPortProvider
    ) {
        RequirementKnowledgeSearchPort searchPort = searchPortProvider.getIfAvailable(
                RequirementKnowledgeSearchPort::noop
        );
        return new DeepRetrievalOrchestrator(lifecycle, searchPort);
    }
}
