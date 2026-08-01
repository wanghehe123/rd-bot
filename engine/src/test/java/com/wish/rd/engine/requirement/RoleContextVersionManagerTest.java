package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retrieval.model.RetrievalOutcome;
import com.wish.rd.rag.context.RoleContextBuilder;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.retrieval.run.model.EvidenceQualityDecision;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoleContextVersionManagerTest {

    @Test
    void shouldReuseSemanticVersionAndCreateNextVersionForNewEvidence() {
        InMemoryRoleContextPackageStore store = new InMemoryRoleContextPackageStore();
        AtomicInteger ids = new AtomicInteger();
        RoleContextVersionManager manager = new RoleContextVersionManager(
                new RoleContextBuilder(), store, () -> "pkg-" + ids.incrementAndGet(), 18_000);
        RdRequirementTask task = task();
        TaskMaterial firstMaterial = material("m-1", "hash-1", "需求正文", "用户可以催单", 10L);

        Map<AgentRole, RoleContextPackage> first = manager.ensureLatestContexts(task, List.of(firstMaterial), 100L);
        Map<AgentRole, RoleContextPackage> same = manager.ensureLatestContexts(
                task, List.of(material("m-1", "hash-1", "需求正文", "用户可以催单", 999L)), 200L);
        Map<AgentRole, RoleContextPackage> changed = manager.ensureLatestContexts(
                task, List.of(firstMaterial, material("m-2", "hash-2", "接口约束", "新增接口测试", 20L)), 300L);

        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            assertEquals(1, first.get(role).packageVersion());
            assertEquals(first.get(role).packageId(), same.get(role).packageId());
            assertEquals(2, changed.get(role).packageVersion());
        }
        assertEquals(8, store.listByTask(task.taskId()).size());
    }

    @Test
    void shouldReuseContextAcrossDifferentRetrievalRunsWithSameEvidence() {
        InMemoryRoleContextPackageStore store = new InMemoryRoleContextPackageStore();
        AtomicInteger ids = new AtomicInteger();
        RoleContextVersionManager manager = new RoleContextVersionManager(
                new RoleContextBuilder(), store, () -> "pkg-" + ids.incrementAndGet(), 18_000);
        RdRequirementTask task = task();
        RoleContextEvidence code = new RoleContextEvidence(
                "code-1", "CODE", "code://OrderService.java#urge", "OrderService.urge",
                "sha256:code-1", "urge implementation", 10L,
                "matched coding role", 0.9d, "CODE_SYMBOL", false
        );

        RoleContextPackage first = manager.ensureLatestContext(
                task, AgentRole.CODING_AGENT, successful("run-1", code), 100L);
        RoleContextPackage sameRun = manager.ensureLatestContext(
                task, AgentRole.CODING_AGENT, successful("run-1", code), 200L);
        RoleContextPackage differentRun = manager.ensureLatestContext(
                task, AgentRole.CODING_AGENT, successful("run-2", code), 300L);

        assertEquals("run-1", first.retrievalRunId());
        assertEquals(first.packageId(), sameRun.packageId());
        assertEquals(first.packageId(), differentRun.packageId());
        assertEquals(first.packageVersion(), differentRun.packageVersion());
        assertEquals(manager.semanticSignatureOf(first), manager.semanticSignatureOf(differentRun));
    }

    private RetrievalOutcome successful(String runId, RoleContextEvidence evidence) {
        return new RetrievalOutcome(
                runId, RetrievalRunStatus.SUCCEEDED, RetrievalConsumerType.AGENT_ROLE,
                AgentRole.CODING_AGENT, "stage-1", List.of(evidence), EvidenceQualityDecision.SUFFICIENT,
                "quality-1", List.of(), List.of(), "sufficient"
        );
    }

    private RdRequirementTask task() {
        return RdRequirementTask.created("task-1", new CreateRequirementTaskCommand(
                "上下文版本", "P1", "https://github.com/example/repo.git", "example", "repo", "main",
                "可交付", List.of("测试通过"), false), 1L);
    }

    private TaskMaterial material(String id, String hash, String title, String preview, long timestamp) {
        return new TaskMaterial(
                id, "task-1", TaskMaterialType.REQUIREMENT_DOC, TaskMaterialSourceType.MANUAL_TEXT,
                title, "manual://" + id, "text/plain", hash, preview, "", "", "", "{}",
                timestamp, timestamp);
    }
}
