package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.alert.RepairAlertSinkPort;
import com.wish.rd.exec.repair.alert.RepairExecutionWatchdog;
import com.wish.rd.exec.repair.docker.ContainerRunnerPort;
import com.wish.rd.exec.repair.docker.DockerClaudeCodeExecutor;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.result.StructuredResultValidator;
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

    private static final String RESULT_SCHEMA_RESOURCE = "executor/claude/result.schema.json";

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
            ObjectProvider<RepairAlertSinkPort> alertSinkProvider
    ) {
        return new RepairExecutionWatchdog(
                new RepairExecutionWatchdog.Policy(
                        properties.getTimeoutAlertMillis(),
                        properties.getBudgetAlertUsd() == null ? BigDecimal.ZERO : properties.getBudgetAlertUsd()
                ),
                alertSinkProvider.getIfAvailable(InMemoryRepairAlertSink::new)
        );
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
     * Exposes Docker Claude Code execution as the exec repair executor port.
     *
     * @param workspaceFactory workspace factory
     * @param containerRunner  container runner
     * @param resultValidator  structured result validator
     * @param properties       docker executor properties
     * @param watchdog         timeout and budget watchdog
     * @return repair executor port
     */
    @Bean
    @ConditionalOnBean({RepairWorkspaceFactory.class, ContainerRunnerPort.class, RepairExecutionWatchdog.class})
    @ConditionalOnMissingBean(RepairExecutorPort.class)
    public RepairExecutorPort repairExecutorPort(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort containerRunner,
            StructuredResultValidator resultValidator,
            DockerExecutorProperties properties,
            RepairExecutionWatchdog watchdog
    ) {
        return new DockerClaudeCodeExecutor(
                workspaceFactory,
                containerRunner,
                resultValidator,
                properties.toExecutorConfiguration(),
                watchdog
        );
    }

    private static String resultSchemaJson() throws IOException {
        ClassPathResource resource = new ClassPathResource(RESULT_SCHEMA_RESOURCE);
        try (var inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
