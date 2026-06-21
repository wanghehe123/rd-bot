# RD-Bot Agent Guide

This file applies to the whole repository. Read it before changing code.

## Project Shape

RD-Bot is a Java 21 / Spring Boot 3.5 multi-module monolith. It is split by
layers, not by microservices.

- `rag`: core RAG domain, ingestion, knowledge, retrieval, prompt planning,
  memory, trace, settings, and runtime task state.
- `engine`: orchestration layer. It wires RAG capabilities into end-to-end
  flows. Keep business orchestration here.
- `bootstrap`: Spring Boot entrypoint, REST controllers, configuration, static
  admin frontend, and external adapter implementations.
- `exec`: repair execution layer. Currently placeholder; future Docker Claude
  Code execution, repair records, validation, and code platform ports belong
  here.
- `skill`: future reusable repair skills. Currently placeholder.
- `frontend`: frontend workspace if present. Do not assume it is part of Maven.

Follow [RULE.md](RULE.md) for Java style, naming, comments, REST conventions,
patterns, and tests.

## Current Product Direction

The latest implementation plan lives in Feishu Wiki under the parent page:

- Parent: `https://my.feishu.cn/wiki/KN6dwQ48wic3OcktRghccUnnnng`
- P0: `https://my.feishu.cn/wiki/W7bzwwbAciPkqZkECSXc146znfb`
- P1: `https://my.feishu.cn/wiki/LHEGwQY69ipBx5kzXNgcIDQNnFf`
- P2: `https://my.feishu.cn/wiki/AAohwtzsWiKhCckDpA7clXNEn8f`
- P3: `https://my.feishu.cn/wiki/ZFGSwPefQiR8H1kukY0cQtbCn7f`

Do not invent a different roadmap. If the docs and code disagree, inspect both
and ask the user before making a design-changing edit.

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
     GitHub App for production and PAT only for local smoke/fallback.
4. **P3: Production governance**
   - Audit, idempotency, dead-letter handling, manual recovery, alerting,
     allowlists, secret boundaries, log redaction, and operational views.

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

## External Integrations

Do not guess external API details.

- Feishu ticket API fields, authentication, status enums, and write-back format
  are user-provided at implementation time.
- RocketMQ infrastructure is user-created based on names the project chooses.
- Docker image name, Claude Code command, and yolo flags should be configurable.
- GitHub auth should be designed by the project; document the choice and keep it
  replaceable.

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
- Do not revert user changes or unrelated untracked files.
- Do not make destructive git or filesystem changes unless explicitly asked.
- Do not commit, stage, push, or create PRs unless explicitly asked.
- Update README/RULE/Feishu docs only when the task asks for docs or when an
  implementation changes documented behavior.
