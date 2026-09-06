package com.wish.rd.bootstrap.threading;

import com.wish.rd.engine.requirement.job.RequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageFinalizationPort;
import com.wish.rd.engine.requirement.manager.ManagerDecisionStore;
import com.wish.rd.engine.requirement.manager.impl.InMemoryManagerDecisionStore;
import com.wish.rd.engine.requirement.publication.RequirementPublicationStore;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RdTaskStatusEventStore;
import com.wish.rd.rag.runtime.RdTaskStore;
import com.wish.rd.rag.runtime.impl.CoordinatedRdTaskStatePersistence;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

/** Supplies the local durable-job store only when no production adapter is configured. */
@Configuration
@EnableConfigurationProperties({
        RequirementDeliverySchedulingProperties.class,
        RequirementDeliveryAuditedWritebackProperties.class
})
public class RequirementDeliveryJobConfiguration {

    @Bean
    @ConditionalOnMissingBean(com.wish.rd.engine.requirement.audit.AuditedWritebackGateMode.class)
    com.wish.rd.engine.requirement.audit.AuditedWritebackGateMode auditedWritebackGateMode(
            RequirementDeliveryAuditedWritebackProperties properties
    ) {
        return properties.getGateMode();
    }

    @Bean
    @ConditionalOnMissingBean(RequirementDeliveryJobStore.class)
    RequirementDeliveryJobStore inMemoryRequirementDeliveryJobStore() {
        return new InMemoryRequirementDeliveryJobStore();
    }

    @Bean
    @ConditionalOnMissingBean(ManagerDecisionStore.class)
    ManagerDecisionStore inMemoryManagerDecisionStore() {
        return new InMemoryManagerDecisionStore();
    }

    /**
     * Supplies the single-runtime finalization contract only when PostgreSQL has not installed
     * its transactional boundary.
     *
     * @param stageCommandStore durable command store selected for the current profile
     * @param jobStore umbrella job store selected for the current profile
     * @param taskStore task snapshot store selected for the current profile
     * @param eventStore task timeline store selected for the current profile
     * @param idGenerator shared event identifier source
     * @param publicationStoreProvider optional publication ledger for PR receipt finalization
     * @return memory-mode finalizer for focused/local runs
     */
    @Bean
    @ConditionalOnMissingBean(RequirementStageFinalizationPort.class)
    RequirementStageFinalizationPort inMemoryRequirementStageFinalizationPort(
            RequirementStageCommandStore stageCommandStore,
            RequirementDeliveryJobStore jobStore,
            RdTaskStore taskStore,
            RdTaskStatusEventStore eventStore,
            SnowflakeIdGenerator idGenerator,
            ObjectProvider<RequirementPublicationStore> publicationStoreProvider,
            ObjectProvider<com.wish.rd.engine.requirement.policy.RequirementPolicyRunStore> policyRunStoreProvider,
            ObjectProvider<com.wish.rd.engine.requirement.audit.AuditedTaskStateStore> auditedTaskStateStoreProvider,
            ObjectProvider<ManagerDecisionStore> managerDecisionStoreProvider
    ) {
        InMemoryRequirementStageFinalizationPort port = new InMemoryRequirementStageFinalizationPort(
                stageCommandStore,
                jobStore,
                taskStore,
                new CoordinatedRdTaskStatePersistence(taskStore, eventStore),
                idGenerator,
                publicationStoreProvider.getIfAvailable(),
                policyRunStoreProvider.getIfAvailable(),
                null,
                null,
                null,
                null,
                null,
                auditedTaskStateStoreProvider.getIfAvailable());
        port.setManagerDecisionStore(managerDecisionStoreProvider.getIfAvailable());
        return port;
    }
}
