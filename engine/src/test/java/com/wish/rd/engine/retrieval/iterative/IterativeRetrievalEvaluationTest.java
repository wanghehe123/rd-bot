package com.wish.rd.engine.retrieval.iterative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retrieval.DeepRetrievalOrchestrator;
import com.wish.rd.engine.retrieval.RequirementKnowledgeSearchPort;
import com.wish.rd.engine.retrieval.model.RetrievalOutcome;
import com.wish.rd.engine.retrieval.model.RetrievalScope;
import com.wish.rd.engine.retrieval.model.SearchResult;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays the frozen retrieval evaluation dataset through the real orchestrator in both modes.
 * The fixture search port is deterministic, while lifecycle, scope, selection and iterative
 * audit behavior remain production code paths.
 */
class IterativeRetrievalEvaluationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int TOP_K = 5;
    private static final RetrievalIterationLimits LIMITS =
            new RetrievalIterationLimits(3, 2_000L, 60_000L, 2);

    @Test
    void datasetReplayComparesSinglePassAndIterativeMetrics() throws Exception {
        List<EvaluationCase> cases = loadCases();
        List<Observation> baseline = run(cases, false);
        List<Observation> iterative = run(cases, true);

        Aggregate baselineMetrics = aggregate(baseline);
        Aggregate iterativeMetrics = aggregate(iterative);

        assertEquals(4, baselineMetrics.sampleCount());
        assertEquals(4, iterativeMetrics.sampleCount());
        assertEquals(3, baselineMetrics.scoredCount());
        assertEquals(3, iterativeMetrics.scoredCount());
        assertEquals(1.0d / 3.0d, baselineMetrics.recallAtK(), 1.0e-9);
        assertEquals(1.0d / 3.0d, baselineMetrics.mrr(), 1.0e-9);
        assertEquals(1.0d / 3.0d, baselineMetrics.ndcg(), 1.0e-9);
        assertEquals(1.0d / 3.0d, baselineMetrics.evidenceCoverage(), 1.0e-9);
        assertEquals(1.0d, iterativeMetrics.recallAtK(), 1.0e-9);
        assertEquals(1.0d, iterativeMetrics.mrr(), 1.0e-9);
        assertEquals(1.0d, iterativeMetrics.ndcg(), 1.0e-9);
        assertEquals(1.0d, iterativeMetrics.evidenceCoverage(), 1.0e-9);
        assertEquals(1.0d, baselineMetrics.refusalAccuracy(), 1.0e-9);
        assertEquals(1.0d, iterativeMetrics.refusalAccuracy(), 1.0e-9);
        assertEquals(4, baselineMetrics.latencyCount());
        assertEquals(4, iterativeMetrics.latencyCount());
        assertEquals(42.0d, baselineMetrics.p50LatencyMs(), 1.0e-9);
        assertEquals(49.0d, iterativeMetrics.p50LatencyMs(), 1.0e-9);
        assertTrue(iterativeMetrics.p95LatencyMs() > baselineMetrics.p95LatencyMs());

        Observation rewrite = iterative.stream()
                .filter(observation -> observation.caseId().equals("eval-rewrite"))
                .findFirst().orElseThrow();
        assertTrue(rewrite.outcome().succeeded());
        assertTrue(rewrite.outcome().selectedEvidence().stream()
                .anyMatch(evidence -> evidence.evidenceId().equals("evidence-rewrite")));
        assertFalse(rewrite.outcome().stopReason().isBlank());
    }

    private List<Observation> run(List<EvaluationCase> cases, boolean iterative) {
        AtomicInteger runIds = new AtomicInteger();
        List<Observation> observations = new ArrayList<>();
        for (EvaluationCase evaluationCase : cases) {
            RdRequirementTask task = task(evaluationCase.caseId());
            RequirementKnowledgeSearchPort searchPort = new FixtureSearchPort(evaluationCase);
            DeepRetrievalOrchestrator orchestrator = new DeepRetrievalOrchestrator(
                    new RetrievalRunLifecycle(new InMemoryRetrievalRunStore(),
                            () -> "eval-run-" + runIds.incrementAndGet(),
                            () -> 1_000L + runIds.get()),
                    searchPort,
                    IterativeRetrievalPolicy.disabled(),
                    RetrievalRoundAuditStore.noop()
            );
            RetrievalOutcome outcome = iterative
                    ? orchestrator.retrieveWithPolicy(
                            task, List.of(), RetrievalConsumerType.AGENT_ROLE,
                            AgentRole.REQUIREMENT_REVIEWER, "eval-stage", "", TOP_K,
                            IterativeRetrievalPolicy.enabled(LIMITS))
                    : orchestrator.retrieve(
                            task, List.of(), RetrievalConsumerType.AGENT_ROLE,
                            AgentRole.REQUIREMENT_REVIEWER, "eval-stage", "", TOP_K);
            observations.add(new Observation(
                    evaluationCase.caseId(), outcome, iterative,
                    iterative ? evaluationCase.iterativeLatencyMs() : evaluationCase.baselineLatencyMs(),
                    evaluationCase.relevantEvidenceIds(), evaluationCase.requiredEvidenceIds(),
                    evaluationCase.expectedRefusal()
            ));
        }
        return List.copyOf(observations);
    }

    private static Aggregate aggregate(List<Observation> observations) {
        List<Double> recalls = new ArrayList<>();
        List<Double> mrrs = new ArrayList<>();
        List<Double> ndcgs = new ArrayList<>();
        List<Double> coverage = new ArrayList<>();
        List<Boolean> refusal = new ArrayList<>();
        List<Double> latency = new ArrayList<>();
        for (Observation observation : observations) {
            List<String> retrieved = observation.outcome().selectedEvidence().stream()
                    .filter(evidence -> !evidence.sharedRoot())
                    .map(RoleContextEvidence::evidenceId).toList();
            if (!observation.expectedRefusal()) {
                recalls.add(recallAtK(retrieved, observation.relevantEvidenceIds(), TOP_K));
                mrrs.add(reciprocalRank(retrieved, observation.relevantEvidenceIds()));
                ndcgs.add(ndcg(retrieved, observation.relevantEvidenceIds(), TOP_K));
                coverage.add(evidenceCoverage(retrieved, observation.requiredEvidenceIds()));
            }
            boolean predictedRefusal = observation.outcome().status() == RetrievalRunStatus.FAILED_RETRYABLE
                    || observation.outcome().status() == RetrievalRunStatus.FAILED_NEEDS_HUMAN;
            refusal.add(predictedRefusal == observation.expectedRefusal());
            latency.add((double) observation.latencyMs());
        }
        return new Aggregate(
                observations.size(), recalls.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d),
                mrrs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d),
                ndcgs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d),
                coverage.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d),
                refusal.stream().filter(Boolean::booleanValue).count() / (double) refusal.size(),
                percentile(latency, 0.50d), percentile(latency, 0.95d), latency.size(), recalls.size()
        );
    }

    private static double recallAtK(List<String> retrieved, Set<String> relevant, int topK) {
        if (relevant.isEmpty()) {
            return 0.0d;
        }
        Set<String> top = new LinkedHashSet<>(retrieved.subList(0, Math.min(topK, retrieved.size())));
        top.retainAll(relevant);
        return top.size() / (double) relevant.size();
    }

    private static double reciprocalRank(List<String> retrieved, Set<String> relevant) {
        for (int index = 0; index < retrieved.size(); index++) {
            if (relevant.contains(retrieved.get(index))) {
                return 1.0d / (index + 1);
            }
        }
        return 0.0d;
    }

    private static double ndcg(List<String> retrieved, Set<String> relevant, int topK) {
        if (relevant.isEmpty()) {
            return 0.0d;
        }
        double dcg = 0.0d;
        for (int index = 0; index < Math.min(topK, retrieved.size()); index++) {
            if (relevant.contains(retrieved.get(index))) {
                dcg += 1.0d / (Math.log(index + 2) / Math.log(2.0d));
            }
        }
        double idcg = 0.0d;
        for (int index = 0; index < Math.min(topK, relevant.size()); index++) {
            idcg += 1.0d / (Math.log(index + 2) / Math.log(2.0d));
        }
        return idcg == 0.0d ? 0.0d : dcg / idcg;
    }

    private static double evidenceCoverage(List<String> selected, Set<String> required) {
        if (required.isEmpty()) {
            return 1.0d;
        }
        Set<String> covered = new LinkedHashSet<>(selected);
        covered.retainAll(required);
        return covered.size() / (double) required.size();
    }

    private static double percentile(List<Double> values, double quantile) {
        List<Double> sorted = values.stream().sorted().toList();
        int index = Math.max(0, Math.min(sorted.size() - 1,
                (int) Math.ceil(quantile * sorted.size()) - 1));
        return sorted.get(index);
    }

    private static List<EvaluationCase> loadCases() throws IOException {
        InputStream stream = IterativeRetrievalEvaluationTest.class
                .getResourceAsStream("/retrieval-evaluation/v1.jsonl");
        if (stream == null) {
            throw new IllegalStateException("retrieval evaluation dataset is missing");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            List<EvaluationCase> cases = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    cases.add(EvaluationCase.from(OBJECT_MAPPER.readTree(line)));
                }
            }
            assertEquals(4, cases.size());
            return List.copyOf(cases);
        }
    }

    private static RdRequirementTask task(String title) {
        return RagStreamTaskRegistry.inMemory().createRequirementTask(new CreateRequirementTaskCommand(
                title, "P1", "https://example.test/repo.git", "fixture", "repo", "main",
                "deterministic retrieval evaluation", List.of(), false
        ));
    }

    private record EvaluationCase(
            String caseId,
            Set<String> relevantEvidenceIds,
            Set<String> requiredEvidenceIds,
            long baselineLatencyMs,
            long iterativeLatencyMs,
            boolean expectedRefusal,
            String firstPassEvidenceId,
            String rewriteEvidenceId
    ) {
        static EvaluationCase from(JsonNode node) {
            return new EvaluationCase(
                    node.path("caseId").asText(), set(node.path("relevantEvidenceIds")),
                    set(node.path("requiredEvidenceIds")), node.path("baselineLatencyMs").asLong(),
                    node.path("iterativeLatencyMs").asLong(), node.path("expectedRefusal").asBoolean(),
                    node.path("firstPassEvidenceId").asText(), node.path("rewriteEvidenceId").asText()
            );
        }

        private static Set<String> set(JsonNode values) {
            Set<String> result = new LinkedHashSet<>();
            values.elements().forEachRemaining(value -> {
                if (!value.asText().isBlank()) {
                    result.add(value.asText());
                }
            });
            return Set.copyOf(result);
        }
    }

    private record Observation(
            String caseId,
            RetrievalOutcome outcome,
            boolean iterative,
            long latencyMs,
            Set<String> relevantEvidenceIds,
            Set<String> requiredEvidenceIds,
            boolean expectedRefusal
    ) { }

    private record Aggregate(
            int sampleCount,
            double recallAtK,
            double mrr,
            double ndcg,
            double evidenceCoverage,
            double refusalAccuracy,
            double p50LatencyMs,
            double p95LatencyMs,
            int latencyCount,
            int scoredCount
    ) { }

    private static final class FixtureSearchPort implements RequirementKnowledgeSearchPort {
        private final EvaluationCase evaluationCase;

        private FixtureSearchPort(EvaluationCase evaluationCase) {
            this.evaluationCase = evaluationCase;
        }

        @Override
        public RetrievalScope resolveScope(RdRequirementTask task) {
            return new RetrievalScope(List.of(), "fixture/repo", false, "");
        }

        @Override
        public SearchResult search(RdRequirementTask task, AgentRole role, String query,
                                   RetrievalScope scope, int topK) {
            String evidenceId = query.contains("missingEvidenceTypes=REQUIREMENT_MATERIAL")
                    ? evaluationCase.rewriteEvidenceId() : evaluationCase.firstPassEvidenceId();
            if ("__THROW__".equals(evidenceId)) {
                throw new IllegalStateException("fixture provider refusal");
            }
            if (evidenceId.isBlank()) {
                return SearchResult.empty();
            }
            String requiredType = evidenceId.startsWith("noise-") ? "UNRELATED" : "REQUIREMENT_MATERIAL";
            return new SearchResult(List.of(new RoleContextEvidence(
                    evidenceId, "FIXTURE", "fixture://" + evidenceId, evidenceId,
                    "hash-" + evidenceId, "fixture evidence " + evidenceId, 1_000L,
                    "frozen evaluation fixture", 1.0d, requiredType, false
            )), List.of());
        }
    }
}
