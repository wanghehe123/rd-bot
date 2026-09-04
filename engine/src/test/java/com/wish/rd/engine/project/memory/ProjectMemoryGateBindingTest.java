package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.project.memory.model.ProjectMemoryGateThresholds;
import com.wish.rd.engine.project.memory.model.ProjectMemorySecurityAlert;
import com.wish.rd.engine.project.memory.model.ProjectMemorySecurityAlertType;
import com.wish.rd.engine.project.memory.model.ProjectMemoryShadowEvaluationAudit;
import com.wish.rd.engine.project.memory.model.ProjectMemoryShadowMetrics;
import com.wish.rd.rag.project.memory.ProjectMemoryModeService;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryModeStore;
import com.wish.rd.rag.project.memory.model.ProjectMemoryReadMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryGateBindingTest {
    private static final String CORPUS = "project-memory-evaluation/corpus-v1.json";
    private static final String THRESHOLDS = "project-memory-evaluation/gate-thresholds-v1.json";

    private final InMemoryProjectMemoryModeStore modeStore = new InMemoryProjectMemoryModeStore();
    private final ProjectMemoryModeService modeService = new ProjectMemoryModeService(modeStore);
    private final InMemoryProjectMemoryGateBindingStore bindingStore = new InMemoryProjectMemoryGateBindingStore();
    private final RecordingSecurityAlertSink alertSink = new RecordingSecurityAlertSink();
    private final ProjectMemoryShadowEvaluationEngine evaluationEngine = new ProjectMemoryShadowEvaluationEngine();
    private final ProjectMemoryGateBindingService gateBindingService = new ProjectMemoryGateBindingService(
            modeService, bindingStore, alertSink, () -> 1_700_000_000_000L);

    @Test
    void promotesCanaryProjectToPrimaryWhenGatePassesAndBindsRevision() {
        ProjectMemoryShadowEvaluationAudit audit = passingAudit("101");

        gateBindingService.promoteToPrimary("101", "gate-v1", audit);

        assertEquals(ProjectMemoryReadMode.PRIMARY, modeStore.getConfig("101").readMode());
        assertTrue(gateBindingService.isGateRevisionBound("101", "gate-v1"));
        assertTrue(alertSink.alerts().isEmpty());
    }

    @Test
    void blocksPrimaryWhenLeakageIsNonZeroAndRaisesHighPriorityAlert() {
        var corpus = evaluationEngine.loadCorpus(CORPUS);
        ProjectMemoryGateThresholds thresholds = evaluationEngine.loadThresholds(THRESHOLDS);
        ProjectMemoryShadowEvaluationAudit leakyAudit = evaluationEngine.evaluate("102", corpus, thresholds, false);

        assertThrows(ProjectMemoryGateBindingRejectedException.class,
                () -> gateBindingService.promoteToPrimary("102", "gate-v1", leakyAudit));
        assertEquals(ProjectMemoryReadMode.LEGACY, modeStore.getConfig("102").readMode());
        assertEquals(1, alertSink.alerts().size());
        assertEquals(ProjectMemorySecurityAlertType.CROSS_PROJECT_LEAKAGE, alertSink.alerts().getFirst().type());
    }

    @Test
    void rejectsPrimaryWhenGateRevisionDoesNotMatchAudit() {
        ProjectMemoryShadowEvaluationAudit audit = passingAudit("101");

        assertThrows(ProjectMemoryGateBindingRejectedException.class,
                () -> gateBindingService.promoteToPrimary("101", "gate-v2", audit));
        assertFalse(gateBindingService.isGateRevisionBound("101", "gate-v2"));
        assertEquals(ProjectMemorySecurityAlertType.GATE_REVISION_MISMATCH, alertSink.alerts().getFirst().type());
    }

    @Test
    void rejectsPrimaryWhenAnyFrozenThresholdFails() {
        ProjectMemoryShadowMetrics failingMetrics = new ProjectMemoryShadowMetrics(
                "101", 0, 0.1d, 1.0d, 0.0d, 0.0d, 0.0d, 1.0d, 10L, 2, 0.1d, 3,
                List.of("recallAtK"));
        ProjectMemoryShadowEvaluationAudit audit = new ProjectMemoryShadowEvaluationAudit(
                "101", "corpus-v1", "gate-v1", failingMetrics, false, List.of("threshold failure"));

        assertThrows(ProjectMemoryGateBindingRejectedException.class,
                () -> gateBindingService.promoteToPrimary("101", "gate-v1", audit));
        assertEquals(ProjectMemorySecurityAlertType.GATE_THRESHOLD_FAILURE, alertSink.alerts().getFirst().type());
    }

    @Test
    void rollbackIsolatedPerProjectWithoutAffectingCanaryPeers() {
        gateBindingService.promoteToPrimary("101", "gate-v1", passingAudit("101"));
        gateBindingService.promoteToPrimary("102", "gate-v1", passingAudit("102"));

        gateBindingService.rollbackToLegacy("101");

        assertEquals(ProjectMemoryReadMode.LEGACY, modeStore.getConfig("101").readMode());
        assertEquals(ProjectMemoryReadMode.PRIMARY, modeStore.getConfig("102").readMode());
        assertFalse(gateBindingService.isGateRevisionBound("101", "gate-v1"));
        assertTrue(gateBindingService.isGateRevisionBound("102", "gate-v1"));
    }

    private ProjectMemoryShadowEvaluationAudit passingAudit(String projectId) {
        var corpus = evaluationEngine.loadCorpus(CORPUS);
        ProjectMemoryGateThresholds thresholds = evaluationEngine.loadThresholds(THRESHOLDS);
        return evaluationEngine.evaluate(projectId, corpus, thresholds, true);
    }

    private static final class RecordingSecurityAlertSink implements ProjectMemorySecurityAlertSink {
        private final List<ProjectMemorySecurityAlert> alerts = new ArrayList<>();

        @Override
        public void publish(ProjectMemorySecurityAlert alert) {
            alerts.add(alert);
        }

        List<ProjectMemorySecurityAlert> alerts() {
            return List.copyOf(alerts);
        }
    }
}
