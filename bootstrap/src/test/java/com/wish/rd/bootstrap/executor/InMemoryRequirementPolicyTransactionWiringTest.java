package com.wish.rd.bootstrap.executor;

import com.wish.rd.engine.requirement.policy.RequirementPolicyTransactionPort;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RdTaskStatusEventStore;
import com.wish.rd.rag.runtime.RdTaskStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the explicit memory-mode policy transaction wiring. */
class InMemoryRequirementPolicyTransactionWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("rd.knowledge.store=memory")
            .withBean(RdTaskStore.class, InMemoryRdTaskStore::new)
            .withBean(RdTaskStatusEventStore.class, InMemoryRdTaskStatusEventStore::new)
            .withBean(SnowflakeIdGenerator.class, SnowflakeIdGenerator::defaultGenerator)
            .withUserConfiguration(InMemoryAgentObservabilityConfiguration.class);

    @Test
    void shouldExposePolicyTransactionPortOnlyWhenKnowledgeStoreIsMemory() {
        runner.run(context -> assertThat(context).hasSingleBean(RequirementPolicyTransactionPort.class));

        new ApplicationContextRunner()
                .withPropertyValues("rd.knowledge.store=postgres")
                .withUserConfiguration(InMemoryAgentObservabilityConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean(RequirementPolicyTransactionPort.class));
    }
}
