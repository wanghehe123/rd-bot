package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockRepairExecutorConfigurationTest {

    @Test
    void shouldOnlyRegisterMockRepairExecutorWhenExplicitlyEnabled() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(MockExecutorContext.class);

        contextRunner.run(context -> assertEquals(0, context.getBeanNamesForType(RepairExecutorPort.class).length));
        contextRunner
                .withPropertyValues("rd.executor.mock.enabled=true")
                .run(context -> {
                    assertEquals(1, context.getBeanNamesForType(RepairExecutorPort.class).length);
                    RepairExecutionResult result = context.getBean(RepairExecutorPort.class).execute(command());

                    assertEquals(RepairExecutionStatus.SUCCESS, result.status());
                    assertTrue(result.summary().contains("需求任务已完成"));
                    assertEquals("SUCCESS", result.rawResultJson().get("status"));
                    assertEquals("mock", result.dockerMetadataJson().get("provider"));
                    assertEquals("PASSED", result.testMetadataJson().get("testStatus"));
                    assertEquals("LOW", result.riskMetadataJson().get("riskLevel"));
                });
    }

    private RepairJobCommand command() {
        return new RepairJobCommand(
                "task-1001",
                "task-1001",
                "",
                "增加订单催单功能",
                "implement requirement",
                "https://github.com/acme/order.git",
                "acme",
                "order",
                "main",
                "requirement/task-1001",
                Map.of("taskType", "REQUIREMENT"),
                Map.of("bridge", "engine-requirement-executor")
        );
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MockRepairExecutorConfiguration.class)
    static class MockExecutorContext {
    }
}
