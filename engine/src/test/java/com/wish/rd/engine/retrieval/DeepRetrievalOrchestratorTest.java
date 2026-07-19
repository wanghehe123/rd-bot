package com.wish.rd.engine.retrieval;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retrieval.model.ChannelAudit;
import com.wish.rd.engine.retrieval.model.RetrievalOutcome;
import com.wish.rd.engine.retrieval.model.RetrievalScope;
import com.wish.rd.engine.retrieval.model.SearchResult;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.retrieval.run.model.EvidenceQualityDecision;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepRetrievalOrchestratorTest {

    @Test
    void retrievesScopedRoleEvidenceAndBindsTheCurrentStageRun() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RequirementKnowledgeSearchPort searchPort = new RequirementKnowledgeSearchPort() {
            @Override
            public RetrievalScope resolveScope(RdRequirementTask task) {
                return new RetrievalScope(List.of("waimai-kb"), "github.com/example/waimai", true, "");
            }

            @Override
            public SearchResult search(
                    RdRequirementTask task,
                    AgentRole role,
                    String query,
                    RetrievalScope scope,
                    int topK
            ) {
                return new SearchResult(List.of(
                        evidence("code-product", "CODE",
                                "code://server/src/product/ProductController.java#save",
                                "ProductController.save validates and persists products", 0.96d,
                                "CODE_SYMBOL"),
                        evidence("qa-product", "TEST",
                                "code://server/src/test/product/ProductControllerTest.java#save",
                                "ProductController save API integration test", 0.81d,
                                "TEST_ENTRY"),
                        evidence("coupon-noise", "WORKFLOW_EXPERIENCE", "rd-experience://coupon",
                                "unrelated coupon campaign", 0.0d, "OPTIONAL_HISTORY")
                ), List.of(
                        new ChannelAudit("VectorSearch", false, 3, "", ""),
                        new ChannelAudit("KeywordSearch", false, 2, "", "")
                ));
            }
        };
        DeepRetrievalOrchestrator orchestrator = new DeepRetrievalOrchestrator(
                new RetrievalRunLifecycle(store, () -> "id-" + ids.incrementAndGet(), () -> 100L),
                searchPort
        );
        RdRequirementTask task = requirementTask();

        RetrievalOutcome outcome = orchestrator.retrieve(
                task,
                List.of(requirementMaterial(task.taskId())),
                RetrievalConsumerType.AGENT_ROLE,
                AgentRole.CODING_AGENT,
                "stage-code-1",
                "reviewer approved product maintenance",
                8
        );

        assertEquals(RetrievalRunStatus.SUCCEEDED, outcome.status());
        assertEquals(EvidenceQualityDecision.SUFFICIENT, outcome.qualityDecision());
        assertEquals("stage-code-1", outcome.stageRunId());
        assertEquals(List.of("waimai-kb"), store.find(outcome.runId()).orElseThrow().knowledgeBaseIds());
        assertTrue(outcome.selectedEvidence().stream()
                .anyMatch(item -> item.evidenceId().equals("code-product")));
        assertFalse(outcome.selectedEvidence().stream()
                .anyMatch(item -> item.evidenceId().equals("qa-product")));
        assertFalse(outcome.selectedEvidence().stream()
                .anyMatch(item -> item.evidenceId().equals("coupon-noise")));
        assertTrue(store.listArtifacts(outcome.runId()).stream()
                .anyMatch(artifact -> artifact.artifactType().equals("SELECTED_EVIDENCE")
                        && artifact.artifactUri().contains("ProductController.java")
                        && artifact.contentHash().equals("sha256:code-product")));
    }

    @Test
    void requirementProseWithoutCriticalRoleEvidenceWaitsForInput() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        DeepRetrievalOrchestrator orchestrator = new DeepRetrievalOrchestrator(
                new RetrievalRunLifecycle(store, () -> "id-" + ids.incrementAndGet(), () -> 100L),
                RequirementKnowledgeSearchPort.noop()
        );
        RdRequirementTask task = requirementTask();

        RetrievalOutcome outcome = orchestrator.retrieve(
                task, List.of(requirementMaterial(task.taskId())), RetrievalConsumerType.AGENT_ROLE,
                AgentRole.QA_AGENT, "stage-qa-1", "", 8);

        assertEquals(RetrievalRunStatus.WAITING_INPUT, outcome.status());
        assertEquals(EvidenceQualityDecision.NEED_INPUT, outcome.qualityDecision());
        assertTrue(outcome.stopReason().contains("关键证据"), outcome.stopReason());
        assertTrue(outcome.missingEvidenceTypes().contains("TEST_ENTRY"));
    }

    private static RoleContextEvidence evidence(
            String id,
            String sourceType,
            String uri,
            String summary,
            double score,
            String requiredType
    ) {
        return new RoleContextEvidence(
                id, sourceType, uri, id, "sha256:" + id, summary, 100L,
                "role-aware retrieval", score, requiredType, false
        );
    }

    private static RdRequirementTask requirementTask() {
        return RagStreamTaskRegistry.inMemory().createRequirementTask(new CreateRequirementTaskCommand(
                "商品管理", "P1", "https://github.com/example/waimai", "example", "waimai", "main",
                "管理员可以保存商品", List.of("商品保存接口返回 200"), false
        ));
    }

    private static TaskMaterial requirementMaterial(String taskId) {
        return new TaskMaterial(
                "material-1", taskId, TaskMaterialType.REQUIREMENT_DOC, TaskMaterialSourceType.MANUAL_TEXT,
                "商品管理需求", "rd-task://" + taskId + "/material/1", "text/markdown",
                "sha256:material-1", "管理员可以新增并保存商品", "", "", "", "{}", 100L, 100L
        );
    }
}
