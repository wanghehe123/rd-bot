package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.InMemoryRepairAlertSink;
import com.wish.rd.bootstrap.financial.FinancialProperties;
import com.wish.rd.bootstrap.skill.impl.QaPlaywrightSkillProvisioner;
import com.wish.rd.bootstrap.skill.impl.RoleHandoffDocumentSkillProvisioner;

import com.wish.rd.exec.repair.alert.RepairAlertSinkPort;
import com.wish.rd.exec.repair.alert.RepairExecutionWatchdog;
import com.wish.rd.exec.repair.docker.AuthEnvironmentResolver;
import com.wish.rd.exec.repair.docker.ContainerRunnerPort;
import com.wish.rd.exec.repair.docker.ContainerControlPort;
import com.wish.rd.exec.repair.docker.impl.DockerExecutionRegistry;
import com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.execution.RepairExecutionControlPort;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.health.ModelHealthStore;
import com.wish.rd.exec.repair.health.ModelHealthStateStore;
import com.wish.rd.exec.repair.health.impl.InMemoryModelHealthStateStore;
import com.wish.rd.exec.repair.provider.ProviderFallbackPreflightPort;
import com.wish.rd.exec.repair.result.StructuredResultValidator;
import com.wish.rd.exec.repair.security.RegisteredRepositoryCatalog;
import com.wish.rd.exec.repair.security.model.ExecutionAllowlistPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Spring wiring for Docker Claude Code execution ports.
 */
@Configuration(proxyBeanMethods = false)
public class DockerExecutorConfiguration {

    // Runtime-neutral: every workspace writes this contract to input/result.schema.json, and
    // the in-container QA skill reads it regardless of which runtime produced the workspace.
    private static final String RESULT_SCHEMA_RESOURCE = "executor/result.schema.json";

    /**
     * Provides the pure structured-result validator used by the Docker executor.
     *
     * @return structured result validator
     */
    @Bean
    @ConditionalOnMissingBean
    public StructuredResultValidator structuredResultValidator() {
        return new StructuredResultValidator();
    }

    /**
     * Provides the timeout and budget watchdog used by Docker execution.
     *
     * @param properties docker executor properties
     * @param alertSinkProvider repair alert sink provider
     * @return repair execution watchdog
     */
    @Bean
    @ConditionalOnMissingBean
    public RepairExecutionWatchdog repairExecutionWatchdog(
            DockerExecutorProperties properties,
            ObjectProvider<RepairAlertSinkPort> alertSinkProvider,
            ObjectProvider<com.wish.rd.exec.repair.alert.RepairBudgetObservationSinkPort> budgetObservationSinkProvider
    ) {
        return new RepairExecutionWatchdog(
                new RepairExecutionWatchdog.Policy(
                        properties.getTimeoutAlertMillis(),
                        properties.getBudgetAlertCny() == null ? BigDecimal.ZERO : properties.getBudgetAlertCny()
                ),
                alertSinkProvider.getIfAvailable(InMemoryRepairAlertSink::new),
                budgetObservationSinkProvider.getIfAvailable(
                        com.wish.rd.exec.repair.alert.RepairBudgetObservationSinkPort::noop)
        );
    }

    /**
     * Provides the model-provider circuit breaker state store used by Docker execution.
     *
     * @param properties docker executor properties
     * @param stateStoreProvider configured atomic state backend
     * @return model health store
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelHealthStore modelHealthStore(
            DockerExecutorProperties properties,
            ObjectProvider<ModelHealthStateStore> stateStoreProvider
    ) {
        ModelHealthStateStore stateStore = stateStoreProvider.getIfAvailable();
        String configuredStore = properties.getCircuitBreaker().getStateStore();
        if (stateStore == null && "redis".equals(configuredStore)) {
            throw new IllegalStateException(
                    "Redis ModelHealthStateStore is required when circuit-breaker.state-store=redis"
            );
        }
        if (stateStore == null) {
            stateStore = new InMemoryModelHealthStateStore();
        }
        return new ModelHealthStore(properties.getCircuitBreaker().toPolicy(), stateStore);
    }

    /**
     * Provides Docker repair execution target allowlist policy.
     *
     * @param properties docker executor properties
     * @return execution allowlist policy
     */
    @Bean
    @ConditionalOnMissingBean
    public ExecutionAllowlistPolicy executionAllowlistPolicy(
            DockerExecutorProperties properties,
            ObjectProvider<RegisteredRepositoryCatalog> registeredRepositories
    ) {
        return properties.toExecutionAllowlistPolicy().withRegisteredRepositories(
                registeredRepositories.getIfAvailable(RegisteredRepositoryCatalog::none)
        );
    }

    /**
     * Provides running Docker execution registry and manual stop control.
     *
     * @param controlPortProvider container control provider
     * @return Docker execution registry
     */
    @Bean
    @ConditionalOnMissingBean(RepairExecutionControlPort.class)
    public DockerExecutionRegistry dockerExecutionRegistry(ObjectProvider<ContainerControlPort> controlPortProvider) {
        return new DockerExecutionRegistry(controlPortProvider.getIfAvailable());
    }

    /**
     * Provides the standard workspace factory once a container runner is enabled.
     *
     * @param properties docker executor properties
     * @return repair workspace factory
     * @throws IOException when the bundled schema cannot be read
     */
    @Bean
    @ConditionalOnBean(ContainerRunnerPort.class)
    @ConditionalOnMissingBean
    public RepairWorkspaceFactory repairWorkspaceFactory(DockerExecutorProperties properties) throws IOException {
        return new RepairWorkspaceFactory(properties.getWorkspaceRoot(), resultSchemaJson());
    }

    /**
     * Provisions the governed QA skill snapshot that is mounted read-only into QA containers.
     */
    @Bean
    @ConditionalOnBean(ContainerRunnerPort.class)
    @ConditionalOnMissingBean
    public QaPlaywrightSkillProvisioner.Provision qaPlaywrightSkillProvision(
            DockerExecutorProperties properties
    ) {
        QaPlaywrightSkillProvisioner.Provision provision = new QaPlaywrightSkillProvisioner(
                properties.getWorkspaceRoot().resolve("_qa-skills")
        ).provision();
        if (!provision.installed()) {
            throw new IllegalStateException("failed to provision QA Playwright skill: " + provision.message());
        }
        return provision;
    }

    /** Provisions the governed Markdown handoff skill mounted for review, planning, and coding roles. */
    @Bean
    @ConditionalOnBean(ContainerRunnerPort.class)
    @ConditionalOnMissingBean
    public RoleHandoffDocumentSkillProvisioner.Provision roleHandoffDocumentSkillProvision(
            DockerExecutorProperties properties
    ) {
        RoleHandoffDocumentSkillProvisioner.Provision provision = new RoleHandoffDocumentSkillProvisioner(
                properties.getWorkspaceRoot().resolve("_role-handoff-skills")
        ).provision();
        if (!provision.installed()) {
            throw new IllegalStateException("failed to provision role handoff skill: " + provision.message());
        }
        return provision;
    }

    /**
     * Exposes Docker Claude Code execution as the exec repair executor port.
     *
     * @param workspaceFactory workspace factory
     * @param containerRunner  container runner
     * @param resultValidator  structured result validator
     * @param properties       docker executor properties
     * @param watchdog         timeout and budget watchdog
     * @param modelHealthStore model provider health store
     * @param executionRegistry running execution registry
     * @param repositoryPortProvider workspace repository port provider
     * @return repair executor port
     */
    @Bean
    @ConditionalOnBean({RepairWorkspaceFactory.class, ContainerRunnerPort.class, RepairExecutionWatchdog.class})
    @ConditionalOnMissingBean(RepairExecutorPort.class)
    public DockerClaudeCodeExecutor repairExecutorPort(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            DockerExecutorProperties properties,
            RepairExecutionWatchdog watchdog,
            ModelHealthStore modelHealthStore,
            DockerExecutionRegistry executionRegistry,
            ExecutionAllowlistPolicy executionAllowlistPolicy,
            QaPlaywrightSkillProvisioner.Provision qaSkillProvision,
            RoleHandoffDocumentSkillProvisioner.Provision handoffSkillProvision,
            ObjectProvider<RepairWorkspaceRepositoryPort> repositoryPortProvider,
            ObjectProvider<FinancialProperties> financialPropertiesProvider,
            ObjectProvider<ProviderFallbackPreflightPort> providerFallbackPreflightProvider
    ) {
        DockerClaudeCodeExecutor.Configuration configuration = properties.toExecutorConfiguration()
                .withQaSkill(new DockerClaudeCodeExecutor.QaSkillConfiguration(
                        qaSkillProvision.installPath(),
                        qaSkillProvision.skillId(),
                        qaSkillProvision.version(),
                        qaSkillProvision.checksum(),
                        qaSkillProvision.policyJson()
                ))
                .withHandoffSkill(new DockerClaudeCodeExecutor.HandoffSkillConfiguration(
                        handoffSkillProvision.installPath(),
                        handoffSkillProvision.skillId(),
                        handoffSkillProvision.version(),
                        handoffSkillProvision.checksum(),
                        handoffSkillProvision.policyJson()
                ));
        return new DockerClaudeCodeExecutor(
                workspaceFactory,
                containerRunner,
                resultValidator,
                configuration,
                repositoryPortProvider.getIfAvailable(RepairWorkspaceRepositoryPort::noop),
                watchdog,
                modelHealthStore,
                executionRegistry,
                executionAllowlistPolicy,
                AuthEnvironmentResolver.system(),
                financialPropertiesProvider.getIfAvailable(FinancialProperties::new).toBudgetCurrencyConverter(),
                providerFallbackPreflightProvider.getIfAvailable(
                        ProviderFallbackPreflightPort::unavailable)
        );
    }

    private static String resultSchemaJson() throws IOException {
        ClassPathResource resource = new ClassPathResource(RESULT_SCHEMA_RESOURCE);
        try (var inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
