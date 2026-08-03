package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.docker.ContainerRunnerPort;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.pi.impl.DockerPiAgentExecutor;
import com.wish.rd.exec.repair.pi.AgentPrivateArtifactPublisher;
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
@ConditionalOnProperty(prefix = "rd.executor.agent-runtime", name = "enabled", havingValue = "true")
public class AgentRuntimeExecutorConfiguration {

    @Bean
    // Property-based gate: @ConditionalOnBean across plain @Configuration classes has
    // non-deterministic evaluation order and silently skipped this bean at startup.
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
            ExecutionAllowlistPolicy executionAllowlistPolicy
    ) {
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
                com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor.AuthEnvironmentResolver.system()
        );
    }

    @Bean
    @ConditionalOnMissingBean(AgentRuntimeRouter.class)
    public AgentRuntimeRouter agentRuntimeRouter(
            ObjectProvider<RepairExecutorPort> legacyExecutorProvider,
            ObjectProvider<DockerPiAgentExecutor> piExecutorProvider
    ) {
        RepairExecutorPort legacyExecutor = legacyExecutorProvider.getIfAvailable();
        if (legacyExecutor == null) {
            throw new IllegalStateException(
                    "agent runtime router requires the existing requirement executor for Claude compatibility"
            );
        }
        Map<com.wish.rd.rag.project.agent.model.AgentRuntimeType, AgentRuntimeExecutorPort> executors =
                new EnumMap<>(com.wish.rd.rag.project.agent.model.AgentRuntimeType.class);
        AgentRuntimeExecutorPort legacyAdapter = request -> legacyExecutor.execute(request.command());
        executors.put(com.wish.rd.rag.project.agent.model.AgentRuntimeType.CLAUDE_CODE, legacyAdapter);
        executors.put(com.wish.rd.rag.project.agent.model.AgentRuntimeType.MODEL_ONLY, legacyAdapter);
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
            com.wish.rd.bootstrap.executor.OpenAiChatCompletionsProperties openAiProperties,
            ModelProviderProfileService providerProfileService,
            AgentToolPolicyService toolPolicyService,
            PiAgentExecutorProperties piProperties
    ) {
        return new EngineRequirementExecutionProfileResolver(
                profileService,
                snapshotService,
                snapshotStore,
                openAiProperties.isEnabled(),
                providerProfileService,
                toolPolicyService,
                piProperties.getContextProtocolVersion(),
                piProperties.getContextPolicyMode(),
                piProperties.isDynamicStateEnabled(),
                piProperties.getMaxInjectedStateBytes()
        );
    }
}
