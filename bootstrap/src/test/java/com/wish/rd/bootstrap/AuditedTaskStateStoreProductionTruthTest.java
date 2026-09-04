package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.executor.InMemoryAgentObservabilityConfiguration;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateStore;
import com.wish.rd.engine.requirement.audit.impl.InMemoryAuditedTaskStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards production audited-state store selection. */
class AuditedTaskStateStoreProductionTruthTest {

    @Test
    void memoryProfileAllowsInMemoryStore() {
        new ApplicationContextRunner()
                .withPropertyValues("rd.knowledge.store=memory")
                .withUserConfiguration(InMemoryAgentObservabilityConfiguration.class)
                .run(context -> assertThat(context.getBean(AuditedTaskStateStore.class))
                        .isInstanceOf(InMemoryAuditedTaskStateStore.class));
    }

    @Test
    void postgresProfileMustNotUseInMemoryStore() {
        ConditionalOnProperty postgres = PostgresAuditedTaskStateStore.class.getAnnotation(
                ConditionalOnProperty.class);
        assertThat(postgres.name()).containsExactly("rd.knowledge.store");
        assertThat(postgres.havingValue()).isEqualTo("postgres");

        new ApplicationContextRunner()
                .withPropertyValues("rd.knowledge.store=postgres")
                .withUserConfiguration(InMemoryAgentObservabilityConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean(AuditedTaskStateStore.class));
    }
}
