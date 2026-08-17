# Live HTTP evidence (2026-08-15)

Process: isolated `spring-boot:run` on **18081**, database `rdbot_acceptance`, `spring.task.scheduling.enabled=false`.
The first boot returned collector failure because PostgreSQL could not type-bind `#{projectId} IS NULL`. After `jdbcType=VARCHAR` / `TIMESTAMP`, the ledger collector succeeds.

## `/actuator/prometheus` → **200** (15113 bytes)

- `taskId=` count: 0
- `projectId=` count: 0
- `stageRunId=` count: 0
- `rd_bot_delivery_completed_total` present: true
- `rd_bot_observability_collection_success{source="delivery_ledger"}` = 1

Representative lines:

```
rd_bot_observability_collection_success{source="delivery_ledger"} 1
rd_bot_delivery_completed_total{outcome="success"} 0
rd_bot_delivery_completed_total{outcome="failure"} 0
rd_bot_delivery_completed_total{outcome="cancelled"} 0
rd_bot_delivery_queue_backlog{status="WAITING",resource="GENERIC"} 0
```

## Admin routes

| Path | Status | Notes |
| --- | --- | --- |
| `/admin/observability/delivery/overview` (24h) | 200 | ledger available; successRate `noSample=true` (0/0). True empty window, not a fake healthy zero-rate. |
| `/admin/observability/delivery/overview?window=7d` | 200 | accepted=24 terminal=24 running=0; successRate 0/24 `noSample=false` (true zero successes). |
| `/admin/observability/delivery/timeseries` | 200 | |
| `/admin/observability/delivery/failures` | 200 | |
| `/admin/observability/delivery/tasks?window=7d` | 200 | total=24; item keys: taskId, title, projectId, status, role, durationSeconds, failureCategory, tracePath, taskPath. No prompt/raw events. Paths `/admin/traces/{id}` and `/admin/rd-tasks/{id}`. |
| `/admin/observability` | 200 | SPA index.html |
| `overview?window=2h` | 400 | `{"message":"unsupported observability window: 2h"}` |
| `overview?projectId=all` | 400 | `{"message":"projectId all is not a real project id"}` |

Local datasource failure (before the JDBC type fix) returned `dataQuality[0].available=false` with warning `delivery ledger query failed` and omitted `rd_bot_delivery_*` business series while still exposing `rd_bot_observability_*` self-health. That path remains covered by unit tests and the earlier capture in this file's first draft.
