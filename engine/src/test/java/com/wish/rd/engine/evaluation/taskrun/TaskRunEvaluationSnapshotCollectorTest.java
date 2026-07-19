package com.wish.rd.engine.evaluation.taskrun;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.evaluation.model.EvaluationJudgeProvider;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunConfig;
import com.wish.rd.engine.evaluation.model.EvaluationSource;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.RetrievalRunStore;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRunEvaluationSnapshotCollectorTest {

    @Test
    void shouldBuildTaskScopedRedactedDatasetAndRecordFromPersistentStores() throws Exception {
        RagStreamTaskRegistry registry = RagStreamTaskRegistry.inMemory();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "商品管理", "P1", "https://user:repository-secret@example.com/waimai", "example", "waimai", "main",
                "页面可维护商品", List.of("构建通过", "token=acceptance-secret-123456"), false));
        AgentStageRunStore stages = mock(AgentStageRunStore.class);
        AgentStageArtifactStore artifacts = mock(AgentStageArtifactStore.class);
        RoleContextPackageStore contexts = mock(RoleContextPackageStore.class);
        RetrievalRunStore retrievals = mock(RetrievalRunStore.class);
        WorkflowExperienceStore experiences = mock(WorkflowExperienceStore.class);
        AgentStageRun reviewer = new AgentStageRun(
                "stage-1", task.taskId(), AgentRole.REQUIREMENT_REVIEWER, AgentStageStatus.SUCCEEDED, 1,
                "idem-1", "ctx-1", "prompt-1", "result-1", "long-cat",
                "[{\"status\":\"SUCCESS\",\"token\":\"secret-value-123456\"}]", "{}", "", "",
                1L, 2L, 1L, 2L);
        when(stages.listByTask(task.taskId())).thenReturn(List.of(reviewer));
        when(artifacts.listByTask(task.taskId())).thenReturn(List.of(new AgentStageArtifact(
                "result-1", "stage-1", task.taskId(), AgentRole.REQUIREMENT_REVIEWER, "RESULT_JSON",
                "file:///tmp/result.json", "review result",
                "{\"decision\":\"PASS\",\"api_key\":\"sensitive-value-123456\"}", "sha256:abc", "{}", 2L)));
        when(contexts.listByTask(task.taskId())).thenReturn(List.of(new RoleContextPackage(
                "ctx-1", task.taskId(), AgentRole.REQUIREMENT_REVIEWER.name(), 1,
                List.of(), List.of("构建通过"), List.of(), 1000, 100, List.of(), 1L)));
        when(retrievals.listByTask(task.taskId())).thenReturn(List.of());
        when(experiences.listByTask(task.taskId())).thenReturn(List.of());
        TaskRunEvaluationSnapshotCollector collector = new TaskRunEvaluationSnapshotCollector(
                registry, stages, artifacts, contexts, retrievals, experiences, new ObjectMapper());
        EvaluationRun run = EvaluationRun.created("eval-1", new EvaluationRunConfig(
                "评测商品管理", "task-run.generated.jsonl", EvaluationSource.TASK_RUN, "local", 0,
                "", "", 90, EvaluationJudgeProvider.NONE, 0, false, "", task.taskId()), 1, "", 3L);

        TaskRunEvaluationSnapshotCollector.TaskRunEvaluationPayload payload = collector.collect(run);

        assertEquals("TASK-" + task.taskId(), payload.dataset().get("sample_id"));
        assertEquals(task.taskId(), payload.record().get("task_id"));
        assertEquals("task-run", payload.record().get("suite"));
        String json = new ObjectMapper().writeValueAsString(payload.record());
        String datasetJson = new ObjectMapper().writeValueAsString(payload.dataset());
        assertTrue(json.contains("REQUIREMENT_REVIEWER"));
        assertTrue(json.contains("result-1"));
        assertTrue(json.contains("<redacted>"));
        assertFalse(json.contains("sensitive-value-123456"));
        assertFalse(json.contains("secret-value-123456"));
        assertFalse(datasetJson.contains("repository-secret"));
        assertFalse(datasetJson.contains("acceptance-secret-123456"));
        assertTrue(datasetJson.contains("<redacted>"));
        @SuppressWarnings("unchecked")
        var stage = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) payload.record().get("stages"))
                .get("REQUIREMENT_REVIEWER");
        assertEquals(true, stage.get("resultArtifactPresent"));
        @SuppressWarnings("unchecked")
        var taskRun = (java.util.Map<String, Object>) payload.record().get("task_run");
        assertEquals("main", taskRun.get("baseBranch"));
        assertTrue(taskRun.containsKey("workBranch"));
        assertTrue(taskRun.containsKey("commitSha"));
        @SuppressWarnings("unchecked")
        var provenance = (java.util.Map<String, Object>) payload.record().get("provenance");
        assertEquals("task-run-v2", provenance.get("snapshotSchemaVersion"));
        assertTrue(String.valueOf(provenance.get("datasetSha256")).startsWith("sha256:"));
        assertTrue(String.valueOf(provenance.get("recordPayloadSha256")).startsWith("sha256:"));
        assertTrue(json.length() <= 500_000);
        verify(stages).listByTask(task.taskId());
        verify(artifacts).listByTask(task.taskId());
        verify(retrievals).listByTask(task.taskId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldProjectOnlySelectedEvidenceWithUriHashPreviewAndConsumer() throws Exception {
        RagStreamTaskRegistry registry = RagStreamTaskRegistry.inMemory();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "商品管理", "P1", "https://github.com/example/waimai", "example", "waimai", "main",
                "页面可维护商品", List.of("商品保存接口返回 200"), false));
        AgentStageRunStore stages = mock(AgentStageRunStore.class);
        AgentStageArtifactStore artifacts = mock(AgentStageArtifactStore.class);
        RoleContextPackageStore contexts = mock(RoleContextPackageStore.class);
        WorkflowExperienceStore experiences = mock(WorkflowExperienceStore.class);
        InMemoryRetrievalRunStore retrievals = new InMemoryRetrievalRunStore();
        RetrievalRunLifecycle lifecycle = new RetrievalRunLifecycle(retrievals, new java.util.function.Supplier<>() {
            private int value;

            @Override
            public String get() {
                return "retrieval-id-" + (++value);
            }
        }, () -> 10L);
        RetrievalRun retrieval = lifecycle.start(
                task.taskId(), RetrievalConsumerType.AGENT_ROLE, AgentRole.CODING_AGENT.name(), "stage-code",
                "商品保存接口 Controller Service", List.of("waimai-kb"));
        lifecycle.appendArtifact(retrieval.runId(), "RETRIEVAL_PLAN", "rag://retrieval/plan",
                "plan must never be treated as retrieved context", "sha256:plan");
        lifecycle.appendArtifact(retrieval.runId(), "SELECTED_EVIDENCE", "code://server/src/product/ProductController.java#save",
                "ProductController.save validates and persists the product", "sha256:code");
        lifecycle.complete(retrieval.runId(), 4, 1, false, "critical code evidence found");
        when(stages.listByTask(task.taskId())).thenReturn(List.of());
        when(artifacts.listByTask(task.taskId())).thenReturn(List.of());
        when(contexts.listByTask(task.taskId())).thenReturn(List.of(new RoleContextPackage(
                "ctx-code", task.taskId(), AgentRole.CODING_AGENT.name(), 1,
                List.of(new RoleContextEvidence(
                        "code-1", "CODE", "code://server/src/product/ProductController.java#save",
                        "ProductController.save", "sha256:code",
                        "ProductController.save validates and persists the product", 10L)),
                List.of("商品保存接口返回 200"), List.of(), 1_000, 80, List.of(), 10L)));
        when(experiences.listByTask(task.taskId())).thenReturn(List.of());
        TaskRunEvaluationSnapshotCollector collector = new TaskRunEvaluationSnapshotCollector(
                registry, stages, artifacts, contexts, retrievals, experiences, new ObjectMapper());
        EvaluationRun run = EvaluationRun.created("eval-selected", new EvaluationRunConfig(
                "selected evidence", "task-run.generated.jsonl", EvaluationSource.TASK_RUN, "local", 0,
                "", "", 90, EvaluationJudgeProvider.NONE, 0, false, "", task.taskId()), 1, "", 11L);

        TaskRunEvaluationSnapshotCollector.TaskRunEvaluationPayload payload = collector.collect(run);

        List<Map<String, Object>> groups = (List<Map<String, Object>>) payload.record().get("retrieved_contexts");
        assertEquals(1, groups.size());
        assertEquals("CODING_AGENT", groups.getFirst().get("consumer"));
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) groups.getFirst().get("evidence");
        assertEquals(1, evidence.size());
        assertEquals("code://server/src/product/ProductController.java#save", evidence.getFirst().get("sourceUri"));
        assertEquals("sha256:code", evidence.getFirst().get("contentHash"));
        assertTrue(String.valueOf(evidence.getFirst().get("contentPreview")).contains("ProductController.save"));
        assertFalse(new ObjectMapper().writeValueAsString(groups).contains("plan must never"));
    }
}
