# RD-Bot Agent Guide

This file applies to the whole repository. Read it before changing code.

## Product Positioning

RD-Bot is an AI-native software delivery harness for R&D workflows. It is not a
chatbot and not a direct code-generation demo. Treat the model as a replaceable
worker inside a controlled platform.

The project value lives in the harness around the model:

- workflow orchestration,
- task-scoped RAG context packaging,
- tool and connector boundaries,
- sandboxed execution,
- validation and rollback evidence,
- PR-based delivery,
- audit trails,
- policy gates,
- human-in-the-loop recovery.

The goal is to turn a Feishu ticket, mock ticket, or manually created
engineering task into a governed repair workflow:

`Ticket -> Context -> Plan -> Policy Gate -> Sandbox Execution -> Validation -> Pull Request -> Report -> Audit`

## Harness Engineering Principles

- Do not attribute product capability only to the model. Build the surrounding
  tools, state, constraints, execution environment, feedback, and observability.
- Keep the control plane separate from the execution plane. The platform
  orchestrates; delegates execute.
- External systems are replaceable connectors behind ports.
- Every risky operation must be policy-checked before it changes code,
  repositories, tickets, secrets, or production-adjacent state.
- Agent output is not trusted until validation evidence is recorded.
- Agent delivery is reviewable. Prefer PRs and reports over direct writes to
  protected branches.
- Human operators remain responsible for priority, acceptance, risk decisions,
  and manual recovery.

## Project Shape

RD-Bot is a Java 21 / Spring Boot 3.5 multi-module monolith. It is split by
layers, not by microservices.

- `rag`: core RAG domain, ingestion, knowledge, retrieval, prompt planning,
  memory, trace, settings, and runtime task state.
- `engine`: orchestration layer. It wires RAG capabilities into end-to-end
  flows. Keep business orchestration here.
- `bootstrap`: Spring Boot entrypoint, REST controllers, configuration, static
  admin frontend, and external adapter implementations.
- `exec`: execution-plane module. It owns the contract for sandboxed repair
  execution, repair records, validation results, artifact persistence, and code
  platform ports. The first production direction is Docker + Claude Code, but
  domain contracts must not depend on Claude-specific APIs.
- `skill`: reusable workflow-step module. It hosts composable repair,
  validation, analysis, risk scoring, review, and reporting skills as they are
  extracted from the main workflow.
- `frontend`: frontend workspace if present. Do not assume it is part of Maven.

Follow [RULE.md](RULE.md) for Java style, naming, comments, REST conventions,
patterns, and tests.

## Harness Concept Mapping

RD-Bot intentionally separates the control plane, execution plane,
knowledge/context plane, and integration plane.

| RD-Bot module | Harness-style concept | Responsibility |
| --- | --- | --- |
| `bootstrap` | Adapter/API plane | REST, configuration, admin UI, and external adapter implementations. |
| `engine` | Control plane / workflow orchestrator | Turns tickets or manual tasks into repair workflows and coordinates stages. |
| `rag` | Knowledge/context plane | Produces task-scoped context packages, retrieval traces, and prompt plans. |
| `exec` | Delegate/execution plane | Runs repair agents in sandboxed environments, records artifacts, and validates results. |
| `skill` | Reusable workflow steps | Common repair, validation, analysis, policy, and reporting capabilities. |
| `frontend` | Developer portal / operations UI | Shows task state, evidence, policy results, metrics, and manual recovery actions. |

## Core Domain Vocabulary

- `RepairWorkflow`: one end-to-end automated R&D task.
- `RepairRecord`: durable audit record for one workflow execution.
- `RepairArtifact`: large output produced by a workflow stage, such as logs,
  patches, prompts, test results, and PR metadata.
- `ContextPackage`: task-scoped RAG output used by the repair agent.
- `ExecutionDelegate`: sandboxed worker that runs code repair and validation.
- `PolicyGate`: programmable guardrail before risky operations.
- `Connector`: replaceable adapter for ticket, code, model, queue, storage, and
  notification systems.
- `DistributedLockExecutor`: domain-level lock port used when a runtime state
  transition must be mutually exclusive across deployed instances.

## Current Product Direction

The public, recruiter-readable future direction lives in
[docs/roadmap/public-roadmap.md](docs/roadmap/public-roadmap.md).

The private implementation plan lives in Feishu Wiki under the parent page:

- Parent: `https://my.feishu.cn/wiki/KN6dwQ48wic3OcktRghccUnnnng`
- P0: `https://my.feishu.cn/wiki/W7bzwwbAciPkqZkECSXc146znfb`
- P1: `https://my.feishu.cn/wiki/LHEGwQY69ipBx5kzXNgcIDQNnFf`
- P2: `https://my.feishu.cn/wiki/AAohwtzsWiKhCckDpA7clXNEn8f`
- P3: `https://my.feishu.cn/wiki/ZFGSwPefQiR8H1kukY0cQtbCn7f`

Do not invent a different roadmap. If the docs and code disagree, inspect both
and ask the user before making a design-changing edit.

Private Feishu planning pages are implementation references only and should not
be required to understand the public project direction.

## Development Priority

Build in this order unless the user explicitly changes scope:

1. **P0: Knowledge productionization first**
   - PostgreSQL persistence.
   - Project-managed SQL scripts, not Flyway/Liquibase unless the user changes
     this decision.
   - Snowflake IDs using `bigint` database columns.
   - Persistent knowledge bases, documents, chunks, ingestion tasks, node logs,
     Feishu document import, scheduled refresh, `repair_records`, and
     `repair_record_artifacts`.
2. **P1: Feishu ticket + RocketMQ scheduling**
   - Ticket system must be an interface/port. This project implements Feishu
     first.
   - The user will provide exact Feishu ticket API fields during development.
     Do not guess field names or status enums.
   - Use RocketMQ. Suggested names: topic `RD_BOT_REPAIR_TICKET`, consumer group
     `GID_RD_BOT_REPAIR_WORKER`, tags `P0`, `P1`, `P2`.
3. **P2: Docker Claude Code + GitHub PR**
   - Execution runs in Docker.
   - Claude Code runs in yolo mode by default because it is sandboxed.
   - Do not hard-kill for resource or timeout limits by default. Add timeout and
     budget alerts; RD decides whether to stop the task.
   - Code platform must be a port. This project implements GitHub first. Prefer
     GitHub App for production, `gh` CLI for local real-PR smoke, and PAT only
     as an explicit local fallback.
4. **P3: Production governance**
   - Audit, idempotency, dead-letter handling, manual recovery, alerting,
     allowlists, secret boundaries, log redaction, and operational views.

Keep this engineering roadmap separate from the demo roadmap in
[docs/roadmap/public-roadmap.md](docs/roadmap/public-roadmap.md). The demo
roadmap should prioritize a runnable golden path for portfolio and interview
review without requiring private Feishu, RocketMQ, or GitHub App credentials.

## End-to-End Workflow

RD-Bot should optimize for a durable, resumable workflow rather than a single
agent RPC:

`Ticket or Manual Task -> RepairWorkflow -> ContextPackage -> Repair Plan -> PolicyGate -> ExecutionDelegate -> Validation -> Pull Request or Artifact -> Report -> Audit`

When a real external integration is unavailable, provide a mock or local adapter
that exercises the same port contract.

## Repair Workflow State Model

A repair workflow is durable and resumable. Recommended state transitions:

`CREATED -> CONTEXT_BUILDING -> CONTEXT_READY -> PLAN_GENERATED -> WAITING_POLICY -> WAITING_APPROVAL -> EXECUTING -> VALIDATING -> PR_CREATING -> PR_CREATED -> REPORTING -> COMPLETED`

Failure and recovery states:

- `FAILED_RETRYABLE`
- `FAILED_NEEDS_HUMAN`
- `CANCELLED`
- `DEAD_LETTERED`
- `RECOVERING`

## Architecture Rules

- Preserve dependency direction: `bootstrap -> engine -> rag`.
- Put external system contracts behind ports. Do not call Feishu, RocketMQ,
  Docker, GitHub, PostgreSQL, or model SDKs directly from core RAG logic.
- `bootstrap` handles HTTP/adapters/configuration only. It should not own
  business orchestration.
- `engine` orchestrates use cases and task flow.
- `rag` owns RAG domain behavior and context packaging.
- `exec` owns repair execution models, repair record persistence interfaces,
  Docker execution, result validation, and code platform abstractions.
- Treat model providers and repair executors as replaceable workers. Workflow
  orchestration must depend on ports, not a concrete model SDK or CLI command.
- Production shared mutable state must not rely on JVM-level `synchronized`.
  Use database atomic constraints/transactions, queue idempotency, or a lock
  port such as `DistributedLockExecutor` for cross-instance mutual exclusion.
- Keep distributed lock SDKs out of core domains. `rag`, `engine`, and `exec`
  may depend on lock ports; Redisson-specific implementation and configuration
  belong in `bootstrap`.
- JVM-level `synchronized` is acceptable only for process-local resource
  protection such as local file append, lifecycle close guards, token-cache
  refresh guards, Snowflake sequence internals, or test/mock snapshots.
- Use records for immutable value objects and normalize null inputs in compact
  constructors.
- Keep existing route shapes compatible unless a task explicitly changes them.
- Bug-fix RAG is task-scoped, not conversation-scoped. `RagBugFixEngine` must
  build and return a RAG result keyed by `taskId`; it must not load, append, or
  persist conversation memory, and `BugFixMessage`/queue requests should not
  carry `conversationId`.
- `RagBugFixEngine` only performs RAG retrieval/context packaging. It must not
  call `BugFixAgentEngine.submit` or otherwise trigger agent execution; upstream
  orchestration is responsible for taking the returned RAG result and invoking
  the agent.

## Policy Gates

Risky operations must pass explicit policy gates. Governance is not only a P3
feature; every phase should preserve a minimal guardrail path.

Examples:

- The agent cannot push directly to protected branches.
- The agent must create PRs instead of committing to `main`.
- Any production-impacting change requires manual approval.
- Secrets must never be included in prompts, logs, artifacts, or PR comments.
- Docker execution must use allowlisted repositories, images, and commands.
- High-risk patches require human review before PR creation.
- Validation failures must preserve artifacts and stop before PR creation unless
  the user explicitly chooses a different policy.

## External Integrations

Do not guess external API details.

- Feishu ticket API fields, authentication, status enums, and write-back format
  are user-provided at implementation time.
- RocketMQ infrastructure is user-created based on names the project chooses.
- Docker image name, Claude Code command, and yolo flags should be configurable.
- GitHub auth should be designed by the project; document the choice and keep it
  replaceable.
- Redisson is the first distributed lock implementation. Keep Redis address,
  password, and lock mode configurable; local fallback is only for single-node
  development or tests, not production multi-instance correctness.

When information is missing and affects public contracts, schema, queues,
security, or persistence, ask the user.

## Persistence Guidance

- Use PostgreSQL.
- Keep SQL under a clear project-managed directory, recommended:
  `bootstrap/src/main/resources/sql/postgres`.
- Favor repository/store ports plus PostgreSQL implementations.
- Main repair table: `repair_records`.
- Large logs, diffs, patches, prompt snapshots, and result files should be in
  `repair_record_artifacts` or object storage references, not overloaded into
  the main row.
- Preserve enough fields for audit: ticket, RAG context, executor, Docker,
  GitHub, test result, risk, errors, timestamps, and extension JSON.

## Observability and Evaluation

Every workflow stage should emit structured traces. Minimum trace dimensions:

- ticket id,
- task id,
- knowledge base version,
- retrieved document ids,
- prompt plan id,
- model/executor config,
- Docker image and command,
- validation command,
- test result,
- generated diff summary,
- PR URL,
- policy decision,
- error category,
- human intervention reason.

Minimum product metrics:

- context build latency,
- repair success rate,
- validation pass rate,
- PR creation rate,
- human intervention rate,
- retry rate,
- mean time to repair,
- top failure categories.

## Testing

Use focused tests first, then broader verification.

- Preferred full verification: `./mvnw test`
- Focused module verification examples:
  - `./mvnw -pl rag test`
  - `./mvnw -pl bootstrap -am test`
  - `./mvnw -pl rag,bootstrap -am -Dtest=SomeTest test`
- Keep `/test/...` channels isolated from production routes.
- For persistence work, add tests that prove restart/reload behavior at the
  repository boundary or with a PostgreSQL-compatible test setup.

If a verification command cannot run locally because services are missing
PostgreSQL, RocketMQ, Docker, or credentials, report that clearly and include
the focused tests that did run.

## Working Practices

- Inspect current code and docs before editing. Prefer `rg` for search.
- Keep edits scoped to the requested phase.
- Before adding `synchronized` to production code, classify the state being
  protected. If it can be touched by multiple application instances, use a
  database/queue guarantee or `DistributedLockExecutor` instead.
- Do not revert user changes or unrelated untracked files.
- Do not make destructive git or filesystem changes unless explicitly asked.
- Do not commit, stage, push, or create PRs unless explicitly asked.
- Update README/RULE/Feishu docs only when the task asks for docs or when an
  implementation changes documented behavior.
- Local `docs/` artifacts are ignored by git in this repository. If a new doc is
  meant to be reviewed or published, mention the path explicitly in the final
  response and verify whether the user wants ignore rules changed.
