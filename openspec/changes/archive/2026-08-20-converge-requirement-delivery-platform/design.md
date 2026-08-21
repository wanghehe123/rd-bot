## Context

See proposal.md for motivation. Current `openspec/specs/` only documents OpenViking projection admin. The requirement-delivery main chain already runs in production code:

```
Feishu IM / 管理台 → RdTaskController POST /admin/rd-tasks/requirements
→ RequirementDeliveryEngine
→ EngineRequirementExecutionProfileResolver (compatibilityRuntime = PI)
→ AgentRuntimeRouter
→ DockerPiAgentExecutor
```

Historical enums (`BUG_FIX`, `CLAUDE_CODE`, `MODEL_ONLY`) remain so PostgreSQL rows and frozen snapshots can still be read. This change documents that boundary; it does not migrate data or rename tables.

The provenance index `docs/openspec/historical-spec-provenance-audit.md` is absent in this worktree. Superpowers plans that still mention Claude defaults or BugFix wiring are treated as superseded input, not current requirements.

## Goals / Non-Goals

**Goals:**

- Record the verified write/execute boundary as an archivable OpenSpec capability.
- Keep fail-closed semantics for illegal task types and unregistered runtimes.
- Point later changes at this spec instead of deleted engines or historical plans.

**Non-Goals:**

- No runtime, API, or frontend behavior change in this change.
- No DROP of historical SQL (`repair_records`, evaluation tables, Claude `agent_type` check on `rd_project_runtime_profiles`).
- No rename of `RdBugFixTask` or `AgentRuntimeType` enum values.
- No rewrite of the unarchived `project-agent-strategy-console` draft.
- No cleanup of leftover SPA HTML fallback for `/admin/evaluations`, unused ticket ports, or retrieval-log sink with no producer.

## Decisions

### Document the live chain, not every leftover type

The spec talks about operator-visible paths (`POST /admin/rd-tasks/requirements`, Feishu `ignored`, runtime `PI`). Leftover Java types (`TicketProviderPort`, `RagRetrievalLogEvent`) stay in design/non-goals until a later deletion change has tests.

Alternatives considered: a full-repo spec dump of every remaining class. Rejected because openspec config forbids inventing brownfield coverage without current tests.

### Keep historical rows readable

`BUG_FIX` list filters and detail views stay. Write and submit do not. Dropping the enum would break reads of existing `rd_tasks.task_type` values.

Alternatives considered: rewrite historical rows to `REQUIREMENT` on read. Rejected; that would fabricate a delivery pipeline those tasks never had.

### Fail closed at the router, not at the enum

`AgentRuntimeType` still deserializes `CLAUDE_CODE` / `MODEL_ONLY`. `AgentRuntimeRouter` throws `UnsupportedAgentRuntimeException` when no executor is registered. Compatibility default is `PI` so new work never lands on the retired path.

Alternatives considered: reject those enum values at parse time. Rejected; frozen snapshots must still decode so the error can name the requested runtime.

### Archive after validate, because the code already matches

This is a documentation change. Implementation and tests already exist on `chore/redundancy-audit`. After `openspec validate --strict` and the focused tests listed in tasks.md, the delta can be archived into `openspec/specs/requirement/delivery-platform/spec.md`.

## Risks / Trade-offs

- [Risk] Unarchived `project-agent-strategy-console` still allows choosing `CLAUDE_CODE` / `MODEL_ONLY` in its draft spec → Mitigation: this change does not modify that draft; runtime fail-closed remains the current verified behavior. A later change must reconcile the strategy console spec with Pi-only execution.
- [Risk] SPA still serves `index.html` for `/admin/evaluations` bookmarks → Mitigation: listed as non-goal; navigation no longer offers the page. Follow-up can drop the fallback without changing this capability.
- [Risk] Missing provenance audit file → Mitigation: proposal records the gap and classifies cited documents in-place.

## Migration Plan

No deployment migration. Operators who still export `RD_REPAIR_QUEUE_MODE` or `RD_EXECUTOR_OPENAI_CHAT_*` get unbound environment variables; those keys were removed from `application.yaml`.

Rollback is reverting this documentation change; it does not restore deleted executors.
