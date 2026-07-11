# Project Delivery Console, Retrieval Rules, and Execution Traces Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the knowledge-centric admin homepage with a project delivery console, turn global keyword CRUD into project-aware retrieval rules with preview, replace legacy RAG traces with task execution traces, and remove the Sample Questions frontend surface.

**Architecture:** Add read-model services in `engine`, keep HTTP and PostgreSQL adapters in `bootstrap`, and keep project/rewrite domain rules in `rag`. The frontend shares URL-backed project scope across Dashboard, Retrieval Rules, and Execution Traces, while existing task detail APIs remain the execution-detail truth.

**Tech Stack:** Java 23, Spring Boot 3.5, Maven, PostgreSQL, MyBatis, React 18, TypeScript, Vite, Tailwind/shadcn-style components, Node test runner, Browser/CDP acceptance checks.

---

## Scope and Working Rules

- Follow `AGENTS.md`, `RULE.md`, and `docs/superpowers/specs/2026-07-10-rd-task-control-plane-enhancements-lessons-spec.md` before implementation.
- Preserve unrelated dirty-worktree changes. Read each touched file before editing and patch only the required blocks.
- Do not commit, push, or create a PR unless the user separately requests it. Commit steps from the generic planning workflow are intentionally omitted.
- Use TDD for every domain/API behavior: write the focused failing test, run it, implement the minimum behavior, then rerun the focused suite.
- Do not run real Provider, PR creation, Feishu delivery, queue replay, or task execution side effects during acceptance.

## File Responsibility Map

### Shared frontend scope and navigation

- Create `frontend/src/hooks/useProjectScope.ts`: parse, validate, persist, and update `projectId` in URL search parameters.
- Create `frontend/src/components/ProjectScopeSelector.tsx`: reusable project selector with explicit all-project support.
- Modify `frontend/src/components/AdminLayout.tsx`: remove Sample Questions and rename navigation labels.
- Modify `frontend/src/App.tsx`: remove Sample Questions route and lazy import; lazy-load dedicated retrieval and execution-trace pages.
- Modify `frontend/src/pages/AdminPages.tsx`: remove Sample Questions, Mapping, Trace, and Trace Detail exports after extracting their replacements.
- Modify `frontend/src/api.ts`: remove Sample Questions and legacy RAG trace frontend methods.
- Modify `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/AdminFrontendController.java`: remove `/admin/sample-questions` SPA fallback.

### Delivery dashboard

- Create `engine/src/main/java/com/wish/rd/engine/admin/dashboard/DashboardRuntimeSnapshotPort.java`: runtime alert, execution, and spend projection port.
- Create `engine/src/main/java/com/wish/rd/engine/admin/dashboard/model/RdDashboardOverview.java`: stable dashboard read model.
- Create `engine/src/main/java/com/wish/rd/engine/admin/dashboard/RdDashboardQueryService.java`: project filtering, status categorization, ratios, current execution, and recent delivery aggregation.
- Create `engine/src/main/java/com/wish/rd/engine/agent/AgentStageProgressCalculator.java`: one latest-attempt/progress/current-role calculation shared by Dashboard, execution traces, and task execution overview.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/DashboardRuntimeSnapshotAdapter.java`: adapt stage attempts, alerts, Docker execution, and CNY conversion.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/dashboard/RdDashboardController.java`: expose `/admin/dashboard/overview`.
- Create `frontend/src/services/dashboardService.ts`: typed dashboard API client.
- Rewrite `frontend/src/pages/DashboardPage.tsx`: render project delivery metrics and drill-down links.

### Retrieval rules

- Create `rag/src/main/java/com/wish/rd/rag/rewrite/QueryTermMappingStore.java`: shared persistence port.
- Create `rag/src/main/java/com/wish/rd/rag/rewrite/impl/InMemoryQueryTermMappingStore.java`: test/non-production implementation.
- Create `rag/src/main/java/com/wish/rd/rag/rewrite/model/QueryTermMappingScope.java`: `GLOBAL`/`PROJECT` validation.
- Create `rag/src/main/java/com/wish/rd/rag/rewrite/model/QueryRewritePreview.java`: original text, rewritten text, and ordered matches.
- Modify `ManagedQueryTermMapping`, `QueryTermMappingCommand`, `QueryTermMappingRegistry`, and `RuleBasedQueryRewriteService`: project-aware selection and deterministic preview.
- Modify `engine/src/main/java/com/wish/rd/engine/rag/RagBugFixEngine.java`: select rules using the task project ID.
- Create PostgreSQL row, mapper, and store under `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/{entity,mapper,impl}`.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/QueryTermMappingConfiguration.java` to select the PostgreSQL or in-memory store explicitly and expose one registry bean.
- Modify `bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql`: add idempotent `rd_query_term_mappings` migration and migrate defaults as global rules.
- Modify `QueryTermMappingController`: filtering and `/mappings/preview`.
- Create `frontend/src/services/retrievalRuleService.ts` and `frontend/src/pages/admin/retrieval/RetrievalRulesPage.tsx`.

### Execution traces

- Extend `engine/src/main/java/com/wish/rd/engine/agent/AgentStageRunStore.java` with batch task lookup.
- Modify in-memory and PostgreSQL stage-run stores/mappers to implement batch lookup without N+1 queries.
- Create `engine/src/main/java/com/wish/rd/engine/admin/trace/model/ExecutionTracePage.java` and related row/filter records.
- Create `engine/src/main/java/com/wish/rd/engine/admin/trace/ExecutionTraceQueryService.java`: task-rooted trace list projection.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/trace/ExecutionTraceController.java`.
- Extend audit querying with an explicit `taskId` filter.
- Create `frontend/src/services/executionTraceService.ts`, `ExecutionTracePage.tsx`, and `ExecutionTraceDetailPage.tsx`.

## Task 1: Remove Sample Questions and Introduce Shared Project Scope

**Files:**
- Create: `frontend/src/hooks/useProjectScope.ts`
- Create: `frontend/src/components/ProjectScopeSelector.tsx`
- Create: `frontend/test/projectScope.test.ts`
- Modify: `frontend/test/adminVisualSystem.test.ts`
- Modify: `frontend/src/components/AdminLayout.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/pages/AdminPages.tsx`
- Modify: `frontend/src/api.ts`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/AdminFrontendController.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/AdminFrontendControllerTest.java`

- [ ] **Step 1: Add failing route and navigation policy assertions**

Add assertions that the frontend contains “检索规则” and “执行追踪”, contains no “示例问题” menu/route, and the Spring fallback returns 404 for `/admin/sample-questions` while all retained routes return the shell.

```ts
assert.doesNotMatch(layout, /示例问题/);
assert.doesNotMatch(app, /sample-questions|SampleQuestionPage/);
assert.match(layout, /检索规则/);
assert.match(layout, /执行追踪/);
```

```java
mockMvc.perform(get("/admin/sample-questions"))
        .andExpect(status().isNotFound());
```

- [ ] **Step 2: Run the focused tests and verify the expected failure**

Run:

```bash
cd frontend && node --test --experimental-strip-types test/adminVisualSystem.test.ts
./mvnw -q -pl bootstrap -Dtest=AdminFrontendControllerTest test
```

Expected: frontend assertions fail because the old label and route remain; Spring assertion fails because the old SPA fallback remains.

- [ ] **Step 3: Remove the Sample Questions frontend surface and rename the two retained entries**

Remove the menu item, lazy route, page export, API methods, and Spring SPA fallback. Keep the backend `/sample-questions` controller unchanged.

- [ ] **Step 4: Add failing project-scope normalization tests**

Define pure helpers used by the hook:

```ts
export function resolveProjectScope(
  urlProjectId: string | null,
  rememberedProjectId: string | null,
  enabledProjectIds: string[],
  allowAll: boolean
): string;
```

Test these cases:

```ts
assert.equal(resolveProjectScope("p2", "p1", ["p1", "p2"], true), "p2");
assert.equal(resolveProjectScope("missing", "p2", ["p1", "p2"], true), "p2");
assert.equal(resolveProjectScope(null, null, ["p1"], true), "p1");
assert.equal(resolveProjectScope("all", null, ["p1"], true), "all");
assert.equal(resolveProjectScope("all", null, ["p1"], false), "p1");
```

- [ ] **Step 5: Implement the URL-backed hook and selector**

The hook must read `projectId` via `useSearchParams`, validate against enabled project IDs, write changes with `setSearchParams`, and store only the selected ID under `rd-bot:last-project-id`. The selector must expose `allowAll`, loading, empty, and unavailable states without changing page layout width.

- [ ] **Step 6: Rerun focused tests**

Run:

```bash
cd frontend && node --test --experimental-strip-types test/adminVisualSystem.test.ts test/projectScope.test.ts
./mvnw -q -pl bootstrap -Dtest=AdminFrontendControllerTest test
```

Expected: all focused tests pass.

## Task 2: Build the Dashboard Aggregation Read Model

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/admin/dashboard/DashboardRuntimeSnapshotPort.java`
- Create: `engine/src/main/java/com/wish/rd/engine/admin/dashboard/model/RdDashboardOverview.java`
- Create: `engine/src/main/java/com/wish/rd/engine/admin/dashboard/RdDashboardQueryService.java`
- Create: `engine/src/test/java/com/wish/rd/engine/admin/dashboard/RdDashboardQueryServiceTest.java`
- Create: `engine/src/main/java/com/wish/rd/engine/agent/AgentStageProgressCalculator.java`
- Create: `engine/src/test/java/com/wish/rd/engine/agent/AgentStageProgressCalculatorTest.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskExecutionOverviewController.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/DashboardRuntimeSnapshotAdapter.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/dashboard/RdDashboardController.java`
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/dashboard/RdDashboardControllerTest.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/runtime/model/RdTaskQuery.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/runtime/RagStreamTaskRegistry.java`
- Modify: `rag/src/test/java/com/wish/rd/rag/runtime/RagStreamTaskRegistryTest.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskController.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskControllerTest.java`
- Modify: `frontend/src/services/rdTaskService.ts`
- Modify: `frontend/src/pages/admin/rdtask/RdTaskListPage.tsx`
- Modify: `frontend/vite.config.ts`

- [ ] **Step 1: Write failing status-category and project-filter tests**

The test fixture must contain both `BUG_FIX` and `REQUIREMENT` tasks in two projects, including `EXECUTING`, `WAITING_APPROVAL`, `FAILED_NEEDS_HUMAN`, `COMMITTED`, `MERGED`, `COMPLETED`, and `REJECTED`.

Assert the exact contract:

```java
RdDashboardOverview result = service.overview("project-1", 10);
assertEquals(2L, result.requirementCount());
assertEquals(3L, result.bugFixCount());
assertEquals(1L, result.inProgressCount());
assertEquals(2L, result.waitingHumanCount());
assertEquals(2L, result.completedCount());
assertEquals(2L, result.blockedCount());
assertEquals(new BigDecimal("0.5000"), result.successRate().value());
assertTrue(result.successRate().available());
```

Add a no-terminal-sample assertion where `successRate.available()` is false and the value is absent rather than zero.

- [ ] **Step 2: Run the engine test and verify it fails**

Run:

```bash
./mvnw -q -pl engine -Dtest=RdDashboardQueryServiceTest test
```

Expected: compilation fails because the dashboard service and read model do not exist.

- [ ] **Step 3: Implement the engine read model and service**

Use immutable records. The top-level record must contain:

```java
public record RdDashboardOverview(
        String projectId,
        String projectName,
        long requirementCount,
        long bugFixCount,
        long inProgressCount,
        long waitingHumanCount,
        long completedCount,
        long blockedCount,
        Map<String, Long> statusCounts,
        AvailabilityRatio successRate,
        RuntimeSnapshot runtime,
        List<TaskSummary> currentExecutions,
        List<TaskSummary> recentDeliveries,
        KnowledgeSupport knowledgeSupport,
        long generatedAtEpochMillis
) { }
```

Filter `registry.listTasks()` before counting. Sort current and recent rows by `updateTimeEpochMillis` descending and cap each list using the validated `limit` argument. Keep the status-category sets in one service-level constant so the controller and frontend cannot diverge.

Inject `RdProjectService`, `KnowledgeBaseStore`, and `KnowledgeDocumentStore` so the selected project name and its bound knowledge-base/document summary are resolved from domain stores. A missing optional knowledge source must set `knowledgeSupport.available=false`; it must not invent zero documents.

Implement `AgentStageProgressCalculator` before the runtime adapter. It receives the task type and all runs for one task, filters to the valid role order, selects the highest `attemptNo` per role, and returns current role, current status, completed steps, total steps, provider, and retry count. Replace the duplicated latest-attempt/progress selection in `RdTaskExecutionOverviewController` with this calculator and keep its response contract unchanged.

- [ ] **Step 4: Add project filtering to the canonical task list query**

First add a failing registry test with two otherwise identical tasks in different projects, then extend `RdTaskQuery` with `projectId` and filter it in `RagStreamTaskRegistry.queryTasks`. Extend `GET /admin/rd-tasks`, `RdTaskListQuery`, and `RdTaskListPage` so Dashboard drill-down URLs such as `/admin/rd-tasks?projectId=p1&status=FAILED_NEEDS_HUMAN` are honored after refresh.

Run:

```bash
./mvnw -q -pl rag -Dtest=RagStreamTaskRegistryTest test
./mvnw -q -pl bootstrap -Dtest=RdTaskControllerTest test
cd frontend && node --test --experimental-strip-types test/projectScope.test.ts
```

Expected: the focused task-query tests pass and the list request includes `projectId` only when a concrete project is selected.

- [ ] **Step 5: Implement the runtime snapshot port and bootstrap adapter**

The port accepts the filtered task IDs and project ID. The adapter returns:

- active alert count for the selected project/task set;
- running Docker execution count;
- estimated spend in CNY and `costAvailable`;
- per-task current role, stage status, progress, Provider, retry count, and elapsed execution state using `AgentStageProgressCalculator`;
- `available=false` for a missing optional runtime provider instead of zeroing an unknown source.

Reuse `BudgetCurrencyConverter`; never expose USD fields.

- [ ] **Step 6: Write and run a failing controller contract test**

```java
mockMvc.perform(get("/admin/dashboard/overview").param("projectId", "project-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projectId").value("project-1"))
        .andExpect(jsonPath("$.requirementCount").value(2))
        .andExpect(jsonPath("$.statusCounts.EXECUTING").value(1))
        .andExpect(jsonPath("$.runtime.estimatedSpendCny").isNumber());
```

Also assert 404 for an unknown project and 400 for a non-positive limit.

- [ ] **Step 7: Implement the controller and Vite proxy**

Expose `GET /admin/dashboard/overview`. Add `/admin/dashboard/overview` to the Vite proxy so development mode reaches port 18080 without intercepting `/admin/dashboard` HTML navigation.

- [ ] **Step 8: Run dashboard backend tests**

Run:

```bash
./mvnw -q -pl engine -Dtest=RdDashboardQueryServiceTest,AgentStageProgressCalculatorTest test
./mvnw -q -pl bootstrap -Dtest=RdDashboardControllerTest,RdTaskExecutionOverviewControllerTest test
```

Expected: all tests pass.

## Task 3: Replace the Knowledge-Centric Dashboard UI

**Files:**
- Create: `frontend/src/services/dashboardService.ts`
- Create: `frontend/src/pages/dashboard/dashboardPresentation.ts`
- Create: `frontend/test/dashboardPresentation.test.ts`
- Modify: `frontend/src/pages/DashboardPage.tsx`
- Modify: `frontend/src/styles.css`
- Modify: `frontend/test/adminVisualSystem.test.ts`

- [ ] **Step 1: Write failing presentation and source-policy tests**

Assert drill-down query generation and unavailable ratios:

```ts
assert.equal(taskListHref("p1", { status: "FAILED_NEEDS_HUMAN" }),
  "/admin/rd-tasks?projectId=p1&status=FAILED_NEEDS_HUMAN");
assert.equal(formatAvailabilityRatio({ available: false, value: null }), "--");
assert.equal(formatAvailabilityRatio({ available: true, value: 0.5 }), "50.0%");
```

Source policy must reject the old knowledge-first labels:

```ts
assert.doesNotMatch(dashboard, /资产构成|知识资产健康|知识库快照/);
assert.match(dashboard, /需求总数/);
assert.match(dashboard, /Bug 总数/);
assert.match(dashboard, /当前执行/);
assert.match(dashboard, /近期交付/);
```

- [ ] **Step 2: Run the tests and confirm failure**

Run:

```bash
cd frontend && node --test --experimental-strip-types test/dashboardPresentation.test.ts test/adminVisualSystem.test.ts
```

Expected: tests fail because the dashboard still fetches knowledge overview and renders knowledge-first cards.

- [ ] **Step 3: Implement the typed dashboard service**

Define the TypeScript DTO with the same names as the Java record. Fetch only `/admin/dashboard/overview`, passing `projectId` when the selected scope is not `all`.

- [ ] **Step 4: Rewrite DashboardPage**

Use `ProjectScopeSelector` in the header. Render six stable metric tiles, a status distribution, current execution table, recent delivery list, project health rail, and compact knowledge support. Every metric click must use `taskListHref` with the selected project scope.

Do not add decorative charts without data. Status bars must use backend counts and `aria-label` values. Loading, empty, partial unavailable, and retry states must retain stable dimensions.

- [ ] **Step 5: Add responsive styles**

Keep cards at 8px radius or less. At 1024px the health rail moves below the main area; at 390px metrics become two columns and tables use horizontal scroll inside their own container, never at page root.

- [ ] **Step 6: Run frontend tests and typecheck**

Run:

```bash
cd frontend && node --test --experimental-strip-types test/*.test.ts
cd frontend && npm run typecheck
```

Expected: tests and TypeScript pass.

## Task 4: Make Retrieval Rules Project-Aware and Persistent

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/rewrite/QueryTermMappingStore.java`
- Create: `rag/src/main/java/com/wish/rd/rag/rewrite/impl/InMemoryQueryTermMappingStore.java`
- Create: `rag/src/main/java/com/wish/rd/rag/rewrite/model/QueryTermMappingScope.java`
- Create: `rag/src/main/java/com/wish/rd/rag/rewrite/model/QueryRewritePreview.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/rewrite/model/ManagedQueryTermMapping.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/rewrite/model/QueryTermMappingCommand.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/rewrite/QueryTermMappingRegistry.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/rewrite/impl/RuleBasedQueryRewriteService.java`
- Modify: `rag/src/test/java/com/wish/rd/rag/rewrite/QueryTermMappingRegistryTest.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/QueryTermMappingRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/QueryTermMappingMapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresQueryTermMappingStore.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/QueryTermMappingConfiguration.java`
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresQueryTermMappingStoreTest.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/RuntimeComponentRegistrationPolicyTest.java`
- Modify: `bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql`

- [ ] **Step 1: Write failing scope, conflict, and preview tests**

Create global and project rules sharing the same source term. Assert deterministic selection:

```java
QueryRewritePreview preview = registry.preview("project-1", "支付下单金额错误");
assertEquals("支付 POST /p1/orders orders.amount 错误", preview.rewrittenText());
assertEquals(List.of("project-order", "global-amount"),
        preview.matches().stream().map(QueryRewriteMatch::mappingId).toList());
```

Add tests for disabled rules, another project's rule, stable tie-breaking, global-only fallback, and no match returning unchanged text.

- [ ] **Step 2: Run the rag tests and verify failure**

Run:

```bash
./mvnw -q -pl rag -Dtest=QueryTermMappingRegistryTest test
```

Expected: compilation fails because scope, project ID, store, and preview do not exist.

- [ ] **Step 3: Add the store port and domain model**

The store contract must be explicit:

```java
public interface QueryTermMappingStore {
    ManagedQueryTermMapping save(ManagedQueryTermMapping mapping);
    Optional<ManagedQueryTermMapping> findById(String id);
    List<ManagedQueryTermMapping> list();
    void delete(String id);
}
```

`QueryTermMappingCommand` must carry `projectId`, `scope`, source, target, priority, enabled, and remark. Reject a blank project ID for `PROJECT` and reject a nonblank project ID for `GLOBAL`.

- [ ] **Step 4: Refactor the registry and rewrite service**

Keep `inMemory()` and `withDefaults()` factories for existing tests. Add `list(projectId, scope, enabled, keyword)`, `rewriteService(projectId)`, and `preview(projectId, text)`. Existing no-argument `rewriteService()` must delegate to global-only behavior for compatibility.

- [ ] **Step 5: Add the idempotent PostgreSQL migration**

Create `rd_query_term_mappings` with:

```sql
id BIGINT PRIMARY KEY,
project_id BIGINT NULL REFERENCES rd_projects(project_id),
scope VARCHAR(16) NOT NULL CHECK (scope IN ('GLOBAL','PROJECT')),
source_term VARCHAR(256) NOT NULL,
target_term TEXT NOT NULL,
priority INTEGER NOT NULL,
enabled BOOLEAN NOT NULL,
remark TEXT NOT NULL DEFAULT '',
created_at TIMESTAMPTZ NOT NULL,
updated_at TIMESTAMPTZ NOT NULL,
CHECK ((scope='GLOBAL' AND project_id IS NULL) OR (scope='PROJECT' AND project_id IS NOT NULL))
```

Add indexes for `(project_id, enabled, priority DESC)` and `(scope, enabled, priority DESC)`. Seed the two existing defaults with fixed IDs using `ON CONFLICT DO NOTHING` so repeated migrations are stable.

- [ ] **Step 6: Implement and test the PostgreSQL adapter**

Use `@Param` on every multi-argument mapper method. The store test must verify global null project IDs, project ID round trip, ordering, update timestamps, and delete behavior.

Add `QueryTermMappingConfiguration`: when a PostgreSQL store bean exists, construct `QueryTermMappingRegistry` with it; otherwise create an in-memory store seeded with the two compatibility defaults. Remove direct component construction from the registry and update `RuntimeComponentRegistrationPolicyTest` so production wiring has one unambiguous registry bean.

- [ ] **Step 7: Run rag and bootstrap persistence tests**

Run:

```bash
./mvnw -q -pl rag -Dtest=QueryTermMappingRegistryTest test
./mvnw -q -pl bootstrap -Dtest=PostgresQueryTermMappingStoreTest,RdProjectPostgresSchemaPolicyTest test
```

Expected: all tests pass.

## Task 5: Apply Project Rules in Runtime and Expose Retrieval APIs

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/rag/RagBugFixEngine.java`
- Modify: `engine/src/test/java/com/wish/rd/engine/RagBugFixEngineTest.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/admin/rewrite/QueryTermMappingAdminEngine.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rewrite/QueryTermMappingController.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/QueryTermMappingControllerTest.java`
- Modify: `frontend/vite.config.ts`

- [ ] **Step 1: Write a failing runtime project-isolation test**

Create two tasks in different projects and two project rules for the same source term. Assert each Bug Fix prompt receives only its own project rewrite plus global rules. Add a task without project ID and assert it receives only global rules.

- [ ] **Step 2: Run the engine test and verify failure**

Run:

```bash
./mvnw -q -pl engine -Dtest=RagBugFixEngineTest test
```

Expected: project isolation assertion fails because the engine currently calls `rewriteService()` without a project ID.

- [ ] **Step 3: Pass task project scope into prompt construction**

Resolve the task once from `RagStreamTaskRegistry`, take its normalized `projectId`, and call `rewriteService(projectId)`. Do not infer project from ticket text or knowledge-base IDs.

- [ ] **Step 4: Write failing controller tests for filtering and preview**

```java
mockMvc.perform(get("/mappings").param("projectId", "project-1").param("enabled", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].scope").value("PROJECT"));

mockMvc.perform(post("/mappings/preview")
        .contentType(APPLICATION_JSON)
        .content("""{"projectId":"project-1","text":"下单失败"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.originalText").value("下单失败"))
        .andExpect(jsonPath("$.matches").isArray());
```

Also assert 400 for blank preview text and invalid project scope.

- [ ] **Step 5: Implement admin engine and controller methods**

Keep existing CRUD paths. Add optional query parameters and `POST /mappings/preview`. Return a structured error body with `message`; never expose stack traces.

- [ ] **Step 6: Run runtime and controller tests**

Run:

```bash
./mvnw -q -pl engine -Dtest=RagBugFixEngineTest test
./mvnw -q -pl bootstrap -Dtest=QueryTermMappingControllerTest test
```

Expected: all tests pass.

## Task 6: Build the Retrieval Rules Page

**Files:**
- Create: `frontend/src/services/retrievalRuleService.ts`
- Create: `frontend/src/pages/admin/retrieval/retrievalRulePresentation.ts`
- Create: `frontend/src/pages/admin/retrieval/RetrievalRulesPage.tsx`
- Create: `frontend/test/retrievalRulePresentation.test.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/pages/AdminPages.tsx`
- Modify: `frontend/src/styles.css`

- [ ] **Step 1: Write failing rule-scope presentation tests**

```ts
assert.equal(canCreateProjectRule("all"), false);
assert.equal(canCreateProjectRule("7479447343427883008"), true);
assert.equal(scopeLabel("GLOBAL"), "全局");
assert.equal(scopeLabel("PROJECT"), "当前项目");
```

Add tests that preview matches preserve backend order and empty matches produce “未应用任何规则”.

- [ ] **Step 2: Run the focused frontend test and verify failure**

Run:

```bash
cd frontend && node --test --experimental-strip-types test/retrievalRulePresentation.test.ts
```

Expected: module-not-found failure.

- [ ] **Step 3: Implement typed service and page**

Render project scope, four summary metrics, filters, table, editor dialog, delete confirmation, inline enabled switch, and preview panel. Keep preview input and output visible side-by-side on desktop and stacked on mobile. Disable project-rule creation in the all-project scope with an explicit explanation.

- [ ] **Step 4: Extract MappingPage from AdminPages and update lazy routing**

Route `/admin/mappings` to `RetrievalRulesPage`. Remove the old `MappingPage` implementation only after the replacement compiles.

- [ ] **Step 5: Verify long-dialog and accessibility behavior**

Ensure dialog header/footer remain visible at 390x844, scroll only the content body, and give edit/delete/preview icon buttons accessible names and tooltips.

- [ ] **Step 6: Run frontend tests and typecheck**

Run:

```bash
cd frontend && node --test --experimental-strip-types test/*.test.ts
cd frontend && npm run typecheck
```

Expected: all tests pass.

## Task 7: Build the Task-Rooted Execution Trace Read Model

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/agent/AgentStageRunStore.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/agent/impl/InMemoryAgentStageRunStore.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresAgentStageRunStore.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RdAgentStageRunMapper.java`
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresAgentStageRunStoreTest.java`
- Create: `engine/src/main/java/com/wish/rd/engine/admin/trace/model/ExecutionTraceQuery.java`
- Create: `engine/src/main/java/com/wish/rd/engine/admin/trace/model/ExecutionTracePage.java`
- Create: `engine/src/main/java/com/wish/rd/engine/admin/trace/ExecutionTraceQueryService.java`
- Create: `engine/src/test/java/com/wish/rd/engine/admin/trace/ExecutionTraceQueryServiceTest.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/trace/ExecutionTraceController.java`
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/trace/ExecutionTraceControllerTest.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/audit/RepairAuditQueryPort.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRepairAuditStore.java`
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresRepairAuditStoreTest.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/operation/RepairOperationController.java`

- [ ] **Step 1: Write failing latest-attempt and filtering tests**

Fixture requirements:

- a task with `REQUIREMENT_REVIEWER` attempts 1 failed and 2 succeeded;
- a currently running `SOLUTION_ARCHITECT` attempt;
- another task in a different project;
- a task with no stage run;
- provider and keyword filters.

Assertions:

```java
ExecutionTracePage page = service.query(new ExecutionTraceQuery(
        "project-1", "REQUIREMENT", null, "SOLUTION_ARCHITECT", "long-cat", null, 1, 20));
assertEquals(1L, page.total());
assertEquals(2, page.records().getFirst().retryCount());
assertEquals("SOLUTION_ARCHITECT", page.records().getFirst().currentRole());
assertEquals("RUNNING", page.records().getFirst().currentStageStatus());
assertEquals("long-cat", page.records().getFirst().providerName());
```

- [ ] **Step 2: Run the test and verify failure**

Run:

```bash
./mvnw -q -pl engine -Dtest=ExecutionTraceQueryServiceTest test
```

Expected: compilation fails because the trace query service does not exist.

- [ ] **Step 3: Add batch stage-run lookup**

Add:

```java
List<AgentStageRun> listByTasks(List<String> taskIds);
```

The default implementation may delegate to `listByTask` for small in-memory tests, but `PostgresAgentStageRunStore` must execute one `WHERE task_id IN (...)` query. An empty ID list returns an empty list without querying PostgreSQL.

- [ ] **Step 4: Implement the trace service**

Page tasks first, batch-load stage runs for that task page, choose the latest attempt per role using `attemptNo`, then compute current role, progress, provider, elapsed time, retry count, and blocked flag. Preserve tasks without stage runs so newly created work remains observable.

- [ ] **Step 5: Extend audit querying by task ID**

Add `eventsByTaskId(String taskId)` to the port and PostgreSQL/in-memory implementations. Update `/admin/operations/audit-events` to accept either `taskId` or `repairRecordId`, reject both together with 400, and never fetch all events for frontend filtering.

- [ ] **Step 6: Write and implement the controller contract**

Expose `/admin/execution-traces` with project, task type, status, role, provider, keyword, page, and pageSize parameters. Assert pagination metadata and field names with MockMvc.

- [ ] **Step 7: Run trace, store, and operation tests**

Run:

```bash
./mvnw -q -pl engine -Dtest=ExecutionTraceQueryServiceTest test
./mvnw -q -pl bootstrap -Dtest=ExecutionTraceControllerTest,RepairOperationControllerTest,PostgresAgentStageRunStoreTest,PostgresRepairAuditStoreTest test
```

Expected: all tests pass.

## Task 8: Replace Legacy RAG Trace Pages

**Files:**
- Create: `frontend/src/services/executionTraceService.ts`
- Create: `frontend/src/pages/admin/trace/executionTracePresentation.ts`
- Create: `frontend/src/pages/admin/trace/ExecutionTracePage.tsx`
- Create: `frontend/src/pages/admin/trace/ExecutionTraceDetailPage.tsx`
- Create: `frontend/test/executionTracePresentation.test.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/pages/AdminPages.tsx`
- Modify: `frontend/src/styles.css`
- Modify: `frontend/vite.config.ts`

- [ ] **Step 1: Write failing trace presentation tests**

Test query serialization, latest-attempt grouping, elapsed formatting, and blocked labels:

```ts
assert.equal(executionTraceHref({ projectId: "p1", provider: "long-cat", page: 2 }),
  "/admin/traces?projectId=p1&provider=long-cat&page=2");
assert.equal(formatTraceProgress(14, 28), "14 / 28");
assert.equal(traceBlockingLabel("FAILED_NEEDS_HUMAN"), "需要人工处理");
```

- [ ] **Step 2: Run the test and verify failure**

Run:

```bash
cd frontend && node --test --experimental-strip-types test/executionTracePresentation.test.ts
```

Expected: module-not-found failure.

- [ ] **Step 3: Implement the list page**

Use `ProjectScopeSelector`, filter controls, stable pagination, and an operational table containing task, project, type/status, current stage, progress, Provider, elapsed, retries, updated time, and detail action. The empty state must say “暂无交付执行记录” and must not reference `/test/rag/prompt-flow`.

- [ ] **Step 4: Implement the detail page using existing truths**

Fetch task detail, execution overview, task timeline, and task-filtered audit events. Render task summary, stage timeline grouped by role, each attempt, provider attempt history, artifact availability, redacted errors, running executions, budget, and recovery events. Provide links to `/admin/rd-tasks/{taskId}` and back to the preserved trace filters.

- [ ] **Step 5: Replace lazy imports and remove legacy TracePage implementations**

Keep `/admin/traces` and `/admin/traces/:traceId` paths, but treat the parameter as `taskId`. Remove frontend calls to `/rag/traces/runs`.

- [ ] **Step 6: Add the Vite proxies and run frontend verification**

Proxy `/admin/execution-traces` and `/admin/operations/audit-events`. Run:

```bash
cd frontend && node --test --experimental-strip-types test/*.test.ts
cd frontend && npm run typecheck
```

Expected: all tests and TypeScript pass.

## Task 9: Build, Real-Data Acceptance, and QA Evidence

**Files:**
- Create: `docs/qa/2026-07-10-project-delivery-console-acceptance-report.md`
- Regenerate: `bootstrap/src/main/resources/static/admin/*`
- Update tests only if real acceptance exposes a reproducible defect.

- [ ] **Step 1: Run all affected automated suites**

```bash
./mvnw -q -pl rag -Dtest=QueryTermMappingRegistryTest test
./mvnw -q -pl engine -Dtest=RdDashboardQueryServiceTest,AgentStageProgressCalculatorTest,ExecutionTraceQueryServiceTest,RagBugFixEngineTest test
./mvnw -q -pl bootstrap -Dtest=AdminFrontendControllerTest,RdDashboardControllerTest,RdTaskControllerTest,RdTaskExecutionOverviewControllerTest,QueryTermMappingControllerTest,ExecutionTraceControllerTest,RepairOperationControllerTest,PostgresQueryTermMappingStoreTest,PostgresAgentStageRunStoreTest,PostgresRepairAuditStoreTest,RdProjectPostgresSchemaPolicyTest test
cd frontend && node --test --experimental-strip-types test/*.test.ts
cd frontend && npm run typecheck
```

Expected: every suite passes with no skipped acceptance assertion counted as success.

- [ ] **Step 2: Validate the SQL migration twice**

Execute `p0_knowledge_productionization.sql` twice against the local PostgreSQL database. Then run:

```sql
SELECT scope, project_id, source_term, target_term, priority, enabled
FROM rd_query_term_mappings
ORDER BY scope, project_id NULLS FIRST, priority DESC, id;
```

Expected: fixed global seed rows appear once, project rows retain values, and no duplicate or second conversion occurs.

- [ ] **Step 3: Install changed modules before starting bootstrap**

```bash
./mvnw -q -pl rag,engine install -DskipTests
./mvnw -q -pl bootstrap spring-boot:run
```

Expected: backend listens on `127.0.0.1:18080` without stale-class errors. If a migrated `rag`/`engine` class raises `NoClassDefFoundError`, run `./mvnw -q install -DskipTests` and restart.

- [ ] **Step 4: Perform real HTTP and PostgreSQL cross-checks**

Verify:

```bash
curl -fsS 'http://127.0.0.1:18080/admin/dashboard/overview?projectId=7479447343427883008'
curl -fsS 'http://127.0.0.1:18080/mappings?projectId=7479447343427883008'
curl -fsS 'http://127.0.0.1:18080/admin/execution-traces?projectId=7479447343427883008&page=1&pageSize=20'
```

Cross-check dashboard counts with:

```sql
SELECT task_type, status, count(*)
FROM rd_tasks
WHERE project_id = 7479447343427883008 AND status <> 'DELETED'
GROUP BY task_type, status
ORDER BY task_type, status;
```

Expected: API task-type and status totals exactly match PostgreSQL. Record response status and selected fields in the QA report.

- [ ] **Step 5: Verify retrieval rule persistence without external side effects**

Create one temporary project rule through HTTP, preview it, verify the row in PostgreSQL, restart bootstrap, fetch it again, then delete the temporary rule. Use a clearly QA-prefixed source term and record only IDs and redacted content in the report.

- [ ] **Step 6: Build and sync the Spring-hosted bundle**

```bash
cd frontend && npm run build
./mvnw -q -pl bootstrap resources:resources
```

Verify 200 responses for `/admin`, `/admin/dashboard`, `/admin/mappings`, `/admin/traces`, the CSS entry, the JS entry, and all new lazy chunks. Confirm the bundle contains “检索规则” and “执行追踪” and does not contain “示例问题” or `/rag/traces/runs`.

- [ ] **Step 7: Run page-by-page browser acceptance**

Test these routes:

- `/admin/dashboard?projectId=7479447343427883008`
- `/admin/mappings?projectId=7479447343427883008`
- `/admin/traces?projectId=7479447343427883008`
- one real `/admin/traces/{taskId}`
- `/admin/rd-tasks?projectId=7479447343427883008`

Run at 1440x900, 1024x768, and 390x844. For every route assert:

- the page heading and real-data region are visible;
- document-level horizontal overflow is zero;
- labels and buttons do not overlap;
- mobile navigation is closed by default and closes after selection;
- long dialogs keep actions visible;
- drill-down links preserve `projectId` and status filters;
- console contains no warning, error, or uncaught exception.

- [ ] **Step 8: Write the QA report and final checks**

The report must include environment, exact commands, automated results, HTTP status and key fields, PostgreSQL cross-check rows, browser matrix, screenshots, intentionally skipped external side effects, and cleanup of the temporary retrieval rule.

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors. The final status may remain dirty because the repository already contains unrelated work; list only files touched by this implementation and do not revert other changes.

## Final Acceptance Gate

Implementation is complete only when all conditions hold:

1. Sample Questions is absent from navigation, frontend routes, frontend APIs, fallback tests, and the static bundle.
2. Dashboard values are project-filtered backend aggregates and match PostgreSQL.
3. Retrieval rules persist in PostgreSQL, respect project/global precedence, and return deterministic preview evidence.
4. Runtime Bug Fix rewriting uses the task project and never leaks another project's rule.
5. Execution trace list uses batched stage queries and details show latest plus historical attempts.
6. All changed pages pass desktop, tablet, and mobile browser acceptance with no console errors.
7. No Provider, PR, Feishu, queue replay, or task execution side effect was triggered during QA.
