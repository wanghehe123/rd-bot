package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.execution.RepairArtifact;
import com.wish.rd.exec.repair.execution.RepairArtifactType;
import com.wish.rd.exec.repair.execution.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.RepairJobCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Explicit local smoke executor for requirement-delivery golden paths.
 */
@Configuration(proxyBeanMethods = false)
public class MockRepairExecutorConfiguration {

    /**
     * Registers a deterministic repair executor only when local smoke mode is explicitly enabled.
     *
     * @return mock repair executor
     */
    @Bean
    @ConditionalOnMissingBean(RepairExecutorPort.class)
    @ConditionalOnProperty(prefix = "rd.executor.mock", name = "enabled", havingValue = "true")
    public RepairExecutorPort mockRepairExecutorPort() {
        return this::execute;
    }

    private RepairExecutionResult execute(RepairJobCommand command) {
        String summary = "需求任务已完成: " + command.ticketTitle();
        String changedFiles = """
                client/src/pages/OrderDetail.tsx
                server/src/routes/requirement.ts
                """.strip();
        return new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                summary,
                "",
                List.of(
                        new RepairArtifact(
                                RepairArtifactType.RESULT_JSON,
                                "result.json",
                                "mock://%s/result.json".formatted(command.taskId()),
                                "mock structured result",
                                Map.of("provider", "mock")
                        ),
                        new RepairArtifact(
                                RepairArtifactType.TEST_LOG,
                                "test.log",
                                "mock://%s/test.log".formatted(command.taskId()),
                                "mock validation log",
                                Map.of("provider", "mock")
                        )
                ),
                Map.of(
                        "status", "SUCCESS",
                        "summary", summary,
                        "prBody", prBody(command, summary),
                        "changedFiles", changedFiles,
                        "testCommands", "mock validation",
                        "testStatus", "PASSED",
                        "riskLevel", "LOW"
                ),
                Map.of(
                        "provider", "mock",
                        "enabledBy", "rd.executor.mock.enabled"
                ),
                Map.of(),
                Map.of(
                        "testStatus", "PASSED",
                        "testCommands", "mock validation"
                ),
                Map.of("riskLevel", "LOW"),
                ""
        );
    }

    private String prBody(RepairJobCommand command, String summary) {
        return """
                ## 改动介绍
                - %s

                ## 需求任务
                - taskId: %s
                - baseBranch: %s
                - workBranch: %s

                ## 验证
                - mock validation: PASSED
                """.formatted(
                summary,
                command.taskId(),
                command.baseBranch(),
                command.workBranch()
        ).strip();
    }
}
