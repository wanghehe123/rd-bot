package com.wish.rd.engine.admin.observability.model;

/**
 * Structured alert candidate. Drill-down stays on existing admin routes.
 *
 * @param id stable signal id
 * @param metric metric name
 * @param severity INFO/WARNING
 * @param firing true when consecutive duration is satisfied
 * @param suppressed true when observation-only, ineligible, or unavailable
 * @param observedValue observed number or NaN
 * @param threshold configured threshold
 * @param window window token
 * @param projectId blank when all projects
 * @param drillDownPath existing admin path
 * @param summary operator summary, never raw error text
 */
public record DeliveryAlertCandidate(
        String id,
        String metric,
        String severity,
        boolean firing,
        boolean suppressed,
        double observedValue,
        double threshold,
        String window,
        String projectId,
        String drillDownPath,
        String summary
) {

    public DeliveryAlertCandidate {
        id = id == null ? "" : id.strip();
        metric = metric == null ? "" : metric.strip();
        severity = severity == null || severity.isBlank() ? "INFO" : severity.strip();
        window = window == null ? "24h" : window;
        projectId = projectId == null ? "" : projectId;
        drillDownPath = drillDownPath == null || drillDownPath.isBlank()
                ? "/admin/observability"
                : drillDownPath;
        summary = summary == null ? "" : summary;
    }
}
