# P1 Feishu Ticket And RocketMQ Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the P1 path that receives Feishu Helpdesk ticket events, schedules repair work through RocketMQ, prepares RAG context, persists repair records, and writes status back to Feishu behind replaceable ports.

**Architecture:** Keep dependency direction as `bootstrap -> engine -> rag`, with `exec` owning repair record persistence contracts. `rag` exposes ticket value objects and RAG context packaging only; `engine` orchestrates ticket repair use cases and queue consumption; `bootstrap` owns Feishu Helpdesk HTTP adapters, RocketMQ adapters, webhook controllers, Spring properties, and test-channel controllers. Feishu field names stay configurable through mapping rules, not hard-coded into domain logic.

**Tech Stack:** Java 21, Spring Boot 3.5, Maven, JUnit 5, PostgreSQL SQL under `bootstrap/src/main/resources/sql/postgres`, MyBatis-Plus persistence already present, RocketMQ behind a local port, Feishu Helpdesk v1 OpenAPI, existing `RepairRecordRepository`, existing `RagBugFixEngine`.

---

## Current Constraints

- `AGENTS.md` requires P1 to keep the ticket system behind an interface/port, implement Feishu first, use RocketMQ, and avoid guessing missing Feishu business fields.
- Helpdesk APIs require `Authorization` plus `X-Lark-Helpdesk-Authorization = base64(helpdesk_id:helpdesk_token)`. Secrets must be injected through configuration and never logged.
- Current `TicketSystemPort` only has `findTicket(String)`. Split it into provider/update contracts instead of expanding a generic port.
- Current `TicketSnapshot` only has `ticketId`, `title`, `description`, `labels`, and `createdAt`. P1 needs priority, raw status/stage, source channel, custom fields, chat ID, and message-derived logs without leaking Feishu DTOs into `engine`.
- `RagBugFixEngine` already prepares RAG context from `TicketSnapshot` and logs. Keep it task-scoped and do not add conversation memory or agent execution into it.
- `exec` already owns `RepairRecordRepository`, `RepairRecord`, `RepairRecordArtifact`, and PostgreSQL adapters. P1 should reuse them and widen statuses only where lifecycle semantics require it.
- Local verification must not require real Feishu, RocketMQ, PostgreSQL, or credentials. Real-provider smoke tests are separate and property-gated.

## Overall Acceptance Standards

1. `./mvnw -pl rag test` passes and proves the new ticket ports/value objects normalize nulls and do not depend on Feishu, RocketMQ, HTTP, or Spring web clients.
2. `./mvnw -pl engine test` passes and proves ticket events are converted into queue messages, queue messages are consumed by `TicketRepairEngine`, RAG context is prepared once per ticket task, and repair records move through the expected states.
3. `./mvnw -pl bootstrap -am test` passes with mock Feishu and in-memory queue adapters, without real Feishu or RocketMQ.
4. A property-gated real smoke test can be run with credentials and infrastructure, but it is skipped by default and reports missing configuration clearly.
5. The MQ message body contains only `ticketId`, `priority`, `traceId`, `attempt`, `source`, and event metadata. It must not contain full ticket content, logs, access tokens, or Helpdesk token.
6. Feishu-specific DTOs and raw API response shapes exist only under `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/ticket`.
7. `engine` and `rag` use only standard records such as `TicketSnapshot`, `TicketUpdateCommand`, and `RepairTicketMessage`.
8. `repair_records` can be queried by `ticketId`, status, priority, and created time through a production controller.
9. Queue failures and Feishu failures update `repair_records` to a failed or waiting state with an error summary and persist raw request/response metadata as artifacts with secrets redacted.
10. `/test/...` endpoints stay isolated from production routes.

## File Map

### RAG Module

- Modify `rag/src/main/java/com/wish/rd/adapter/TicketSystemPort.java`
  - Either deprecate it or replace it with smaller ports below.
- Create `rag/src/main/java/com/wish/rd/adapter/TicketProviderPort.java`
  - `Optional<TicketSnapshot> findTicket(String ticketId)`.
  - `TicketMessages findMessages(String ticketId, TicketMessageQuery query)`.
- Create `rag/src/main/java/com/wish/rd/adapter/TicketUpdatePort.java`
  - `TicketUpdateResult sendMessage(TicketReplyCommand command)`.
  - `TicketUpdateResult updateTicket(TicketUpdateCommand command)`.
- Modify `rag/src/main/java/com/wish/rd/adapter/TicketSnapshot.java`
  - Add standard fields: `priority`, `status`, `stage`, `source`, `chatId`, `customFields`, `updatedAt`, `closedAt`.
  - Keep existing constructor call sites working by adding a compact overload or static factory.
- Create `rag/src/main/java/com/wish/rd/adapter/TicketMessage.java`
- Create `rag/src/main/java/com/wish/rd/adapter/TicketMessages.java`
- Create `rag/src/main/java/com/wish/rd/adapter/TicketMessageQuery.java`
- Create `rag/src/main/java/com/wish/rd/adapter/TicketReplyCommand.java`
- Create `rag/src/main/java/com/wish/rd/adapter/TicketUpdateCommand.java`
- Create `rag/src/main/java/com/wish/rd/adapter/TicketUpdateResult.java`
- Test `rag/src/test/java/com/wish/rd/rag/ticket/TicketContractTest.java`

### Engine Module

- Create `engine/src/main/java/com/wish/rd/engine/ticket/RepairTicketMessage.java`
  - MQ-safe message: `ticketId`, `priority`, `traceId`, `attempt`, `source`, `eventId`, `eventType`.
- Create `engine/src/main/java/com/wish/rd/engine/ticket/RepairQueuePublisher.java`
- Create `engine/src/main/java/com/wish/rd/engine/ticket/RepairQueueConsumer.java`
- Create `engine/src/main/java/com/wish/rd/engine/ticket/RepairQueuePublishResult.java`
- Create `engine/src/main/java/com/wish/rd/engine/ticket/TicketEventIngestionEngine.java`
  - Converts standard Feishu event envelopes into `RepairTicketMessage` and publishes to MQ.
- Create `engine/src/main/java/com/wish/rd/engine/ticket/TicketRepairEngine.java`
  - Consumes `RepairTicketMessage`, loads ticket/messages through `TicketProviderPort`, creates/updates `RepairRecordRepository`, calls `RagBugFixEngine`, writes status through `TicketUpdatePort` when configured.
- Create `engine/src/main/java/com/wish/rd/engine/ticket/TicketFieldMapping.java`
  - Maps custom fields to `problemSystem`, `symptom`, `triggerWay`, `priority`, `logs`, `repository`, `branch`, `expectedResult`, `actualResult`.
- Create `engine/src/main/java/com/wish/rd/engine/ticket/TicketRepairDecision.java`
  - `READY_FOR_RAG`, `WAITING_FOR_INFO`, `IGNORED_CLOSED`, `FAILED`.
- Modify `exec/src/main/java/com/wish/rd/exec/repair/RepairRecordStatus.java`
  - Add P1 statuses if required by implementation: `QUEUED`, `CONTEXT_COLLECTING`, `CONTEXT_READY`, `WAITING_FOR_INFO`.
- Modify `exec/src/main/java/com/wish/rd/exec/repair/RepairRecordRepository.java`
  - Add paged query only if production controller requires repository-level filtering beyond `findByTicketId`.
- Test `engine/src/test/java/com/wish/rd/engine/TicketEventIngestionEngineTest.java`
- Test `engine/src/test/java/com/wish/rd/engine/TicketRepairEngineTest.java`
- Test `engine/src/test/java/com/wish/rd/engine/TicketFieldMappingTest.java`

### Bootstrap Module

- Modify `bootstrap/pom.xml`
  - Add RocketMQ client dependency after choosing exact artifact.
  - Add HTTP client support if Feishu adapter uses Spring `WebClient`; otherwise use JDK `HttpClient` and avoid a new dependency.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/ticket/FeishuHelpdeskProperties.java`
  - app identity, base URL, helpdesk ID/token, field mapping, write-back policy.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/ticket/FeishuHelpdeskAuth.java`
  - Builds `X-Lark-Helpdesk-Authorization`; masks secrets in `toString`.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/ticket/FeishuHelpdeskClient.java`
  - Low-level HTTP port for list/get ticket, list messages, send message, update ticket, list custom fields, download image.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/ticket/FeishuTicketAdapter.java`
  - Implements `TicketProviderPort` and `TicketUpdatePort`.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/ticket/FeishuTicketMapper.java`
  - Maps raw Helpdesk DTOs to standard `TicketSnapshot`, `TicketMessage`, and update commands.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/ticket/FeishuHelpdeskWebhookController.java`
  - Receives Feishu event callbacks and delegates to `TicketEventIngestionEngine`.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/ticket/MockFeishuTicketAdapter.java`
  - Default local adapter when real Feishu is disabled.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/rocketmq/RocketMqRepairQueueProperties.java`
  - topic `RD_BOT_REPAIR_TICKET`, group `GID_RD_BOT_REPAIR_WORKER`, tags `P0/P1/P2`.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/rocketmq/RocketMqRepairQueueAdapter.java`
  - Implements `RepairQueuePublisher` and wires consumer callback to `RepairQueueConsumer`.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/rocketmq/InMemoryRepairQueueAdapter.java`
  - Default local/test queue adapter.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/repair/RepairRecordController.java`
  - Production query API for repair records and artifacts.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/testchannel/FeishuTicketTestChannelController.java`
  - Test-only endpoints for mock event replay and single-ticket repair flow.
- Test `bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuTicketAdapterTest.java`
- Test `bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuHelpdeskWebhookControllerTest.java`
- Test `bootstrap/src/test/java/com/wish/rd/bootstrap/RocketMqRepairQueueAdapterTest.java`
- Test `bootstrap/src/test/java/com/wish/rd/bootstrap/RepairRecordControllerTest.java`
- Test `bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuTicketTestChannelControllerTest.java`

### SQL And Persistence

- Modify `bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql`
  - Add indexes required by P1 query if not already sufficient:
    - `idx_repair_records_ticket`
    - `idx_repair_records_status`
    - optional `idx_repair_records_priority` if priority stays inside `extension_json` and query performance needs a generated column later.
- Prefer storing P1 details in existing `extension_json` and `repair_record_artifacts` for this phase.
- Do not add Flyway or Liquibase.

---

## Task Breakdown

### Task 1: Split Ticket Contracts

**Implementation:**
- Replace the broad `TicketSystemPort` boundary with `TicketProviderPort` and `TicketUpdatePort`.
- Expand `TicketSnapshot` into the standard RD-Bot ticket shape while keeping old call sites compiling.
- Add immutable records for ticket messages, reply commands, update commands, and update results.

**Acceptance:**
- `TicketSnapshot` compact constructors normalize null strings to `""`, nullable lists/maps to immutable empty collections, and nullable timestamps to `Instant.EPOCH` or documented empty values.
- `rag` has no imports from Feishu, RocketMQ, Spring web, or bootstrap packages.
- Existing tests that construct the five-field `TicketSnapshot` still compile.
- `./mvnw -pl rag test` passes.

**Detailed Changes:**
- `TicketProviderPort` replaces read operations.
- `TicketUpdatePort` owns write-back operations.
- `TicketReplyCommand` carries `ticketId`, `messageType`, `content`, `traceId`, and `repairRecordId`.
- `TicketUpdateCommand` carries `ticketId`, `status`, `tags`, `comment`, `customFields`, `solved`, and `traceId`.
- `TicketUpdateResult` carries `success`, `providerMessageId`, `providerCode`, and `message`.

### Task 2: Define Queue Message And Event Ingestion

**Implementation:**
- Add a queue message that contains only routing metadata.
- Add an ingestion engine that accepts standard event data, derives priority, trace ID, and attempt, then publishes to the queue.
- Keep event parsing in bootstrap; engine receives normalized event inputs only.

**Acceptance:**
- Queue message JSON never contains title, description, logs, tokens, Helpdesk token, or raw API response.
- `ticketId` is the message key and is non-blank.
- `priority` is normalized to `P0`, `P1`, or `P2`, defaulting to `P2`.
- Duplicate `eventId + ticketId + eventType` can be detected or recorded so repeated events do not create duplicate active repair records.
- `./mvnw -pl engine -Dtest=TicketEventIngestionEngineTest test` passes.

**Detailed Changes:**
- `RepairTicketMessage` fields: `ticketId`, `priority`, `traceId`, `attempt`, `source`, `eventId`, `eventType`, `createdAt`.
- `RepairQueuePublisher.publish(RepairTicketMessage)` returns `RepairQueuePublishResult`.
- `TicketEventIngestionEngine` uses `RepairQueuePublisher` only; it does not call Feishu, RAG, or repair execution.

### Task 3: Implement Ticket Repair Consumer Orchestration

**Implementation:**
- Add `TicketRepairEngine` as the P1 use-case orchestrator.
- On message consumption, load `TicketSnapshot` and messages, create or reuse a repair record, run `RagBugFixEngine` with upstream `taskId`, persist RAG summary/artifacts, and optionally write back to Feishu.

**Acceptance:**
- Missing ticket transitions to `FAILED` with a redacted error summary.
- Closed tickets are ignored or recorded as terminal according to `TicketRepairDecision`.
- Insufficient fields transition to `WAITING_FOR_INFO` and send a configured question through `TicketUpdatePort` if write-back is enabled.
- Valid tickets transition `QUEUED -> CONTEXT_COLLECTING -> CONTEXT_READY`.
- `RagBugFixEngine` remains task-scoped and does not call agent execution.
- `./mvnw -pl engine -Dtest=TicketRepairEngineTest test` passes.

**Detailed Changes:**
- `TicketRepairEngine.handle(RepairTicketMessage)` is the only consumer entrypoint.
- It calls `RepairRecordRepository.findByTicketId` before creating a new record.
- It stores raw normalized ticket snapshot and message summaries as `repair_record_artifacts`.
- It updates `repair_records.rag_summary` with `BugFixMessage.contextSummary`.
- It puts `priority`, `traceId`, `eventId`, `eventType`, `source`, `feishuStatus`, and `feishuStage` into `extensionJson`.

### Task 4: Add Feishu Helpdesk Adapter

**Implementation:**
- Implement low-level Helpdesk HTTP calls in bootstrap.
- Map Feishu DTOs into standard ticket records.
- Redact secrets in logs and artifacts.

**Acceptance:**
- Requests include `Authorization` and `X-Lark-Helpdesk-Authorization`.
- `X-Lark-Helpdesk-Authorization` is generated from configured `helpdeskId` and `helpdeskToken` and never exposed in `toString`, exception messages, or artifacts.
- `GET /open-apis/helpdesk/v1/tickets/:ticket_id` maps status, stage, chat ID, custom fields, guest, agents, and timestamps.
- `GET /open-apis/helpdesk/v1/tickets/:ticket_id/messages` maps text/post/image content into `TicketMessage` and extracts log-like content when configured.
- `PUT /open-apis/helpdesk/v1/tickets/:ticket_id` uses user identity where required by config.
- `./mvnw -pl bootstrap -am -Dtest=FeishuTicketAdapterTest test` passes with a fake HTTP server or mock client.

**Detailed Changes:**
- `FeishuHelpdeskProperties` keys should be under `rd.feishu.helpdesk.*`.
- Support `enabled=false` default and a mock adapter by default.
- Implement APIs from the research section: list/get ticket, list messages, ticket images, send message, update ticket, custom fields.
- Do not implement custom field create/update/delete in P1 unless explicitly enabled later.

### Task 5: Add Feishu Webhook Controller

**Implementation:**
- Add a controller under bootstrap that receives Feishu Helpdesk event callbacks and converts them to normalized engine inputs.
- Support ticket created, ticket updated, and ticket message created events.

**Acceptance:**
- Event body parsing supports `helpdesk.ticket.created_v1`, `helpdesk.ticket.updated_v1`, and `helpdesk.ticket_message.created_v1`.
- Ticket updated events always cause a fresh ticket detail lookup later in the consumer; engine does not trust partial event fields as the final snapshot.
- User message events from `sender_type=2` can requeue a waiting ticket.
- Invalid signatures or missing required event fields return a 4xx response and do not publish.
- `./mvnw -pl bootstrap -am -Dtest=FeishuHelpdeskWebhookControllerTest test` passes.

**Detailed Changes:**
- Keep Feishu webhook verification isolated in bootstrap.
- Controller delegates to `TicketEventIngestionEngine`.
- Do not call `RagBugFixEngine` directly from the controller.

### Task 6: Add RocketMQ Adapter And Local Queue Adapter

**Implementation:**
- Add RocketMQ producer/consumer adapter behind engine queue ports.
- Add in-memory adapter for tests and local no-infrastructure startup.

**Acceptance:**
- Topic defaults to `RD_BOT_REPAIR_TICKET`.
- Consumer group defaults to `GID_RD_BOT_REPAIR_WORKER`.
- Tags are `P0`, `P1`, and `P2`.
- Message key is `ticketId`; message properties include `priority`, `traceId`, `attempt`, and `source=feishu`.
- Consumer failures increment attempt and preserve trace ID.
- Local startup works with `rd.repair.queue.mode=memory`.
- `./mvnw -pl bootstrap -am -Dtest=RocketMqRepairQueueAdapterTest test` passes without a real RocketMQ broker.

**Detailed Changes:**
- `RocketMqRepairQueueProperties` keys under `rd.rocketmq.repair.*`.
- Real RocketMQ beans are gated by `rd.repair.queue.mode=rocketmq`.
- In-memory adapter is gated by `rd.repair.queue.mode=memory` and `matchIfMissing=true`.

### Task 7: Expose Repair Record Query API

**Implementation:**
- Add production read APIs for repair records and artifacts.
- Keep write operations internal for P1 except test-channel replay.

**Acceptance:**
- `GET /repair-records?ticketId=&status=&priority=&createdFrom=&createdTo=&page=&pageSize=` returns paged records.
- `GET /repair-records/{id}` returns a single record.
- `GET /repair-records/{id}/artifacts` returns artifacts ordered by creation time.
- The API does not return raw secrets or full Helpdesk tokens.
- `./mvnw -pl bootstrap -am -Dtest=RepairRecordControllerTest test` passes.

**Detailed Changes:**
- Extend `RepairRecordRepository` only if needed for efficient query.
- If priority stays in `extensionJson`, controller can filter in memory for P1 only if result size is bounded; otherwise add a repository query contract.

### Task 8: Add Test Channel And End-To-End Mock Flow

**Implementation:**
- Add `/test/...` endpoints to replay mock Feishu events and run one full ticket-to-RAG flow.
- Keep test routes isolated from production routes.

**Acceptance:**
- `POST /test/feishu/helpdesk/events/ticket-created` publishes an in-memory queue message.
- `POST /test/repair/tickets/{ticketId}/run` consumes the ticket and produces a `repair_record`.
- Response includes `ticketId`, `repairRecordId`, `status`, `contextSummary`, `publishedMessage`, and artifact IDs.
- Test endpoints are located under `bootstrap/controller/testchannel`.
- `./mvnw -pl bootstrap -am -Dtest=FeishuTicketTestChannelControllerTest test` passes.

**Detailed Changes:**
- Use `MockFeishuTicketAdapter` to return deterministic ticket, messages, and custom fields.
- Use existing `RagBugFixEngine` and in-memory stores.
- Do not require PostgreSQL, Feishu, or RocketMQ.

### Task 9: Add PostgreSQL And Artifact Coverage

**Implementation:**
- Reuse existing `repair_records` and `repair_record_artifacts`.
- Add statuses and query support needed by P1.
- Persist raw provider snapshots as artifacts with secrets redacted.

**Acceptance:**
- Restart/reload behavior is covered by property-gated PostgreSQL integration when `rd.integration.postgres.enabled=true`.
- A record created by ticket consumption can be found by `ticketId`.
- Artifacts include at least `FEISHU_EVENT`, `FEISHU_TICKET_SNAPSHOT`, `FEISHU_MESSAGES`, and `RAG_CONTEXT`.
- SQL remains project-managed under `bootstrap/src/main/resources/sql/postgres`; no Flyway/Liquibase.
- Existing `PostgresPersistenceCrudIntegrationTest` continues to pass when enabled.

**Detailed Changes:**
- Add enum values to `RepairRecordStatus` and ensure `PostgresRepairRecordRepository` round-trips them.
- If adding query repository methods, update both in-memory and PostgreSQL implementations.

### Task 10: Add Real-Provider Smoke Procedure

**Implementation:**
- Add a disabled-by-default smoke test and a doc section with required environment variables.
- Do not run real write-back unless `rd.feishu.helpdesk.write-back.enabled=true`.

**Acceptance:**
- Missing Feishu/RocketMQ config produces a skipped test or a clear assumption failure, not a random connection failure.
- Read-only smoke can call ticket list/detail and custom fields.
- Write smoke is separately gated and never runs by default.
- Verification instructions include exact Maven command and required properties.

**Detailed Changes:**
- Test class: `bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuHelpdeskRealSmokeTest.java`.
- Documentation: `docs/smoke/feishu-helpdesk-p1.md`.
- Commands:
  - `./mvnw -pl bootstrap -am -Dtest=FeishuHelpdeskRealSmokeTest -Drd.integration.feishu.enabled=true test`
  - `./mvnw -pl bootstrap -am -Dtest=RocketMqRepairQueueRealSmokeTest -Drd.integration.rocketmq.enabled=true test`

## Suggested Implementation Order

1. Task 1 contracts.
2. Task 2 queue message and ingestion engine.
3. Task 3 ticket repair consumer orchestration with in-memory ports.
4. Task 7 repair record query API.
5. Task 8 test channel for local full flow.
6. Task 4 Feishu adapter.
7. Task 5 Feishu webhook controller.
8. Task 6 RocketMQ adapter.
9. Task 9 PostgreSQL/artifact hardening.
10. Task 10 real-provider smoke.

This order keeps each step testable without external infrastructure until the final adapter and smoke phases.

## Verification Commands

```bash
./mvnw -pl rag test
./mvnw -pl engine test
./mvnw -pl bootstrap -am test
./mvnw test
```

If PostgreSQL, Feishu, or RocketMQ credentials are not available, run only the default tests and report the skipped property-gated smoke commands.
