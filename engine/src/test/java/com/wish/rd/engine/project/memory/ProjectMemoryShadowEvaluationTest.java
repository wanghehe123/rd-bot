package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.project.memory.model.ProjectMemoryGateThresholds;
import com.wish.rd.engine.project.memory.model.ProjectMemoryShadowEvaluationAudit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryShadowEvaluationTest {
    private static final String CORPUS = "project-memory-evaluation/corpus-v1.json";
    private static final String THRESHOLDS = "project-memory-evaluation/gate-thresholds-v1.json";

    private final ProjectMemoryShadowEvaluationEngine engine = new ProjectMemoryShadowEvaluationEngine();

    @Test
    void computesFrozenMetricsAndEmbedsDatasetAndGateRevisionInAudit() {
        var corpus = engine.loadCorpus(CORPUS);
        ProjectMemoryGateThresholds thresholds = engine.loadThresholds(THRESHOLDS);

        ProjectMemoryShadowEvaluationAudit audit = engine.evaluate("101", corpus, thresholds);

        assertEquals("corpus-v1", audit.datasetRevision());
        assertEquals("gate-v1", audit.gateRevision());
        assertEquals("101", audit.projectId());
        assertTrue(audit.passed());
        assertEquals(0, audit.metrics().crossProjectLeakage());
        assertEquals(1.0d, audit.metrics().recallAtK(), 0.0001d);
        assertEquals(1.0d, audit.metrics().precisionAtK(), 0.0001d);
        assertEquals(0.0d, audit.metrics().staleConflictRate(), 0.0001d);
        assertEquals(0.0d, audit.metrics().abstentionRate(), 0.0001d);
        assertEquals(0.0d, audit.metrics().duplicateContextRate(), 0.0001d);
        assertEquals(1.0d, audit.metrics().sourceCoverage(), 0.0001d);
        assertEquals(2, audit.metrics().examinedRows());
        assertTrue(audit.metrics().p95LatencyMillis() <= thresholds.maxP95LatencyMillis());
        assertTrue(audit.metrics().tokenShare() <= thresholds.maxTokenShare());
        assertTrue(audit.metrics().failedThresholds().isEmpty());
    }

    @Test
    void detectsCrossProjectLeakageWhenProjectFilterIsDisabled() {
        var corpus = engine.loadCorpus(CORPUS);
        ProjectMemoryGateThresholds thresholds = engine.loadThresholds(THRESHOLDS);

        ProjectMemoryShadowEvaluationAudit audit = engine.evaluate("102", corpus, thresholds, false);

        assertFalse(audit.passed());
        assertEquals(1, audit.metrics().crossProjectLeakage());
        assertTrue(audit.failureReasons().contains("cross-project leakage detected"));
        assertTrue(audit.metrics().failedThresholds().contains("crossProjectLeakage"));
    }

    @Test
    void passesProjectIsolationProbeWhenFilteringIsEnforced() {
        var corpus = engine.loadCorpus(CORPUS);
        ProjectMemoryGateThresholds thresholds = engine.loadThresholds(THRESHOLDS);

        ProjectMemoryShadowEvaluationAudit audit = engine.evaluate("102", corpus, thresholds, true);

        assertTrue(audit.passed());
        assertEquals(0, audit.metrics().crossProjectLeakage());
    }
}
