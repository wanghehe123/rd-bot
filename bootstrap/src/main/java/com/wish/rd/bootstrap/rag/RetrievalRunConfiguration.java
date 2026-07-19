package com.wish.rd.bootstrap.rag;

import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.engine.retrieval.DeepRetrievalOrchestrator;
import com.wish.rd.engine.retrieval.RequirementKnowledgeSearchPort;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.RetrievalRunStore;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

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
