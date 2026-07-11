# Project Alert Recipients and CNY Budget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the mixed Feishu recipient textarea with independent optional group/user lists and make every RD-Bot budget-facing contract use CNY at a fixed 7.20 CNY/USD rate.

**Architecture:** Keep the existing `RdAlertRecipient` API payload and Feishu routing model; only the admin form maps it to separate `CHAT_ID` and `OPEN_ID` lists. Normalize provider-reported USD at the execution boundary into CNY, then use CNY for thresholds, alert comparison, persisted project configuration, APIs, and UI. Preserve the legacy PostgreSQL USD column solely as a one-time migration input.

**Tech Stack:** Java 23, Spring Boot, MyBatis/PostgreSQL, React 18, TypeScript, Vite, Lucide, JUnit 5, MockMvc.

**Repository rule:** Do not commit in this plan; the repository `AGENTS.md` requires an explicit user request before commit/push.

---

### Task 1: Make project alert configuration CNY-native and keep recipient arrays optional

**Files:**
- Modify: `rag/src/main/java/com/wish/rd/rag/project/alert/model/RdProjectAlertConfig.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/project/alert/model/RdProjectAlertConfigCommand.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/project/alert/RdProjectAlertConfigService.java`
- Modify: `rag/src/test/java/com/wish/rd/rag/project/alert/RdProjectAlertConfigServiceTest.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/project/RdProjectAlertConfigController.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/project/RdProjectAlertConfigControllerTest.java`

- [x] **Step 1: Write failing domain and HTTP tests for CNY field names and empty recipients**

Change the existing test commands and assertions before production code:

```java
assertEquals(new BigDecimal("36.00"), saved.budgetThresholdCny());
assertEquals(List.of(), saved.recipients());
```

```java
mockMvc.perform(put("/admin/projects/{projectId}/alert-config", projectId)
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"enabled":true,"recipients":[],"eventTypes":["BUDGET_EXCEEDED"],
             "budgetThresholdCny":36,"failureThreshold":2}
            """))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.budgetThresholdCny", is(36)))
    .andExpect(jsonPath("$.recipients", hasSize(0)));
```

- [x] **Step 2: Run the focused tests and verify the CNY contract is absent**

Run:

```bash
./mvnw -q -pl rag -Dtest=RdProjectAlertConfigServiceTest test
./mvnw -q -pl bootstrap -am -Dtest=RdProjectAlertConfigControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation/test failure referring to missing `budgetThresholdCny` while the old `budgetThresholdUsd` contract still exists.

- [x] **Step 3: Rename the public domain and controller contract**

Rename the record component and command component to `budgetThresholdCny`; retain `recipients = List.of()` for null/empty input. The controller request must expose only:

```java
public record AlertConfigRequest(
        boolean enabled,
        List<RdAlertRecipient> recipients,
        Set<RdProjectAlertEventType> eventTypes,
        BigDecimal budgetThresholdCny,
        int failureThreshold
) { }
```

The service must continue to deduplicate recipients, but it must not require any recipient when `enabled=true`.

- [x] **Step 4: Re-run focused domain and controller tests**

Run the commands from Step 2.

Expected: PASS; the JSON response uses `budgetThresholdCny` and a configuration with `[]` recipients persists successfully.

### Task 2: Persist CNY truth and migrate existing PostgreSQL thresholds exactly once

**Files:**
- Modify: `bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RdProjectAlertConfigRow.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RdProjectAlertConfigMapper.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRdProjectAlertConfigStore.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresRdProjectAlertConfigStoreTest.java`

- [x] **Step 1: Write failing persistence tests for the CNY mapper field**

Replace USD fixture names in `PostgresRdProjectAlertConfigStoreTest` with the CNY equivalent and assert the row written to MyBatis contains the CNY amount:

```java
RdProjectAlertConfig config = new RdProjectAlertConfig(
        "7482000000000000201", true, List.of(), Set.of(),
        new BigDecimal("54.0000"), 2, 1L, 2L);

store.save(config);
verify(mapper).upsert(argThat(row ->
        new BigDecimal("54.0000").compareTo(row.budgetThresholdCny) == 0));
```

- [x] **Step 2: Run the persistence test and verify it fails on the missing CNY row field**

Run:

```bash
./mvnw -q -pl bootstrap -am -Dtest=PostgresRdProjectAlertConfigStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because `budgetThresholdCny` and the corresponding mapper SQL do not exist.

- [x] **Step 3: Add an idempotent CNY migration and map only CNY in production code**

Append this migration after the table definition, keeping the legacy USD column only for migration input:

```sql
ALTER TABLE rd_project_alert_configs
    ADD COLUMN IF NOT EXISTS budget_threshold_cny NUMERIC(12, 4);

UPDATE rd_project_alert_configs
SET budget_threshold_cny = ROUND(budget_threshold_usd * 7.20, 4)
WHERE budget_threshold_cny IS NULL;

ALTER TABLE rd_project_alert_configs
    ALTER COLUMN budget_threshold_cny SET DEFAULT 0;
ALTER TABLE rd_project_alert_configs
    ALTER COLUMN budget_threshold_cny SET NOT NULL;
```

Rename `RdProjectAlertConfigRow.budgetThresholdUsd` to `budgetThresholdCny`, then update mapper `INSERT`, `ON CONFLICT`, `SELECT`, and store serialization/deserialization to use `budget_threshold_cny` only. New writes must leave `budget_threshold_usd` untouched.

- [x] **Step 4: Re-run the persistence test and execute PostgreSQL migration twice**

Run:

```bash
./mvnw -q -pl bootstrap -am -Dtest=PostgresRdProjectAlertConfigStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
docker exec postgres psql -U postgres -d ragent -f /workspace/bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql
docker exec postgres psql -U postgres -d ragent -f /workspace/bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql
```

Expected: focused test PASS; SQL exits 0 twice. In the real database, a legacy `7.5000` threshold yields one `54.0000` CNY value, not `388.8000` after replay.

### Task 3: Normalize provider USD to CNY before the watchdog and expose only CNY budget DTOs

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/alert/BudgetCurrencyConverter.java`
- Create: `exec/src/test/java/com/wish/rd/exec/repair/alert/BudgetCurrencyConverterTest.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/impl/DockerClaudeCodeExecutor.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/DockerExecutorProperties.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/DockerExecutorConfiguration.java`
- Modify: `bootstrap/src/main/resources/application.yaml`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskExecutionOverviewController.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskExecutionOverviewControllerTest.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/DockerExecutorConfigurationTest.java`

- [x] **Step 1: Write failing converter and overview tests**

Add a unit test defining the fixed rounding rule:

```java
assertEquals(new BigDecimal("0.8640"),
        new BudgetCurrencyConverter(new BigDecimal("7.20"))
                .usdToCny(new BigDecimal("0.12")));
```

Update overview assertions to require CNY API fields:

```java
.andExpect(jsonPath("$.budget.budgetAlertCny", is(54.00)))
.andExpect(jsonPath("$.budget.estimatedSpendCny", is(0.8640)));
```

- [x] **Step 2: Run tests and verify the missing converter/CNY DTO failure**

Run:

```bash
./mvnw -q -pl exec -Dtest=BudgetCurrencyConverterTest test
./mvnw -q -pl bootstrap -am -Dtest=RdTaskExecutionOverviewControllerTest,DockerExecutorConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because the converter, `budget-alert-cny`, and CNY overview fields are not implemented.

- [x] **Step 3: Implement a single fixed-rate converter and wire it at the provider boundary**

Implement the converter in `exec` without Spring dependencies:

```java
public final class BudgetCurrencyConverter {
    private final BigDecimal cnyPerUsd;

    public BigDecimal usdToCny(BigDecimal usd) {
        BigDecimal normalized = usd == null ? BigDecimal.ZERO : usd;
        return normalized.multiply(cnyPerUsd).setScale(4, RoundingMode.HALF_UP);
    }
}
```

Add `rd.financial.cny-per-usd: ${RD_FINANCIAL_CNY_PER_USD:7.20}` through a focused Spring `FinancialProperties` bean. Rename Docker threshold configuration to `budgetAlertCny`, default `36.00`, and pass the converter to the production `DockerClaudeCodeExecutor` constructor. `evaluateWatchdog` must convert raw provider USD before calling `watchdog.evaluate`.

In `RdTaskExecutionOverviewController`, parse raw provider `estimatedSpendUsd`/`estimatedSpend` only as an internal input, convert it with the same rate, and return a budget record with `estimatedSpendCny` and `budgetAlertCny`.

- [x] **Step 4: Re-run conversion, configuration, and overview tests**

Run the commands from Step 2.

Expected: PASS; `$0.12` is observed as `¥0.8640`, the global default is `¥36.00`, and the HTTP DTO has no public `Usd` field.

### Task 4: Compare and send project alert budgets in CNY

**Files:**
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImRepairAlertSink.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/feishu/im/ProjectAwareFeishuRepairAlertSinkTest.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/feishu/im/FeishuImRepairAlertSinkTest.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/FeishuBudgetObservationAdapter.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/alert/RepairBudgetObservationSinkPort.java`

- [x] **Step 1: Write failing CNY comparison tests**

Update the project-aware sink test so a CNY project threshold is met by CNY observation values:

```java
configService.update(projectId, new RdProjectAlertConfigCommand(
        true, List.of(recipient), Set.of(BUDGET_EXCEEDED),
        new BigDecimal("36.0000"), 2));

sink.observe("repair-1", taskId, new BigDecimal("36.0000"),
        new BigDecimal("36.0000"), now);
assertThat(deliveryAttempts()).hasSize(1);
```

Assert the captured alert metadata uses only `estimatedSpendCny`, `thresholdSpendCny`, and `projectThresholdSpendCny`.

- [x] **Step 2: Run the alert tests and verify they fail against old USD names**

Run:

```bash
./mvnw -q -pl bootstrap -am -Dtest=ProjectAwareFeishuRepairAlertSinkTest,FeishuImRepairAlertSinkTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL while `budgetThresholdUsd` and non-currency metadata are still used.

- [x] **Step 3: Rename the alert configuration usage and emit CNY-labelled metadata**

Use `config.budgetThresholdCny()` for all comparisons. Keep `RepairBudgetObservationSinkPort` amounts generic but name its Javadoc and local variables `estimatedSpendCny`/`globalThresholdCny`; its callers already receive CNY after Task 3. Build budget alert metadata as:

```java
Map.of(
    "currency", "CNY",
    "estimatedSpendCny", estimatedSpendCny.toPlainString(),
    "thresholdSpendCny", config.budgetThresholdCny().toPlainString(),
    "globalThresholdCny", globalThresholdCny.toPlainString(),
    "projectThresholdSpendCny", config.budgetThresholdCny().toPlainString()
)
```

`formatAlert` must render budget values with `¥` and must never expose raw USD metadata.

- [x] **Step 4: Re-run project-aware alert tests**

Run the command from Step 2.

Expected: PASS; alert routing compares CNY-to-CNY and remains fail-open when Feishu delivery fails.

### Task 5: Replace the mixed recipient textarea with two optional, validated editor lists

**Files:**
- Modify: `frontend/src/pages/admin/project/ProjectListPage.tsx`
- Modify: `frontend/src/services/projectService.ts`
- Modify: `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`
- Modify: `frontend/src/services/rdTaskService.ts`

- [x] **Step 1: Define the frontend behavior with a browser-level failing case**

Before changing the UI, start the current static admin bundle and record the failure: the existing dialog presents one textarea containing `CHAT_ID:`/`OPEN_ID:` prefixes and shows `预算阈值 USD`. The acceptance screenshot must show neither after implementation.

- [x] **Step 2: Replace string parsing state with two arrays and validation helpers**

In `ProjectAlertDialog`, replace `recipients: string` with `chatRecipients: string[]` and `userRecipients: string[]`. Load from the existing payload by type; save through this pure mapping:

```ts
const normalizeRecipients = (type: AlertRecipientType, values: string[]) =>
  [...new Set(values.map((value) => value.trim()).filter(Boolean))]
    .map((value) => ({ type, value }));

const recipients = [
  ...normalizeRecipients("CHAT_ID", chatRecipients),
  ...normalizeRecipients("OPEN_ID", userRecipients),
];
```

Before save, reject non-empty group IDs that do not start with `oc_`, and non-empty user IDs that do not start with `ou_`. Empty arrays and all-blank rows are valid.

- [x] **Step 3: Implement the two-column editor using icon controls**

Use Lucide `Plus`/`Trash2` in icon-only Buttons with `Tooltip` labels. Each list has a stable header, a compact `Input` per value, and a delete control. Use `sm:grid-cols-2` for desktop and a single-column mobile fallback. Label the budget field `预算阈值（元）` and send `budgetThresholdCny`.

Update service contracts:

```ts
export interface ProjectAlertConfig {
  budgetThresholdCny: number;
}

export interface RdTaskExecutionBudget {
  estimatedSpendCny: number;
  budgetAlertCny: number;
}
```

Update task overview money formatting to `Intl.NumberFormat("zh-CN", { style: "currency", currency: "CNY" })`.

- [x] **Step 4: Verify TypeScript and reproduce the revised interaction in the browser**

Run:

```bash
cd frontend
npm run typecheck
npm run build
```

Expected: PASS. In a `1440x900` browser, each `Plus` adds only to its own list and either list can be deleted to empty. In `390x844`, the panels stack without horizontal overflow. Saving blank lists produces `recipients: []` and the detail overview renders `¥` values.

### Task 6: Full regression, real PostgreSQL migration, and acceptance evidence

**Files:**
- Modify: `docs/qa/rd-task-control-plane-enhancements-acceptance-plan-2026-07-10.md`
- Modify: `docs/qa/rd-task-control-plane-enhancements-acceptance-report-2026-07-10.md`
- Modify: `docs/superpowers/specs/2026-07-10-rd-task-control-plane-enhancements-lessons-spec.md`

- [x] **Step 1: Run all automated tests and whitespace/security checks**

Run:

```bash
./mvnw -q test
cd frontend && npm run typecheck && npm run build
git diff --check
```

Expected: all Maven tests pass; frontend build can retain the existing non-blocking chunk-size warning; diff check has no whitespace errors.

- [x] **Step 2: Execute real PostgreSQL migration and HTTP contract checks**

Start bootstrap in PostgreSQL mode after installing changed modules, then verify:

```bash
curl -fsS 'http://127.0.0.1:<port>/admin/projects/<projectId>/alert-config' \
  | jq '{recipients,budgetThresholdCny}'
docker exec postgres psql -U postgres -d ragent -Atc \
  "select budget_threshold_usd,budget_threshold_cny from rd_project_alert_configs where project_id=<projectId>;"
```

Expected: project API has CNY field only; a legacy USD record has a one-time converted CNY value; subsequent SQL replay leaves it unchanged.

- [x] **Step 3: Update evidence documents and durable lesson**

Record concrete test counts, task/project IDs, migrated threshold values, desktop/mobile browser evidence, and any external Feishu limitation. Add a lesson that provider currency must be normalized at the boundary, never mixed with threshold currency, and migrations must avoid replay multiplication.

## Plan self-review

- Spec coverage: Task 1/2 covers optional recipient persistence and CNY project thresholds; Task 3 covers provider conversion and task overview; Task 4 covers Feishu routing; Task 5 covers the approved UI; Task 6 covers real migration and browser evidence.
- No placeholders: all production fields, conversion values, migration SQL, validation rules, commands, and expected results are explicit.
- Type consistency: CNY public contracts are named `budgetThresholdCny`, `budgetAlertCny`, and `estimatedSpendCny`; raw USD only appears as a provider-boundary input or legacy migration source.

## Implementation evidence

- The PostgreSQL migration was executed twice against the local `ragent` database. Project `7479447343427883008` remains `budget_threshold_usd=7.5000` and is normalized once to `budget_threshold_cny=54.0000`.
- `GET /admin/projects/7479447343427883008/alert-config` returns `budgetThresholdCny=54.0000` and a typed `OPEN_ID` recipient; the public response has no USD budget field.
- The Spring-hosted built bundle was checked in the browser: independent group/user add controls create only their own row, the personal list can be deleted to empty, and the alert dialog renders the CNY label and its cancel/save controls at a constrained viewport.
- Real Feishu delivery remains intentionally untested; the configured recipient was not sent a message during this acceptance run.
