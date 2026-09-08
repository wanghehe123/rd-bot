package com.wish.rd.engine.retrieval;

import com.wish.rd.engine.retrieval.iterative.impl.InMemoryRetrievalRoundAuditStore;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retrieval.iterative.IterativeRetrievalPolicy;
import com.wish.rd.engine.retrieval.iterative.RetrievalIterationLimits;
import com.wish.rd.engine.retrieval.iterative.RetrievalRoundAudit;
import com.wish.rd.engine.retrieval.model.RetrievalOutcome;
import com.wish.rd.engine.retrieval.model.RetrievalScope;
import com.wish.rd.engine.retrieval.model.SearchResult;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies iterative retrieval is opt-in, bounded, audited, and scope-stable across rewrites. */
class DeepRetrievalOrchestratorIterativePolicyTest {

    @Test
    void disabledPolicyKeepsSinglePassDefault() {
        AtomicInteger searches = new AtomicInteger();
        InMemoryRetrievalRoundAuditStore audits = new InMemoryRetrievalRoundAuditStore();
        DeepRetrievalOrchestrator orchestrator = orchestrator(searches, audits, new AtomicInteger());

        RetrievalOutcome outcome = orchestrator.retrieveWithPolicy(
                task(), List.of(), RetrievalConsumerType.AGENT_ROLE, AgentRole.CODING_AGENT,
                "stage-1", "", 8, IterativeRetrievalPolicy.disabled()
        );

        assertNotNull(outcome);
        assertEquals(1, searches.get());
        assertEquals(0, audits.listByTask("task-1").size());
    }

    @Test
    void enabledPolicyPersistsEveryRoundAndReusesResolvedScope() {
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger scopeResolutions = new AtomicInteger();
        List<RetrievalScope> seenScopes = new ArrayList<>();
        List<String> seenQueries = new ArrayList<>();
        InMemoryRetrievalRoundAuditStore audits = new InMemoryRetrievalRoundAuditStore();
        RdRequirementTask task = task();
        RequirementKnowledgeSearchPort searchPort = new RequirementKnowledgeSearchPort() {
            @Override
            public RetrievalScope resolveScope(RdRequirementTask task) {
                int call = scopeResolutions.incrementAndGet();
                return new RetrievalScope(List.of(), "project/repo", true, "");
            }

            @Override
            public SearchResult search(RdRequirementTask task, AgentRole role, String query,
                                       RetrievalScope scope, int topK) {
                searches.incrementAndGet();
                seenScopes.add(scope);
                seenQueries.add(query);
                return new SearchResult(List.of(new RoleContextEvidence(
                        "candidate-noise", "FIXTURE", "fixture://candidate-noise", "noise",
                        "hash", "noise", 100L, "fixture", 1.0d, "UNRELATED", false
                )), List.of());
            }
        };
        DeepRetrievalOrchestrator orchestrator = new DeepRetrievalOrchestrator(
                lifecycle(), searchPort, IterativeRetrievalPolicy.disabled(), audits
        );

        RetrievalOutcome outcome = orchestrator.retrieveWithPolicy(
                task, List.of(), RetrievalConsumerType.AGENT_ROLE, AgentRole.CODING_AGENT,
                "stage-2", "", 8,
                IterativeRetrievalPolicy.enabled(new RetrievalIterationLimits(2, 1_000L, 60_000L, 5))
        );

        assertEquals("MAX_ROUNDS", outcome.stopReason());
        assertEquals(2, searches.get());
        assertEquals(1, scopeResolutions.get());
        assertEquals(2, audits.listByTask(task.taskId()).size());
        assertEquals(2, audits.listByTask(task.taskId()).stream()
                .map(RetrievalRoundAudit::roundNo).distinct().count());
        assertTrue(seenScopes.stream().allMatch(scope -> scope.knowledgeBaseIds().isEmpty()));
        List<RetrievalRoundAudit> roundAudits = audits.listByTask(task.taskId());
        assertEquals(seenQueries, roundAudits.stream().map(RetrievalRoundAudit::query).toList());
        assertFalse(roundAudits.getFirst().query().contains("missingEvidenceTypes="));
        assertTrue(roundAudits.get(1).query().contains(
                "missingEvidenceTypes=PROJECT_KNOWLEDGE_SCOPE,CODE_SYMBOL"));
        assertTrue(roundAudits.getFirst().candidateEvidenceIds().contains("candidate-noise"));
        assertFalse(roundAudits.getFirst().selectedEvidenceIds().contains("candidate-noise"));
    }

    private static DeepRetrievalOrchestrator orchestrator(
            AtomicInteger searches,
            InMemoryRetrievalRoundAuditStore audits,
            AtomicInteger scopeResolutions
    ) {
        return new DeepRetrievalOrchestrator(lifecycle(), new RequirementKnowledgeSearchPort() {
            @Override
            public RetrievalScope resolveScope(RdRequirementTask task) {
                scopeResolutions.incrementAndGet();
                return new RetrievalScope(List.of(), "project/repo", true, "");
            }

            @Override
            public SearchResult search(RdRequirementTask task, AgentRole role, String query,
                                       RetrievalScope scope, int topK) {
                searches.incrementAndGet();
                return SearchResult.empty();
            }
        }, IterativeRetrievalPolicy.disabled(), audits);
    }

    private static RetrievalRunLifecycle lifecycle() {
        AtomicInteger ids = new AtomicInteger();
        return new RetrievalRunLifecycle(new InMemoryRetrievalRunStore(),
                () -> "run-" + ids.incrementAndGet(), () -> 100L + ids.get());
    }

    private static RdRequirementTask task() {
        return RagStreamTaskRegistry.inMemory().createRequirementTask(new CreateRequirementTaskCommand(
                "task-1", "P1", "https://github.com/example/repo", "example", "repo", "main",
                "expected", List.of("criteria"), false
        ));
    }
}
