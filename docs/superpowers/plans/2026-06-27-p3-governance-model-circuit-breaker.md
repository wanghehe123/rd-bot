# P3 Governance and Model Circuit Breaker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add ragent-compatible model provider tri-state circuit breaking to `DockerClaudeCodeExecutor#execute` and complete the P3 production governance surface for auditability, recovery, observability, safety, and manual operation.

**Architecture:** Keep the control plane in `engine`, the execution plane in `exec`, and adapters/configuration in `bootstrap`. Model health is an execution-plane concern owned by `exec`; P3 governance uses small ports for audit, alert, queue recovery, execution control, and knowledge refresh metrics, with PostgreSQL adapters in `bootstrap`.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5, Maven multi-module build, PostgreSQL project-managed SQL, RocketMQ adapter, Docker CLI adapter, Feishu CLI-derived requirements.

---

## Source Requirements

- `AGENTS.md`: RD-Bot is an AI-native software delivery harness; preserve `bootstrap -> engine -> rag`, keep external systems behind ports, keep Docker Claude Code as sandboxed execution, do not auto-merge, keep P3 governance focused on audit, idempotency, dead-letter handling, manual recovery, alerting, allowlists, secret boundaries, log redaction, and operational views.
- Feishu P3 Wiki `https://my.feishu.cn/wiki/ZFGSwPefQiR8H1kukY0cQtbCn7f`: P3 must answer which ticket triggered a task, what knowledge was used, which container ran, what changes were produced, whether tests passed, who needs CR, and who handles failure. It requires P3-1 through P3-6 plus the open question: when a ticket is cancelled during repair, how should the repair be stopped.
- ragent source: `/Users/wish233/IdeaProjects/ragent/infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/model/ModelHealthStore.java`. Preserve `CLOSED`, `OPEN`, `HALF_OPEN`, `allowCall`, `isUnavailable`, `markSuccess`, and `markFailure` behavior.
- Existing RD-Bot seam: `DockerClaudeCodeExecutor#execute` already has the provider fallback chain and a migration comment at the exact model health insertion point.

## File Structure

### Model circuit breaker

- Create: `exec/src/main/java/com/wish/rd/exec/repair/model/ModelCircuitBreakerPolicy.java`
  - Immutable policy record: enabled flag, failure threshold, open duration.
- Create: `exec/src/main/java/com/wish/rd/exec/repair/model/ModelHealthState.java`
  - Enum with `CLOSED`, `OPEN`, `HALF_OPEN`.
- Create: `exec/src/main/java/com/wish/rd/exec/repair/model/ModelHealthSnapshot.java`
  - Immutable view used by metadata and tests.
- Create: `exec/src/main/java/com/wish/rd/exec/repair/model/ModelHealthStore.java`
  - ragent-compatible health store using `ConcurrentHashMap` and `compute`.
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/DockerClaudeCodeExecutor.java`
  - Add `ModelHealthStore` dependency, skip unavailable providers, update health after each attempt, and expose provider health metadata.
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/DockerExecutorProperties.java`
  - Add nested `CircuitBreakerProperties`.
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/DockerExecutorConfiguration.java`
  - Add `ModelHealthStore` bean and inject it into `DockerClaudeCodeExecutor`.
- Modify: `bootstrap/src/main/resources/application.yaml`
  - Add default `rd.executor.docker.circuit-breaker` settings.
- Test: `exec/src/test/java/com/wish/rd/exec/repair/model/ModelHealthStoreTest.java`
- Test: `exec/src/test/java/com/wish/rd/exec/repair/docker/DockerClaudeCodeExecutorTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/DockerExecutorConfigurationTest.java`

### P3 audit, alerts, and operation data

- Create: `engine/src/main/java/com/wish/rd/engine/audit/RepairAuditEventType.java`
- Create: `engine/src/main/java/com/wish/rd/engine/audit/RepairAuditEvent.java`
- Create: `engine/src/main/java/com/wish/rd/engine/audit/RepairAuditSinkPort.java`
- Create: `engine/src/main/java/com/wish/rd/engine/audit/NoopRepairAuditSink.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/ticket/TicketRepairEngine.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/ticket/TicketRepairExecutionConsumer.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/bugfix/RdBotFixEngine.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/EngineBugFixExecutorAdapter.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/PostgresRepairAuditSink.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RepairAuditEventRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RepairAuditEventMapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/operation/RepairOperationController.java`
- Modify: `bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql`

### P3 queue recovery and dead-letter handling

- Create: `engine/src/main/java/com/wish/rd/engine/ticket/RepairQueueRecoveryPort.java`
- Create: `engine/src/main/java/com/wish/rd/engine/ticket/RepairQueueDeadLetter.java`
- Create: `engine/src/main/java/com/wish/rd/engine/ticket/RepairQueueDeadLetterRepository.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/ticket/TicketRepairExecutionConsumer.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/rocketmq/RocketMqRepairQueueAdapter.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/PostgresRepairQueueDeadLetterRepository.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RepairQueueDeadLetterRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RepairQueueDeadLetterMapper.java`

### P3 Docker execution control and cancellation

- Create: `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairExecutionControlPort.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairExecutionStopCommand.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairExecutionStopResult.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/docker/DockerExecutionRegistry.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/docker/ContainerControlPort.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/DockerClaudeCodeExecutor.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/ProcessContainerRunner.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskController.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/bugfix/RdBotFixEngine.java`

### P3 knowledge refresh metrics and security controls

- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeRefreshMetric.java`
- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeRefreshMetricSink.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeRefreshScheduler.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/PostgresKnowledgeRefreshMetricSink.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/security/ExecutionAllowlistPolicy.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/security/SecretRedactor.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/DockerClaudeCodeExecutor.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/DockerExecutorProperties.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/ProcessContainerRunner.java`

## Task 1: Port ragent ModelHealthStore into exec

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/model/ModelCircuitBreakerPolicy.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/model/ModelHealthState.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/model/ModelHealthSnapshot.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/model/ModelHealthStore.java`
- Test: `exec/src/test/java/com/wish/rd/exec/repair/model/ModelHealthStoreTest.java`

- [ ] **Step 1: Write failing tests for ragent state semantics**

Create `ModelHealthStoreTest` with tests named:

```java
@Test
void shouldAllowClosedModelByDefault()

@Test
void shouldOpenAfterFailureThreshold()

@Test
void shouldMoveOpenToHalfOpenAfterOpenDuration()

@Test
void shouldAllowOnlyOneHalfOpenCallInFlight()

@Test
void shouldCloseCircuitAfterSuccess()

@Test
void shouldReopenCircuitWhenHalfOpenCallFails()

@Test
void shouldReturnUnavailableForOpenAndBusyHalfOpenStates()
```

Use a policy of `new ModelCircuitBreakerPolicy(true, 2, 50L)` and sleep no more than `75L` in the half-open expiry test.

- [ ] **Step 2: Run the focused failing test**

Run:

```bash
./mvnw -pl exec -Dtest=ModelHealthStoreTest test
```

Expected before implementation: compilation failure because the model health classes do not exist.

- [ ] **Step 3: Add model health records and enum**

Create exact public API:

```java
public record ModelCircuitBreakerPolicy(boolean enabled, int failureThreshold, long openDurationMillis) {
    public static ModelCircuitBreakerPolicy disabled()
    public static ModelCircuitBreakerPolicy defaults()
}

public enum ModelHealthState {
    CLOSED,
    OPEN,
    HALF_OPEN
}

public record ModelHealthSnapshot(
        String modelId,
        ModelHealthState state,
        int consecutiveFailures,
        long openUntilEpochMillis,
        boolean halfOpenInFlight
) {
}
```

Normalize policy as: disabled policy keeps `enabled=false`; enabled policy clamps `failureThreshold` to at least `1` and `openDurationMillis` to at least `1L`.

- [ ] **Step 4: Add ModelHealthStore with ragent-compatible behavior**

Implement these public methods:

```java
public boolean isUnavailable(String id)
public boolean allowCall(String id)
public void markSuccess(String id)
public void markFailure(String id)
public ModelHealthSnapshot snapshot(String id)
public Map<String, ModelHealthSnapshot> snapshots()
```

Rules:
- If policy is disabled, `allowCall` returns true for non-blank ids, `isUnavailable` returns false, and mark methods do not mutate state.
- Blank or null id never creates a map entry.
- `OPEN` before `openUntil` blocks calls.
- Expired `OPEN` becomes `HALF_OPEN` and sets `halfOpenInFlight=true` for exactly one call.
- Busy `HALF_OPEN` blocks additional calls.
- `markSuccess` closes the circuit.
- `markFailure` in `HALF_OPEN` reopens immediately.
- `markFailure` in `CLOSED` opens after threshold failures and resets the failure counter.

- [ ] **Step 5: Run focused model health tests**

Run:

```bash
./mvnw -pl exec -Dtest=ModelHealthStoreTest test
```

Expected: all `ModelHealthStoreTest` tests pass.

## Task 2: Integrate circuit breaker into DockerClaudeCodeExecutor#execute

**Files:**
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/DockerClaudeCodeExecutor.java`
- Test: `exec/src/test/java/com/wish/rd/exec/repair/docker/DockerClaudeCodeExecutorTest.java`

- [ ] **Step 1: Add failing executor tests**

Add tests named:

```java
@Test
void shouldSkipOpenProviderAndRunNextProvider()

@Test
void shouldFailWithoutRunningContainerWhenAllProvidersAreCircuitOpen()

@Test
void shouldOpenProviderCircuitAfterFallbackFailureThreshold()

@Test
void shouldNotOpenProviderCircuitForNeedInfo()

@Test
void shouldNotOpenProviderCircuitWhenRepositoryPublishFails()
```

Use two providers named `deepseek` and `anthropic`. For the all-open test, pre-open both providers with `markFailure` twice on a store configured with threshold `2`; assert `runner.requests().size()` is `0`, status is `FAILED`, and `providerAttemptsJson` contains `SKIPPED_CIRCUIT_OPEN`.

- [ ] **Step 2: Run executor tests to prove the gap**

Run:

```bash
./mvnw -pl exec -Dtest=DockerClaudeCodeExecutorTest test
```

Expected before implementation: new tests fail because `DockerClaudeCodeExecutor` has no model health dependency.

- [ ] **Step 3: Add constructor-compatible ModelHealthStore dependency**

Keep existing public constructors source-compatible. Add one constructor overload:

```java
public DockerClaudeCodeExecutor(
        RepairWorkspaceFactory workspaceFactory,
        ContainerRunnerPort containerRunner,
        StructuredResultValidator resultValidator,
        Configuration configuration,
        RepairWorkspaceRepositoryPort workspaceRepository,
        RepairExecutionWatchdog watchdog,
        ModelHealthStore modelHealthStore
)
```

Existing constructors delegate to this overload with `new ModelHealthStore(ModelCircuitBreakerPolicy.disabled())`.

- [ ] **Step 4: Gate each provider before running Docker**

In `execute`, before `cleanOutputDirectory`, call `modelHealthStore.allowCall(provider.name())`.

If false:
- Add attempt metadata with `status=SKIPPED_CIRCUIT_OPEN`.
- Include `circuitState`, `openUntilEpochMillis`, and `halfOpenInFlight`.
- Continue to the next provider.
- Do not call `containerRunner.run`.

If every provider is skipped, return `FAILED` with error message:

```text
all Claude Code providers are unavailable by circuit breaker
```

- [ ] **Step 5: Mark provider health from execution result**

Classify provider health before repository publish changes the execution status:
- `SUCCESS`, `NEED_INFO`, `UNSAFE` call `markSuccess`.
- `FAILED_VALIDATION` calls `markFailure`.
- `FAILED` calls `markFailure` only when `shouldFallback(result)` is true.
- Repository publish failure does not call `markFailure` because the model provider already returned valid output.

- [ ] **Step 6: Run executor tests**

Run:

```bash
./mvnw -pl exec -Dtest=DockerClaudeCodeExecutorTest,ModelHealthStoreTest test
```

Expected: all tests pass and existing fallback tests still pass.

## Task 3: Wire circuit breaker configuration through bootstrap

**Files:**
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/DockerExecutorProperties.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/DockerExecutorConfiguration.java`
- Modify: `bootstrap/src/main/resources/application.yaml`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/DockerExecutorConfigurationTest.java`

- [ ] **Step 1: Add failing bootstrap configuration tests**

Add tests that assert:
- Defaults bind to `enabled=true`, `failureThreshold=3`, `openDurationMillis=60000`.
- Custom properties override all three values.
- `RepairExecutorPort` bean is a `DockerClaudeCodeExecutor` constructed with a non-disabled `ModelHealthStore`.

- [ ] **Step 2: Run configuration tests to prove the gap**

Run:

```bash
./mvnw -pl bootstrap -am -Dtest=DockerExecutorConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected before implementation: tests fail because no circuit breaker properties or bean exist.

- [ ] **Step 3: Add property model**

Add nested class to `DockerExecutorProperties`:

```java
public static class CircuitBreakerProperties {
    private boolean enabled = true;
    private int failureThreshold = 3;
    private long openDurationMillis = 60_000L;
}
```

Expose getters/setters and a method:

```java
public ModelCircuitBreakerPolicy toPolicy()
```

- [ ] **Step 4: Add Spring bean wiring**

Add:

```java
@Bean
@ConditionalOnMissingBean
public ModelHealthStore modelHealthStore(DockerExecutorProperties properties) {
    return new ModelHealthStore(properties.getCircuitBreaker().toPolicy());
}
```

Update `repairExecutorPort(...)` to receive `ModelHealthStore modelHealthStore` and pass it to the new executor constructor.

- [ ] **Step 5: Add application defaults**

Under `rd.executor.docker` add:

```yaml
circuit-breaker:
  enabled: ${RD_EXECUTOR_DOCKER_CIRCUIT_BREAKER_ENABLED:true}
  failure-threshold: ${RD_EXECUTOR_DOCKER_CIRCUIT_BREAKER_FAILURE_THRESHOLD:3}
  open-duration-millis: ${RD_EXECUTOR_DOCKER_CIRCUIT_BREAKER_OPEN_DURATION_MILLIS:60000}
```

- [ ] **Step 6: Run configuration verification**

Run:

```bash
./mvnw -pl bootstrap -am -Dtest=DockerExecutorConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: tests pass.

## Task 4: Add unified repair audit trail

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/audit/RepairAuditEventType.java`
- Create: `engine/src/main/java/com/wish/rd/engine/audit/RepairAuditEvent.java`
- Create: `engine/src/main/java/com/wish/rd/engine/audit/RepairAuditSinkPort.java`
- Create: `engine/src/main/java/com/wish/rd/engine/audit/NoopRepairAuditSink.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/ticket/TicketRepairEngine.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/ticket/TicketRepairExecutionConsumer.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/bugfix/RdBotFixEngine.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/EngineBugFixExecutorAdapter.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/PostgresRepairAuditSink.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RepairAuditEventRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RepairAuditEventMapper.java`
- Modify: `bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql`
- Test: `engine/src/test/java/com/wish/rd/engine/TicketRepairEngineTest.java`
- Test: `engine/src/test/java/com/wish/rd/engine/TicketRepairExecutionConsumerTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresPersistenceCrudIntegrationTest.java`

- [ ] **Step 1: Add failing audit tests**

Assert that a single `repairRecordId` produces ordered audit events for:
- `TICKET_RECEIVED`
- `TICKET_SNAPSHOT_FETCHED`
- `RAG_CONTEXT_READY`
- `EXECUTION_STARTED`
- `EXECUTION_FINISHED`
- `PR_CREATED`
- `TICKET_WRITE_BACK_FINISHED`

For a failure path, assert `errorCategory` and `handlerHint` are present in metadata.

- [ ] **Step 2: Add audit contracts**

Use exact enum constants:

```java
public enum RepairAuditEventType {
    TICKET_RECEIVED,
    TICKET_SNAPSHOT_FETCHED,
    RAG_CONTEXT_READY,
    EXECUTION_STARTED,
    EXECUTION_FINISHED,
    PR_CREATED,
    TICKET_WRITE_BACK_FINISHED,
    QUEUE_DEAD_LETTERED,
    MANUAL_RECOVERY_REQUESTED,
    EXECUTION_STOP_REQUESTED,
    EXECUTION_STOPPED,
    KNOWLEDGE_REFRESH_FAILED,
    SECURITY_POLICY_REJECTED
}
```

`RepairAuditEvent` fields:

```java
String repairRecordId;
String taskId;
String ticketId;
RepairAuditEventType type;
String externalSystem;
String summary;
Map<String, String> metadata;
long createdAtEpochMillis;
```

- [ ] **Step 3: Persist audit events**

Add SQL table:

```sql
CREATE TABLE IF NOT EXISTS repair_audit_events (
    id BIGINT PRIMARY KEY,
    repair_record_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(256) NOT NULL DEFAULT '',
    ticket_id VARCHAR(256) NOT NULL DEFAULT '',
    event_type VARCHAR(64) NOT NULL,
    external_system VARCHAR(64) NOT NULL DEFAULT '',
    summary TEXT NOT NULL DEFAULT '',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_repair_audit_events_record
    ON repair_audit_events (repair_record_id, created_at);
```

Generate ids with existing Snowflake ID generator style used by other Postgres adapters.

- [ ] **Step 4: Emit audit events from existing seams**

Inject `RepairAuditSinkPort` into:
- `TicketRepairEngine` for ticket receive, snapshot, RAG context, and write-back.
- `TicketRepairExecutionConsumer` for execution handoff and final status.
- `EngineBugFixExecutorAdapter` for Docker execution finish and PR creation.

Use `NoopRepairAuditSink` when the port is absent in tests.

- [ ] **Step 5: Run audit tests**

Run:

```bash
./mvnw -pl engine,bootstrap -am -Dtest=TicketRepairEngineTest,TicketRepairExecutionConsumerTest,PostgresPersistenceCrudIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: audit events are queryable by `repair_record_id`.

## Task 5: Add RocketMQ idempotency, dead-letter, retry cap, and manual replay

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/ticket/RepairQueueDeadLetter.java`
- Create: `engine/src/main/java/com/wish/rd/engine/ticket/RepairQueueDeadLetterRepository.java`
- Create: `engine/src/main/java/com/wish/rd/engine/ticket/RepairQueueRecoveryPort.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/ticket/TicketRepairExecutionConsumer.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/rocketmq/RocketMqRepairQueueAdapter.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/PostgresRepairQueueDeadLetterRepository.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RepairQueueDeadLetterRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RepairQueueDeadLetterMapper.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/operation/RepairOperationController.java`
- Test: `engine/src/test/java/com/wish/rd/engine/TicketRepairExecutionConsumerTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/RocketMqRepairQueueAdapterTest.java`

- [ ] **Step 1: Add failing idempotency and dead-letter tests**

Assert:
- Same `eventId` or same `(ticketId, source, eventType)` does not create duplicate `repair_records`.
- Message attempt greater than configured max creates a dead-letter row and returns consume success to stop retry storms.
- Manual replay republishes the original `RepairTicketMessage` with `attempt=1` and writes an audit event `MANUAL_RECOVERY_REQUESTED`.

- [ ] **Step 2: Add dead-letter schema**

Add SQL table:

```sql
CREATE TABLE IF NOT EXISTS repair_queue_dead_letters (
    id BIGINT PRIMARY KEY,
    ticket_id VARCHAR(256) NOT NULL,
    trace_id VARCHAR(256) NOT NULL DEFAULT '',
    source VARCHAR(64) NOT NULL DEFAULT '',
    event_id VARCHAR(256) NOT NULL DEFAULT '',
    event_type VARCHAR(128) NOT NULL DEFAULT '',
    original_attempt INTEGER NOT NULL DEFAULT 0,
    reason TEXT NOT NULL DEFAULT '',
    message_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    replayed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    replayed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_repair_queue_dead_letters_event
    ON repair_queue_dead_letters (ticket_id, source, event_id, event_type);
```

- [ ] **Step 3: Enforce retry cap**

Use `rd.rocketmq.repair.max-retry-attempts`. In `TicketRepairExecutionConsumer`, when `message.attempt()` exceeds the cap, save a dead letter and return true so RocketMQ does not redeliver indefinitely.

- [ ] **Step 4: Add manual replay API**

Expose:

```http
GET /admin/operations/dead-letters
POST /admin/operations/dead-letters/{id}/replay
```

`replay` must:
- Load the dead letter.
- Publish a new message with `attempt=1`.
- Mark the row `replayed=true`.
- Emit audit event `MANUAL_RECOVERY_REQUESTED`.

- [ ] **Step 5: Run queue tests**

Run:

```bash
./mvnw -pl engine,bootstrap -am -Dtest=TicketRepairExecutionConsumerTest,RocketMqRepairQueueAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: duplicate messages are idempotent; capped failures enter manual recovery.

## Task 6: Add Docker execution observability and RD stop path

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairExecutionControlPort.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairExecutionStopCommand.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairExecutionStopResult.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/docker/DockerExecutionRegistry.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/docker/ContainerControlPort.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/DockerClaudeCodeExecutor.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/ProcessContainerRunner.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskController.java`
- Test: `exec/src/test/java/com/wish/rd/exec/repair/docker/DockerClaudeCodeExecutorTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/ProcessContainerRunnerTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskControllerTest.java`

- [ ] **Step 1: Add failing stop-path tests**

Assert:
- `DockerClaudeCodeExecutor` registers running task metadata before calling `ContainerRunnerPort.run`.
- Metadata is unregistered in a `finally` block.
- `POST /admin/rd-tasks/{taskId}/stop` calls execution control and marks task cancelled.
- Stop returns a stable response when no container is running.
- Timeout and budget alerts still do not stop execution automatically.

- [ ] **Step 2: Add execution registry and control records**

`DockerExecutionRegistry` stores:

```java
String repairRecordId;
String taskId;
String ticketId;
String provider;
String containerName;
long startedAtEpochMillis;
long lastHeartbeatEpochMillis;
Path outputDirectory;
```

Use `ConcurrentHashMap<String, RunningExecution>` keyed by taskId.

- [ ] **Step 3: Add container stop control**

`ContainerControlPort`:

```java
RepairExecutionStopResult stop(RepairExecutionStopCommand command);
```

`ProcessContainerRunner` implements it by launching:

```bash
docker stop <containerName>
```

Do not use shell string concatenation. Build argv as `List.of("docker", "stop", containerName)`.

- [ ] **Step 4: Add RD manual stop endpoint**

Add endpoint:

```http
POST /admin/rd-tasks/{taskId}/stop
```

Behavior:
- Call `RepairExecutionControlPort.stop`.
- Mark the task as `CANCELLED` through `RagStreamTaskRegistry`.
- Emit audit events `EXECUTION_STOP_REQUESTED` and `EXECUTION_STOPPED`.
- Return task id, stopped flag, container name, and message.

- [ ] **Step 5: Define ticket cancellation behavior**

Do not guess Feishu cancellation fields. Implement a local engine method:

```java
public RepairExecutionStopResult stopByTicketId(String ticketId, String reason)
```

Existing Feishu adapters may call it only after a user-provided field/status mapping identifies cancellation. Until that mapping is provided, the manual admin stop endpoint is the production-safe stop path.

- [ ] **Step 6: Run execution control tests**

Run:

```bash
./mvnw -pl exec,bootstrap -am -Dtest=DockerClaudeCodeExecutorTest,ProcessContainerRunnerTest,RdTaskControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: stop is explicit and manual; alerts never hard-kill tasks.

## Task 7: Add knowledge refresh metrics and alerts

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeRefreshMetric.java`
- Create: `rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeRefreshMetricSink.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeRefreshScheduler.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/PostgresKnowledgeRefreshMetricSink.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/operation/RepairOperationController.java`
- Modify: `bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql`
- Test: `rag/src/test/java/com/wish/rd/rag/knowledge/KnowledgeRefreshSchedulerTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresPersistenceCrudIntegrationTest.java`

- [ ] **Step 1: Add failing refresh metric tests**

Assert:
- A successful refresh writes source token, old chunk count, new chunk count, duration, and success flag.
- A failed refresh writes source token and error reason.
- Consecutive failures for the same source reach threshold and create a `KNOWLEDGE_REFRESH_FAILED` alert.

- [ ] **Step 2: Add refresh metric model**

`KnowledgeRefreshMetric` fields:

```java
String documentId;
String sourceType;
String sourceToken;
boolean success;
int previousChunkCount;
int newChunkCount;
long durationMillis;
String errorReason;
long createdAtEpochMillis;
```

- [ ] **Step 3: Persist metrics**

Add SQL table:

```sql
CREATE TABLE IF NOT EXISTS knowledge_refresh_metrics (
    id BIGINT PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(64) NOT NULL DEFAULT '',
    source_token VARCHAR(512) NOT NULL DEFAULT '',
    success BOOLEAN NOT NULL,
    previous_chunk_count INTEGER NOT NULL DEFAULT 0,
    new_chunk_count INTEGER NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_reason TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_refresh_metrics_source
    ON knowledge_refresh_metrics (source_type, source_token, created_at);
```

- [ ] **Step 4: Add operations API for refresh health**

Expose:

```http
GET /admin/operations/knowledge-refresh
```

Return latest refresh delay, success rate, top failure sources, and chunk count deltas.

- [ ] **Step 5: Run refresh tests**

Run:

```bash
./mvnw -pl rag,bootstrap -am -Dtest=KnowledgeRefreshSchedulerTest,PostgresPersistenceCrudIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: failed refreshes are visible with source token and reason.

## Task 8: Add allowlists and log redaction for execution safety

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/security/ExecutionAllowlistPolicy.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/security/SecretRedactor.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/DockerClaudeCodeExecutor.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/DockerExecutorProperties.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/ProcessContainerRunner.java`
- Modify: `bootstrap/src/main/resources/application.yaml`
- Test: `exec/src/test/java/com/wish/rd/exec/repair/security/SecretRedactorTest.java`
- Test: `exec/src/test/java/com/wish/rd/exec/repair/docker/DockerClaudeCodeExecutorTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/DockerAssetPolicyTest.java`

- [ ] **Step 1: Add failing allowlist and redaction tests**

Assert:
- Non-allowlisted repository URL is rejected before Docker runs.
- Non-allowlisted image is rejected before Docker runs.
- Secrets in env, docker argv, metadata, alerts, and audit event metadata are replaced with `<redacted>`.
- `RD_CLAUDE_AUTH_TOKEN_ENV` remains visible as an env-var name, while the referenced secret value is not visible.

- [ ] **Step 2: Add allowlist policy**

`ExecutionAllowlistPolicy` accepts:

```java
List<String> allowedRepositoryUrls;
List<String> allowedImages;
```

Rules:
- Empty list means no restriction for local smoke compatibility.
- Non-empty list requires exact match after trimming.
- Rejection returns `FAILED` and emits `SECURITY_POLICY_REJECTED`.

- [ ] **Step 3: Add redactor**

`SecretRedactor` replaces values for keys containing:

```text
SECRET
TOKEN
PASSWORD
API_KEY
ACCESS_KEY
PAT
AUTHORIZATION
```

It must redact map values and `KEY=value` strings.

- [ ] **Step 4: Add configuration**

Under `rd.executor.docker` add:

```yaml
allowed-images: []
allowed-repositories: []
```

Bind them in `DockerExecutorProperties`.

- [ ] **Step 5: Run safety tests**

Run:

```bash
./mvnw -pl exec,bootstrap -am -Dtest=SecretRedactorTest,DockerClaudeCodeExecutorTest,DockerAssetPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: unsafe execution is blocked before container launch and no secret value appears in metadata.

## Task 9: Add operations API and admin entry point

**Files:**
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/operation/RepairOperationController.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskController.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/repair/RepairRecordController.java`
- Modify: `frontend/src/pages/admin/rdtask/RdTaskListPage.tsx`
- Modify: `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`
- Modify: frontend route registration file used by the current frontend workspace
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/RepairOperationControllerTest.java`

- [ ] **Step 1: Add failing operations API tests**

Assert endpoints:

```http
GET /admin/operations/overview
GET /admin/operations/alerts
GET /admin/operations/audit-events?repairRecordId={id}
GET /admin/operations/dead-letters
POST /admin/operations/dead-letters/{id}/replay
GET /admin/operations/knowledge-refresh
```

`overview` must include counts for pending, running, failed, waiting CR, dead-lettered, and active alerts.

- [ ] **Step 2: Implement backend views**

Return records, not raw `Map<String,Object>`, for every response body. Truncate large summaries in list endpoints to `200` characters and keep detailed data on detail endpoints.

- [ ] **Step 3: Add frontend operations entry**

Add a single operations section reachable from the existing admin navigation. It must show:
- task overview counts,
- active alerts,
- dead letters with replay action,
- knowledge refresh failures,
- recent audit events for a selected repair record,
- stop button for running tasks.

- [ ] **Step 4: Run backend operations tests**

Run:

```bash
./mvnw -pl bootstrap -am -Dtest=RepairOperationControllerTest,RdTaskControllerTest,RepairRecordControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all operations endpoints return typed, redacted views.

## Task 10: Final verification and CR handoff

**Files:**
- Modify: `docs/execution/docker-claude-code.md`
- Modify: `docs/roadmap/public-roadmap.md`
- Modify: `AGENTS.md` only if implementation introduces a durable new rule.

- [ ] **Step 1: Update docs**

Document:
- model circuit breaker config,
- manual stop behavior,
- no auto-stop on timeout/budget alert,
- audit trail query by `repair_record_id`,
- dead-letter replay,
- security allowlists and redaction.

- [ ] **Step 2: Run focused verification**

Run:

```bash
./mvnw -pl exec,bootstrap,engine,rag -am -Dtest=ModelHealthStoreTest,DockerClaudeCodeExecutorTest,DockerExecutorConfigurationTest,TicketRepairExecutionConsumerTest,RepairOperationControllerTest,KnowledgeRefreshSchedulerTest,SecretRedactorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: focused P3 and circuit breaker tests pass.

- [ ] **Step 3: Run full verification**

Run:

```bash
./mvnw test
```

Expected: full Maven suite passes. If local PostgreSQL, RocketMQ, Docker, or credentials are missing, record which integration tests were skipped or failed due to missing external services and include the focused tests that passed.

- [ ] **Step 4: Subagent CR**

After implementation, use `superpowers:subagent-driven-development` review flow:
- spec compliance reviewer checks every task in this plan and P3 Wiki P3-1 through P3-6,
- code quality reviewer checks boundaries, JavaDoc, tests, redaction, and dependency direction,
- final reviewer checks the whole branch before merge or PR.

## Acceptance Checklist

- [ ] `DockerClaudeCodeExecutor#execute` uses model health before real provider execution.
- [ ] Model health preserves ragent tri-state behavior: `CLOSED`, `OPEN`, `HALF_OPEN`.
- [ ] Provider attempts include skipped circuit-open providers in metadata.
- [ ] Provider fallback still works when a provider fails validation.
- [ ] `NEED_INFO` and `UNSAFE` do not mark a provider unhealthy.
- [ ] Repository publish and PR creation failures do not count as model provider failures.
- [ ] P3-1: `repair_record_id` can retrieve ordered audit events from ticket intake through write-back.
- [ ] P3-2: duplicate queue messages do not duplicate `repair_records`; capped retries enter dead-letter; replay works.
- [ ] P3-3: Docker execution has running metadata, heartbeat timestamp, alert-only timeout/budget, log artifact visibility, and manual stop.
- [ ] P3-4: knowledge refresh metrics include delay, success rate, failure source, source token, error reason, and chunk count delta.
- [ ] P3-5: repository and image allowlists block unsafe execution; logs and metadata redact secrets.
- [ ] P3-6: operations APIs expose repair records, execution state, alerts, knowledge refresh, failures, retry, and stop actions.
- [ ] Ticket cancellation during execution has a safe stop path: explicit admin stop now, Feishu cancellation bridge only after exact cancel mapping is provided.
- [ ] No route shape is broken for existing public endpoints.
- [ ] `./mvnw test` passes or external-service gaps are documented with focused test evidence.

## Self-Review Notes

- Spec coverage: Tasks 1-3 cover the explicit ragent model circuit breaker migration; Tasks 4-9 cover Feishu P3 tasks P3-1 through P3-6 and the cancellation stop question; Task 10 covers documentation, verification, and subagent CR.
- Type consistency: Model health classes live under `exec.repair.model`; Docker execution control classes live under `exec.repair.execution` and `exec.repair.docker`; operation API classes live under `bootstrap.controller.admin.operation`.
- Scope control: This plan does not guess Feishu cancellation fields or ticket status enums. It implements a generic manual stop path and leaves Feishu cancel detection behind a user-provided mapping.
