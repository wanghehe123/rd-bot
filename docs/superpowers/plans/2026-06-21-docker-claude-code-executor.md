# Docker Claude Code Executor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the P2 repair execution path that runs Claude Code in Docker, validates structured repair output, creates a GitHub PR through a replaceable code-platform port, and records auditable execution artifacts.

**Architecture:** Keep RAG and orchestration boundaries intact. `engine` continues to orchestrate task flow through `BugFixExecutor`; `exec` owns repair execution contracts, result validation, Docker execution models, alert policy, and code-platform ports; `bootstrap` owns external adapters for Docker process execution, GitHub, Spring configuration, and persistence wiring. Claude Code runs as a non-interactive process inside a Docker container and communicates with Java through files plus captured stdout/stderr, not through a Java SDK.

**Tech Stack:** Java 21, Spring Boot 3.5, Maven, JUnit 5, PostgreSQL JSONB fields already defined in `repair_records`, Docker CLI or Docker Engine API behind a Java port, Claude Code CLI print mode, GitHub App for production, PAT only for local smoke fallback.

---

## Current Constraints

- `AGENTS.md` requires P2 execution to run in Docker, Claude Code to default to yolo mode because the container is the sandbox, timeout and budget alerts to avoid default hard-kill, and GitHub integration to be replaceable.
- External details that are not user-provided must not be guessed: Feishu ticket field names, Feishu status enums, Docker image name, Claude Code command flags, and GitHub credentials stay configurable.
- Current code already has `engine/src/main/java/com/wish/rd/engine/bugfix/BugFixExecutor.java`. Keep it as the engine-facing bridge for this phase to avoid forcing `engine` to depend on `exec`.
- Current `exec` repair model has only `CREATED`, `RAG_READY`, `EXECUTING`, `VALIDATING`, `COMPLETED`, and `FAILED`. Add validation-specific result statuses in execution result objects first; only widen `RepairRecordStatus` if a later UI or query requires it.
- Current SQL already has `executor_json`, `docker_json`, `github_json`, `test_json`, `risk_json`, `error_message`, and `repair_record_artifacts`. Prefer writing detailed logs and patches to artifacts, not to the main row.

## Overall Acceptance Standards

1. `./mvnw -pl exec test` passes and proves execution contracts, result validation, alert policy, and mock code-platform behavior.
2. `./mvnw -pl bootstrap -am -Dtest=DockerClaudeCodeExecutorTest,DockerExecutorConfigurationTest,GitHubCodePlatformAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test` passes without requiring real Docker, real GitHub, Feishu, or Claude credentials.
3. A local dry-run path can execute a fake Docker runner and produce all required artifacts: `result.json`, `patch.diff`, `test.log`, `claude-events.jsonl`, and `docker-meta.json`.
4. Invalid `result.json` or missing required artifacts transitions the repair execution result to validation failure and does not call the code-platform PR creation port.
5. Timeout and budget thresholds emit alert records but do not stop or kill the Docker container by default.
6. No Docker, GitHub, Feishu, PostgreSQL, or Claude SDK details are called directly from `rag`; production external calls live in `bootstrap` adapters behind `exec` ports.
7. A successful mock end-to-end flow moves the RD task from `CREATED` through `SEARCHING`, `EXECUTING`, and `COMMITTED`, and stores a PR URL in the existing task snapshot.

## File Map

### Exec Module

- Create `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairJobCommand.java`
  - Immutable command built from RAG output, ticket metadata, repository metadata, and execution policy.
- Create `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairExecutionResult.java`
  - Canonical result returned by repair executors.
- Create `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairExecutionStatus.java`
  - Execution-level statuses: `SUCCESS`, `FAILED`, `NEED_INFO`, `UNSAFE`, `FAILED_VALIDATION`.
- Create `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairExecutorPort.java`
  - Main execution port consumed by bootstrap bridge.
- Create `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairArtifact.java`
  - Artifact descriptor for result, patch, logs, prompt snapshot, Docker metadata, and Claude stream.
- Create `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairArtifactType.java`
  - Enum for artifact classification.
- Create `exec/src/main/java/com/wish/rd/exec/repair/result/StructuredRepairResult.java`
  - Parsed `result.json` record.
- Create `exec/src/main/java/com/wish/rd/exec/repair/result/StructuredResultValidator.java`
  - JSON validation and business validation.
- Create `exec/src/main/java/com/wish/rd/exec/repair/result/StructuredResultValidation.java`
  - Validation report with errors and parsed value.
- Create `exec/src/main/java/com/wish/rd/exec/repair/docker/DockerClaudeCodeExecutor.java`
  - Orchestrates workspace input files, container runner, output collection, validation, and alerts.
- Create `exec/src/main/java/com/wish/rd/exec/repair/docker/ContainerRunnerPort.java`
  - Port for starting/watching Docker containers.
- Create `exec/src/main/java/com/wish/rd/exec/repair/docker/ContainerRunRequest.java`
  - Container image, command, env, mounts, working directory, and policy.
- Create `exec/src/main/java/com/wish/rd/exec/repair/docker/ContainerRunResult.java`
  - Exit code, duration, stdout/stderr artifact references, and metadata.
- Create `exec/src/main/java/com/wish/rd/exec/repair/docker/RepairWorkspace.java`
  - Input/output directory descriptor.
- Create `exec/src/main/java/com/wish/rd/exec/repair/docker/RepairWorkspaceFactory.java`
  - Creates per-task workspace under a configurable root.
- Create `exec/src/main/java/com/wish/rd/exec/repair/alert/RepairAlert.java`
  - Alert record.
- Create `exec/src/main/java/com/wish/rd/exec/repair/alert/RepairAlertType.java`
  - `TIMEOUT_WARNING`, `BUDGET_WARNING`, `MISSING_ARTIFACT`, `VALIDATION_FAILED`.
- Create `exec/src/main/java/com/wish/rd/exec/repair/alert/RepairAlertSinkPort.java`
  - Port for persisting or notifying alerts.
- Create `exec/src/main/java/com/wish/rd/exec/repair/alert/RepairExecutionWatchdog.java`
  - Emits timeout and budget warnings without killing execution.
- Create `exec/src/main/java/com/wish/rd/exec/repair/code/CodePlatformPort.java`
  - Replaceable code-platform abstraction.
- Create `exec/src/main/java/com/wish/rd/exec/repair/code/CreatePullRequestCommand.java`
  - Branch, commit, title, body, and artifact metadata for PR creation.
- Create `exec/src/main/java/com/wish/rd/exec/repair/code/PullRequestResult.java`
  - PR URL, branch names, commit SHA, and provider metadata.
- Create `exec/src/test/java/com/wish/rd/exec/repair/**`
  - Focused tests for contracts, validation, Docker orchestration with fake runner, alert policy, and code-platform mock.

### Bootstrap Module

- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/DockerExecutorProperties.java`
  - Spring properties with safe defaults and configurable image/command/yolo flag/workspace root.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/ProcessContainerRunner.java`
  - Docker CLI adapter using `ProcessBuilder`.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/EngineBugFixExecutorAdapter.java`
  - Spring bean implementing `engine` `BugFixExecutor` and delegating to `exec` `RepairExecutorPort`.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/InMemoryRepairAlertSink.java`
  - Local alert sink for tests and zero-config startup.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/github/GitHubCodePlatformProperties.java`
  - Config holder for GitHub mode, app credentials, PAT fallback, repository allowlist.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/github/MockGitHubCodePlatformAdapter.java`
  - Default mock adapter unless real GitHub is enabled.
- Create `bootstrap/src/main/java/com/wish/rd/bootstrap/github/GitHubCodePlatformAdapter.java`
  - Real adapter gated by property; may use REST over `java.net.http.HttpClient` first.
- Create `bootstrap/src/test/java/com/wish/rd/bootstrap/DockerClaudeCodeExecutorTest.java`
  - Integration-style test with fake runner, no Docker dependency.
- Create `bootstrap/src/test/java/com/wish/rd/bootstrap/DockerExecutorConfigurationTest.java`
  - Spring bean and property tests.
- Create `bootstrap/src/test/java/com/wish/rd/bootstrap/GitHubCodePlatformAdapterTest.java`
  - Mock and request construction tests.

### Docker Assets

- Create `bootstrap/src/main/resources/executor/claude/Dockerfile`
  - Reference image recipe for local build.
- Create `bootstrap/src/main/resources/executor/claude/rd-claude-entrypoint.sh`
  - Container entrypoint contract for input/output files.
- Create `bootstrap/src/main/resources/executor/claude/result.schema.json`
  - JSON Schema for Claude Code structured output.

### Documentation

- Create `docs/execution/docker-claude-code.md`
  - Operator-facing local smoke guide, configuration keys, artifact protocol, and safety notes.

---

## Execution Protocol

Each repair task gets a workspace:

```text
<workspace-root>/<taskId>/
  input/
    context.json
    prompt.md
    result.schema.json
  repo/
    target repository checkout or mounted clone
  output/
    result.json
    patch.diff
    test.log
    claude-events.jsonl
    docker-meta.json
```

Required `result.json` shape:

```json
{
  "status": "SUCCESS",
  "summary": "修复摘要",
  "changedFiles": ["src/main/java/example/OrderService.java"],
  "testCommands": ["./mvnw -pl service test"],
  "testStatus": "PASSED",
  "riskLevel": "LOW",
  "prBody": "PR 描述正文",
  "needHumanAction": false
}
```

Required validation rules:

- `status` must be one of `SUCCESS`, `FAILED`, `NEED_INFO`, `UNSAFE`.
- `summary` must not be blank.
- `changedFiles` must be present. It may be empty only when `status` is `NEED_INFO` or `FAILED`.
- `testCommands` must be present. It may be empty only when `testStatus` is `SKIPPED`.
- `testStatus` must be one of `PASSED`, `FAILED`, `SKIPPED`.
- `riskLevel` must be one of `LOW`, `MEDIUM`, `HIGH`.
- `prBody` must not be blank when `status` is `SUCCESS`.
- `needHumanAction` must be true when `status` is `NEED_INFO`, `UNSAFE`, or `testStatus` is `FAILED`.
- `patch.diff`, `test.log`, and `claude-events.jsonl` must exist for `SUCCESS`.

---

### Task 1: Exec Execution Contracts

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairJobCommand.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairExecutionResult.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairExecutionStatus.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairExecutorPort.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairArtifact.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/execution/RepairArtifactType.java`
- Test: `exec/src/test/java/com/wish/rd/exec/repair/execution/RepairExecutionContractTest.java`

- [ ] **Step 1: Write failing contract tests**

Run: `./mvnw -pl exec -Dtest=RepairExecutionContractTest test`

Expected: FAIL because execution contract classes do not exist.

- [ ] **Step 2: Implement immutable records and enums**

Required signatures:

```java
public record RepairJobCommand(
        String repairRecordId,
        String taskId,
        String ticketId,
        String ticketTitle,
        String prompt,
        String repositoryUrl,
        String repoOwner,
        String repoName,
        String baseBranch,
        String workBranch,
        Map<String, String> contextJson,
        Map<String, String> policyJson
) { }
```

```java
public enum RepairExecutionStatus {
    SUCCESS,
    FAILED,
    NEED_INFO,
    UNSAFE,
    FAILED_VALIDATION
}
```

```java
public interface RepairExecutorPort {
    RepairExecutionResult execute(RepairJobCommand command);
}
```

- [ ] **Step 3: Normalize nulls in compact constructors**

All strings become `""`; maps become `Map.of()`; artifacts become `List.of()`. Required IDs (`taskId`, `repairRecordId`) throw `IllegalArgumentException` when blank.

- [ ] **Step 4: Verify focused tests**

Run: `./mvnw -pl exec -Dtest=RepairExecutionContractTest test`

Expected: PASS.

**Acceptance Criteria:**
- `RepairJobCommand` rejects blank `taskId` and `repairRecordId`.
- `RepairExecutionResult` carries status, summary, PR URL, artifacts, raw result JSON, Docker metadata JSON, GitHub metadata JSON, test metadata JSON, risk metadata JSON, and error message.
- No class in this task imports `bootstrap` or `engine`.
- Public records and interfaces have JavaDoc.

---

### Task 2: Structured Result Validation

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/result/StructuredRepairResult.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/result/StructuredResultValidator.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/result/StructuredResultValidation.java`
- Test: `exec/src/test/java/com/wish/rd/exec/repair/result/StructuredResultValidatorTest.java`

- [ ] **Step 1: Write failing validator tests**

Test cases:
- valid success result passes.
- missing `summary` fails.
- success with blank `prBody` fails.
- invalid `status` fails.
- `FAILED` with failed tests does not require patch artifact.
- `NEED_INFO` requires `needHumanAction=true`.

Run: `./mvnw -pl exec -Dtest=StructuredResultValidatorTest test`

Expected: FAIL because validator classes do not exist.

- [ ] **Step 2: Implement parser using Jackson if available from module dependencies**

If `exec` lacks Jackson after dependency inspection, add `jackson-databind` through Spring Boot dependency management in `exec/pom.xml`. Do not hand-parse JSON with string operations.

- [ ] **Step 3: Implement business validation**

Return a validation object instead of throwing for expected malformed agent output:

```java
public record StructuredResultValidation(
        boolean valid,
        StructuredRepairResult result,
        List<String> errors
) { }
```

- [ ] **Step 4: Verify focused tests**

Run: `./mvnw -pl exec -Dtest=StructuredResultValidatorTest test`

Expected: PASS.

**Acceptance Criteria:**
- Invalid JSON returns `valid=false` and contains a parse error message.
- Missing or invalid business fields return `valid=false` with all detected errors, not just the first error.
- Validation has no dependency on Docker, GitHub, Spring, or filesystem.
- Test names clearly express every validation rule listed in the execution protocol.

---

### Task 3: Workspace And Artifact Protocol

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/docker/RepairWorkspace.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/docker/RepairWorkspaceFactory.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/docker/RepairWorkspaceFiles.java`
- Test: `exec/src/test/java/com/wish/rd/exec/repair/docker/RepairWorkspaceFactoryTest.java`

- [ ] **Step 1: Write failing workspace tests**

Test cases:
- workspace path includes sanitized `taskId`.
- `input`, `repo`, and `output` directories are created.
- `prompt.md`, `context.json`, and `result.schema.json` are written with UTF-8.
- invalid `taskId` containing path traversal is rejected.

Run: `./mvnw -pl exec -Dtest=RepairWorkspaceFactoryTest test`

Expected: FAIL because workspace classes do not exist.

- [ ] **Step 2: Implement workspace records**

Use `java.nio.file.Path`, `Files.createDirectories`, and `StandardCharsets.UTF_8`.

- [ ] **Step 3: Implement safe task directory naming**

Allow only letters, numbers, hyphen, underscore, and dot. Reject path separators and blank values.

- [ ] **Step 4: Verify focused tests**

Run: `./mvnw -pl exec -Dtest=RepairWorkspaceFactoryTest test`

Expected: PASS.

**Acceptance Criteria:**
- The workspace factory never writes outside the configured workspace root.
- `context.json` includes `taskId`, `ticketId`, `repoOwner`, `repoName`, `baseBranch`, and `workBranch`.
- The generated output paths exactly match the protocol names: `result.json`, `patch.diff`, `test.log`, `claude-events.jsonl`, `docker-meta.json`.
- Tests use a temporary directory and leave no repository files behind.

---

### Task 4: Container Runner Port And Fake Runner

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/docker/ContainerRunnerPort.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/docker/ContainerRunRequest.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/docker/ContainerRunResult.java`
- Create: `exec/src/test/java/com/wish/rd/exec/repair/docker/FakeContainerRunner.java`
- Test: `exec/src/test/java/com/wish/rd/exec/repair/docker/ContainerRunnerContractTest.java`

- [ ] **Step 1: Write failing runner contract tests**

Run: `./mvnw -pl exec -Dtest=ContainerRunnerContractTest test`

Expected: FAIL because runner port classes do not exist.

- [ ] **Step 2: Implement request and result records**

Required request fields:
- `containerName`
- `image`
- `command`
- `env`
- `mounts`
- `workingDirectory`
- `networkMode`
- `removeAfterExit`
- `allowPrivileged`

- [ ] **Step 3: Implement fake runner**

Fake runner writes deterministic output files into the workspace output directory:
- `result.json` with `SUCCESS`
- `patch.diff` with a small diff body
- `test.log` with a passing test line
- `claude-events.jsonl` with one JSON event
- `docker-meta.json` with image and command

- [ ] **Step 4: Verify focused tests**

Run: `./mvnw -pl exec -Dtest=ContainerRunnerContractTest test`

Expected: PASS.

**Acceptance Criteria:**
- The port is synchronous for P2: `run(request)` returns after container process exits.
- Fake runner can simulate exit code `0`, non-zero exit, and missing artifact scenarios.
- No test in this task invokes real Docker.
- The request can represent yolo mode through command flags without hard-coding the exact Claude Code binary path.

---

### Task 5: Docker Claude Code Executor

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/docker/DockerClaudeCodeExecutor.java`
- Modify: `exec/pom.xml` only if Jackson is required by prior tasks.
- Test: `exec/src/test/java/com/wish/rd/exec/repair/docker/DockerClaudeCodeExecutorTest.java`

- [ ] **Step 1: Write failing executor tests**

Test cases:
- success output returns `RepairExecutionStatus.SUCCESS`.
- missing `result.json` returns `FAILED_VALIDATION`.
- invalid `result.json` returns `FAILED_VALIDATION`.
- non-zero container exit returns `FAILED` unless a valid `NEED_INFO` result exists.
- validation failure does not contain a PR URL.

Run: `./mvnw -pl exec -Dtest=DockerClaudeCodeExecutorTest test`

Expected: FAIL because executor does not exist.

- [ ] **Step 2: Implement executor orchestration**

Sequence:
1. create workspace.
2. write `prompt.md`, `context.json`, `result.schema.json`.
3. build `ContainerRunRequest`.
4. call `ContainerRunnerPort`.
5. collect artifacts from `output`.
6. validate `result.json`.
7. return `RepairExecutionResult`.

- [ ] **Step 3: Preserve Docker and Claude metadata**

The result must include:
- image name.
- container name.
- command list.
- exit code.
- duration millis.
- output artifact paths.

- [ ] **Step 4: Verify focused tests**

Run: `./mvnw -pl exec -Dtest=DockerClaudeCodeExecutorTest test`

Expected: PASS.

**Acceptance Criteria:**
- Executor never invokes GitHub directly.
- Executor never updates `RagStreamTaskRegistry` directly.
- Executor returns `FAILED_VALIDATION` when validation fails.
- Executor includes all produced artifacts in `RepairExecutionResult.artifacts()`.
- Executor can be tested with fake runner only.

---

### Task 6: Timeout And Budget Alerts Without Default Kill

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/alert/RepairAlert.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/alert/RepairAlertType.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/alert/RepairAlertSinkPort.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/alert/RepairExecutionWatchdog.java`
- Test: `exec/src/test/java/com/wish/rd/exec/repair/alert/RepairExecutionWatchdogTest.java`

- [ ] **Step 1: Write failing alert policy tests**

Test cases:
- elapsed time above threshold emits `TIMEOUT_WARNING`.
- estimated spend above threshold emits `BUDGET_WARNING`.
- repeated checks do not emit duplicate alerts for the same task and alert type.
- warning returns `shouldStop=false`.

Run: `./mvnw -pl exec -Dtest=RepairExecutionWatchdogTest test`

Expected: FAIL because alert classes do not exist.

- [ ] **Step 2: Implement alert records and sink port**

Alert fields:
- `repairRecordId`
- `taskId`
- `type`
- `message`
- `metadata`
- `createdAtEpochMillis`

- [ ] **Step 3: Implement watchdog**

The watchdog evaluates elapsed millis and estimated budget. It records warnings and returns an object that explicitly says execution should continue.

- [ ] **Step 4: Verify focused tests**

Run: `./mvnw -pl exec -Dtest=RepairExecutionWatchdogTest test`

Expected: PASS.

**Acceptance Criteria:**
- No watchdog method calls Docker stop, process destroy, or container kill.
- Warning thresholds are configurable through a value object, not constants hidden in the executor.
- Duplicate alert suppression is covered by tests.
- Alert sink is a port, so Feishu, database, or logs can be added later without changing watchdog logic.

---

### Task 7: Code Platform Port And Mock GitHub Path

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/code/CodePlatformPort.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/code/CreatePullRequestCommand.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/code/PullRequestResult.java`
- Test: `exec/src/test/java/com/wish/rd/exec/repair/code/CodePlatformPortContractTest.java`

- [ ] **Step 1: Write failing code-platform tests**

Test cases:
- command rejects blank repo owner, repo name, base branch, and work branch.
- pull request result normalizes optional metadata.
- mock platform records command and returns deterministic PR URL.

Run: `./mvnw -pl exec -Dtest=CodePlatformPortContractTest test`

Expected: FAIL because code-platform classes do not exist.

- [ ] **Step 2: Implement port and records**

Keep the port provider-neutral:

```java
public interface CodePlatformPort {
    PullRequestResult createPullRequest(CreatePullRequestCommand command);
}
```

- [ ] **Step 3: Verify focused tests**

Run: `./mvnw -pl exec -Dtest=CodePlatformPortContractTest test`

Expected: PASS.

**Acceptance Criteria:**
- Port does not expose GitHub-specific token, installation ID, or REST path fields.
- Command includes artifact references and `prBody`.
- Mock implementation can prove "validation failure does not create PR" in a later orchestration test.
- No Feishu or Docker concepts leak into the code-platform port.

---

### Task 8: Engine Bridge And PR Creation Orchestration

**Files:**
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/EngineBugFixExecutorAdapter.java`
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/EngineBugFixExecutorAdapterTest.java`
- Modify only if necessary: `engine/src/main/java/com/wish/rd/engine/bugfix/BugFixExecutionResult.java`

- [ ] **Step 1: Write failing bridge tests**

Test cases:
- success execution calls code-platform port and returns PR URL.
- validation failure does not call code-platform port.
- unsafe result does not call code-platform port.
- adapter maps `RepairExecutionResult` to existing `BugFixExecutionResult`.

Run: `./mvnw -pl bootstrap -am -Dtest=EngineBugFixExecutorAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because bridge does not exist.

- [ ] **Step 2: Implement Spring adapter**

The adapter implements `com.wish.rd.engine.bugfix.BugFixExecutor`, builds a `RepairJobCommand`, calls `RepairExecutorPort`, and calls `CodePlatformPort` only when execution status is `SUCCESS`.

- [ ] **Step 3: Preserve current engine route**

Do not change `RdBotFixEngine.runAfterAcquire(...)` status flow in this task. The adapter is injected as the `BugFixExecutor` bean.

- [ ] **Step 4: Verify focused tests**

Run: `./mvnw -pl bootstrap -am -Dtest=EngineBugFixExecutorAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

**Acceptance Criteria:**
- `engine` module still has no dependency on `exec`.
- `bootstrap` owns the bridge because it depends on both `engine` and `exec`.
- Successful result includes the PR URL returned by `CodePlatformPort`.
- Failed validation result returns `BugFixExecutionResult.resultJson()` with validation errors and blank PR URL.

---

### Task 9: Process-Based Docker Adapter

**Files:**
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/DockerExecutorProperties.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/ProcessContainerRunner.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/ProcessContainerRunnerTest.java`

- [ ] **Step 1: Write failing process runner tests**

Use a fake command launcher abstraction or a test-only command that does not require Docker. Test command construction separately from process execution.

Run: `./mvnw -pl bootstrap -am -Dtest=ProcessContainerRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because process adapter does not exist.

- [ ] **Step 2: Implement configurable properties**

Required default keys:

```properties
rd.executor.docker.enabled=false
rd.executor.docker.image=rd-bot/claude-code:local
rd.executor.docker.workspace-root=/tmp/rd-bot/repair-workspaces
rd.executor.docker.command=claude
rd.executor.docker.yolo-flag=--dangerously-skip-permissions
rd.executor.docker.output-format=stream-json
rd.executor.docker.network-mode=bridge
rd.executor.docker.remove-after-exit=true
rd.executor.docker.timeout-alert-millis=1800000
rd.executor.docker.budget-alert-usd=5.00
```

- [ ] **Step 3: Implement Docker CLI command builder**

Build argv without shell string concatenation:

```text
docker run --rm --name <containerName> --network <networkMode>
  -v <workspace>:/work
  -w /work/repo
  -e ANTHROPIC_API_KEY
  <image>
  claude -p --dangerously-skip-permissions --output-format stream-json --verbose <prompt>
```

Credentials must come from environment or secret manager; tests must not print or assert raw token values.

- [ ] **Step 4: Verify focused tests**

Run: `./mvnw -pl bootstrap -am -Dtest=ProcessContainerRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

**Acceptance Criteria:**
- Command builder uses `List<String>` argv, not `sh -c`.
- Tests prove image, workspace mount, yolo flag, output format, and network mode are configurable.
- Raw secret values are never logged.
- Real Docker is not required for unit tests.

---

### Task 10: Docker Image Reference Assets

**Files:**
- Create: `bootstrap/src/main/resources/executor/claude/Dockerfile`
- Create: `bootstrap/src/main/resources/executor/claude/rd-claude-entrypoint.sh`
- Create: `bootstrap/src/main/resources/executor/claude/result.schema.json`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/DockerAssetPolicyTest.java`

- [ ] **Step 1: Write failing asset policy test**

Test cases:
- Dockerfile exists.
- entrypoint exists.
- result schema exists and contains all required fields.
- Dockerfile creates or uses a non-root user.
- Dockerfile installs Claude Code with a pinned configurable version.

Run: `./mvnw -pl bootstrap -am -Dtest=DockerAssetPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because assets do not exist.

- [ ] **Step 2: Add Dockerfile**

Use a Node LTS base, install `git`, `openssh-client`, `ca-certificates`, and `jq`, install Claude Code by npm package version, and run as non-root user.

- [ ] **Step 3: Add entrypoint**

The entrypoint must:
- require `/work/input/prompt.md`.
- require `/work/input/result.schema.json`.
- create `/work/output`.
- run Claude Code in print mode.
- tee stream output to `/work/output/claude-events.jsonl`.
- write Docker metadata to `/work/output/docker-meta.json`.

- [ ] **Step 4: Verify focused tests**

Run: `./mvnw -pl bootstrap -am -Dtest=DockerAssetPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

**Acceptance Criteria:**
- The image recipe does not copy host SSH keys or cloud credentials.
- The entrypoint never writes outside `/work/output`.
- The JSON schema matches the execution protocol fields.
- Asset tests are pure file-content tests and do not build the Docker image.

---

### Task 11: Mock And Real GitHub Adapters

**Files:**
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/github/GitHubCodePlatformProperties.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/github/MockGitHubCodePlatformAdapter.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/github/GitHubCodePlatformAdapter.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/GitHubCodePlatformAdapterTest.java`

- [ ] **Step 1: Write failing GitHub adapter tests**

Test cases:
- mock adapter is selected by default.
- repository allowlist rejects non-allowed repo.
- PR request body contains title, body, head branch, and base branch.
- PAT fallback can be configured for local smoke.
- GitHub App mode requires app ID, installation ID, and private key reference.

Run: `./mvnw -pl bootstrap -am -Dtest=GitHubCodePlatformAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because adapters do not exist.

- [ ] **Step 2: Implement properties and mock adapter**

Default must be mock to preserve zero-config startup.

- [ ] **Step 3: Implement real adapter request construction**

Use `java.net.http.HttpClient` or a small internal client wrapper. Keep actual network calls behind an injectable sender so tests assert requests without calling GitHub.

- [ ] **Step 4: Verify focused tests**

Run: `./mvnw -pl bootstrap -am -Dtest=GitHubCodePlatformAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

**Acceptance Criteria:**
- Production choice is GitHub App; PAT is explicitly named local smoke fallback.
- Repo allowlist is enforced before any outbound HTTP call.
- Tests do not require GitHub credentials or network.
- Adapter does not know Feishu ticket fields.

---

### Task 12: Repair Record Persistence Updates

**Files:**
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/RepairRecordRepository.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/InMemoryRepairRecordRepository.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/PostgresRepairRecordRepository.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RepairRecordMapper.java`
- Test: `exec/src/test/java/com/wish/rd/exec/repair/InMemoryRepairRecordRepositoryTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresPersistenceCrudIntegrationTest.java`

- [ ] **Step 1: Write failing persistence tests**

Test cases:
- update executor JSON independently.
- update Docker JSON independently.
- update GitHub JSON independently.
- update test JSON independently.
- update risk JSON independently.
- update error message.

Run: `./mvnw -pl exec,bootstrap -am -Dtest=InMemoryRepairRecordRepositoryTest,PostgresPersistenceCrudIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because repository does not expose these updates.

- [ ] **Step 2: Add focused update methods**

Add methods such as:
- `updateExecutorJson(String repairRecordId, String executorJson)`
- `updateDockerJson(String repairRecordId, String dockerJson)`
- `updateGithubJson(String repairRecordId, String githubJson)`
- `updateTestJson(String repairRecordId, String testJson)`
- `updateRiskJson(String repairRecordId, String riskJson)`
- `updateErrorMessage(String repairRecordId, String errorMessage)`

- [ ] **Step 3: Implement in-memory and PostgreSQL repositories**

Use existing JSONB columns. Do not add new SQL columns in this task.

- [ ] **Step 4: Verify focused tests**

Run: `./mvnw -pl exec,bootstrap -am -Dtest=InMemoryRepairRecordRepositoryTest,PostgresPersistenceCrudIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS when PostgreSQL-compatible test setup is available. If PostgreSQL is unavailable, the exec module test must still pass and the bootstrap integration test limitation must be reported.

**Acceptance Criteria:**
- Main row stores summaries and JSON metadata only.
- Large logs, patches, prompt snapshots, and event streams remain artifact rows.
- Existing repair record create/find artifact behavior remains compatible.
- No Flyway or Liquibase is introduced.

---

### Task 13: Feishu Ticket Write-Back Port Skeleton

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/ticket/TicketUpdateCommand.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/ticket/TicketWriteBackPort.java`
- Create: `exec/src/main/java/com/wish/rd/exec/repair/ticket/TicketWriteBackResult.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/ticket/MockTicketWriteBackAdapter.java`
- Test: `exec/src/test/java/com/wish/rd/exec/repair/ticket/TicketWriteBackPortContractTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/MockTicketWriteBackAdapterTest.java`

- [ ] **Step 1: Write failing ticket write-back tests**

Test success and failure command creation without Feishu-specific field names.

Run: `./mvnw -pl exec,bootstrap -am -Dtest=TicketWriteBackPortContractTest,MockTicketWriteBackAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because ticket write-back port does not exist.

- [ ] **Step 2: Implement generic command**

Command fields:
- `ticketId`
- `taskId`
- `repairRecordId`
- `statusSummary`
- `pullRequestUrl`
- `failureReason`
- `needHumanAction`
- `metadata`

- [ ] **Step 3: Implement mock adapter**

Mock adapter records commands for tests. It does not call Feishu.

- [ ] **Step 4: Verify focused tests**

Run: `./mvnw -pl exec,bootstrap -am -Dtest=TicketWriteBackPortContractTest,MockTicketWriteBackAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

**Acceptance Criteria:**
- No guessed Feishu field name or status enum appears in code.
- Success command includes PR URL and blank failure reason.
- Failure command includes failure reason and `needHumanAction=true`.
- Real Feishu adapter remains out of scope until exact API fields are provided.

---

### Task 14: End-To-End Mock P2 Flow

**Files:**
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/P2RepairExecutionFlowTest.java`
- Modify: Spring configuration only where required to wire mock runner, mock GitHub, mock ticket write-back, and in-memory repair repository.

- [ ] **Step 1: Write failing mock E2E test**

Test flow:
1. create mock ticket.
2. run `RdBotFixEngine.runBugFix(...)`.
3. fake Docker runner writes success artifacts.
4. adapter validates result.
5. mock GitHub returns PR URL.
6. task status becomes `COMMITTED`.
7. execution result JSON contains `SUCCESS`.

Run: `./mvnw -pl bootstrap -am -Dtest=P2RepairExecutionFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL until prior tasks are wired.

- [ ] **Step 2: Wire mock beans for test profile**

Use Spring component wiring or explicit test configuration. Do not require real Docker, GitHub, Claude, RocketMQ, Feishu, or PostgreSQL.

- [ ] **Step 3: Add validation-failure E2E case**

The fake runner writes invalid `result.json`. Assert task becomes `REJECTED` or returns blank PR URL according to current `RdBotFixEngine` behavior. If changing engine behavior is required, make it a separate explicit task and update tests first.

- [ ] **Step 4: Verify focused tests**

Run: `./mvnw -pl bootstrap -am -Dtest=P2RepairExecutionFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

**Acceptance Criteria:**
- Success path creates exactly one PR through mock code-platform port.
- Validation failure creates zero PRs.
- Success path records all five required artifact names.
- No test depends on external services.
- Existing `RdBotFixEngineTest` continues to pass.

---

### Task 15: Operator Documentation And Smoke Commands

**Files:**
- Create: `docs/execution/docker-claude-code.md`
- Modify: `README.md` only if a public route or required setup changed.

- [ ] **Step 1: Write operator documentation**

Include:
- architecture boundary.
- local fake-runner smoke command.
- optional real Docker smoke prerequisites.
- configuration keys.
- artifact protocol.
- alert behavior.
- GitHub auth modes.
- Feishu write-back limitation.

- [ ] **Step 2: Verify documentation links and commands**

Run:

```bash
rg -n "rd.executor.docker|result.json|GitHub App|dangerously-skip-permissions|FAILED_VALIDATION" docs/execution/docker-claude-code.md
```

Expected: All required sections are found.

- [ ] **Step 3: Run focused verification suite**

Run:

```bash
./mvnw -pl exec test
./mvnw -pl bootstrap -am -Dtest=DockerClaudeCodeExecutorTest,DockerExecutorConfigurationTest,GitHubCodePlatformAdapterTest,P2RepairExecutionFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS without external services.

- [ ] **Step 4: Run broader verification when available**

Run: `./mvnw test`

Expected: PASS. If PostgreSQL, Docker, GitHub, Claude, or Feishu credentials are unavailable, report exactly which broader checks were skipped and keep the focused mock verification evidence.

**Acceptance Criteria:**
- Documentation states that yolo mode is allowed only because execution is inside Docker.
- Documentation states that timeout and budget alerts do not kill by default.
- Documentation states that real Feishu write-back requires user-provided API fields before implementation.
- Documentation includes the exact artifact names and validation rules from this plan.

---

## Final Definition Of Done

- All task-specific acceptance criteria above are satisfied.
- Focused tests pass:

```bash
./mvnw -pl exec test
./mvnw -pl bootstrap -am -Dtest=DockerClaudeCodeExecutorTest,DockerExecutorConfigurationTest,GitHubCodePlatformAdapterTest,P2RepairExecutionFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- Full verification is attempted:

```bash
./mvnw test
```

- If full verification cannot run because local services or credentials are absent, the final report names the missing service and includes the focused tests that did run.
- No user-created untracked files are reverted or deleted.
- No git stage, commit, push, or PR is performed unless the user explicitly asks.

## Implementation Order

1. Tasks 1-2 establish execution contracts and result validation.
2. Tasks 3-5 establish Docker Claude Code execution with a fake runner.
3. Task 6 adds non-killing alert policy.
4. Tasks 7-8 connect successful execution to PR creation through ports.
5. Tasks 9-11 add real adapter shells and Docker assets behind config.
6. Task 12 persists execution metadata into existing JSONB columns.
7. Task 13 adds Feishu write-back boundary without guessing Feishu fields.
8. Task 14 proves the mock end-to-end P2 flow.
9. Task 15 documents operation and final verification.
