# Architecture review: WP-4 delivery observability API

**Change:** `upgrade-delivery-observability`  
**Date:** 2026-08-15  
**Trigger:** public management API + query performance + compatibility (model-escalation).  
**Advisor target:** `premium-advisor` is not registered in this harness. The `model-escalation` skill was loaded from `.opencode/skills/model-escalation/SKILL.md` on 2026-08-15; because the advisor subagent is unavailable, this review records the conservative option from the already-accepted OpenSpec `design.md` Decision 7–8 and proceeds with that option only.

## Decision

Implement four **read-only GET** adapters under `/admin/observability/delivery/**` that call `DeliveryObservabilityQueryService` only. Do not add tables, indexes, or write APIs in this change.

## Constraints honored

- No Prometheus labels for `taskId`, `stageRunId`, `projectId`, model free text, repo URL, or error text.
- Blank `projectId` means all projects; sentinel `all` is rejected with HTTP 400 before any ledger query.
- Illegal window/filter/page returns HTTP 400 with the existing `{ "message": ... }` envelope used by `RdDashboardController`.
- Controller has no `DataSource` / mapper dependency.
- Task rows expose id, title, project, status, role, duration, folded category, and links to `/admin/traces/{taskId}` and `/admin/rd-tasks/{taskId}` only.
- Timeseries bucket count is `min(window, maxTimeseriesPoints)` from configuration (default 48).
- No submit/retry/approve/capacity/lease/state mutation.

## Compatibility

- `/actuator/prometheus` path is unchanged.
- Frontend proxy adds `/admin/observability/delivery` without hijacking `/admin/observability` SPA navigation (same pattern as dashboard overview vs dashboard page).

## Query performance

- Reuse the WP-1 window/project mapper; no new SQL in the controller.
- Indexes and rollup tables remain WP-6 decisions after EXPLAIN evidence.
