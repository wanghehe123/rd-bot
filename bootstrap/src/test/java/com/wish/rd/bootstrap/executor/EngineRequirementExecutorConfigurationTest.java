package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.github.GitHubCodePlatformProperties;
import com.wish.rd.bootstrap.github.impl.MockGitHubCodePlatformAdapter;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.engine.requirement.RequirementExecutorPort;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublishCommand;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublication;
import com.wish.rd.engine.requirement.RequirementPullRequestPublisherPort;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRequirementExecutorConfigurationTest {

    @Test
    void shouldWireRequirementExecutorAndPullRequestPublisherSeparately() {
        EngineRequirementExecutorConfiguration configuration = new EngineRequirementExecutorConfiguration();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("repairExecutor", successfulRepairExecutor());
        beans.addBean("codePlatform", new MockGitHubCodePlatformAdapter(new GitHubCodePlatformProperties()));

        RequirementExecutorPort executor = configuration.requirementExecutor(
                beans.getBeanProvider(RepairExecutorPort.class),
                beans.getBeanProvider(CodePlatformPort.class),
                new org.springframework.core.task.SimpleAsyncTaskExecutor("test-executor-io-")
        );
        RequirementExecutionResult result = executor.execute(request());

        assertTrue(result.success());
        assertEquals("", result.pullRequestUrl());

        RequirementPullRequestPublisherPort publisher = configuration.requirementPullRequestPublisher(
                beans.getBeanProvider(CodePlatformPort.class)
        );
        RequirementPullRequestPublication publication = publisher.publish(publishCommand());

        assertTrue(publication.success());
        assertEquals("https://github.com/acme/order/pull/requirement-task-1001", publication.pullRequestUrl());
        assertTrue(publication.metadataJson().contains("mock"));
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

    private RequirementPullRequestPublishCommand publishCommand() {
        return new RequirementPullRequestPublishCommand(
                "task-1001",
                "需求交付",
                "https://github.com/acme/order.git",
                "",
                "",
                "main",
                "requirement/task-1001",
                """
                        {"status":"SUCCESS","summary":"done","prBody":"body","multiAgentStatus":"SUCCESS","deliveryReview":{"approved":true}}
                        """
        );
    }
}
