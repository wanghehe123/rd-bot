package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.executor.DockerExecutorConfiguration;
import com.wish.rd.bootstrap.executor.DockerExecutorProperties;
import com.wish.rd.bootstrap.executor.InMemoryRepairAlertSink;
import com.wish.rd.bootstrap.executor.ProcessGitRepairWorkspaceRepository;
import com.wish.rd.bootstrap.executor.ProcessContainerRunner;
import com.wish.rd.exec.repair.alert.RepairAlert;
import com.wish.rd.exec.repair.alert.RepairAlertSinkPort;
import com.wish.rd.exec.repair.alert.RepairAlertType;
import com.wish.rd.exec.repair.docker.DockerClaudeCodeExecutor;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.model.ModelHealthStore;
import com.wish.rd.exec.repair.security.ExecutionAllowlistPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerExecutorConfigurationTest {

    @Test
    void shouldExposeDockerClaudeCodeDefaults() {
        DockerExecutorProperties properties = new DockerExecutorProperties();

        assertFalse(properties.isEnabled());
        assertEquals("rd-bot/claude-code:local", properties.getImage());
        assertEquals(Path.of("/tmp/rd-bot/repair-workspaces"), properties.getWorkspaceRoot());
        assertEquals(List.of(
                "claude",
                "-p",
                "--dangerously-skip-permissions",
                "--output-format",
                "stream-json",
                "--verbose"
        ), properties.claudeCommand());
        assertEquals("bridge", properties.getNetworkMode());
        assertTrue(properties.isRemoveAfterExit());
        assertEquals(1_800_000L, properties.getTimeoutAlertMillis());
        assertEquals(0, new BigDecimal("5.00").compareTo(properties.getBudgetAlertUsd()));
        assertTrue(properties.getCircuitBreaker().isEnabled());
        assertEquals(3, properties.getCircuitBreaker().getFailureThreshold());
        assertEquals(60_000L, properties.getCircuitBreaker().getOpenDurationMillis());
        assertTrue(properties.getSecurity().isEnabled());
        assertFalse(properties.getGit().isEnabled());
        assertEquals("RD-Bot", properties.getGit().getUserName());
        assertEquals("rd-bot@example.local", properties.getGit().getUserEmail());
        assertEquals(120L, properties.getGit().getTimeoutSeconds());

        DockerClaudeCodeExecutor.Configuration configuration = properties.toExecutorConfiguration();

        assertEquals(properties.getImage(), configuration.image());
        assertEquals(properties.claudeCommand(), configuration.command());
        assertFalse(configuration.allowPrivileged());
        assertEquals(1, configuration.providers().size());
        assertEquals("anthropic", configuration.providers().getFirst().name());
        assertEquals("", configuration.providers().getFirst().env().get("ANTHROPIC_API_KEY"));
    }

    @Test
    void shouldBindDockerCircuitBreakerProperties() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(DockerConfigurationContext.class)
                .withPropertyValues(
                        "rd.executor.docker.circuit-breaker.enabled=false",
                        "rd.executor.docker.circuit-breaker.failure-threshold=7",
                        "rd.executor.docker.circuit-breaker.open-duration-millis=45000"
                );

        contextRunner.run(context -> {
            DockerExecutorProperties properties = context.getBean(DockerExecutorProperties.class);
            ModelHealthStore healthStore = context.getBean(ModelHealthStore.class);

            assertFalse(properties.getCircuitBreaker().isEnabled());
            assertEquals(7, properties.getCircuitBreaker().getFailureThreshold());
            assertEquals(45_000L, properties.getCircuitBreaker().getOpenDurationMillis());
            assertFalse(healthStore.policy().enabled());
            assertEquals(7, healthStore.policy().failureThreshold());
            assertEquals(45_000L, healthStore.policy().openDurationMillis());
        });
    }

    @Test
    void shouldBindDockerSecurityAllowlistProperties() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(DockerConfigurationContext.class)
                .withPropertyValues(
                        "rd.executor.docker.security.enabled=true",
                        "rd.executor.docker.security.repository-urls[0]=https://github.com/example/*",
                        "rd.executor.docker.security.repositories[0]=example/order",
                        "rd.executor.docker.security.base-branches[0]=main",
                        "rd.executor.docker.security.work-branches[0]=repair/*"
                );

        contextRunner.run(context -> {
            DockerExecutorProperties properties = context.getBean(DockerExecutorProperties.class);
            ExecutionAllowlistPolicy policy = context.getBean(ExecutionAllowlistPolicy.class);

            assertTrue(properties.getSecurity().isEnabled());
            assertEquals(List.of("example/order"), properties.getSecurity().getRepositories());
            assertTrue(policy.enabled());
            assertEquals(List.of("https://github.com/example/*"), policy.repositoryUrls());
            assertEquals(List.of("repair/*"), policy.workBranches());
        });
    }

    @Test
    void shouldBindDeepSeekAndAnthropicProviderChainFromProperties() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(DockerConfigurationContext.class)
                .withPropertyValues(
                        "rd.executor.docker.providers[0].name=deepseek",
                        "rd.executor.docker.providers[0].base-url=https://api.deepseek.com/anthropic",
                        "rd.executor.docker.providers[0].auth-token-env=DEEPSEEK_API_KEY",
                        "rd.executor.docker.providers[0].model=deepseek-v4-pro[1m]",
                        "rd.executor.docker.providers[0].default-opus-model=deepseek-v4-pro[1m]",
                        "rd.executor.docker.providers[0].default-sonnet-model=deepseek-v4-pro[1m]",
                        "rd.executor.docker.providers[0].default-haiku-model=deepseek-v4-flash",
                        "rd.executor.docker.providers[0].subagent-model=deepseek-v4-flash",
                        "rd.executor.docker.providers[0].effort-level=max",
                        "rd.executor.docker.providers[1].name=anthropic",
                        "rd.executor.docker.providers[1].api-key-env=ANTHROPIC_API_KEY",
                        "rd.executor.docker.providers[1].model=claude-sonnet-4-5"
                );

        contextRunner.run(context -> {
            DockerExecutorProperties properties = context.getBean(DockerExecutorProperties.class);

            assertEquals(2, properties.toExecutorConfiguration().providers().size());
            Map<String, String> deepseekEnv = properties.toExecutorConfiguration().providers().get(0).env();
            assertEquals("https://api.deepseek.com/anthropic", deepseekEnv.get("ANTHROPIC_BASE_URL"));
            assertEquals("DEEPSEEK_API_KEY", deepseekEnv.get("RD_CLAUDE_AUTH_TOKEN_ENV"));
            assertEquals("", deepseekEnv.get("DEEPSEEK_API_KEY"));
            assertEquals("deepseek-v4-pro[1m]", deepseekEnv.get("ANTHROPIC_MODEL"));
            assertEquals("deepseek-v4-flash", deepseekEnv.get("CLAUDE_CODE_SUBAGENT_MODEL"));
            assertEquals("max", deepseekEnv.get("CLAUDE_CODE_EFFORT_LEVEL"));
            assertEquals("ANTHROPIC_API_KEY", properties.toExecutorConfiguration().providers().get(1).env()
                    .get("RD_CLAUDE_API_KEY_ENV"));
            assertEquals("", properties.toExecutorConfiguration().providers().get(1).env().get("ANTHROPIC_API_KEY"));
        });
    }

    @Test
    void shouldOnlyRegisterProcessRunnerWhenDockerExecutionIsEnabled() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(DockerConfigurationContext.class);

        contextRunner.run(context -> assertEquals(0, context.getBeanNamesForType(ProcessContainerRunner.class).length));
        contextRunner.withPropertyValues("rd.executor.docker.enabled=false")
                .run(context -> assertEquals(0, context.getBeanNamesForType(ProcessContainerRunner.class).length));
        contextRunner.withPropertyValues("rd.executor.docker.enabled=true")
                .run(context -> assertEquals(1, context.getBeanNamesForType(ProcessContainerRunner.class).length));
    }

    @Test
    void shouldRegisterInMemoryRepairAlertSinkForZeroConfigStartup() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(DockerConfigurationContext.class);

        contextRunner.run(context -> {
            assertEquals(1, context.getBeanNamesForType(RepairAlertSinkPort.class).length);
            InMemoryRepairAlertSink sink = context.getBean(InMemoryRepairAlertSink.class);

            sink.publish(new RepairAlert(
                    "repair-1001",
                    "task-1001",
                    RepairAlertType.TIMEOUT_WARNING,
                    "timeout warning",
                    Map.of("elapsedMillis", "2000"),
                    10_000L
            ));

            assertEquals(1, sink.alerts().size());
            assertEquals(RepairAlertType.TIMEOUT_WARNING, sink.alerts().getFirst().type());
            assertThrows(UnsupportedOperationException.class, () -> sink.alerts().clear());
        });
    }

    @Test
    void shouldRegisterDockerRepairExecutorWhenDockerExecutionIsEnabled() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(DockerConfigurationContext.class);

        contextRunner.run(context -> assertEquals(0, context.getBeanNamesForType(RepairExecutorPort.class).length));
        contextRunner
                .withPropertyValues("rd.executor.docker.enabled=true")
                .run(context -> {
                    assertEquals(1, context.getBeanNamesForType(RepairExecutorPort.class).length);
                    assertTrue(context.getBean(RepairExecutorPort.class) instanceof DockerClaudeCodeExecutor);
                    assertTrue(context.getBean(ModelHealthStore.class).policy().enabled());
                });
    }

    @Test
    void shouldRegisterGitWorkspaceRepositoryOnlyWhenEnabled() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(DockerConfigurationContext.class);

        contextRunner.run(context -> assertEquals(0, context.getBeanNamesForType(RepairWorkspaceRepositoryPort.class).length));
        contextRunner
                .withPropertyValues("rd.executor.docker.git.enabled=true")
                .run(context -> {
                    assertEquals(1, context.getBeanNamesForType(RepairWorkspaceRepositoryPort.class).length);
                    assertTrue(context.getBean(RepairWorkspaceRepositoryPort.class)
                            instanceof ProcessGitRepairWorkspaceRepository);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            DockerExecutorProperties.class,
            ProcessContainerRunner.class,
            ProcessGitRepairWorkspaceRepository.class,
            InMemoryRepairAlertSink.class,
            DockerExecutorConfiguration.class
    })
    static class DockerConfigurationContext {
    }
}
