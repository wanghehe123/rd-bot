package com.wish.rd.bootstrap.executor;

import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageCommandStore;
import com.wish.rd.engine.requirement.manager.ManagerDecisionStore;
import com.wish.rd.engine.requirement.manager.impl.InMemoryManagerDecisionStore;
import com.wish.rd.engine.requirement.policy.RequirementPolicyTransactionPort;
import com.wish.rd.engine.retry.RequirementRetryDispatchTransactionPort;
import com.wish.rd.engine.retry.TaskRetryTaskPort;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RdTaskStatusEventStore;
import com.wish.rd.rag.runtime.RdTaskStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Locks the explicit memory-mode policy transaction wiring.
 * T09/W8：memory 模式不是首发支持面，但接线合同必须成立且不伪造 Postgres 能力——
 * 运行器补齐与真实 memory 部署相同的 InMemory store（命令、Manager 决策）。
 */
class InMemoryRequirementPolicyTransactionWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("rd.knowledge.store=memory")
            .withBean(RdTaskStore.class, InMemoryRdTaskStore::new)
            .withBean(RdTaskStatusEventStore.class, InMemoryRdTaskStatusEventStore::new)
            .withBean(RequirementStageCommandStore.class, InMemoryRequirementStageCommandStore::new)
            .withBean(ManagerDecisionStore.class, InMemoryManagerDecisionStore::new)
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

    @Test
    void shouldExposeCheckpointBoundRetryDispatchPortWhenTaskPortIsPresent() {
        runner.withBean(TaskRetryTaskPort.class, () -> mock(TaskRetryTaskPort.class))
                .run(context -> assertThat(context).hasSingleBean(RequirementRetryDispatchTransactionPort.class));
    }
}
