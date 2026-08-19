package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.EngineRequirementBranchPublisherAdapter;
import com.wish.rd.bootstrap.executor.impl.EngineRequirementExecutorAdapter;
import com.wish.rd.bootstrap.executor.impl.EngineRequirementPublicationReconcileAdapter;
import com.wish.rd.bootstrap.executor.impl.EngineRequirementPullRequestPublisherAdapter;
import com.wish.rd.bootstrap.executor.impl.ObjectStorageQaEvidencePublisher;
import com.wish.rd.bootstrap.executor.impl.ObjectStorageRoleHandoffPublisher;
import com.wish.rd.bootstrap.executor.impl.RoleHandoffAttachmentResolver;

import com.wish.rd.bootstrap.threading.RdBotThreadPoolConfiguration;
import com.wish.rd.engine.requirement.RequirementBranchPublisherPort;
import com.wish.rd.engine.requirement.RequirementExecutorPort;
import com.wish.rd.engine.requirement.RequirementPullRequestPublisherPort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.runtime.AgentRuntimeRouter;
import com.wish.rd.rag.qa.QaValidationProfileService;
import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * Spring wiring for the engine-facing requirement executor bridge.
 */
@Configuration(proxyBeanMethods = false)
public class EngineRequirementExecutorConfiguration {

    /**
     * Stands in for the legacy direct-execution path when only agent runtime is configured.
     * Reaching it means dispatch bypassed the router, which is a wiring defect rather than a
     * runtime condition worth absorbing silently.
     */
    private static final RepairExecutorPort ROUTED_BY_AGENT_RUNTIME = command -> {
        throw new IllegalStateException(
                "requirement execution must be dispatched by the agent runtime router: " + command.taskId()
        );
    };

    RequirementExecutorPort requirementExecutor(
            ObjectProvider<RepairExecutorPort> repairExecutorProvider,
            ObjectProvider<CodePlatformPort> codePlatformProvider,
            AsyncTaskExecutor executorIoTaskExecutor
    ) {
        return requirementExecutor(
                repairExecutorProvider,
                codePlatformProvider,
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(com.wish.rd.bootstrap.executor.impl.TaskMaterialAttachmentResolver.class),
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(QaValidationProfileService.class),
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(ObjectStorageQaEvidencePublisher.class),
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(ProjectRuntimeProfileService.class),
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(ObjectStorageRoleHandoffPublisher.class),
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(RoleHandoffAttachmentResolver.class),
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(AgentRuntimeRouter.class),
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(AgentExecutionProfileSnapshotStore.class),
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(com.wish.rd.bootstrap.oracle.HostOwnedAssertionGate.class),
                new AgentRuntimeProperties(),
                executorIoTaskExecutor
        );
    }

    /**
     * 创建需求执行器桥接端口。
     *
     * @param repairExecutorProvider 修复执行端口
     * @return 需求执行端口
     */
    @Bean
    @ConditionalOnMissingBean(RequirementExecutorPort.class)
    public RequirementExecutorPort requirementExecutor(
            ObjectProvider<RepairExecutorPort> repairExecutorProvider,
            ObjectProvider<CodePlatformPort> codePlatformProvider,
            ObjectProvider<com.wish.rd.bootstrap.executor.impl.TaskMaterialAttachmentResolver> attachmentResolverProvider,
            ObjectProvider<QaValidationProfileService> qaValidationProfileServiceProvider,
            ObjectProvider<ObjectStorageQaEvidencePublisher> qaEvidencePublisherProvider,
            ObjectProvider<ProjectRuntimeProfileService> runtimeProfileServiceProvider,
            ObjectProvider<ObjectStorageRoleHandoffPublisher> handoffPublisherProvider,
            ObjectProvider<RoleHandoffAttachmentResolver> handoffAttachmentResolverProvider,
            ObjectProvider<AgentRuntimeRouter> agentRuntimeRouterProvider,
            ObjectProvider<AgentExecutionProfileSnapshotStore> snapshotStoreProvider,
            ObjectProvider<com.wish.rd.bootstrap.oracle.HostOwnedAssertionGate> hostOwnedAssertionGateProvider,
            AgentRuntimeProperties agentRuntimeProperties,
            @Qualifier(RdBotThreadPoolConfiguration.EXECUTOR_IO_EXECUTOR_BEAN)
            AsyncTaskExecutor executorIoTaskExecutor
    ) {
        EngineRequirementExecutorAdapter.AgentRuntimeConfiguration agentRuntimeConfiguration =
                EngineRequirementExecutorAdapter.AgentRuntimeConfiguration.disabled();
        if (agentRuntimeProperties != null && agentRuntimeProperties.isEnabled()) {
            AgentRuntimeRouter router = agentRuntimeRouterProvider.getIfAvailable();
            AgentExecutionProfileSnapshotStore snapshotStore = snapshotStoreProvider.getIfAvailable();
            if (router == null || snapshotStore == null) {
                throw new IllegalStateException(
                        "rd.executor.agent-runtime.enabled requires runtime router and snapshot store"
                );
            }
            agentRuntimeConfiguration = new EngineRequirementExecutorAdapter.AgentRuntimeConfiguration(
                    true, router, snapshotStore
            );
        }
        RepairExecutorPort repairExecutor = repairExecutorProvider.getIfAvailable();
        if (repairExecutor == null) {
            if (!agentRuntimeConfiguration.enabled()) {
                return RequirementExecutorPort.unavailable();
            }
            // The router owns dispatch once agent runtime is on, so the adapter never reaches
            // its direct-execution branch. Supply a placeholder that says so instead of
            // treating an absent legacy executor as "no requirement executor at all".
            repairExecutor = ROUTED_BY_AGENT_RUNTIME;
        }
        return new EngineRequirementExecutorAdapter(
                repairExecutor,
                executorIoTaskExecutor,
                attachmentResolverProvider.getIfAvailable(),
                qaValidationProfileServiceProvider.getIfAvailable(),
                qaEvidencePublisherProvider.getIfAvailable(),
                runtimeProfileServiceProvider.getIfAvailable(),
                handoffPublisherProvider.getIfAvailable(),
                handoffAttachmentResolverProvider.getIfAvailable(),
                agentRuntimeConfiguration,
                hostOwnedAssertionGateProvider.getIfAvailable()
        );
    }

    /**
     * 创建复核后的需求交付 PR 发布端口。
     *
     * @param codePlatformProvider 代码平台端口
     * @return PR 发布端口
     */
    @Bean
    @ConditionalOnMissingBean(RequirementPullRequestPublisherPort.class)
    public RequirementPullRequestPublisherPort requirementPullRequestPublisher(
            ObjectProvider<CodePlatformPort> codePlatformProvider
    ) {
        CodePlatformPort codePlatform = codePlatformProvider.getIfAvailable();
        if (codePlatform == null) {
            return RequirementPullRequestPublisherPort.unavailable();
        }
        return new EngineRequirementPullRequestPublisherAdapter(codePlatform);
    }

    /**
     * Resolves UNKNOWN_REMOTE_RESULT by matching open PRs before decideReplay blocks.
     * Absent when no code platform is available (ledger stays WAIT_RECONCILE).
     *
     * @param codePlatformProvider code platform port
     * @return reconcile port or null when unavailable
     */
    @Bean
    @ConditionalOnMissingBean(RequirementPublicationReconcilePort.class)
    public RequirementPublicationReconcilePort requirementPublicationReconciler(
            ObjectProvider<CodePlatformPort> codePlatformProvider
    ) {
        CodePlatformPort codePlatform = codePlatformProvider.getIfAvailable();
        if (codePlatform == null) {
            return new RequirementPublicationReconcilePort() {
                @Override
                public java.util.Optional<MatchedOpenPullRequest> findMatchingOpenPullRequest(ReconcileQuery query) {
                    return java.util.Optional.empty();
                }
            };
        }
        return new EngineRequirementPublicationReconcileAdapter(codePlatform);
    }

    /**
     * 创建复核后、建 PR 前的工作分支推送端口。缺少任一依赖（非 Docker/Git 环境）时
     * 回退到 {@link RequirementBranchPublisherPort#unavailable()}，保持旧交付行为不变。
     *
     * @param handoffAttachmentResolverProvider 角色交接附件解析器
     * @param workspaceFactoryProvider          修复工作区工厂
     * @param workspaceRepositoryProvider       工作区仓库端口
     * @return 工作分支推送端口
     */
    @Bean
    @ConditionalOnMissingBean(RequirementBranchPublisherPort.class)
    public RequirementBranchPublisherPort requirementBranchPublisher(
            ObjectProvider<RoleHandoffAttachmentResolver> handoffAttachmentResolverProvider,
            ObjectProvider<RepairWorkspaceFactory> workspaceFactoryProvider,
            ObjectProvider<RepairWorkspaceRepositoryPort> workspaceRepositoryProvider
    ) {
        RoleHandoffAttachmentResolver handoffAttachmentResolver = handoffAttachmentResolverProvider.getIfAvailable();
        RepairWorkspaceFactory workspaceFactory = workspaceFactoryProvider.getIfAvailable();
        RepairWorkspaceRepositoryPort workspaceRepository = workspaceRepositoryProvider.getIfAvailable();
        if (handoffAttachmentResolver == null || workspaceFactory == null || workspaceRepository == null) {
            return RequirementBranchPublisherPort.unavailable();
        }
        return new EngineRequirementBranchPublisherAdapter(
                handoffAttachmentResolver, workspaceFactory, workspaceRepository);
    }
}
