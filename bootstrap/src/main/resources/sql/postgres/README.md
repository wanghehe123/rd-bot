# Postgres schema scripts

Apply these migrations against database `rdbot` after starting local infra.

## Prerequisites

- Postgres **with pgvector** (`CREATE EXTENSION vector` is in `p0_knowledge_productionization.sql`)
- Recommended: `docker compose up -d` from the repo root (image `pgvector/pgvector:pg16`)

## Apply order

Scripts are applied in **numeric** `pN_` order (then filename). `p10` runs after `p9`; both `p8_*` files run after `p7`.

| Order | File |
|------:|------|
| 0 | `p0_knowledge_productionization.sql` |
| 1 | `p1_multi_agent_orchestration.sql` |
| 2 | `p2_rag_retrieval_state.sql` |
| 3 | `p3_task_retry_ai_review.sql` |
| 4 | `p4_web_evaluation_console.sql` |
| 5 | `p5_qa_evidence.sql` |
| 6 | `p6_evaluation_data_quality.sql` |
| 7 | `p7_project_runtime_profiles.sql` |
| 8 | `p8_default_qa_v2.sql` |
| 8 | `p8_pi_agent_runtime.sql` |
| 9 | `p9_default_qa_v2_gate.sql` |
| 10 | `p10_skill_hub.sql` |
| 11 | `p11_openviking_projection.sql` |
| 12 | `p12_openviking_reconcile.sql` |
| 13 | `p13_openviking_identity_backfill.sql` |
| 14 | `p14_agent_strategy_profiles.sql` |
| 15 | `p15_host_verification.sql` |
| 16 | `p16_model_provider_credentials.sql` |
| 17 | `p17_fix_tool_policy_seed_hashes.sql` |
| 18 | `p18_pi_agent_state_and_remediation.sql` |
| 19 | `p19_project_agent_memory.sql` |
| 20 | `p20_task_audited_state.sql` |
| 21 | `p21_host_verify_fix_command_generation.sql` |
| 22 | `p22_task_manager_decisions.sql` |

Skip `README.md` and any non-`.sql` files.

`p18_pi_agent_state_and_remediation.sql` is additive and PI-capability gated. It adds the
execution-profile capability column, the live Agent-state projection, and the durable
`QA_PRODUCT_FIX` / `QA_PROTOCOL_RETRY` remediation ledger. Remediation commands carry a
round identity that is mutually exclusive with normal and retry-checkpoint generations;
historical rows remain normal commands and historical profiles default to no capabilities.

`p19_project_agent_memory.sql` adds project-scoped Agent Memory revisions, sources, and
operations. PostgreSQL remains the canonical store.

`p20_task_audited_state.sql` adds Host audited-state heads/revisions/audit runs/completion
bindings, and relaxes `rd_agent_remediation_rounds.kind` to accept `HOST_VERIFY_FIX`
while keeping `remediation_no BETWEEN 1 AND 2` for product-fix and host-verify-fix kinds.

`p21_host_verify_fix_command_generation.sql` extends
`ck_rd_requirement_stage_command_generation_v2` so `HOST_VERIFY_FIX` commands may use the
remediation generation identity (p20 allowed the round kind but not the command row).

`p22_task_manager_decisions.sql` adds `rd_task_manager_decisions` (`UNIQUE(task_id, round_no)`,
`UNIQUE(task_id, source_command_id)`), extends rounds kind/number/targets and command generation
CHECKs with `MANAGER_GAP_FIX`, and adds a partial unique index on coding-targeting rounds per
`source_stage_run_id`. Claim/recoverable SQL in `RequirementStageCommandMapper` joins `rd_tasks`
so `paused=true` is never claimed and `WAITING_USER_INPUT` only admits `USER_ANSWER_RESUME`.

## Bootstrap

```bash
docker compose up -d
./scripts/bootstrap-db.sh
```

Defaults: `localhost:5432`, database `rdbot`, user/password `postgres`/`postgres`.

The local RD-Bot control-plane database is often `ragent`. Use `POSTGRES_DB=ragent` (or `POSTGRES_URL`) when applying against that instance.

Overrides: `POSTGRES_URL` (JDBC or `postgresql://…`), or `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` (also `POSTGRES_USERNAME`).

## MinIO bucket

`docker compose` includes a one-shot `minio-init` service that creates bucket `biz`. Manual alternative:

```bash
mc alias set local http://localhost:9000 rustfsadmin rustfsadmin
mc mb --ignore-existing local/biz
```
