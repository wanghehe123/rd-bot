package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.executor.DockerExecutorConfiguration;
import com.wish.rd.bootstrap.executor.DockerExecutorProperties;
import com.wish.rd.bootstrap.executor.impl.InMemoryRepairAlertSink;
import com.wish.rd.bootstrap.executor.impl.ProcessGitRepairWorkspaceRepository;
import com.wish.rd.bootstrap.executor.impl.ProcessContainerRunner;
import com.wish.rd.bootstrap.skill.impl.QaPlaywrightSkillProvisioner;
import com.wish.rd.exec.repair.alert.model.RepairAlert;
import com.wish.rd.exec.repair.alert.RepairAlertSinkPort;
import com.wish.rd.exec.repair.alert.model.RepairAlertType;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.health.ModelHealthStore;
import com.wish.rd.exec.repair.security.model.ExecutionAllowlistPolicy;
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
    void shouldExposeSharedContainerInfrastructureDefaults() {
        DockerExecutorProperties properties = new DockerExecutorProperties();

        assertFalse(properties.isEnabled());
        assertEquals(Path.of("/tmp/rd-bot/repair-workspaces"), properties.getWorkspaceRoot());
        assertEquals("bridge", properties.getNetworkMode());
        assertTrue(properties.isRemoveAfterExit());
        assertEquals(1_800_000L, properties.getTimeoutAlertMillis());
        assertEquals(0, new BigDecimal("36.00").compareTo(properties.getBudgetAlertCny()));
        assertTrue(properties.getCircuitBreaker().isEnabled());
        assertEquals(3, properties.getCircuitBreaker().getFailureThreshold());
        assertEquals(60_000L, properties.getCircuitBreaker().getOpenDurationMillis());
        assertTrue(properties.getSecurity().isEnabled());
        assertFalse(properties.getGit().isEnabled());
        assertEquals("RD-Bot", properties.getGit().getUserName());
        assertEquals("rd-bot@example.local", properties.getGit().getUserEmail());
        assertEquals(120L, properties.getGit().getTimeoutSeconds());
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
                        "rd.executor.docker.security.work-branches[0]=repair/*",
                        "rd.executor.docker.security.work-branches[1]=requirement/*"
                );

        contextRunner.run(context -> {
            DockerExecutorProperties properties = context.getBean(DockerExecutorProperties.class);
            ExecutionAllowlistPolicy policy = context.getBean(ExecutionAllowlistPolicy.class);

            assertTrue(properties.getSecurity().isEnabled());
            assertEquals(List.of("example/order"), properties.getSecurity().getRepositories());
            assertTrue(policy.enabled());
            assertEquals(List.of("https://github.com/example/*"), policy.repositoryUrls());
            assertEquals(List.of("repair/*", "requirement/*"), policy.workBranches());
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
    void shouldNotRegisterAnyRepairExecutorNowThatTheClaudeRuntimeIsGone() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(DockerConfigurationContext.class);

        contextRunner.run(context -> assertEquals(0, context.getBeanNamesForType(RepairExecutorPort.class).length));
        contextRunner
                .withPropertyValues("rd.executor.docker.enabled=true")
                .run(context -> {
                    // Enabling container execution provisions the shared infrastructure but no
                    // longer contributes a direct-dispatch executor; the router owns dispatch.
                    assertEquals(0, context.getBeanNamesForType(RepairExecutorPort.class).length);
                    QaPlaywrightSkillProvisioner.Provision provision = context.getBean(
                            QaPlaywrightSkillProvisioner.Provision.class
                    );
                    assertTrue(provision.installed());
                    assertEquals("qa-playwright-cli", provision.skillId());
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
