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
import com.wish.rd.engine.requirement.review.AiReviewRunStore;
import com.wish.rd.engine.requirement.review.impl.InMemoryAiReviewRunStore;
import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryCheckpointStore;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.rag.qa.QaValidationProfileService;
import com.wish.rd.rag.qa.QaValidationProfileStore;
import com.wish.rd.rag.qa.impl.InMemoryQaValidationProfileStore;
import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileService;
import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileStore;
import com.wish.rd.rag.project.runtime.impl.InMemoryProjectRuntimeProfileStore;

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

    @Bean
    @ConditionalOnMissingBean(AiReviewRunStore.class)
    AiReviewRunStore aiReviewRunStore() {
        return new InMemoryAiReviewRunStore();
    }

    @Bean
    @ConditionalOnMissingBean(TaskRetryCheckpointStore.class)
    TaskRetryCheckpointStore taskRetryCheckpointStore() {
        return new InMemoryTaskRetryCheckpointStore();
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowExperienceStore.class)
    WorkflowExperienceStore workflowExperienceStore() {
        return WorkflowExperienceStore.noop();
    }

    @Bean
    @ConditionalOnMissingBean(QaValidationProfileStore.class)
    QaValidationProfileStore qaValidationProfileStore() {
        return new InMemoryQaValidationProfileStore();
    }

    @Bean
    @ConditionalOnMissingBean(QaValidationProfileService.class)
    QaValidationProfileService qaValidationProfileService(QaValidationProfileStore store) {
        return new QaValidationProfileService(store);
    }

    @Bean
    @ConditionalOnMissingBean(ProjectRuntimeProfileStore.class)
    ProjectRuntimeProfileStore projectRuntimeProfileStore() {
        return new InMemoryProjectRuntimeProfileStore();
    }

    @Bean
    @ConditionalOnMissingBean(ProjectRuntimeProfileService.class)
    ProjectRuntimeProfileService projectRuntimeProfileService(ProjectRuntimeProfileStore store) {
        return new ProjectRuntimeProfileService(store);
    }
}
