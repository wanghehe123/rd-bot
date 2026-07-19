package com.wish.rd.engine.retry;

import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.review.impl.InMemoryAiReviewRunStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryCheckpointStore;
import com.wish.rd.engine.retry.model.TaskFailureRecoverySnapshot;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskFailureRecoveryServiceTest {

    @Test
    void returnsLatestFailedStageWithParsedDiagnosisAndBoundedRawArtifact() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        InMemoryAgentStageArtifactStore artifacts = new InMemoryAgentStageArtifactStore();
        AgentStageRun failed = AgentStageRun.pending(
                        "stage-review-2", "task-1", AgentRole.REQUIREMENT_REVIEWER, 2,
                        "task-1:REQUIREMENT_REVIEWER:2", 20L)
                .withResultArtifactId("result-review-2", 30L)
                .withProviderMetadata("long-cat", "[]", 30L)
                .withStatus(AgentStageStatus.FAILED_NEEDS_HUMAN,
                        "REQUIREMENT_REVIEW_NEEDS_HUMAN", "需要补充需求信息", 40L);
        stages.save(AgentStageRun.pending(
                "stage-review-1", "task-1", AgentRole.REQUIREMENT_REVIEWER, 1,
                "task-1:REQUIREMENT_REVIEWER:1", 10L));
        stages.save(failed);
        artifacts.save(new AgentStageArtifact(
                "result-review-2", "stage-review-2", "task-1", AgentRole.REQUIREMENT_REVIEWER,
                "RESULT_JSON", "rd-artifact://stage-review-2/result", "需求评审结果",
                """
                        {"decision":"NEED_INFO","missingInformation":["确认顾客测试账号"]}
                        """,
                "sha256:review", "{}", 40L
        ));

        TaskFailureRecoveryService service = new TaskFailureRecoveryService(
                new FakeTaskPort(failedTask()),
                stages,
                artifacts,
                new InMemoryRetrievalRunStore(),
                new InMemoryAiReviewRunStore(),
                new InMemoryTaskRetryCheckpointStore(),
                new TaskRetryPointResolver(),
                new TaskFailureDiagnosticParser()
        );

        TaskFailureRecoverySnapshot snapshot = service.snapshot("task-1");

        assertEquals("stage-review-2", snapshot.retryPoint().failedStageRunId());
        assertEquals(2, snapshot.failedAttemptNo());
        assertEquals("long-cat", snapshot.providerName());
        assertEquals("result-review-2", snapshot.rawResultArtifactId());
        assertEquals("sha256:review", snapshot.rawResultContentHash());
        assertTrue(snapshot.diagnostic().requiresSupplement());
        assertEquals("确认顾客测试账号", snapshot.diagnostic().issues().getFirst().detail());
    }

    private static RdRequirementTask failedTask() {
        return new RdRequirementTask(
                "task-1", "REQUIREMENT", "ADMIN", "source", "", "P1", RdTaskStatus.FAILED_NEEDS_HUMAN,
                "订单状态筛选", "project-1", "waimai", "外卖项目", "https://github.example/waimai",
                "owner", "repo", "main", "feature/status", "支持状态筛选", "[\"筛选正确\"]",
                "prompt", "{}", "", "需要补充需求信息", 10L, 300L, false
        );
    }

    private static final class FakeTaskPort implements TaskRetryTaskPort {
        private final RdRequirementTask task;

        private FakeTaskPort(RdRequirementTask task) {
            this.task = task;
        }

        @Override
        public RdRequirementTask getRequirementTask(String taskId) {
            return task;
        }

        @Override
        public RdRequirementTask markRecovering(String taskId, String reason) {
            throw new UnsupportedOperationException("not used by recovery snapshot");
        }

        @Override
        public RdRequirementTask markRetryPreparationFailed(String taskId, String reason) {
            throw new UnsupportedOperationException("not used by recovery snapshot");
        }
    }
}
