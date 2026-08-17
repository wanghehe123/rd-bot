# Delivery observability baseline (WP-0)

Captured **2026-08-15** on a developer machine. These numbers describe the
local Docker PostgreSQL instance used for this change. They are **not**
production capacity, SLO, or traffic conclusions.

## Environment

| Item | Value |
| --- | --- |
| Host | developer laptop, Darwin |
| PostgreSQL | Docker container `postgres` on `127.0.0.1:5432` |
| Databases inspected | `rdbot_acceptance` (small), `ragent` (larger leftover) |
| Default app URL in yaml | `jdbc:postgresql://127.0.0.1:5432/rdbot` — that database **does not exist** on this host |
| How EXPLAIN was run | `docker exec postgres psql -U postgres -d <db> -c 'EXPLAIN (ANALYZE, BUFFERS) ...'` |
| Prometheus scrape | `PrometheusMetricsController.render(MetricsSnapshot.empty())` in-process; no live HTTP scrape this round |

## Fixture / ledger row counts

Deterministic Java catalog (`DeliveryObservabilityFixtures.catalog()`): 6 tasks
(success, running, retry, human failure, negative duration, missing usage).
Empty-window catalog: 0 tasks.

### `rdbot_acceptance`

| Relation | Rows |
| --- | --- |
| `rd_tasks` | 24 |
| `rd_tasks` where `task_type='REQUIREMENT'` | 24 |
| `rd_task_status_events` | 0 |
| `rd_agent_stage_runs` | 42 |
| `rd_requirement_stage_commands` | 40 |
| `rd_rag_retrieval_runs` | 0 |
| `rd_rag_retrieval_steps` | 0 |
| `rd_task_retry_checkpoints` | 25 |
| `rd_ai_review_runs` | 0 |
| `knowledge_documents` | 0 |

### `ragent` (larger leftover, still not production)

| Relation | Rows |
| --- | --- |
| `rd_tasks` | 177 |
| requirement tasks | 101 |
| `rd_agent_stage_runs` | 475 |
| `rd_requirement_stage_commands` | 175 |
| `rd_task_status_events` | 1694 |

## Current Prometheus SQL plans

### Mean task lifecycle (today's incorrect context-latency / MTTR source)

```sql
SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (updated_at - created_at))), 0)
FROM rd_tasks
WHERE task_type = 'REQUIREMENT';
```

`rdbot_acceptance`: Index Scan `idx_rd_tasks_type_status`, 24 rows, execution **4.2 ms**, buffers hit=3.
`ragent`: Seq Scan, 101 requirement / 177 total, execution **0.24 ms**, buffers hit=32.

### Stage totals

```sql
SELECT role, status, COUNT(*) FROM rd_agent_stage_runs GROUP BY role, status;
```

`rdbot_acceptance`: Seq Scan 42 rows, HashAggregate, execution **0.38 ms**.

### Planned durable backlog (not yet queried by Prometheus)

```sql
SELECT status, resource_class, COUNT(*),
       COALESCE(MAX(EXTRACT(EPOCH FROM (now() - created_at))), 0)
FROM rd_requirement_stage_commands
WHERE status IN ('PENDING','FAILED_RETRYABLE','RUNNING')
GROUP BY status, resource_class;
```

`rdbot_acceptance`: Seq Scan 40 rows (0 matching backlog), execution **1.7 ms**.
`ragent`: Seq Scan 175 rows (0 matching backlog), execution **0.07 ms**.

### Windowed status events (planned phase-latency source)

```sql
SELECT task_id, status, entered_at, duration_ms
FROM rd_task_status_events
WHERE entered_at >= now() - interval '24 hours'
ORDER BY task_id, entered_at;
```

`rdbot_acceptance`: Seq Scan, 0 rows in 24h, execution **0.76 ms**.
`ragent` 24h: Index Scan `idx_rd_task_events_task` (`task_id, entered_at`), 0 rows, execution **10.4 ms** (cold-ish buffers).
`ragent` 7d: same index, **205 rows**, execution **0.13 ms**, buffers hit=15.

The existing index is `(task_id, entered_at)`. A window filter on `entered_at`
alone is usable but not optimal at larger volume. **No index is added in WP-0.**
WP-6 may add one only if repeated `EXPLAIN (ANALYZE, BUFFERS)` at realistic
volume misses the provisional local p95 < 500 ms overview target.

### Finished stage runs in 24h

```sql
SELECT id, task_id, role, status, attempt_no, started_at, finished_at
FROM rd_agent_stage_runs
WHERE finished_at >= now() - interval '24 hours';
```

`ragent`: Seq Scan 475 rows, 0 in the last 24h, execution **12.9 ms**.
`idx_rd_agent_stage_runs_task_status` is `(task_id, status, updated_at)` and
does not cover `finished_at`. Again: observe first, do not add an index yet.

## `/actuator/prometheus` response size

`PrometheusMetricsController.render(MetricsSnapshot.empty())` on 2026-08-15
emits the current HELP/TYPE/zero gauges (delivery rates, RAG, control-plane,
projection `NO_DATA`, inventory categories). Measured UTF-8 length:

| Snapshot | Bytes | Notes |
| --- | --- | --- |
| `MetricsSnapshot.empty()` | see test log / WP-0 measurement below | in-process renderer only |
| Live HTTP scrape | not taken | no `rdbot` database; backend not required for WP-0 |

UTF-8 byte length of `render(empty())` recorded by running:

```bash
./mvnw -pl bootstrap -am -Dtest=PrometheusMetricsControllerTest#shouldRenderInventorySeriesEvenBeforeAnyDocumentExists \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

A follow-up measurement after WP-3 must compare v2 scrape size with this
baseline. Do not treat either number as a production cardinality budget.

Empty-render size measured from source on 2026-08-15: **approximately 2.3–2.8 KiB**
(HELP/TYPE + zero gauges for rates, RAG, checkpoints, AI review, projection
`NO_DATA`, and every `InventoryCategory`). Exact live bytes depend on label
escaping and default role padding (`rd_bot_agent_stage_total` for four delivery
roles).

## What this baseline does not prove

- Production row counts, cache hit rates, or p95 query latency
- Whether a new index or rollup table is required
- SLO thresholds
- Prometheus remote-write retention

Those decisions stay in WP-5 / WP-6 after measured traffic.
