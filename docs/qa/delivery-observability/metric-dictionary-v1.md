# Delivery observability metric dictionary v1

Versioned contract for `upgrade-delivery-observability`. Names, units, windows,
sources, availability, and label allowlists are frozen here. Implementation must
not invent a second meaning for the same name.

Inventory command (2026-08-15):

```bash
rg -n 'rd_bot_|/actuator/prometheus' bootstrap frontend docs
```

Frontend has **no** Prometheus consumers. Current `/actuator/prometheus`
consumers are Bootstrap tests, production-acceptance reports, and RULE.md
knowledge-projection/inventory series.

This dictionary is a planning/implementation contract. Local scrape sizes and
SQL timings belong in `baseline.md` and must not be copied here as production
capacity claims.

## Shared rules

| Rule | Value |
| --- | --- |
| Duration unit | seconds |
| Token unit | integer count |
| Cost unit | estimated CNY, never billed truth |
| Default window | 24h |
| Window allowlist | 1h, 24h, 7d, 30d |
| Prometheus forbidden labels | `taskId`, `stageRunId`, `projectId`, model free text, repository URL, error message |
| Prometheus allowed labels | `phase`, `role`, `runtime`, `provider` (allowlist), `outcome`, `status`, `resource`, `direction`, `cache`, `source`, `reason`, `le` |
| Missing timestamp | unavailable / invalid sample, never a zero duration |
| Running work | excluded from completed percentiles; counted in active/age gauges |
| Ratios | always expose numerator, denominator, sampleCount on the admin API |
| P99 | suppressed as insufficient below configured min sample count (default 30) |
| Host-finalizer `duration_ms=0` | ignored; use adjacent `entered_at` or `started_at`/`finished_at` |
| New metric tables | **not** in this change |

Collector sources: `delivery_ledger`, `retrieval_ledger`, `command_ledger`,
`scheduler_live`, `runtime_measurement`, `control_plane`,
`knowledge_projection`, `knowledge_inventory`, `scrape_self`.

## Compatibility status

| Status | Meaning |
| --- | --- |
| `correct` | Keep name and current meaning |
| `correctable` | Keep name during deprecation; recompute from a real synonym |
| `deprecated-misleading` | Stop emitting a fake value; document replacement |
| `new` | v1 series introduced by this change |
| `unrelated` | Knowledge projection/inventory; independently collected |

Deprecation window: one release after consumers and acceptance tests migrate.
Do not delete a correctable name in this change.

---

## 1. Current `/actuator/prometheus` series

| Name | Unit | Numerator / denominator | Window (current) | Source | Availability today | Labels | Compat |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `rd_bot_context_build_latency_seconds` | seconds | wrongly `AVG(updated_at-created_at)` of all requirement tasks | all-time | `rd_tasks` | silent 0 on empty/error | none | **deprecated-misleading → correctable** from context phase `entered_at` |
| `rd_bot_repair_success_rate` | ratio 0-1 | success statuses / all requirement tasks (includes running) | all-time | `rd_tasks` | silent 0 | none | correctable; terminal-only denominator |
| `rd_bot_validation_pass_rate` | ratio 0-1 | QA `SUCCEEDED` / all QA stage runs | all-time | `rd_agent_stage_runs` | silent 0 | none | correctable |
| `rd_bot_pr_creation_rate` | ratio 0-1 | tasks with non-empty `pull_request_url` / all requirement tasks | all-time | `rd_tasks` | silent 0 | none | correctable |
| `rd_bot_human_intervention_rate` | ratio 0-1 | `FAILED_NEEDS_HUMAN` / all requirement tasks | all-time | `rd_tasks` | silent 0 | none | correctable |
| `rd_bot_retry_rate` | ratio 0-1 | `attempt_no>1` / max(QA runs, all tasks) | all-time | stage runs + tasks | silent 0 | none | correctable |
| `rd_bot_mean_time_to_repair_seconds` | seconds | mean `updated_at-created_at` including running | all-time | `rd_tasks` | silent 0 | none | correctable; terminal e2e only |
| `rd_bot_top_failure_categories` | count | failed stage runs grouped by `error_category` or status | all-time | `rd_agent_stage_runs` | `{category=NONE} 0` | `category` (must become allowlist / `UNKNOWN`) | correctable |
| `rd_bot_agent_stage_total` | count | stage runs by role+status | point-in-time | `rd_agent_stage_runs` | default `NO_DATA` 0 per role | `role`, `status` | correct; keep |
| `rd_bot_rag_run_total` | count | retrieval runs by status | point-in-time | `rd_rag_retrieval_runs` | `{status=NO_DATA} 0` | `status` | correct |
| `rd_bot_rag_run_duration_seconds` | seconds | mean run `updated-created` | all-time | `rd_rag_retrieval_runs` | silent 0 | none | correctable → histogram |
| `rd_bot_rag_step_duration_seconds` | seconds | mean `duration_ms/1000` | all-time | `rd_rag_retrieval_steps` | silent 0 | none | correctable |
| `rd_bot_rag_iteration_total` | count | sum `current_iteration` | all-time | `rd_rag_retrieval_runs` | silent 0 | none | correct |
| `rd_bot_rag_degraded_total` | count | `SUCCEEDED_DEGRADED` | all-time | `rd_rag_retrieval_runs` | silent 0 | none | correct |
| `rd_bot_rag_scope_violation_total` | count | `error_category=SCOPE_VIOLATION` | all-time | `rd_rag_retrieval_runs` | silent 0 | none | correct |
| `rd_bot_rag_context_budget_ratio` | ratio 0-1 | mean preview length / budget | all-time | `rd_rag_retrieval_runs` | silent 0 | none | correctable |
| `rd_bot_task_retry_checkpoint_total` | count | checkpoints by status | point-in-time | `rd_task_retry_checkpoints` | `{status=NO_DATA} 0` | `status` | correct |
| `rd_bot_ai_review_run_total` | count | reviews by status | point-in-time | `rd_ai_review_runs` | `{status=NO_DATA} 0` | `status` | correct |
| `rd_bot_ai_review_score` | score | mean score where `score>0` | all-time | `rd_ai_review_runs` | silent 0 | none | correctable |
| `rd_bot_ai_review_duration_seconds` | seconds | mean `updated-created` | all-time | `rd_ai_review_runs` | silent 0 | none | correctable |
| `rd_bot_knowledge_projection_outbox_total` | count | outbox by status | point-in-time | `knowledge_external_index_outbox` | `{status=NO_DATA} 0` | `status` | unrelated / correct |
| `rd_bot_knowledge_projection_binding_total` | count | bindings by status | point-in-time | `knowledge_external_index_bindings` | `{status=NO_DATA} 0` | `status` | unrelated / correct |
| `rd_bot_knowledge_projection_stuck_total` | count | `NEEDS_HUMAN`+`DEAD_LETTER` | point-in-time | outbox | silent 0 | none | unrelated / correct |
| `rd_bot_knowledge_projection_oldest_pending_seconds` | seconds | max age of unconverged outbox | point-in-time | outbox | silent 0 | none | unrelated / correct |
| `rd_bot_knowledge_inventory_documents_total` | count | documents by audit category | point-in-time | `knowledge_documents` | category=0 | `category` | unrelated / correct |
| `rd_bot_knowledge_inventory_backfill_pending` | count | `PENDING_BACKFILL` | point-in-time | inventory SQL | silent 0 | none | unrelated / correct |

### Consumers of current series

| Consumer | Path | What it asserts |
| --- | --- | --- |
| `PrometheusMetricsControllerTest` | `bootstrap/.../PrometheusMetricsControllerTest.java` | names present; some exact values |
| `ObservabilityMetricsEvidenceFileTest` | `bootstrap/.../ObservabilityMetricsEvidenceFileTest.java` | presence flags for context/repair/validation/PR/human/retry/MTTR/failures/stages |
| `ObservabilityMetricsProductionAcceptanceReportTest` | same package | evidence sidecar around `/actuator/prometheus` |
| `ObservabilityMetricsRealSmokeTest` | opt-in | live HTTP 200 + metric presence |
| `MultiAgentProductionAcceptanceReportTest` | report text contains metrics URL | path compatibility, not semantic values |
| `OpenVikingProductionBoundaryPolicyTest` | source contains inventory metric names | inventory series exist |
| `RULE.md` | knowledge projection + inventory | SQL ledger gauges, no in-process counters |
| `docs/qa/2026-07-11-rag-retrieval-state-redesign-acceptance.md` | historical | `rd_bot_rag_*` presence |
| `docs/qa/2026-07-13-task-stage-retry-ai-delivery-review-acceptance.md` | historical | retry checkpoint + AI review series |

`RequirementDeliveryMetrics.metricsSnapshot()` is **not** a current Prometheus
consumer; it is test-only until WP-2/WP-3.

---

## 2. v1 delivery series (`rd_bot_delivery_*`)

Fixed duration buckets (seconds, +Inf implied):
`0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120, 300, 600, 1800, 3600`.
Max retained process buckets = 16. Max process project dimensions = 0.

| Name | Unit | Numerator / object | Window membership | Source | Availability | Labels | Compat |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `rd_bot_delivery_accepted_total` | count | tasks entering delivery | accepted time in window | `rd_tasks` + status events | no-sample vs fail | `outcome` unused | new |
| `rd_bot_delivery_active` | count | non-terminal requirement tasks | point-in-time | `rd_tasks` | collector health | `phase` | new |
| `rd_bot_delivery_completed_total` | count | terminal tasks | terminal time in window | `rd_tasks` | no-sample vs fail | `outcome` = success\|failure\|cancelled | new |
| `rd_bot_delivery_duration_seconds` | histogram seconds | accepted→terminal | terminal time; running excluded | adjacent status `entered_at` | invalid if negative/missing | `phase`, `role`, `runtime`, `le` | new |
| `rd_bot_delivery_queue_backlog` | count | durable PENDING / retryable / expired lease | point-in-time | `rd_requirement_stage_commands` | collector health | `status`, `resource` | new |
| `rd_bot_delivery_queue_oldest_seconds` | seconds | oldest durable waiting command | point-in-time | command ledger | collector health | `status`, `resource` | new |
| `rd_bot_delivery_queue_wait_seconds` | histogram seconds | claim − created (process window) | claim time | `RequirementDeliveryMetrics` buckets | warm-up after restart | `le` | new |
| `rd_bot_delivery_queue_service_seconds` | histogram seconds | complete − claim (process window) | completion time | bounded live metrics | warm-up after restart | `le` | new |
| `rd_bot_delivery_resource_in_use` | count | current in-flight | point-in-time | scheduler live | collector health | `resource` | new |
| `rd_bot_delivery_resource_capacity` | count | configured cap | point-in-time | `FairScheduleLimits` | collector health | `resource` | new |
| `rd_bot_delivery_claim_total` | count | claims | process lifetime | live counters | warm-up | none | new |
| `rd_bot_delivery_retry_total` | count | stage retries | process + ledger | live + `attempt_no>1` | split source | none | new |
| `rd_bot_delivery_lease_lost_total` | count | lease losses | process lifetime | live counters | warm-up | none | new |
| `rd_bot_delivery_queue_rejected_total` | count | thread-pool / queue rejections | process lifetime | live counters | warm-up | none | new |
| `rd_bot_delivery_tokens_total` | integer | deduped attempt tokens | attempt finish in window | providerAttempts + `RUNTIME_MEASUREMENT` | unavailable if missing | `runtime`, `provider`, `role`, `direction`, `cache` | new |
| `rd_bot_delivery_estimated_cost_cny_total` | estimated CNY | summed estimates | attempt finish in window | same | unavailable if missing | `runtime`, `provider`, `role` | new |
| `rd_bot_delivery_ttft_seconds` | histogram seconds | first non-thinking text − request/agent start | attempt finish | runtime measurement | unavailable without both events | `runtime`, `role`, `le` | new |
| `rd_bot_delivery_first_response_seconds` | histogram seconds | `PROVIDER_RESPONDED` − start | attempt finish | runtime measurement | not a TTFT alias | `runtime`, `role`, `le` | new |

### Admin API ratio fields (not Prometheus labels)

| Field | Numerator | Denominator | Window |
| --- | --- | --- | --- |
| successRate | terminal success (`COMPLETED`,`COMMITTED`,`MERGED`) | terminal success + failure (`REJECTED`,`FAILED_RETRYABLE`,`FAILED_NEEDS_HUMAN`,`DEAD_LETTERED`) | terminal time |
| qaPassRate | QA attempts `SUCCEEDED` | finished QA attempts | finished time |
| prCreationRate | terminal tasks with PR URL | terminal tasks | terminal time |
| humanInterventionRate | `FAILED_NEEDS_HUMAN` | terminal tasks | terminal time |
| retryRate | stage runs with `attempt_no>1` | finished stage runs | finished time |
| unknownFailureRate | failures without allowlisted category | failed terminal tasks | terminal time |

Failure category allowlist: `TIMEOUT`, `PROVIDER`, `VALIDATION`, `BUDGET`,
`LEASE`, `CANCELLED`, `SCOPE_VIOLATION`, `UNKNOWN`. Free-text errors never
become labels.

Phase allowlist: `material`, `context`, `plan`, `policy`, `execution`,
`validation`, `publication`, `reporting`, `end_to_end`.

Role allowlist: `REQUIREMENT_REVIEWER`, `SOLUTION_ARCHITECT`, `CODING_AGENT`,
`QA_AGENT`.

Resource allowlist: `GENERIC`, `PROVIDER`, `DOCKER`, `BROWSER_QA`.

Runtime allowlist: `pi`, `claude-compat`, `unknown`.

Provider labels: configured provider id allowlist; anything else `OTHER`.

---

## 3. Data-quality series (`rd_bot_observability_*`)

| Name | Unit | Object | Window | Source | Availability | Labels | Compat |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `rd_bot_observability_collection_success` | 0/1 | last scrape of a source | point-in-time | each contributor | always emitted | `source` | new |
| `rd_bot_observability_last_success_unixtime` | unix seconds | last successful source snapshot | point-in-time | each contributor | 0 only if never succeeded **and** flagged via success=0 | `source` | new |
| `rd_bot_observability_invalid_observations_total` | count | rejected samples | process/window | calculators | always | `source`, `reason` | new |
| `rd_bot_observability_scrape_duration_seconds` | seconds | query time | scrape | scrape_self | always | none | new |
| `rd_bot_observability_scrape_bytes` | bytes | response size | scrape | scrape_self | always | none | new |
| `rd_bot_observability_cache_fresh` | 0/1 | immutable snapshot cache | scrape | scrape_self | always | none | new |

`reason` allowlist: `missing_timestamp`, `negative_duration`, `zero_duration_ms_untrusted`,
`duplicate_attempt`, `unknown_label`, `parse_error`.

---

## 4. Corrected legacy aliases

| Legacy name | Replacement | Action in this change |
| --- | --- | --- |
| `rd_bot_context_build_latency_seconds` | `rd_bot_delivery_duration_seconds{phase="context"}` | recompute from adjacent CONTEXT_BUILDING/CONTEXT_READY `entered_at`; update HELP; never copy MTTR |
| `rd_bot_mean_time_to_repair_seconds` | `rd_bot_delivery_duration_seconds{phase="end_to_end"}` mean of terminal samples | recompute from accepted→terminal; empty window → omit business value, emit collection/no-sample health |
| `rd_bot_repair_success_rate` | admin `successRate` | keep gauge but use terminal denominator; 0 only when denominator>0 |
| Other rate gauges | matching admin ratios | same no-sample rule |

If a legacy gauge cannot be recomputed from a real synonym, stop emitting the
misleading number and record the migration in collector health / release notes.

---

## 5. Scheduler memory contract (WP-2)

| Field | Current defect | Replacement freeze |
| --- | --- | --- |
| `queueAgeMillis` | unbounded `ArrayList` for process lifetime | 16 duration buckets only |
| `projectClaims` | unbounded `Map<projectId, LongAdder>` | **0** project dimensions in process metrics |
| `oldestQueueAgeMillis` | process-max only | live oldest + durable ledger oldest |
| `inFlightSamples` | cumulative sum, not current gauge | current in-flight gauge + counters |

Fair scheduling claim/admission logic must not read these buckets.
