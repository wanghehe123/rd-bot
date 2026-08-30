package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.project.memory.model.ProjectMemorySecurityAlert;
import com.wish.rd.engine.project.memory.model.ProjectMemorySecurityAlertType;
import com.wish.rd.engine.project.memory.model.ProjectMemoryShadowEvaluationAudit;
import com.wish.rd.rag.project.memory.ProjectMemoryModeService;
import com.wish.rd.rag.project.memory.model.ProjectMemoryMode;
import com.wish.rd.rag.project.memory.model.ProjectMemoryModeConfig;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Binds frozen shadow gate results to per-project PRIMARY rollout. Canary promotion and rollback are
 * isolated per project.
 */
public final class ProjectMemoryGateBindingService {
    private final ProjectMemoryModeService modeService;
    private final ProjectMemoryGateBindingStore bindingStore;
    private final ProjectMemorySecurityAlertSink alertSink;
    private final Supplier<Long> clock;

    public ProjectMemoryGateBindingService(
            ProjectMemoryModeService modeService,
            ProjectMemoryGateBindingStore bindingStore,
            ProjectMemorySecurityAlertSink alertSink
    ) {
        this(modeService, bindingStore, alertSink, System::currentTimeMillis);
    }

    ProjectMemoryGateBindingService(
            ProjectMemoryModeService modeService,
            ProjectMemoryGateBindingStore bindingStore,
            ProjectMemorySecurityAlertSink alertSink,
            Supplier<Long> clock
    ) {
        this.modeService = Objects.requireNonNull(modeService, "modeService must not be null");
        this.bindingStore = Objects.requireNonNull(bindingStore, "bindingStore must not be null");
        this.alertSink = alertSink == null ? ProjectMemorySecurityAlertSink.noop() : alertSink;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public ProjectMemoryModeConfig promoteToPrimary(
            String projectId,
            String gateRevision,
            ProjectMemoryShadowEvaluationAudit audit
    ) {
        String normalizedProjectId = requireProjectId(projectId);
        String normalizedGateRevision = requireText(gateRevision, "gateRevision");
        Objects.requireNonNull(audit, "audit must not be null");
        if (!audit.projectId().equals(normalizedProjectId)) {
            throw new IllegalArgumentException("audit project mismatch");
        }
        if (!normalizedGateRevision.equals(audit.gateRevision())) {
            publishAlert(normalizedProjectId, ProjectMemorySecurityAlertType.GATE_REVISION_MISMATCH,
                    "gate revision mismatch", normalizedGateRevision, audit.datasetRevision());
            throw new ProjectMemoryGateBindingRejectedException("gate revision mismatch");
        }
        if (audit.metrics().crossProjectLeakage() > 0) {
            publishAlert(normalizedProjectId, ProjectMemorySecurityAlertType.CROSS_PROJECT_LEAKAGE,
                    "cross-project leakage detected", normalizedGateRevision, audit.datasetRevision());
            throw new ProjectMemoryGateBindingRejectedException("cross-project leakage detected");
        }
        if (!audit.passed()) {
            publishAlert(normalizedProjectId, ProjectMemorySecurityAlertType.GATE_THRESHOLD_FAILURE,
                    String.join(",", audit.metrics().failedThresholds()), normalizedGateRevision, audit.datasetRevision());
            throw new ProjectMemoryGateBindingRejectedException("shadow gate thresholds not met");
        }

        ProjectMemoryModeConfig promoted = modeService.updatePolicy(normalizedProjectId, ProjectMemoryMode.PRIMARY);
        bindingStore.save(new com.wish.rd.engine.project.memory.model.ProjectMemoryGateBinding(
                normalizedProjectId, normalizedGateRevision, audit.datasetRevision(), clock.get()));
        return promoted;
    }

    public ProjectMemoryModeConfig rollbackToLegacy(String projectId) {
        String normalizedProjectId = requireProjectId(projectId);
        bindingStore.clear(normalizedProjectId);
        return modeService.updatePolicy(normalizedProjectId, ProjectMemoryMode.OFF);
    }

    public boolean isGateRevisionBound(String projectId, String gateRevision) {
        return bindingStore.find(requireProjectId(projectId))
                .map(binding -> binding.gateRevision().equals(gateRevision))
                .orElse(false);
    }

    private void publishAlert(
            String projectId,
            ProjectMemorySecurityAlertType type,
            String message,
            String gateRevision,
            String datasetRevision
    ) {
        alertSink.publish(new ProjectMemorySecurityAlert(
                projectId, type, message, gateRevision, datasetRevision, clock.get()));
    }

    private static String requireProjectId(String projectId) {
        String normalized = projectId == null ? "" : projectId.strip();
        if (!normalized.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("projectId must be a non-empty numeric identifier");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
