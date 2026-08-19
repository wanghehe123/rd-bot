package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.AgentRemediationRoundRow;
import com.wish.rd.bootstrap.persistence.mapper.AgentRemediationRoundMapper;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Read-only remediation ledger view used by the admin workbench and alert drill-down. */
@RestController
public class RdTaskRemediationController {
    private final AgentRemediationRoundMapper mapper;

    public RdTaskRemediationController(AgentRemediationRoundMapper mapper) {
        this.mapper = java.util.Objects.requireNonNull(mapper);
    }

    @GetMapping("/admin/rd-tasks/{taskId}/remediations")
    public RemediationOverviewView get(@PathVariable("taskId") String taskId) {
        long parsedTaskId;
        try {
            parsedTaskId = PostgresPersistenceSupport.parseId(taskId);
        } catch (NumberFormatException invalid) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "taskId must be numeric", invalid
            );
        }
        List<AgentRemediationRoundRow> rows = mapper.listByTask(parsedTaskId);
        Map<AgentRemediationKind, Integer> used = new EnumMap<>(AgentRemediationKind.class);
        for (AgentRemediationRoundRow row : rows) {
            try {
                used.merge(AgentRemediationKind.valueOf(row.kind), 1, Integer::sum);
            } catch (RuntimeException ignored) {
                // Unknown future kinds remain visible per-row but cannot consume a known v2 limit.
            }
        }
        List<RemediationRoundView> rounds = rows.stream().map(row -> toView(row, used)).toList();
        List<RemediationCapacityView> capacities = java.util.Arrays.stream(AgentRemediationKind.values())
                .map(kind -> new RemediationCapacityView(
                        kind.name(), kind.maximumRounds(), used.getOrDefault(kind, 0),
                        Math.max(0, kind.maximumRounds() - used.getOrDefault(kind, 0))
                )).toList();
        String manualReason = rows.stream()
                .filter(row -> "FAILED_NEEDS_HUMAN".equals(row.status))
                .findFirst()
                .map(row -> "remediation " + row.kind + " reached FAILED_NEEDS_HUMAN")
                .orElse("");
        return new RemediationOverviewView(taskId, capacities, rounds, manualReason);
    }

    private static RemediationRoundView toView(
            AgentRemediationRoundRow row,
            Map<AgentRemediationKind, Integer> used
    ) {
        AgentRemediationKind known = null;
        try {
            known = AgentRemediationKind.valueOf(row.kind);
        } catch (RuntimeException ignored) {
            // Forward-compatible read view.
        }
        int limit = known == null ? 0 : known.maximumRounds();
        int occupied = known == null ? 0 : used.getOrDefault(known, 0);
        return new RemediationRoundView(
                PostgresPersistenceSupport.idString(row.id),
                row.kind,
                row.remediationNo == null ? 0 : row.remediationNo,
                PostgresPersistenceSupport.idString(row.sourceStageRunId),
                PostgresPersistenceSupport.idString(row.targetCodingStageRunId),
                PostgresPersistenceSupport.idString(row.targetQaStageRunId),
                PostgresPersistenceSupport.idString(row.firstCommandId),
                row.status == null ? "" : row.status,
                row.requestHash == null ? "" : row.requestHash,
                limit,
                occupied,
                Math.max(0, limit - occupied),
                "FAILED_NEEDS_HUMAN".equals(row.status)
                        ? "round requires human intervention" : "",
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    public record RemediationOverviewView(
            String taskId,
            List<RemediationCapacityView> capacities,
            List<RemediationRoundView> rounds,
            String manualReason
    ) { }

    public record RemediationCapacityView(String kind, int limit, int used, int remaining) { }

    public record RemediationRoundView(
            String roundId,
            String kind,
            int remediationNo,
            String sourceStageRunId,
            String targetCodingStageRunId,
            String targetQaStageRunId,
            String firstCommandId,
            String status,
            String requestHash,
            int plannedLimit,
            int used,
            int remaining,
            String manualReason,
            long createdAtEpochMillis,
            long updatedAtEpochMillis
    ) { }
}
