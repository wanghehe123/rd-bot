package com.wish.rd.engine.project.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.project.memory.model.ProjectMemoryGateThresholds;
import com.wish.rd.engine.project.memory.model.ProjectMemoryShadowEvaluationAudit;
import com.wish.rd.engine.project.memory.model.ProjectMemoryShadowMetrics;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Computes frozen shadow metrics from a versioned evaluation corpus. */
public final class ProjectMemoryShadowEvaluationEngine {
    private final ObjectMapper objectMapper;

    public ProjectMemoryShadowEvaluationEngine() {
        this(new ObjectMapper());
    }

    ProjectMemoryShadowEvaluationEngine(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public ShadowEvaluationCorpus loadCorpus(String resourcePath) {
        try (InputStream input = resourceStream(resourcePath)) {
            JsonNode root = objectMapper.readTree(input);
            String datasetRevision = text(root, "datasetRevision");
            String gateRevision = text(root, "gateRevision");
            List<ShadowEvaluationProject> projects = new ArrayList<>();
            for (JsonNode projectNode : root.path("projects")) {
                List<ShadowEvaluationQuery> queries = new ArrayList<>();
                for (JsonNode queryNode : projectNode.path("queries")) {
                    queries.add(new ShadowEvaluationQuery(
                            text(queryNode, "queryId"),
                            text(queryNode, "role"),
                            text(queryNode, "text"),
                            stringList(queryNode.path("expectedSourceIds")),
                            stringList(queryNode.path("legacyBaselineSourceIds")),
                            queryNode.path("k").asInt(0)
                    ));
                }
                List<ShadowEvaluationHit> hits = new ArrayList<>();
                for (JsonNode hitNode : projectNode.path("hits")) {
                    hits.add(new ShadowEvaluationHit(
                            text(hitNode, "memoryId"),
                            text(hitNode, "projectId"),
                            text(hitNode, "role"),
                            text(hitNode, "summary"),
                            text(hitNode, "sourceId"),
                            hitNode.path("activeHead").asBoolean(false),
                            hitNode.path("valid").asBoolean(false),
                            hitNode.path("redacted").asBoolean(false),
                            hitNode.path("quality").asDouble(0.0d),
                            hitNode.path("stale").asBoolean(false),
                            hitNode.path("conflict").asBoolean(false),
                            hitNode.path("latencyMillis").asLong(0L),
                            hitNode.path("tokenEstimate").asInt(0)
                    ));
                }
                projects.add(new ShadowEvaluationProject(
                        text(projectNode, "projectId"),
                        text(projectNode, "repositoryFingerprint"),
                        queries,
                        hits
                ));
            }
            return new ShadowEvaluationCorpus(datasetRevision, gateRevision, List.copyOf(projects));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to load shadow corpus: " + resourcePath, exception);
        }
    }

    public ProjectMemoryGateThresholds loadThresholds(String resourcePath) {
        try (InputStream input = resourceStream(resourcePath)) {
            JsonNode root = objectMapper.readTree(input);
            return new ProjectMemoryGateThresholds(
                    text(root, "gateRevision"),
                    text(root, "datasetRevision"),
                    root.path("maxCrossProjectLeakage").asInt(0),
                    root.path("minRecallAtK").asDouble(0.0d),
                    root.path("minPrecisionAtK").asDouble(0.0d),
                    root.path("maxStaleConflictRate").asDouble(0.0d),
                    root.path("maxAbstentionRate").asDouble(0.0d),
                    root.path("maxDuplicateContextRate").asDouble(0.0d),
                    root.path("minSourceCoverage").asDouble(0.0d),
                    root.path("maxP95LatencyMillis").asLong(0L),
                    root.path("maxTokenShare").asDouble(0.0d),
                    root.path("defaultK").asInt(3)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("failed to load gate thresholds: " + resourcePath, exception);
        }
    }

    public ProjectMemoryShadowEvaluationAudit evaluate(
            String projectId,
            ShadowEvaluationCorpus corpus,
            ProjectMemoryGateThresholds thresholds
    ) {
        return evaluate(projectId, corpus, thresholds, true);
    }

    public ProjectMemoryShadowEvaluationAudit evaluate(
            String projectId,
            ShadowEvaluationCorpus corpus,
            ProjectMemoryGateThresholds thresholds,
            boolean enforceProjectFilter
    ) {
        ShadowEvaluationProject project = corpus.projects().stream()
                .filter(candidate -> candidate.projectId().equals(projectId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown project in corpus: " + projectId));

        if (!corpus.datasetRevision().equals(thresholds.datasetRevision())) {
            throw new IllegalArgumentException("dataset revision mismatch between corpus and thresholds");
        }

        int crossProjectLeakage = 0;
        int relevantFound = 0;
        int relevantTotal = 0;
        int precisionHits = 0;
        int precisionTotal = 0;
        int staleConflict = 0;
        int abstained = 0;
        int duplicateContext = 0;
        int coveredSources = 0;
        int expectedSources = 0;
        int examinedRows = project.hits().size();
        int tokenTotal = 0;
        int tokenBudget = 0;
        List<Long> latencies = new ArrayList<>();
        Set<String> injectedSourceIds = new HashSet<>();
        List<String> failureReasons = new ArrayList<>();

        for (ShadowEvaluationQuery query : project.queries()) {
            int k = query.k() > 0 ? query.k() : thresholds.defaultK();
            List<ShadowEvaluationHit> ranked = rank(project, query, k, enforceProjectFilter);
            relevantTotal += query.expectedSourceIds().size();
            expectedSources += query.expectedSourceIds().size();

            for (ShadowEvaluationHit hit : ranked) {
                if (!hit.projectId().equals(project.projectId())) {
                    crossProjectLeakage++;
                }
                if (hit.stale() || hit.conflict()) {
                    staleConflict++;
                }
                latencies.add(hit.latencyMillis());
                tokenTotal += hit.tokenEstimate();
                if (!injectedSourceIds.add(hit.sourceId())) {
                    duplicateContext++;
                }
                if (query.expectedSourceIds().contains(hit.sourceId())) {
                    relevantFound++;
                    precisionHits++;
                }
                precisionTotal++;
            }

            if (ranked.isEmpty() && !query.expectedSourceIds().isEmpty()) {
                abstained++;
            }

            for (String expected : query.expectedSourceIds()) {
                if (ranked.stream().anyMatch(hit -> hit.sourceId().equals(expected))) {
                    coveredSources++;
                }
            }
            tokenBudget += k * 50;
        }

        double recallAtK = relevantTotal == 0 ? 1.0d : (double) relevantFound / relevantTotal;
        double precisionAtK = precisionTotal == 0 ? 1.0d : (double) precisionHits / precisionTotal;
        double staleConflictRate = precisionTotal == 0 ? 0.0d : (double) staleConflict / precisionTotal;
        double abstentionRate = project.queries().isEmpty()
                ? 0.0d : (double) abstained / project.queries().size();
        double duplicateContextRate = precisionTotal == 0 ? 0.0d : (double) duplicateContext / precisionTotal;
        double sourceCoverage = expectedSources == 0 ? 1.0d : (double) coveredSources / expectedSources;
        long p95 = percentile(latencies, 0.95d);
        double tokenShare = tokenBudget == 0 ? 0.0d : (double) tokenTotal / tokenBudget;

        List<String> failedThresholds = new ArrayList<>();
        if (crossProjectLeakage > thresholds.maxCrossProjectLeakage()) {
            failedThresholds.add("crossProjectLeakage");
            failureReasons.add("cross-project leakage detected");
        }
        if (recallAtK < thresholds.minRecallAtK()) {
            failedThresholds.add("recallAtK");
        }
        if (precisionAtK < thresholds.minPrecisionAtK()) {
            failedThresholds.add("precisionAtK");
        }
        if (staleConflictRate > thresholds.maxStaleConflictRate()) {
            failedThresholds.add("staleConflictRate");
        }
        if (abstentionRate > thresholds.maxAbstentionRate()) {
            failedThresholds.add("abstentionRate");
        }
        if (duplicateContextRate > thresholds.maxDuplicateContextRate()) {
            failedThresholds.add("duplicateContextRate");
        }
        if (sourceCoverage < thresholds.minSourceCoverage()) {
            failedThresholds.add("sourceCoverage");
        }
        if (p95 > thresholds.maxP95LatencyMillis()) {
            failedThresholds.add("p95LatencyMillis");
        }
        if (tokenShare > thresholds.maxTokenShare()) {
            failedThresholds.add("tokenShare");
        }

        ProjectMemoryShadowMetrics metrics = new ProjectMemoryShadowMetrics(
                projectId,
                crossProjectLeakage,
                recallAtK,
                precisionAtK,
                staleConflictRate,
                abstentionRate,
                duplicateContextRate,
                sourceCoverage,
                p95,
                examinedRows,
                tokenShare,
                thresholds.defaultK(),
                List.copyOf(failedThresholds)
        );

        boolean passed = metrics.passed();
        return new ProjectMemoryShadowEvaluationAudit(
                projectId,
                corpus.datasetRevision(),
                thresholds.gateRevision(),
                metrics,
                passed,
                List.copyOf(failureReasons)
        );
    }

    private static List<ShadowEvaluationHit> rank(
            ShadowEvaluationProject project,
            ShadowEvaluationQuery query,
            int k,
            boolean enforceProjectFilter
    ) {
        String normalized = query.text().toLowerCase(Locale.ROOT);
        return project.hits().stream()
                .filter(hit -> hit.role().isEmpty() || hit.role().equals(query.role()))
                .filter(hit -> hit.activeHead() && hit.valid() && hit.redacted())
                .filter(hit -> hit.summary().toLowerCase(Locale.ROOT).contains(normalized))
                .filter(hit -> !enforceProjectFilter || hit.projectId().equals(project.projectId()))
                .sorted(Comparator.comparingDouble(ShadowEvaluationHit::quality).reversed()
                        .thenComparing(ShadowEvaluationHit::memoryId))
                .limit(k)
                .toList();
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static InputStream resourceStream(String resourcePath) {
        InputStream input = ProjectMemoryShadowEvaluationEngine.class.getClassLoader().getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IllegalArgumentException("missing resource: " + resourcePath);
        }
        return input;
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").strip();
    }

    private static List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(child -> {
            String value = child.asText("").strip();
            if (!value.isEmpty()) {
                values.add(value);
            }
        });
        return List.copyOf(values);
    }

    public record ShadowEvaluationCorpus(
            String datasetRevision,
            String gateRevision,
            List<ShadowEvaluationProject> projects
    ) {
    }

    public record ShadowEvaluationProject(
            String projectId,
            String repositoryFingerprint,
            List<ShadowEvaluationQuery> queries,
            List<ShadowEvaluationHit> hits
    ) {
    }

    public record ShadowEvaluationQuery(
            String queryId,
            String role,
            String text,
            List<String> expectedSourceIds,
            List<String> legacyBaselineSourceIds,
            int k
    ) {
    }

    public record ShadowEvaluationHit(
            String memoryId,
            String projectId,
            String role,
            String summary,
            String sourceId,
            boolean activeHead,
            boolean valid,
            boolean redacted,
            double quality,
            boolean stale,
            boolean conflict,
            long latencyMillis,
            int tokenEstimate
    ) {
    }
}
