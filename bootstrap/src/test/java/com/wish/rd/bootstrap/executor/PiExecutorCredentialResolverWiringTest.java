package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.docker.AuthEnvironmentResolver;
import com.wish.rd.exec.repair.docker.ContainerRunnerPort;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.pi.AgentPrivateArtifactPublisher;
import com.wish.rd.exec.repair.pi.PiCredentialLeaseIssuer;
import com.wish.rd.exec.repair.pi.PiResourceManifestMaterializerPort;
import com.wish.rd.exec.repair.pi.PiSkillMaterializerPort;
import com.wish.rd.exec.repair.pi.impl.DockerPiAgentExecutor;
import com.wish.rd.exec.repair.result.StructuredResultValidator;
import com.wish.rd.exec.repair.runtime.AgentExecutionEventSink;
import com.wish.rd.exec.repair.security.model.ExecutionAllowlistPolicy;
import com.wish.rd.rag.project.agent.ModelProviderCredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T09/G12：Pi 执行器的凭据解析装配合同——必须先查凭据库（管理台「模型供应商」
 * 保存的 key），再回落进程环境；否则 Docker 自托管路径下凭据控制台对 Pi 完全失效。
 */
class PiExecutorCredentialResolverWiringTest {

    @Test
    void piExecutorUsesStoredFirstResolverWhenCredentialServiceIsPresent() throws Exception {
        AgentRuntimeExecutorConfiguration configuration = new AgentRuntimeExecutorConfiguration();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("credentialService", mock(ModelProviderCredentialService.class));

        DockerPiAgentExecutor executor = configuration.dockerPiAgentExecutor(
                mock(RepairWorkspaceFactory.class),
                mock(ContainerRunnerPort.class),
                mock(StructuredResultValidator.class),
                new PiAgentExecutorProperties(),
                beans.getBeanProvider(RepairWorkspaceRepositoryPort.class),
                beans.getBeanProvider(PiResourceManifestMaterializerPort.class),
                beans.getBeanProvider(PiSkillMaterializerPort.class),
                beans.getBeanProvider(AgentExecutionEventSink.class),
                beans.getBeanProvider(AgentPrivateArtifactPublisher.class),
                ExecutionAllowlistPolicy.disabled(),
                beans.getBeanProvider(PiCredentialLeaseIssuer.class),
                beans.getBeanProvider(ModelProviderCredentialService.class)
        );

        assertInstanceOf(StoredThenSystemAuthEnvironmentResolver.class, resolverOf(executor));
    }

    @Test
    void piExecutorFallsBackToSystemResolverWithoutCredentialService() throws Exception {
        AgentRuntimeExecutorConfiguration configuration = new AgentRuntimeExecutorConfiguration();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();

        DockerPiAgentExecutor executor = configuration.dockerPiAgentExecutor(
                mock(RepairWorkspaceFactory.class),
                mock(ContainerRunnerPort.class),
                mock(StructuredResultValidator.class),
                new PiAgentExecutorProperties(),
                beans.getBeanProvider(RepairWorkspaceRepositoryPort.class),
                beans.getBeanProvider(PiResourceManifestMaterializerPort.class),
                beans.getBeanProvider(PiSkillMaterializerPort.class),
                beans.getBeanProvider(AgentExecutionEventSink.class),
                beans.getBeanProvider(AgentPrivateArtifactPublisher.class),
                ExecutionAllowlistPolicy.disabled(),
                beans.getBeanProvider(PiCredentialLeaseIssuer.class),
                beans.getBeanProvider(ModelProviderCredentialService.class)
        );

        assertEquals(AuthEnvironmentResolver.system(), resolverOf(executor));
    }

    @Test
    void storedCredentialWinsOverProcessEnvironment() {
        ModelProviderCredentialService credentials = mock(ModelProviderCredentialService.class);
        when(credentials.resolveByEnvironmentVariable("OPENCODE_API_KEY")).thenReturn("stored-key");
        StoredThenSystemAuthEnvironmentResolver resolver =
                new StoredThenSystemAuthEnvironmentResolver(credentials, AuthEnvironmentResolver.system());

        assertEquals("stored-key", resolver.resolve("OPENCODE_API_KEY"));
    }

    private static AuthEnvironmentResolver resolverOf(DockerPiAgentExecutor executor) throws Exception {
        for (Class<?> type = executor.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (AuthEnvironmentResolver.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return (AuthEnvironmentResolver) field.get(executor);
                }
            }
        }
        throw new IllegalStateException("no AuthEnvironmentResolver field on " + executor.getClass());
    }
}
