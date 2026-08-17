# Delivery observability final acceptance (WP-9.7)

Captured **2026-08-15** on a developer machine. These numbers are local evidence, not a production capacity or SLO claim.

Change: `upgrade-delivery-observability`. No task-state, fair-scheduling, retry-count, or Provider-fallback behavior was changed. No `rd_metrics*` / `rd_delivery_observability` tables or new indexes were added.

## Commands actually run

| Command | Result |
| --- | --- |
| `./mvnw -pl engine -am -Dtest='DeliveryObservability*Test,RequirementDeliveryMetricsTest,RequirementFairSchedulingSimulationTest,DeliverySloEvaluationServiceTest' … test` | **22** pass |
| `./mvnw -pl exec -am -Dtest='AgentRuntimeMeasurementParserTest,AgentEventTokenUsageParserTest,DockerPiAgentExecutorTest' … test` | **41** run, **2** skipped |
| `./mvnw -pl bootstrap -am -Dtest='PrometheusMetricsControllerTest,DeliveryObservabilityControllerTest,…,AdminFrontendControllerTest' … test` | **109** run, **1** skipped (opt-in `ObservabilityMetricsRealSmokeTest`) |
| Extra bootstrap observability tests (consistency, adapter, properties, persistence policy, SPA route, smoke preconditions) | **11** pass |
| Opt-in `PostgresDeliveryObservabilityRealSmokeTest` with `-Drd.integration.delivery-observability.enabled=true` and `rdbot_acceptance` | **pass** (SQL: no metric tables; EXPLAIN present). HTTP half recorded separately while `:18081` was up. Default JVM skip is asserted by `PostgresDeliveryObservabilityRealSmokePreconditionsTest` (must not be combined with the enable flag). |
| `node --experimental-strip-types --test test/*.test.ts` | **152** pass |
| `npm run typecheck` / `npm run build` | pass |
| `./mvnw test` | rag **330** (2 skipped), engine **528**, exec **276** with **1** failure then isolated re-run **pass** (`DockerClaudeCodeExecutorTest.shouldAllowOnlyOneSharedHalfOpenProbeAcrossExecutors` timing flake, not observability). skill **7**. bootstrap module did not finish a clean summary in the follow-up `-pl skill,bootstrap` run (Spring `@SpringBootTest` context errors on unrelated controllers: `UserAdminControllerTest`, `RagV3Chat*`, `RagSettingsControllerTest`, `IngestionTestChannelControllerTest`). Observability-focused bootstrap tests in 9.3 were green. |
| `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict` | recorded in 9.8 |

## HTTP / DB / browser

- Live HTTP: `docs/qa/delivery-observability/live-http.md` (`:18081`, `rdbot_acceptance`). Prometheus 200, no `taskId=`/`projectId=` labels, `rd_bot_delivery_*` present after JDBC null-bind fix. 24h window is truthful no-sample; 7d has 24 terminals and success 0/24. `window=2h` and `projectId=all` return 400.
- Postgres: 24 requirement tasks in `rdbot_acceptance`; `to_regclass('rd_metrics_events')` and `rd_delivery_observability` are null.
- Browser: `docs/qa/delivery-observability/frontend-acceptance.md` plus screenshots named mobile/tablet/desktop.

## Uncovered / out of scope

- Production Prometheus retention and live multi-day SLO eligibility (documented **不可定 SLO**).
- Pi image rebuild: not required (`docs/qa/delivery-observability/pi-image-rebuild.md`).
- GitHub/Feishu/OpenViking live smokes remain opt-in and skipped by default.
- Do not sync/archive this OpenSpec change until a human reviews.

## Bug fixed during live HTTP

`#{projectId} IS NULL` without `jdbcType=VARCHAR` made PostgreSQL reject all-project queries (`could not determine data type of parameter $1`). Mapper now binds VARCHAR/TIMESTAMP. Collector failure vs no-sample vs true zero remain distinct.
