package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.docker.AuthEnvironmentResolver;
import com.wish.rd.bootstrap.executor.StoredThenSystemAuthEnvironmentResolver;
import com.wish.rd.rag.project.agent.ModelProviderCredentialService;
import com.wish.rd.exec.repair.docker.ContainerRunnerPort;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.pi.impl.DockerPiAgentExecutor;
import com.wish.rd.exec.repair.pi.AgentPrivateArtifactPublisher;
import com.wish.rd.exec.repair.pi.PiCredentialLeaseIssuer;
import com.wish.rd.exec.repair.pi.PiResourceManifestMaterializerPort;
import com.wish.rd.exec.repair.pi.PiSkillMaterializerPort;
import com.wish.rd.exec.repair.runtime.AgentExecutionEventSink;
import com.wish.rd.exec.repair.result.StructuredResultValidator;
import com.wish.rd.exec.repair.security.model.ExecutionAllowlistPolicy;
import com.wish.rd.exec.repair.runtime.AgentRuntimeExecutorPort;
import com.wish.rd.exec.repair.runtime.AgentRuntimeRouter;
import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotService;
import com.wish.rd.rag.project.agent.AgentExecutionProfileService;
import com.wish.rd.rag.project.agent.AgentToolPolicyService;
import com.wish.rd.rag.project.agent.ModelProviderProfileService;
import com.wish.rd.engine.requirement.RequirementExecutionProfileResolverPort;
import com.wish.rd.bootstrap.executor.impl.EngineRequirementExecutionProfileResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/** Requirement-delivery-only runtime routing and snapshot resolution wiring. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "rd.executor.agent-runtime",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AgentRuntimeExecutorConfiguration {

    @Bean
    // Property-based gate: @ConditionalOnBean across plain @Configuration classes has
    // non-deterministic evaluation order and silently skipped this bean at startup.
    // rd.executor.docker.enabled is the shared container-infrastructure gate (it also
    // registers ProcessContainerRunner, which Pi requires), not a Claude-specific flag.
    @ConditionalOnProperty(prefix = "rd.executor.docker", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(DockerPiAgentExecutor.class)
    public DockerPiAgentExecutor dockerPiAgentExecutor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            PiAgentExecutorProperties properties,
            ObjectProvider<RepairWorkspaceRepositoryPort> repositoryProvider,
            ObjectProvider<PiResourceManifestMaterializerPort> resourceMaterializerProvider,
            ObjectProvider<PiSkillMaterializerPort> skillMaterializerProvider,
            ObjectProvider<AgentExecutionEventSink> eventSinkProvider,
            ObjectProvider<AgentPrivateArtifactPublisher> privateArtifactPublisherProvider,
            ExecutionAllowlistPolicy executionAllowlistPolicy,
            ObjectProvider<PiCredentialLeaseIssuer> credentialLeaseIssuerProvider,
            ObjectProvider<ModelProviderCredentialService> providerCredentialServiceProvider
    ) {
        // G12 缺口修复：Pi 执行器的凭据解析必须先查凭据库（管理台「模型供应商」
        // 保存的 key），否则 Docker 自托管路径下凭据控制台对 Pi 完全失效。
        AuthEnvironmentResolver authResolver = providerCredentialServiceProvider
                .getIfAvailable() == null
                        ? AuthEnvironmentResolver.system()
                        : new StoredThenSystemAuthEnvironmentResolver(
                                providerCredentialServiceProvider.getObject(),
                                AuthEnvironmentResolver.system());
        return new DockerPiAgentExecutor(
                workspaceFactory,
                containerRunner,
                resultValidator,
                properties.toExecutorConfiguration(),
                repositoryProvider.getIfAvailable(RepairWorkspaceRepositoryPort::noop),
                executionAllowlistPolicy,
                resourceMaterializerProvider.getIfAvailable(PiResourceManifestMaterializerPort::emptyOnly),
                skillMaterializerProvider.getIfAvailable(PiSkillMaterializerPort::emptyOnly),
                eventSinkProvider.getIfAvailable(AgentExecutionEventSink::noop),
                privateArtifactPublisherProvider.getIfAvailable(AgentPrivateArtifactPublisher::noop),
                authResolver,
                credentialLeaseIssuerProvider.getIfAvailable()
        );
    }

    /**
     * Registers one executor per runtime type that is actually present.
     *
     * <p>Every slot is optional. A runtime type with no executor fails at dispatch with
     * {@code UnsupportedAgentRuntimeException}, naming the type that was requested, rather
     * than preventing this bean from being created. Requiring an executor here instead would
     * couple the Pi path to the legacy one: {@code RepairExecutorPort} has no implementation
     * unless {@code rd.executor.docker.enabled} or {@code rd.executor.mock.enabled} is true,
     * so demanding it aborted context startup for every deployment that runs Pi alone.
     *
     * @param legacyExecutorProvider legacy container executor, absent when not configured
     * @param piExecutorProvider     Pi agent executor, absent when the container gate is closed
     * @return router over the runtime types that have an executor
     */
    @Bean
    @ConditionalOnMissingBean(AgentRuntimeRouter.class)
    public AgentRuntimeRouter agentRuntimeRouter(
            ObjectProvider<RepairExecutorPort> legacyExecutorProvider,
            ObjectProvider<DockerPiAgentExecutor> piExecutorProvider
    ) {
        Map<com.wish.rd.rag.project.agent.model.AgentRuntimeType, AgentRuntimeExecutorPort> executors =
                new EnumMap<>(com.wish.rd.rag.project.agent.model.AgentRuntimeType.class);
        RepairExecutorPort legacyExecutor = legacyExecutorProvider.getIfAvailable();
        if (legacyExecutor != null) {
            AgentRuntimeExecutorPort legacyAdapter = request -> legacyExecutor.execute(request.command());
            executors.put(com.wish.rd.rag.project.agent.model.AgentRuntimeType.CLAUDE_CODE, legacyAdapter);
            executors.put(com.wish.rd.rag.project.agent.model.AgentRuntimeType.MODEL_ONLY, legacyAdapter);
        }
        DockerPiAgentExecutor piExecutor = piExecutorProvider.getIfAvailable();
        if (piExecutor != null) {
            executors.put(com.wish.rd.rag.project.agent.model.AgentRuntimeType.PI, piExecutor);
        }
        return new AgentRuntimeRouter(executors);
    }

    @Bean
    @ConditionalOnMissingBean(RequirementExecutionProfileResolverPort.class)
    public RequirementExecutionProfileResolverPort requirementExecutionProfileResolver(
            AgentExecutionProfileService profileService,
            AgentExecutionProfileSnapshotService snapshotService,
            AgentExecutionProfileSnapshotStore snapshotStore,
            ModelProviderProfileService providerProfileService,
            AgentToolPolicyService toolPolicyService,
            PiAgentExecutorProperties piProperties
    ) {
        return new EngineRequirementExecutionProfileResolver(
                profileService,
                snapshotService,
                snapshotStore,
                providerProfileService,
                toolPolicyService,
                piProperties.getContextProtocolVersion(),
                piProperties.getContextPolicyMode(),
                piProperties.isDynamicStateEnabled(),
                piProperties.getMaxInjectedStateBytes()
        );
    }
}
