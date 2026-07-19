package com.wish.rd.engine.requirement.review;

import com.wish.rd.engine.requirement.review.impl.AiReviewPackageBuilder;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.review.model.AiReviewPackage;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiReviewPackageBuilderTest {

    @Test
    void packagesEveryTaskScopedRagAndRoleArtifactWithoutOmission() {
        InMemoryTaskMaterialStore materials = new InMemoryTaskMaterialStore();
        InMemoryRoleContextPackageStore contexts = new InMemoryRoleContextPackageStore();
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        InMemoryAgentStageArtifactStore stageArtifacts = new InMemoryAgentStageArtifactStore();
        InMemoryRetrievalRunStore retrievals = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();

        RdRequirementTask task = task("task-1");
        materials.save(material("material-1", "task-1", "status filter requirement"));
        materials.save(material("foreign", "task-2", "must never leak"));
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            contexts.save(context("context-" + role.name(), "task-1", role));
        }
        contexts.save(context("foreign-context", "task-2", AgentRole.QA_AGENT));

        int roleIndex = 0;
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            roleIndex++;
            AgentStageRun stage = AgentStageRun.pending(
                    "stage-" + roleIndex, "task-1", role, 1,
                    "task-1:" + role.name() + ":1", 100L + roleIndex);
            stages.save(stage);
            stageArtifacts.save(new AgentStageArtifact(
                    "prompt-" + role.name(), stage.stageRunId(), task.taskId(), role, "PROMPT_SNAPSHOT",
                    "rd-agent-stage://task-1/" + stage.stageRunId() + "/prompt", role + " prompt",
                    "PROMPT_FOR_" + role.name(), "sha256:prompt-" + role.name(), "{}", 105L + roleIndex));
            stageArtifacts.save(new AgentStageArtifact(
                    "result-" + role.name(), stage.stageRunId(), task.taskId(), role, "RESULT_JSON",
                    "rd-agent-stage://task-1/" + stage.stageRunId() + "/result", role + " result",
                    "RESULT_FOR_" + role.name(), "sha256:result-" + role.name(), "{}", 110L + roleIndex));
        }
        stageArtifacts.save(new AgentStageArtifact(
                "foreign-artifact", "stage-x", "task-2", AgentRole.QA_AGENT, "RESULT_JSON", "",
                "foreign", "must never leak", "sha256:foreign", "{}", 111L));

        RetrievalRunLifecycle retrievalLifecycle = new RetrievalRunLifecycle(
                retrievals, () -> "retrieval-" + ids.incrementAndGet(), () -> 120L);
        var retrieval = retrievalLifecycle.start("task-1",
                com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType.AGENT_ROLE,
                AgentRole.REQUIREMENT_REVIEWER.name(), "stage-1", "order status", List.of("kb-1"));
        retrievalLifecycle.appendArtifact(retrieval.runId(), "RETRIEVAL_PLAN", "rag://plan",
                "query decomposition and required evidence", "sha256:plan");
        retrievalLifecycle.appendArtifact(retrieval.runId(), "CHANNEL_RESULT", "rag://channel/code",
                "candidate content from OrderService", "sha256:candidate");
        retrievalLifecycle.appendArtifact(retrieval.runId(), "SELECTED_EVIDENCE", "rag://evidence/order-service",
                "selected OrderService evidence", "sha256:evidence");
        retrievalLifecycle.appendArtifact(retrieval.runId(), "QUALITY_REPORT", "rag://quality",
                "SUFFICIENT", "sha256:quality");

        AiReviewPackage reviewPackage = new AiReviewPackageBuilder(
                materials, contexts, stages, stageArtifacts, retrievals, 300).build(
                task, "{\"approved\":true}");

        Set<String> sourceTypes = reviewPackage.sources().stream()
                .map(source -> source.sourceType()).collect(Collectors.toSet());
        assertTrue(sourceTypes.containsAll(Set.of(
                "TASK", "MATERIAL", "ROLE_CONTEXT", "STAGE_RUN", "STAGE_ARTIFACT",
                "RETRIEVAL_RUN", "RETRIEVAL_ARTIFACT", "DETERMINISTIC_REVIEW")));
        assertEquals(0, reviewPackage.omittedSourceCount());
        assertTrue(reviewPackage.partCount() > 1, "small budget must split without dropping a source");
        assertTrue(reviewPackage.sources().stream().noneMatch(source -> source.content().contains("must never leak")));
        assertEquals(reviewPackage.sources().size(), reviewPackage.sourceIds().size());
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            assertTrue(reviewPackage.sources().stream().anyMatch(source ->
                    source.sourceType().equals("ROLE_CONTEXT") && source.role().equals(role.name())));
            assertTrue(reviewPackage.sources().stream().anyMatch(source ->
                    source.sourceType().equals("STAGE_ARTIFACT")
                            && source.role().equals(role.name())
                            && source.artifactType().equals("PROMPT_SNAPSHOT")
                            && source.content().contains("PROMPT_FOR_" + role.name())));
            assertTrue(reviewPackage.sources().stream().anyMatch(source ->
                    source.sourceType().equals("STAGE_ARTIFACT")
                            && source.role().equals(role.name())
                            && source.artifactType().equals("RESULT_JSON")
                            && source.content().contains("RESULT_FOR_" + role.name())));
        }
        assertTrue(reviewPackage.sources().stream().anyMatch(source ->
                source.sourceType().equals("RETRIEVAL_ARTIFACT")
                        && source.artifactType().equals("RETRIEVAL_PLAN")));
        assertTrue(reviewPackage.sources().stream().anyMatch(source ->
                source.sourceType().equals("RETRIEVAL_ARTIFACT")
                        && source.artifactType().equals("SELECTED_EVIDENCE")));
        assertTrue(reviewPackage.sources().stream().anyMatch(source ->
                source.sourceType().equals("RETRIEVAL_ARTIFACT")
                        && source.artifactType().equals("QUALITY_REPORT")));
    }

    private RdRequirementTask task(String taskId) {
        return new RdRequirementTask(
                taskId, "REQUIREMENT", "ADMIN", "source-1", "", "P1", RdTaskStatus.VALIDATING,
                "订单状态筛选", "project-1", "waimai", "外卖项目", "https://github.example/waimai",
                "owner", "repo", "main", "feature/status", "支持状态筛选", "[\"筛选正确\"]",
                "prompt snapshot", "{\"multiAgentStatus\":\"SUCCESS\"}", "", "",
                10L, 20L, false);
    }

    private TaskMaterial material(String materialId, String taskId, String preview) {
        return new TaskMaterial(materialId, taskId, TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT, "需求", "", "text/plain",
                "sha256:" + materialId, preview, "", "", "", "{}", 30L, 30L);
    }

    private RoleContextPackage context(String contextId, String taskId, AgentRole role) {
        return new RoleContextPackage(contextId, taskId, role.name(), 1, List.of(),
                List.of("筛选正确"), List.of("注意权限"), 18_000, 100, List.of(), 40L);
    }
}
