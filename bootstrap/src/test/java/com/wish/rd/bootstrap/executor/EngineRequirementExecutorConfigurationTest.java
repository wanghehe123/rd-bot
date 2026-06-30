package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.github.GitHubCodePlatformProperties;
import com.wish.rd.bootstrap.github.MockGitHubCodePlatformAdapter;
import com.wish.rd.engine.requirement.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.RequirementExecutionResult;
import com.wish.rd.engine.requirement.RequirementExecutorPort;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.execution.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.rag.runtime.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.RdRequirementTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRequirementExecutorConfigurationTest {

    @Test
    void shouldAllowMockCodePlatformForLocalRequirementGoldenPath() {
        EngineRequirementExecutorConfiguration configuration = new EngineRequirementExecutorConfiguration();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("repairExecutor", successfulRepairExecutor());
        beans.addBean("codePlatform", new MockGitHubCodePlatformAdapter(new GitHubCodePlatformProperties()));

        RequirementExecutorPort executor = configuration.requirementExecutor(
                beans.getBeanProvider(RepairExecutorPort.class),
                beans.getBeanProvider(CodePlatformPort.class)
        );
        RequirementExecutionResult result = executor.execute(request());

        assertTrue(result.success());
        assertEquals(
                "https://github.com/acme/order/pull/requirement-task-1001",
                result.pullRequestUrl()
        );
        assertTrue(result.resultJson().contains("\"provider\":\"mock\""));
    }

    private RepairExecutorPort successfulRepairExecutor() {
        return ignored -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "done",
                "",
                List.of(),
                Map.of("prBody", "body"),
                Map.of(),
                Map.of(),
                Map.of("testStatus", "PASSED"),
                Map.of(),
                ""
        );
    }

    private RequirementExecutionRequest request() {
        CreateRequirementTaskCommand command = new CreateRequirementTaskCommand(
                "需求交付",
                "P1",
                "https://github.com/acme/order.git",
                "",
                "",
                "main",
                "完成需求",
                List.of("测试通过"),
                false
        );
        RdRequirementTask task = RdRequirementTask.created("task-1001", command, 1000L);
        return new RequirementExecutionRequest("task-1001", task, List.of(), "implement");
    }
}
