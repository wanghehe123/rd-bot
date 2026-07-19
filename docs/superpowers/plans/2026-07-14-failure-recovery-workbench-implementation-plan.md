# Failure Recovery Workbench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Turn failed requirement-delivery output into an auditable diagnostic workbench where an operator can add evidence and resume exactly from the resolved failed role.

**Architecture:** Keep the existing retry-point resolver and four-role state machine authoritative. engine adds immutable diagnosis/read models and validates an evidence-bound retry command; bootstrap persists the checkpoint input and exposes thin HTTP adapters; frontend renders a focused recovery workbench rather than dumping role JSON. The requirement-delivery executor continues to reuse successful upstream attempts and inserts recovery evidence only into the new failed/downstream attempts.

**Tech Stack:** Java 21, Spring Boot 3.5, PostgreSQL/MyBatis-Plus, Jackson, React 18, TypeScript, Vite, Tailwind/shadcn, JUnit 5, MockMvc, Node test runner.

---

## File Map

| Area | Files | Responsibility |
| --- | --- | --- |
| Failure diagnosis | engine/src/main/java/com/wish/rd/engine/retry/model/TaskFailureDiagnostic.java; TaskFailureIssue.java; TaskFailureRecoverySnapshot.java; TaskRetryCommand.java; engine/src/main/java/com/wish/rd/engine/retry/TaskFailureDiagnosticParser.java; TaskFailureRecoveryService.java | Convert the latest failed state and bounded result artifact into stable operator-facing fields. |
| Retry command | engine/src/main/java/com/wish/rd/engine/retry/TaskRetryEngine.java; TaskRetryCheckpoint.java | Validate expected failed stage, note, and task-owned evidence before immutable checkpoint creation. |
| Retry persistence | bootstrap/src/main/resources/sql/postgres/p3_task_retry_ai_review.sql; TaskRetryCheckpointRow.java; TaskRetryCheckpointMapper.java; PostgresTaskRetryCheckpointStore.java | Persist operator_note and JSONB evidence_material_ids with idempotent DDL and CAS-safe updates. |
| HTTP and materials | bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/TaskRetryController.java; RdTaskController.java | Return structured recovery data and receive a backward-compatible retry body; tag recovery material by stage. |
| Orchestration | engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java | Validate selected evidence and render the explicit recovery section only into new failed/downstream attempts. |
| UI | frontend/src/components/admin/rdtask/TaskFailureRecoveryWorkbench.tsx; frontend/src/services/taskRetryService.ts; frontend/src/services/rdTaskService.ts; frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx | Show concise diagnosis, evidence controls, retry history, and a collapsed raw-result escape hatch. |
| Tests and acceptance | focused tests below; docs/qa/2026-07-14-failure-recovery-workbench-acceptance.md | Prove persistence, state boundaries, HTTP contract, prompt propagation, Vite routing, and browser usability. |

## Compatibility Rules

- Keep GET /retry-preview, GET /retry-history, and an empty-body POST /retry working.
- Only REJECTED, FAILED_RETRYABLE, and FAILED_NEEDS_HUMAN requirement tasks can create a recovery checkpoint.
- A retry creates a new attemptNo from retryFromRole onward. It never changes an old AgentStageRun, old artifact, or successful upstream stage.
- Selected evidence IDs must belong to the task, be distinct, and be persisted on the checkpoint. The checkpoint list is the audit truth.
- Existing /admin/rd-tasks Vite proxy already covers the new subpath. Add a proxy regression test rather than a redundant prefix.
- Do not commit in this implementation run unless the user explicitly authorizes it.

### Task 1: Add Pure Failure-Diagnosis Models and Parser

**Files:**
- Create: engine/src/main/java/com/wish/rd/engine/retry/model/TaskFailureIssue.java
- Create: engine/src/main/java/com/wish/rd/engine/retry/model/TaskFailureDiagnostic.java
- Create: engine/src/main/java/com/wish/rd/engine/retry/model/TaskFailureRecoverySnapshot.java
- Create: engine/src/main/java/com/wish/rd/engine/retry/TaskFailureDiagnosticParser.java
- Create: engine/src/main/java/com/wish/rd/engine/retry/TaskFailureRecoveryService.java
- Test: engine/src/test/java/com/wish/rd/engine/retry/TaskFailureDiagnosticParserTest.java
- Test: engine/src/test/java/com/wish/rd/engine/retry/TaskFailureRecoveryServiceTest.java

- [ ] **Step 1: Write parser tests for the observed NEED_INFO response and degraded legacy data.**

~~~java
@Test
void mapsNeedInfoMissingInformationIntoOperatorIssues() {
    TaskFailureDiagnostic diagnostic = parser.parse(
            TaskFailurePhase.AGENT_ROLE, AgentRole.REQUIREMENT_REVIEWER,
            "", "", """
            {"decision":"NEED_INFO","feasibility":"CAN_DO",
             "missingInformation":["确认顾客测试账号","锁定频控 HTTP 状态码"],
             "risks":["订单表迁移方案未收敛"],
             "acceptanceCoverage":{"missing":["商家订单列表端点"]}}
            """
    );

    assertTrue(diagnostic.requiresSupplement());
    assertEquals("需求信息不足", diagnostic.title());
    assertEquals(List.of("确认顾客测试账号", "锁定频控 HTTP 状态码"),
            diagnostic.issues().stream().map(TaskFailureIssue::detail).toList());
    assertEquals("商家订单列表端点", diagnostic.acceptanceGaps().getFirst().detail());
}

@Test
void malformedHistoricalJsonFallsBackWithoutThrowing() {
    TaskFailureDiagnostic diagnostic = parser.parse(
            TaskFailurePhase.AGENT_ROLE, AgentRole.CODING_AGENT,
            "AGENT_EXECUTOR_EXCEPTION", "container stopped", "{not-json");

    assertFalse(diagnostic.title().isBlank());
    assertFalse(diagnostic.summary().isBlank());
}
~~~

- [ ] **Step 2: Run the RED parser test.**

Run: ./mvnw -q -pl engine -Dtest=TaskFailureDiagnosticParserTest test
Expected: compilation failure because the diagnosis types and parser do not yet exist.

- [ ] **Step 3: Implement immutable, bounded diagnosis values.**

~~~java
public record TaskFailureIssue(
        String kind, String severity, String title, String detail, String sourceField
) {
    public TaskFailureIssue {
        kind = safe(kind); severity = safe(severity); title = safe(title);
        detail = safe(detail); sourceField = safe(sourceField);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}

public record TaskFailureDiagnostic(
        String category, String title, String summary, String suggestedAction,
        boolean requiresSupplement, List<TaskFailureIssue> issues,
        List<TaskFailureIssue> risks, List<TaskFailureIssue> acceptanceGaps
) {
    public TaskFailureDiagnostic {
        issues = List.copyOf(issues == null ? List.of() : issues);
        risks = List.copyOf(risks == null ? List.of() : risks);
        acceptanceGaps = List.copyOf(acceptanceGaps == null ? List.of() : acceptanceGaps);
    }
}

public record TaskFailureRecoverySnapshot(
        String taskId, RdTaskStatus sourceTaskStatus, TaskRetryPoint retryPoint,
        String failedStageStatus, int failedAttemptNo, String providerName,
        String errorCategory, String errorMessage, TaskFailureDiagnostic diagnostic,
        String rawResultArtifactId, String rawResultContentHash, String rawResultPreview,
        List<TaskRetryCheckpoint> history
) {
    public TaskFailureRecoverySnapshot {
        history = List.copyOf(history == null ? List.of() : history);
    }
}
~~~

TaskFailureDiagnosticParser must use Jackson readTree, recognize requirement-review decision, feasibility, missingInformation, risks, and acceptanceCoverage.missing, and map RAG, AI-review, policy, provider, and generic stage failures from structured phase/error fields. Limit every returned text field. Malformed JSON returns a generic diagnosis, not an exception.

- [ ] **Step 4: Implement the read-only recovery query service.**

~~~java
public TaskFailureRecoverySnapshot snapshot(String taskId) {
    RdRequirementTask task = taskPort.getRequirementTask(taskId);
    List<AgentStageRun> stages = stageRunStore.listByTask(taskId);
    TaskRetryPoint point = resolver.resolve(task, stages,
            retrievalRunStore.listByTask(taskId), aiReviewRunStore.listByTask(taskId));
    AgentStageRun failedStage = findStage(stages, point.failedStageRunId());
    AgentStageArtifact result = findResultArtifact(taskId, failedStage);
    TaskFailureDiagnostic diagnostic = parser.parse(point.failurePhase(), point.retryFromRole(),
            failedStage == null ? "" : failedStage.errorCategory(), point.reason(),
            result == null ? "" : result.contentPreview());
    return new TaskFailureRecoverySnapshot(task.taskId(), task.status(), point,
            failedStage == null ? "" : failedStage.status().name(),
            failedStage == null ? 0 : failedStage.attemptNo(),
            failedStage == null ? "" : failedStage.providerName(),
            failedStage == null ? "" : failedStage.errorCategory(),
            failedStage == null ? point.reason() : failedStage.errorMessage(), diagnostic,
            result == null ? "" : result.artifactId(), result == null ? "" : result.contentHash(),
            result == null ? "" : result.contentPreview(), checkpointStore.listByTask(taskId));
}
~~~

The snapshot exposes a bounded raw preview and artifact ID/hash for the existing read-only escape hatch. It must never start a retry or mutate status.

- [ ] **Step 5: Run the focused engine tests.**

Run: ./mvnw -q -pl engine -Dtest=TaskFailureDiagnosticParserTest,TaskFailureRecoveryServiceTest test
Expected: PASS; tests include the latest failed attempt, missing result artifact, RAG failure, and cross-task artifact exclusion.

### Task 2: Bind Operator Evidence to an Immutable Retry Checkpoint

**Files:**
- Create: engine/src/main/java/com/wish/rd/engine/retry/model/TaskRetryCommand.java
- Modify: engine/src/main/java/com/wish/rd/engine/retry/model/TaskRetryCheckpoint.java
- Modify: engine/src/main/java/com/wish/rd/engine/retry/TaskRetryEngine.java
- Modify: engine/src/main/java/com/wish/rd/engine/retry/impl/InMemoryTaskRetryCheckpointStore.java
- Test: engine/src/test/java/com/wish/rd/engine/retry/TaskRetryEngineTest.java

- [ ] **Step 1: Add failing tests for stage freshness, required supplements, ownership, and idempotency.**

~~~java
@Test
void needInfoRequiresNoteOrTaskOwnedEvidenceAndPersistsBoth() {
    TaskRetryCommand command = new TaskRetryCommand(
            "stage-review-1", "账号改为 user1；频控返回 429", List.of("material-clarification"));

    TaskRetryCheckpoint checkpoint = engine.retry("task-1", "USER", command);

    assertEquals("账号改为 user1；频控返回 429", checkpoint.operatorNote());
    assertEquals(List.of("material-clarification"), checkpoint.evidenceMaterialIds());
}

@Test
void rejectsStaleFailureStageAndEvidenceFromAnotherTask() {
    assertThrows(IllegalStateException.class,
            () -> engine.retry("task-1", "USER", new TaskRetryCommand("old-stage", "说明", List.of())));
    assertThrows(IllegalArgumentException.class,
            () -> engine.retry("task-1", "USER",
                    new TaskRetryCommand("stage-review-1", "说明", List.of("other-task-material"))));
}
~~~

- [ ] **Step 2: Run the RED retry test.**

Run: ./mvnw -q -pl engine -Dtest=TaskRetryEngineTest test
Expected: FAIL because the retry command overload and checkpoint evidence fields do not exist.

- [ ] **Step 3: Define a normalized retry command and checkpoint fields.**

~~~java
public record TaskRetryCommand(
        String expectedFailedStageRunId, String operatorNote, List<String> evidenceMaterialIds
) {
    public TaskRetryCommand {
        expectedFailedStageRunId = safe(expectedFailedStageRunId);
        operatorNote = bounded(operatorNote, 8_000);
        evidenceMaterialIds = distinctIds(evidenceMaterialIds);
    }

    public static TaskRetryCommand empty() {
        return new TaskRetryCommand("", "", List.of());
    }

    private static List<String> distinctIds(List<String> values) {
        return (values == null ? List.<String>of() : values).stream()
                .map(TaskRetryCommand::safe).filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String bounded(String value, int maxChars) {
        String normalized = safe(value);
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
~~~

Extend TaskRetryCheckpoint with String operatorNote and List<String> evidenceMaterialIds immediately before lifecycle fields. Its canonical constructor copies the list; created(...) receives TaskRetryCommand; withStatus(...) carries the fields unchanged.

- [ ] **Step 4: Make TaskRetryEngine validate and retain the command without changing old behavior.**

~~~java
public TaskRetryCheckpoint retry(String taskId, String trigger) {
    return retry(taskId, trigger, TaskRetryCommand.empty());
}

public TaskRetryCheckpoint retry(String taskId, String trigger, TaskRetryCommand command) {
    TaskRetryCheckpoint active = checkpointStore.findActiveByTask(taskId).orElse(null);
    if (active != null) return active;
    TaskFailureRecoverySnapshot snapshot = failureRecoveryService.snapshot(taskId);
    validateCommand(snapshot, command, materialStore.listByTask(taskId));
    TaskRetryCheckpoint proposed = TaskRetryCheckpoint.created(
            nextId(), snapshot.retryPoint(), nextAttempt(taskId), idempotencyKey(snapshot.retryPoint()),
            snapshot.sourceTaskStatus(), command, now());
    // Preserve durable create -> RECOVERING -> new attempts -> DISPATCHED -> dispatch sequence.
}
~~~

validateCommand compares a nonblank expected stage ID to snapshot.retryPoint().failedStageRunId(), checks every selected ID is distinct and belongs to the task, and requires a nonblank note or at least one evidence ID only when snapshot.diagnostic().requiresSupplement() is true. A mismatched current stage is IllegalStateException (HTTP 409); invalid IDs or missing supplement are IllegalArgumentException (HTTP 400).

Update both TaskRetryEngine constructors and the TaskRetryEngineTest factory to inject TaskMaterialStore and TaskFailureRecoveryService. The service itself depends on the existing task/stage/retrieval/review/checkpoint stores and TaskRetryPointResolver, so the dependency graph remains engine -> rag and has no bootstrap SDK dependency.

- [ ] **Step 5: Run retry and resume regression tests.**

Run: ./mvnw -q -pl engine -Dtest=TaskRetryEngineTest,RequirementDeliveryResumeFromCheckpointTest test
Expected: PASS; reviewer/architect attempt counts remain unchanged when retry starts at coding, duplicate active calls return the same checkpoint, and old no-body callers still work.

### Task 3: Persist Checkpoint Evidence with Idempotent PostgreSQL DDL

**Files:**
- Modify: bootstrap/src/main/resources/sql/postgres/p3_task_retry_ai_review.sql
- Modify: bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/TaskRetryCheckpointRow.java
- Modify: bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/TaskRetryCheckpointMapper.java
- Modify: bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresTaskRetryCheckpointStore.java
- Test: bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresTaskRetryCheckpointStoreTest.java

- [ ] **Step 1: Add mapper/store round-trip tests first.**

~~~java
@Test
void roundTripsOperatorNoteAndEvidenceIdsWithoutMutatingTerminalCheckpoint() {
    TaskRetryCheckpoint saved = store.createOrGet(checkpoint("说明", List.of("m-1", "m-2"))).checkpoint();
    TaskRetryCheckpoint reloaded = store.find(saved.checkpointId()).orElseThrow();

    assertEquals("说明", reloaded.operatorNote());
    assertEquals(List.of("m-1", "m-2"), reloaded.evidenceMaterialIds());
    assertThrows(IllegalStateException.class, () -> store.transition(
            saved.checkpointId(), TaskRetryCheckpointStatus.SUCCEEDED,
            TaskRetryCheckpointStatus.DISPATCHED, "", 3L));
}
~~~

- [ ] **Step 2: Run the RED persistence test.**

Run: ./mvnw -q -pl bootstrap -Dtest=PostgresTaskRetryCheckpointStoreTest test
Expected: FAIL until the row, mapper, and checkpoint constructor agree on the new fields.

- [ ] **Step 3: Extend new and existing PostgreSQL schemas safely.**

~~~sql
operator_note TEXT NOT NULL DEFAULT '',
evidence_material_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
~~~

Place the columns in the CREATE TABLE IF NOT EXISTS rd_task_retry_checkpoints definition and append:

~~~sql
ALTER TABLE rd_task_retry_checkpoints
    ADD COLUMN IF NOT EXISTS operator_note TEXT NOT NULL DEFAULT '';
ALTER TABLE rd_task_retry_checkpoints
    ADD COLUMN IF NOT EXISTS evidence_material_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
~~~

- [ ] **Step 4: Map the list through a JSON codec owned by the bootstrap adapter.**

~~~java
row.operatorNote = checkpoint.operatorNote();
row.evidenceMaterialIdsJson = OBJECT_MAPPER.writeValueAsString(checkpoint.evidenceMaterialIds());

return new TaskRetryCheckpoint(
        PostgresPersistenceSupport.idString(row.id),
        PostgresPersistenceSupport.idString(row.taskId),
        enumValue(TaskFailurePhase.class, row.failurePhase, TaskFailurePhase.AGENT_ROLE),
        enumValue(AgentRole.class, row.retryFromRole, null),
        PostgresPersistenceSupport.idString(row.failedStageRunId),
        PostgresPersistenceSupport.idString(row.failedRetrievalRunId),
        PostgresPersistenceSupport.idString(row.failedAiReviewRunId),
        row.attemptNo == null ? 1 : row.attemptNo, safe(row.idempotencyKey),
        enumValue(RdTaskStatus.class, row.sourceTaskStatus, RdTaskStatus.FAILED_RETRYABLE),
        row.sourceTaskVersion == null ? 0L : row.sourceTaskVersion,
        safe(row.operatorNote), readStringList(row.evidenceMaterialIdsJson),
        enumValue(TaskRetryCheckpointStatus.class, row.status, TaskRetryCheckpointStatus.CREATED),
        safe(row.reason), safe(row.errorMessage),
        PostgresPersistenceSupport.toEpochMillis(row.createdAt),
        PostgresPersistenceSupport.toEpochMillis(row.updatedAt));
~~~

The mapper insert explicitly includes operator_note and evidence_material_ids. readStringList accepts only a JSON array of nonblank strings and returns List.of() for historical null or invalid values. Do not construct JSON with string concatenation.

- [ ] **Step 5: Run focused persistence validation.**

Run: ./mvnw -q -pl bootstrap -Dtest=PostgresTaskRetryCheckpointStoreTest test
Expected: PASS; then execute the DDL twice during final acceptance.

### Task 4: Expose Recovery Snapshot and Backward-Compatible Commands

**Files:**
- Modify: bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/TaskRetryController.java
- Modify: bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskController.java
- Test: bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/rdtask/TaskRetryControllerTest.java
- Test: bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskControllerTest.java

- [ ] **Step 1: Add MockMvc tests for the new read model and retry errors.**

~~~java
mvc.perform(get("/admin/rd-tasks/task-1/failure-recovery"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.diagnostic.title").value("需求信息不足"))
        .andExpect(jsonPath("$.diagnostic.issues[0].detail").value("确认顾客测试账号"));

mvc.perform(post("/admin/rd-tasks/task-1/retry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedFailedStageRunId\":\"stage-1\",\"operatorNote\":\"补充说明\",\"evidenceMaterialIds\":[\"m-1\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operatorNote").value("补充说明"))
        .andExpect(jsonPath("$.evidenceMaterialIds[0]").value("m-1"));
~~~

- [ ] **Step 2: Run the RED controller tests.**

Run: ./mvnw -q -pl bootstrap -Dtest=TaskRetryControllerTest,RdTaskControllerTest test
Expected: FAIL because the endpoint and request/response fields do not exist.

- [ ] **Step 3: Add thin request/response adapters.**

~~~java
@GetMapping("/admin/rd-tasks/{taskId}/failure-recovery")
public TaskFailureRecoveryView failureRecovery(@PathVariable String taskId) {
    return TaskFailureRecoveryView.from(failureRecoveryService.snapshot(taskId));
}

@PostMapping("/admin/rd-tasks/{taskId}/retry")
public TaskRetryCheckpointView retry(
        @PathVariable String taskId,
        @RequestBody(required = false) TaskRetryRequest request
) {
    return TaskRetryCheckpointView.from(retryEngine.retry(taskId, "USER",
            request == null ? TaskRetryCommand.empty() : request.toCommand()));
}
~~~

TaskRetryRequest contains expectedFailedStageRunId, operatorNote, and evidenceMaterialIds. Preserve NoSuchElementException -> 404, IllegalArgumentException -> 400, and IllegalStateException -> 409. Expand checkpoint history responses with immutable note/evidence fields.

- [ ] **Step 4: Tag recovery uploads without changing the material trust boundary.**

Add optional recoveryStageRunId to RequirementMaterialInput and the multipart endpoint. Construct metadataJson with Jackson so it merges the existing filename/size metadata with recoveryStageRunId only when nonblank. Validate that the referenced stage belongs to the task before saving. Do not offer a Feishu URL as a recovery input.

- [ ] **Step 5: Run controller regressions.**

Run: ./mvnw -q -pl bootstrap -Dtest=TaskRetryControllerTest,RdTaskControllerTest test
Expected: PASS; includes blank-body compatibility, wrong stage 409, cross-task evidence 400, required supplement 400, material marker, and existing MIME/size cases.

### Task 5: Render Recovery Evidence into New Role Prompts Only

**Files:**
- Modify: engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java
- Test: engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryResumeFromCheckpointTest.java
- Test: engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineTest.java

- [ ] **Step 1: Add a regression test that inspects archived prompt artifacts.**

~~~java
assertThat(promptFor("stage-review-2"), containsString("# 本次失败恢复补充"));
assertThat(promptFor("stage-review-2"), containsString("账号改为 user1"));
assertThat(promptFor("stage-review-2"), containsString("clarification.md"));
assertThat(promptFor("stage-architecture-2"), containsString("# 本次失败恢复补充"));
assertThat(promptFor("stage-review-1"), not(containsString("# 本次失败恢复补充")));
~~~

The test also asserts that every successful upstream attempt remains SUCCEEDED and only the failed role/downstream roles receive a new attempt.

- [ ] **Step 2: Run the RED orchestration test.**

Run: ./mvnw -q -pl engine -Dtest=RequirementDeliveryResumeFromCheckpointTest,RequirementDeliveryEngineTest test
Expected: FAIL because recovery note/evidence is absent from prompt artifacts.

- [ ] **Step 3: Build one bounded recovery section from the active checkpoint.**

~~~java
private String recoveryPromptSection(
        TaskRetryCheckpoint checkpoint, AgentRole role, List<TaskMaterial> materials
) {
    if (checkpoint == null || !includesRoleFrom(checkpoint.retryFromRole(), role)) return "";
    List<TaskMaterial> selected = requireCheckpointMaterials(checkpoint, materials);
    return """
            # 本次失败恢复补充
            - checkpointId: %s
            - operatorNote: %s

            %s
            """.formatted(checkpoint.checkpointId(), checkpoint.operatorNote(), materialPrompt(selected)).strip();
}
~~~

Thread the active checkpoint through executeAgentStages(...), recursive QA remediation calls, and buildAgentPrompt(...). requireCheckpointMaterials rejects missing, duplicate, or cross-task IDs before provider dispatch. Keep existing material/RAG flow intact; this is an explicit supplement, not a replacement context.

- [ ] **Step 4: Run orchestration and retry suite.**

Run: ./mvnw -q -pl engine -Dtest=RequirementDeliveryResumeFromCheckpointTest,RequirementDeliveryEngineTest,TaskRetryEngineTest test
Expected: PASS; selected recovery evidence is both in the normal material/RAG path and explicitly visible in the new prompt.

### Task 6: Build the Failure Recovery Workbench UI

**Files:**
- Create: frontend/src/components/admin/rdtask/TaskFailureRecoveryWorkbench.tsx
- Modify: frontend/src/services/taskRetryService.ts
- Modify: frontend/src/services/rdTaskService.ts
- Modify: frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx
- Modify: frontend/test/taskRetryAiReview.test.ts
- Create: frontend/test/taskFailureRecoveryWorkbench.test.ts
- Modify: frontend/test/viteProxy.test.ts

- [ ] **Step 1: Write source-level UI contract tests before rendering the component.**

~~~ts
test("recovery workbench uses structured diagnosis and keeps raw JSON collapsed", () => {
  assert.match(workbench, /失败诊断与恢复/);
  assert.match(workbench, /diagnostic\.issues/);
  assert.match(workbench, /<details/);
  assert.doesNotMatch(workbench, /<pre[^>]*>\s*\{stage\.resultPreview\}/);
});

test("retry command carries checkpoint-bound note and evidence", () => {
  assert.match(retryService, /expectedFailedStageRunId/);
  assert.match(retryService, /operatorNote/);
  assert.match(retryService, /evidenceMaterialIds/);
});
~~~

- [ ] **Step 2: Run the RED frontend tests.**

Run: node --test frontend/test/taskFailureRecoveryWorkbench.test.ts frontend/test/taskRetryAiReview.test.ts frontend/test/viteProxy.test.ts
Expected: FAIL because the component and types do not exist.

- [ ] **Step 3: Extend services with precise contracts.**

~~~ts
export type TaskRetryCommand = {
  expectedFailedStageRunId: string;
  operatorNote: string;
  evidenceMaterialIds: string[];
};

export const getTaskFailureRecovery = (taskId: string) =>
  api.get<TaskFailureRecoverySnapshot, TaskFailureRecoverySnapshot>(
    "/admin/rd-tasks/" + taskId + "/failure-recovery"
  );

export const retryTaskFromFailure = (taskId: string, command: TaskRetryCommand) =>
  api.post<TaskRetryCheckpoint, TaskRetryCheckpoint>("/admin/rd-tasks/" + taskId + "/retry", command);
~~~

Extend addTextTaskMaterial and uploadTaskMaterial options with optional recoveryStageRunId; preserve existing callers when absent.

- [ ] **Step 4: Implement a focused full-width workbench component.**

~~~tsx
<section aria-labelledby="failure-recovery-heading" className="border-y border-slate-200 py-6">
  <div className="grid gap-6 xl:grid-cols-[minmax(0,1.05fr)_minmax(360px,0.95fr)]">
    <FailureDiagnosis diagnostic={snapshot.diagnostic} retryPoint={snapshot.retryPoint} />
    <RecoveryEvidenceForm
      note={operatorNote}
      materials={materials}
      selectedIds={selectedMaterialIds}
      onAddText={addTextEvidence}
      onUpload={uploadEvidence}
      onRetry={submitRecovery}
    />
  </div>
  <RecoveryHistory checkpoints={snapshot.history} />
  <details><summary>原始产物</summary><pre>{snapshot.rawResultPreview}</pre></details>
</section>
~~~

Use existing Textarea, Input, Checkbox, Button, Badge, Alert, and Lucide icons. New manual text/upload evidence is auto-selected after saving. Disable retry only while saving/dispatching, and when requiresSupplement is true with neither a trimmed note nor selected material. Preserve form values after API failure. Do not add an unrelated edit-task flow or nest cards.

- [ ] **Step 5: Replace the top-right retry dialog rather than duplicate the action.**

Remove retryPreview and the AlertDialog confirmation path from RdTaskDetailPage. Render the workbench after execution overview only for retryable failure states. Replace the stage-row default JSON pre with a compact status/error/result summary and an action that scrolls to the workbench; raw JSON remains only in the workbench collapsed details region. After a successful checkpoint response, reload task, execution overview, materials, role prompts, RAG retrieval runs, and retry history before clearing the pending state.

- [ ] **Step 6: Verify Vite behavior and build the static bundle.**

Run:

~~~bash
node --test frontend/test/taskFailureRecoveryWorkbench.test.ts frontend/test/taskRetryAiReview.test.ts frontend/test/viteProxy.test.ts
npm --prefix frontend run typecheck
npm --prefix frontend run build
~~~

Expected: all tests pass, TypeScript has no error, and the Vite build refreshes bootstrap/src/main/resources/static/admin without a missing /admin/rd-tasks proxy route.

### Task 7: Module Regression, Static Checks, and Working-Tree Review

**Files:**
- Modify: only files introduced by Tasks 1-6 when tests expose a defect.
- Test: all focused tests from Tasks 1-6.

- [ ] **Step 1: Install the changed engine artifact before bootstrap tests.**

Run: ./mvnw -q -pl engine install -DskipTests
Expected: PASS; bootstrap sees current engine classes instead of a stale local Maven jar.

- [ ] **Step 2: Run targeted backend suite.**

~~~bash
./mvnw -q -pl engine -Dtest=TaskFailureDiagnosticParserTest,TaskFailureRecoveryServiceTest,TaskRetryEngineTest,RequirementDeliveryResumeFromCheckpointTest,RequirementDeliveryEngineTest test
./mvnw -q -pl bootstrap -Dtest=TaskRetryControllerTest,RdTaskControllerTest,PostgresTaskRetryCheckpointStoreTest,RdTaskExecutionOverviewControllerTest test
~~~

Expected: PASS. Any unrelated existing dirty-worktree failure is recorded separately and not reverted.

- [ ] **Step 3: Check intended scope and whitespace.**

~~~bash
git diff --check -- engine bootstrap frontend
git status --short
~~~

Expected: no whitespace error in touched files. Do not stage or commit because the user did not request a commit.

### Task 8: Execute the Real Acceptance Procedure

**Files:**
- Modify: docs/qa/2026-07-14-failure-recovery-workbench-acceptance.md with actual command results, IDs, screenshots, and exclusions.
- Create evidence under: qa-runs/failure-recovery-workbench/<timestamp>/

- [ ] **Step 1: Apply checkpoint DDL twice and prove schema availability.**

~~~bash
docker exec postgres psql -v ON_ERROR_STOP=1 -U postgres -d ragent -f /workspace/bootstrap/src/main/resources/sql/postgres/p3_task_retry_ai_review.sql
docker exec postgres psql -v ON_ERROR_STOP=1 -U postgres -d ragent -f /workspace/bootstrap/src/main/resources/sql/postgres/p3_task_retry_ai_review.sql
docker exec postgres psql -U postgres -d ragent -Atc "select column_name from information_schema.columns where table_name='rd_task_retry_checkpoints' and column_name in ('operator_note','evidence_material_ids') order by column_name;"
~~~

Expected: both executions exit 0 and print both column names. Use the actual mounted SQL path if the local container uses another mount.

- [ ] **Step 2: Run required atomic state smoke test.**

Run: ./mvnw -q -pl bootstrap -Drd.integration.task-state-atomic.enabled=true -Dtest=PostgresRdTaskStateAtomicRealSmokeTest test
Expected: PASS against configured PostgreSQL.

- [ ] **Step 3: Run isolated real HTTP recovery fixture.**

Start bootstrap only after ./mvnw -q install -DskipTests, use a non-conflicting local port, create a deterministic failed requirement fixture with one NEED_INFO result artifact, then capture:

~~~bash
curl -fsS "http://127.0.0.1:<port>/admin/rd-tasks/<taskId>/failure-recovery"
curl -fsS -X POST "http://127.0.0.1:<port>/admin/rd-tasks/<taskId>/materials/text" \
  -H 'Content-Type: application/json' \
  --data '{"materialType":"REQUIREMENT_DOC","title":"澄清","content":"顾客账号改为 user1；频控返回 429。","recoveryStageRunId":"<failedStageRunId>"}'
curl -fsS -X POST "http://127.0.0.1:<port>/admin/rd-tasks/<taskId>/retry" \
  -H 'Content-Type: application/json' \
  --data '{"expectedFailedStageRunId":"<failedStageRunId>","operatorNote":"使用 user1，频控 429","evidenceMaterialIds":["<materialId>"]}'
curl -fsS "http://127.0.0.1:<port>/admin/rd-tasks/<taskId>/role-prompts"
~~~

Expected: snapshot lists human-readable issues; retry returns checkpoint with exact note/material ID; new role prompt includes recovery section; a no-body retry remains valid for a retryable provider failure.

- [ ] **Step 4: Verify PostgreSQL attempt boundaries and checkpoint audit fields.**

~~~bash
docker exec postgres psql -U postgres -d ragent -Atc "select role,status,attempt_no from rd_agent_stage_runs where task_id=<taskId> order by role,attempt_no;"
docker exec postgres psql -U postgres -d ragent -Atc "select id,status,operator_note,evidence_material_ids from rd_task_retry_checkpoints where task_id=<taskId> order by attempt_no;"
~~~

Expected: successful upstream roles have no new attempt; failed/downstream roles have one higher attempt; checkpoint values match the HTTP request.

- [ ] **Step 5: Perform desktop and narrow-browser verification.**

Use the real admin route for the fixture task. Capture desktop and narrow screenshots showing issue list without default raw JSON, evidence text/file controls, selected evidence, retry history, disabled required-supplement state, active retry state, and collapsed raw-artifact details. Store screenshots and redacted request/response captures in qa-runs/failure-recovery-workbench/<timestamp>/.

- [ ] **Step 6: Stop services started for acceptance.**

Use the owning terminal session's Ctrl-C, verify the chosen bootstrap/Vite ports have no listener, and record closure evidence.
