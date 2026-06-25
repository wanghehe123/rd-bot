# RD-Bot Repair Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or an equivalent independent review agent to validate the final implementation. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a first production-oriented repair acceptance foundation: durable repair knowledge assets, Docker-Claude-planner-ready acceptance plan contracts, local deterministic validation, and execution-flow propagation without embedding a second local Agent framework.

**Architecture:** `engine` remains the orchestration layer and owns acceptance plan contracts/validation. Docker Claude Code may generate the plan through an engine port, but local RD-Bot validates and persists it before passing it to the repair executor. `exec` owns repair asset persistence contracts; `bootstrap` owns PostgreSQL and REST adapters. P3 governance is represented by interfaces/config fields only and has no production enforcement implementation in this phase.

**Tech Stack:** Java 21, Spring Boot 3.5, Maven, PostgreSQL SQL scripts, MyBatis-Plus, JUnit 5.

---

## Current Code Evidence

- `repair_records` and `repair_record_artifacts` already exist in `bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql`.
- `exec` has `RepairRecordRepository`, in-memory repository, PostgreSQL repository, and admin read controller.
- `engine` has the RAG-to-executor flow in `RdBotFixEngine`; it currently builds a prompt and sends a `BugFixExecutionRequest` without an acceptance plan.
- `bootstrap` bridges `BugFixExecutor` to `RepairExecutorPort` and GitHub PR creation in `EngineBugFixExecutorAdapter`.
- `engine` cannot depend on `exec`; `bootstrap` can depend on both and must remain the adapter boundary.

## Non-Negotiable Decisions

1. Do not build a second local Agent framework in `engine`.
2. Acceptance plan generation is an engine port; Docker Claude Code is a possible implementation behind that port.
3. Docker Claude Code can generate plan candidates, but RD-Bot validates the schema and decides whether to use the plan.
4. Final acceptance pass/fail must come from a deterministic runner/result, not from Claude Code self-evaluation.
5. SQL expansion focuses on bug cause and repair process assets. Scripts are supported as assets, but are not the center of the model.
6. P1 Feishu + RocketMQ flow remains compatible.
7. P3 governance is represented by interfaces/config placeholders only.

## Task 1: Repair Asset SQL And Repository Contract

**Files:**
- Modify: `bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/RepairAssetType.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/RepairAsset.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/CreateRepairAssetCommand.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/RepairRecordRepository.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/InMemoryRepairRecordRepository.java`
- Modify: `exec/src/test/java/com/wish/rd/exec/repair/InMemoryRepairRecordRepositoryTest.java`

- [ ] **Step 1: Write failing exec repository tests**

Add a test proving that a repair record can store multiple repair assets:

```java
@Test
void shouldPersistRepairAssetsFocusedOnBugCauseAndProcess() {
    InMemoryRepairRecordRepository repository = new InMemoryRepairRecordRepository(generatorWithMovingClock());
    RepairRecord record = repository.create(new CreateRepairRecordCommand(
            "FS-ASSET-1", "", "create order fails", Map.of("priority", "P1")
    ));

    RepairAsset rootCause = repository.addAsset(new CreateRepairAssetCommand(
            record.id(),
            RepairAssetType.BUG_CAUSE,
            "字段映射不一致",
            "client sends delivery_address while server requires address",
            "{\"field\":\"address\",\"source\":\"RAG_CONTEXT\"}",
            "",
            true
    ));
    RepairAsset script = repository.addAsset(new CreateRepairAssetCommand(
            record.id(),
            RepairAssetType.ACCEPTANCE_SCRIPT,
            "外卖下单验收脚本",
            "successful reusable local-http script",
            "{\"scriptId\":\"waimai-create-order-basic\"}",
            "",
            true
    ));

    List<RepairAsset> assets = repository.listAssets(record.id());

    assertEquals(2, assets.size());
    assertEquals(rootCause.id(), assets.get(0).id());
    assertEquals(script.id(), assets.get(1).id());
    assertEquals(RepairAssetType.BUG_CAUSE, assets.get(0).assetType());
    assertTrue(assets.get(0).reusable());
}
```

Run:

```bash
./mvnw -pl exec -Dtest=InMemoryRepairRecordRepositoryTest#shouldPersistRepairAssetsFocusedOnBugCauseAndProcess test
```

Expected before implementation: compilation failure because asset types and repository methods do not exist.

- [ ] **Step 2: Implement exec asset records and in-memory repository support**

Add `RepairAssetType` values:

```java
BUG_CAUSE,
FIX_STRATEGY,
REPAIR_PROCESS,
REPRO_STEPS,
ACCEPTANCE_PLAN,
ACCEPTANCE_SCRIPT,
VALIDATION_RESULT,
API_SEMANTIC_HINT,
OTHER
```

Add `RepairAsset` and `CreateRepairAssetCommand` records with compact constructors that normalize null strings and JSON metadata.

- [ ] **Step 3: Expand SQL**

Add `repair_assets`:

```sql
CREATE TABLE IF NOT EXISTS repair_assets (
    id               BIGINT PRIMARY KEY,
    repair_record_id BIGINT NOT NULL REFERENCES repair_records(id) ON DELETE CASCADE,
    asset_type       VARCHAR(64) NOT NULL,
    title            TEXT NOT NULL DEFAULT '',
    summary          TEXT NOT NULL DEFAULT '',
    content_json     JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_artifact_id BIGINT NULL REFERENCES repair_record_artifacts(id) ON DELETE SET NULL,
    reusable         BOOLEAN NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_repair_assets_record ON repair_assets (repair_record_id, created_at);
CREATE INDEX IF NOT EXISTS idx_repair_assets_type ON repair_assets (asset_type, created_at);
CREATE INDEX IF NOT EXISTS idx_repair_assets_reusable ON repair_assets (reusable, asset_type);
```

- [ ] **Step 4: Verify exec repository test passes**

Run:

```bash
./mvnw -pl exec -Dtest=InMemoryRepairRecordRepositoryTest#shouldPersistRepairAssetsFocusedOnBugCauseAndProcess test
```

Expected: PASS.

**Must-Pass Acceptance Criteria:**
- `repair_assets` table exists in SQL with `repair_record_id`, `asset_type`, `content_json`, `source_artifact_id`, `reusable`, and indexes.
- `RepairAssetType` includes bug-cause and process-focused types, not only scripts.
- In-memory repository can add/list assets ordered by creation.
- Existing `repair_record_artifacts` behavior remains unchanged.

## Task 2: PostgreSQL Asset Adapter And Admin Read API

**Files:**
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RepairAssetRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RepairAssetMapper.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/PostgresRepairRecordRepository.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/repair/RepairRecordController.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/RepairRecordControllerTest.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresPersistenceCrudIntegrationTest.java`

- [ ] **Step 1: Write failing admin controller test**

Add a test proving `/repair-records/{id}/assets` returns assets and does not expose unrelated records.

Run:

```bash
./mvnw -pl bootstrap -am -Dtest=RepairRecordControllerTest#listsAssetsOrderedByCreation -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected before implementation: compilation failure or 404 because the endpoint and asset repository methods do not exist.

- [ ] **Step 2: Implement PostgreSQL mapper and repository methods**

`PostgresRepairRecordRepository.addAsset` must:
- fail if the repair record does not exist;
- validate `contentJson` using `RepairRecordJson.normalizeMetadataJson`;
- insert `content_json::jsonb`;
- return a `RepairAsset`.

`listAssets` must return assets ordered by `created_at`, then `id`.

- [ ] **Step 3: Expose admin read endpoint**

Add:

```text
GET /repair-records/{id}/assets
```

This endpoint returns id, repairRecordId, assetType, title, summary, contentJson, sourceArtifactId, reusable, createdAtEpochMillis.

- [ ] **Step 4: Verify focused bootstrap tests**

Run:

```bash
./mvnw -pl bootstrap -am -Dtest=RepairRecordControllerTest,PostgresPersistenceCrudIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS, with PostgreSQL integration tests still gated by existing properties unless enabled.

**Must-Pass Acceptance Criteria:**
- PostgreSQL repository can add/list assets.
- Admin endpoint lists assets for one repair record only.
- Asset JSON is normalized and rejects invalid JSON without mutating stored data.
- Existing record/artifact endpoints remain compatible.

## Task 3: Engine Acceptance Plan Contracts And Validation

**Files:**
- Create package: `engine/src/main/java/com/wish/rd/engine/bugfix/acceptance`
- Create: `AcceptancePlan.java`
- Create: `AcceptancePlanStep.java`
- Create: `AcceptanceAssertion.java`
- Create: `AcceptancePlanStatus.java`
- Create: `AcceptancePlanGenerationCommand.java`
- Create: `AcceptancePlanGenerationResult.java`
- Create: `AcceptancePlanGeneratorPort.java`
- Create: `AcceptancePlanValidator.java`
- Create test: `engine/src/test/java/com/wish/rd/engine/AcceptancePlanValidatorTest.java`

- [ ] **Step 1: Write failing validator tests**

Tests must prove:
- a ready plan requires task ID, ticket ID, at least one step, and at least one assertion;
- a disabled/unsupported plan is allowed only with a nonblank reason;
- `toPromptSection` includes plan source, steps, assertions, and explicitly says RD-Bot is the final validator.

Run:

```bash
./mvnw -pl engine -Dtest=AcceptancePlanValidatorTest test
```

Expected before implementation: compilation failure because the acceptance package does not exist.

- [ ] **Step 2: Implement immutable records and validator**

Keep these in `engine` so `RdBotFixEngine` can use them without depending on `exec`.

`AcceptancePlanGeneratorPort.disabled()` must return a disabled result, so production stays safe when no Docker planner is wired.

- [ ] **Step 3: Verify engine validator tests**

Run:

```bash
./mvnw -pl engine -Dtest=AcceptancePlanValidatorTest test
```

Expected: PASS.

**Must-Pass Acceptance Criteria:**
- `engine` has no import from `exec` or `bootstrap`.
- Ready plans are deterministically validated before use.
- Disabled/unsupported plans are explicit and do not pretend to be successful.
- Prompt text states Docker Claude Code may generate plans but RD-Bot validates and controls pass/fail.

## Task 4: Wire Acceptance Plan Through BugFix Flow

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/bugfix/RdBotFixEngine.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/bugfix/BugFixExecutionRequest.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/bugfix/BugFixPromptBuilder.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/bugfix/RdBotFixResult.java`
- Modify: `engine/src/main/resources/prompt/bugfix.st`
- Modify: `engine/src/test/java/com/wish/rd/engine/RdBotFixEngineTest.java`

- [ ] **Step 1: Write failing flow test**

Add a test proving:
- `RdBotFixEngine` calls `AcceptancePlanGeneratorPort` after RAG context exists;
- the generated plan appears in the prompt;
- `BugFixExecutionRequest` carries the validated plan to the executor;
- if no generator is configured, the request carries a disabled plan.

Run:

```bash
./mvnw -pl engine -Dtest=RdBotFixEngineTest test
```

Expected before implementation: compilation failure or assertions fail.

- [ ] **Step 2: Implement wiring**

`RdBotFixEngine` must:
- inject `ObjectProvider<AcceptancePlanGeneratorPort>`;
- generate and validate the plan after `ragBugFixEngine.findBugFixMessgaesForAgent`;
- include the plan in prompt and execution request;
- not call any Docker/Claude implementation directly.

- [ ] **Step 3: Verify engine flow tests**

Run:

```bash
./mvnw -pl engine -Dtest=RdBotFixEngineTest,AcceptancePlanValidatorTest test
```

Expected: PASS.

**Must-Pass Acceptance Criteria:**
- Acceptance plan generation is local orchestration through a port, not local Agent implementation.
- Docker Claude Code repair prompt receives the validated acceptance plan.
- Existing queue rejection flow still does not call RAG, plan generator, or executor.
- Existing `BugFixExecutionRequest` construction remains source-compatible through an overload.

## Task 5: Bootstrap Bridge Persists Acceptance Plan Context To Execution

**Files:**
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/EngineBugFixExecutorAdapter.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/EngineBugFixExecutorAdapterTest.java`

- [ ] **Step 1: Write failing bridge test**

Test that a ready acceptance plan is present in `RepairJobCommand.contextJson()` as `acceptancePlanJson` and that PR metadata includes `acceptancePlanStatus`.

Run:

```bash
./mvnw -pl bootstrap -am -Dtest=EngineBugFixExecutorAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected before implementation: assertion fails because context/metadata do not include acceptance plan.

- [ ] **Step 2: Implement bridge context propagation**

`EngineBugFixExecutorAdapter` must:
- include plan JSON and status in `RepairJobCommand.contextJson`;
- include `acceptancePlanStatus` in PR metadata;
- not create a PR when repair execution status is not `SUCCESS`, preserving current behavior.

**Must-Pass Acceptance Criteria:**
- The repair executor receives the exact validated plan JSON.
- PR metadata records whether the plan was `READY`, `DISABLED`, or `UNSUPPORTED`.
- Existing successful/failed/unsafe PR behavior remains compatible.

## Task 6: Governance Placeholders Only

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/bugfix/acceptance/AcceptanceExecutionPolicy.java`
- Modify: `bootstrap/src/main/resources/application.yaml`
- Modify: relevant tests if configuration policy is asserted.

- [ ] **Step 1: Add minimal policy value object**

`AcceptanceExecutionPolicy` should include:
- `enabled`;
- `environmentAllowlist`;
- `maxSteps`;
- `writeOperationsEnabled`.

No enforcement engine is implemented in this phase.

- [ ] **Step 2: Add disabled-by-default config sample**

Add:

```yaml
rd:
  repair:
    acceptance:
      enabled: false
      max-steps: 20
      write-operations-enabled: false
      environment-allowlist:
        - local
        - sit
```

**Must-Pass Acceptance Criteria:**
- Governance is represented but not enforced.
- Defaults are disabled/conservative.
- No production allowlist/audit/approval implementation is added in this phase.

## Task 7: Verification And Independent Subagent Review

**Files:**
- No planned production edits.

- [ ] **Step 1: Run focused tests**

Run:

```bash
./mvnw -pl exec,engine,bootstrap -am -Dtest=InMemoryRepairRecordRepositoryTest,AcceptancePlanValidatorTest,RdBotFixEngineTest,RepairRecordControllerTest,EngineBugFixExecutorAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

- [ ] **Step 2: Run broader verification**

Run:

```bash
./mvnw test
```

Expected: PASS, unless an environment-only integration gate is absent. Any failure must be documented with exact output and either fixed or classified.

- [ ] **Step 3: Dispatch independent subagent review**

The subagent must verify:
- SQL has durable asset tables.
- Plans are generated via port and validated locally.
- Docker Claude Code is not used as final pass/fail judge.
- Engine has no `exec`/`bootstrap` imports.
- P1 ticket flow compatibility remains.
- P3 governance is interface/config only.
- Tests pass or failures are accurately reported.

**Must-Pass Acceptance Criteria:**
- Focused tests pass.
- Full test result is recorded.
- Subagent returns no blocking findings, or all blocking findings are fixed and re-verified.
