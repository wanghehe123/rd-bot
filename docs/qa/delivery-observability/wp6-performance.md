# Delivery observability WP-6 performance decision

Captured **2026-08-15**. Numbers are local developer-machine evidence. They are
**not** production capacity conclusions.

## 8.1 Repeated overview/timeseries/failures

In-process catalog (`DeliveryObservabilityQueryServiceTest.inMemoryOverviewP95StaysUnderProvisionalBudget`):
40 iterations of overview + timeseries + failures against the 6-task fixture.
Provisional local target: p95 < 500 ms. The in-memory aggregation is far under
that budget; this does **not** substitute for SQL at production volume.

PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)` from WP-0 `baseline.md`:

| Query | Local result |
| --- | --- |
| requirement task count | 0.24–4.2 ms |
| stage totals | 0.38 ms |
| durable backlog | 1.7 ms |
| 24h status events | 0.13–10.4 ms |
| finished stages 24h | 12.9 ms seq scan on 475 rows |

No cache-hit claim is made for production. Timeout remains
`rd.observability.delivery.query-timeout` (default 2s).

## 8.2 Indexes

EXPLAIN does **not** prove a new index is required at the measured fixture
sizes (all plans < 15 ms). **No index migration is added in this change.**
Existing `(task_id, entered_at)` and `(task_id, status, updated_at)` indexes
remain. A future change may add `entered_at` / `finished_at` covering indexes
only after a realistic-volume miss of the p95 target.

Rollback: not applicable; no schema change.

## 8.3 Process-window coverage vs Prometheus retention

`RequirementDeliveryMetrics` now keeps fixed duration buckets and counters for
the **process window**. Durable backlog/oldest age still come from
`rd_requirement_stage_commands`. Exact historical per-lease wait is still not
recoverable because claim/completion rewrite `updated_at`.

Prometheus scrape retention is an external deployment choice. This change does
**not** add a generic metrics JSON table. If historical queue percentiles are
later required, open a separate command-attempt observation OpenSpec change.

## 8.4 Rollups

Bounded raw-ledger SQL meets the local provisional target. **No rollup or
materialized aggregate is added.** If a future measured volume misses the
target after a proven index, that work belongs in a new change with rebuild,
retention, idempotency and PostgreSQL truth spelled out.
