# QA Playwright Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `test-driven-development` and execute this plan inline task-by-task. The user explicitly prohibited sub-agent delegation and repository commits for this change, so do not dispatch sub-agents and do not commit.

**Goal:** Turn `QA_AGENT` from a self-reported build checker into a strict, evidence-backed current-feature and regression validator that can use Playwright CLI for web work and automatically return real product failures to `CODING_AGENT` once.

**Architecture:** Keep four-role orchestration intact. Add a dedicated QA container runtime containing pinned Playwright CLI and Chromium, while the `skill` module governs and audits the read-only Claude Skill mounted into that container. The executor derives authoritative artifact metadata from real files, the engine enforces a strict QA contract and one bounded QA-to-coding remediation loop, and bootstrap/frontend persist and expose the evidence without making local `file://` paths the user-facing contract.

**Tech Stack:** Java 21, Maven, Spring Boot, PostgreSQL/MyBatis, Docker, Claude Code, `@playwright/cli`, Chromium, Node.js, React/TypeScript/Vite, RustFS/S3-compatible object storage.

---

## Fixed Scope And Invariants

- Only the four-role `REQUIREMENT` pipeline changes in this delivery. `BUG_FIX` remains behaviorally unchanged.
- Existing exact failed-stage manual retry remains intact. Only a product/regression failure reported by `QA_AGENT` may create fresh `CODING_AGENT` and `QA_AGENT` attempts.
- The automatic remediation budget is one coding rework, meaning at most two QA attempts in one delivery submission.
- A malformed, skipped, or evidence-free QA result can never become `PASSED` through compatibility normalization.
- Required browser validation is Chromium headless at desktop `1440x900` and mobile `390x844` for the first release.
- CI gating, commercial QA SaaS, native mobile, load testing, security scanning, and pixel-baseline visual comparison are out of scope.
- All existing uncommitted work is preserved. No reset, rollback, commit, push, or PR is part of this plan.

## File Responsibility Map

- `exec/.../AgentRoleResultValidator.java`: QA JSON shape and cross-field invariants.
- `exec/.../QaEvidenceBundleValidator.java`: real artifact-reference, hash, current/regression, browser-evidence, and repository-cleanliness validation.
- `exec/.../DockerClaudeCodeExecutor.java`: QA image selection, effective QA profile, recursive evidence collection, authoritative metadata, and strict success gate.
- `exec/.../RepairArtifactType.java`: typed screenshot, trace, network, console, HTTP, video, and evidence-manifest artifacts.
- `exec/.../ContainerRunRequest.java`: Docker `--init` and shared-memory options.
- `bootstrap/.../ProcessContainerRunner.java`: safe argv construction for the new Docker options.
- `skill/src/main/resources/skills/qa-playwright-cli/SKILL.md`: browser/API/current/regression workflow used by Claude Code.
- `bootstrap/.../skill/impl/QaPlaywrightSkillProvisioner.java`: install, role-policy, checksum, and mount-context orchestration; audit fields live in Docker/stage metadata.
- `bootstrap/.../ObjectStorageQaEvidencePublisher.java`: private object-storage publication before stage persistence.
- `rag/.../qa/*`: project/task QA profile domain, service, and store port.
- `bootstrap/.../QaValidationProfileController.java` and persistence adapters: PostgreSQL/API adapters for QA profiles.
- `engine/.../RequirementDeliveryEngine.java`: bounded remediation loop, new attempts, feedback context, and alerts.
- `bootstrap/.../QaEvidenceController.java`: task-scoped artifact listing and authenticated content streaming.
- `frontend/.../RdTaskDetailPage.tsx`: per-attempt evidence gallery and validation-scope display.
- `bootstrap/src/main/resources/sql/postgres/p5_qa_evidence.sql`: idempotent QA profile and private-evidence index tables.

### Task 1: Freeze The Strict QA Contract

**Files:**
- Modify: `exec/src/test/java/com/wish/rd/exec/repair/result/AgentRoleResultValidatorTest.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/result/AgentRoleResultValidator.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/RepairWorkspaceFactory.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryReviewer.java`

- [x] Add tests proving QA requires `failureCategory`, `retryRecommendation`, `browserValidation`, `evidenceManifestArtifactId`, `scope`, `exitCode`, `durationMillis`, and `evidenceArtifactIds`.
- [x] Implement the strict validator and JSON schema. Preserve `logArtifactId` as a logical artifact reference, but reject it unless it names a real evidence file later in executor validation.
- [x] Update the engine prompt/reviewer and focused tests.
- [x] Run focused exec and engine tests until green.

The accepted result shape is:

```json
{
  "status": "PASSED|FAILED|SKIPPED",
  "summary": "observed QA conclusion",
  "failureCategory": "NONE|PRODUCT_DEFECT|REGRESSION|ENVIRONMENT|AUTHENTICATION|QA_INFRASTRUCTURE|REQUIREMENT_AMBIGUITY|FLAKY",
  "retryRecommendation": "NONE|CODING_AGENT|HUMAN",
  "browserValidation": {
    "required": true,
    "performed": true,
    "decisionSource": "TASK_OVERRIDE|PROJECT_PROFILE|REPOSITORY_CONFIG|AUTO_DETECTION|NOT_APPLICABLE",
    "baseUrl": "http://127.0.0.1:4173",
    "browser": "chromium",
    "viewports": ["desktop-1440x900", "mobile-390x844"]
  },
  "acceptanceResults": [{
    "criteria": "the criterion under test",
    "scope": "CURRENT|REGRESSION",
    "command": "the real command",
    "status": "PASSED|FAILED|SKIPPED",
    "exitCode": 0,
    "durationMillis": 1200,
    "logArtifactId": "qa-evidence/commands/current-1.log",
    "evidenceArtifactIds": ["qa-evidence/screenshots/current-1.png"]
  }],
  "evidenceManifestArtifactId": "qa-evidence/manifest.json"
}
```

### Task 2: Remove False-Pass Normalization

**Files:**
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/executor/EngineRequirementExecutorAdapterTest.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/EngineRequirementExecutorAdapter.java`

- [x] Replace the legacy-normalization test with one asserting that incomplete QA returns failure and never synthesizes `qa-inline-log-*`.
- [x] Remove only QA success normalization and its synthetic acceptance-result helpers. Keep unrelated solution-architect normalization unchanged.
- [x] Preserve explicit `FAILED` and `SKIPPED` as delivery blockers with original category/evidence references.
- [x] Run adapter tests until green.

### Task 3: Build Authoritative Evidence Collection

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/result/QaEvidenceBundleValidator.java`
- Create: `exec/src/test/java/com/wish/rd/exec/repair/result/QaEvidenceBundleValidatorTest.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/execution/model/RepairArtifactType.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/impl/DockerClaudeCodeExecutor.java`
- Modify: `exec/src/test/java/com/wish/rd/exec/repair/docker/DockerClaudeCodeExecutorTest.java`

- [x] Cover recursive discovery, stable names, MIME, bytes, SHA-256, bounded preview, typed evidence, missing manifest, dangling refs, CURRENT/REGRESSION, exit codes, browser evidence, and repository mutation.
- [x] Collect recursively under `/work/output`, reject symlinks/path escapes, and derive authoritative metadata after container exit.
- [x] Classify auxiliary `qa-evidence/browser/*.log` files as private QA command evidence so every manifest-listed file is publishable.
- [x] Validate result, task criteria, manifest coverage, and referenced evidence before executor success.
- [x] Require `qa-evidence/manifest.json`; require browser evidence only when the effective profile requires it.
- [x] Run focused exec tests until green.

### Task 4: Provision A Dedicated Playwright QA Runtime

**Files:**
- Create: `bootstrap/src/main/resources/executor/claude/Dockerfile.qa`
- Create: `bootstrap/src/main/resources/executor/claude/rd-qa-evidence.mjs`
- Create: `bootstrap/src/main/resources/executor/claude/playwright-cli.config.json`
- Create: `skill/src/main/resources/skills/qa-playwright-cli/SKILL.md`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/DockerExecutorProperties.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/impl/DockerClaudeCodeExecutor.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/model/ContainerRunRequest.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/ProcessContainerRunner.java`
- Modify: corresponding properties, executor, and runner tests.

- [x] Prove only `QA_AGENT` selects `qaImage`, mounts its governed skill read-only, exports `RD_AGENT_ROLE=QA_AGENT`, enables `--init`, and assigns `--shm-size=1g`.
- [x] Implement configuration with backward-compatible defaults and a 20-minute hard timeout.
- [x] Pin `@playwright/cli@0.1.17`, its matching Playwright/Chromium revision, `curl`, and `zip`; never use `latest`.
- [x] Add and Node-test helper actions for commands, manifest, redaction, sanitized trace packaging, and Playwright JSON error propagation.
- [x] Pre-create the non-root Claude home and Skill parent so the read-only Skill mount cannot make `.claude/session-env` unwritable.
- [x] Build the QA image and prove CLI version, real Chromium, desktop/mobile screenshots, trace, manifest, and non-root execution.

### Task 5: Connect Skill Governance And Installation Audit

**Files:**
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/skill/impl/QaPlaywrightSkillProvisioner.java`
- Modify: existing `skill` registry/policy/installer types.
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/EngineRequirementExecutorAdapter.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/EngineRequirementExecutorConfiguration.java`

- [x] Test QA allowlisting, Coding rejection, version/checksum enforcement, classpath extraction, idempotent snapshots, audit metadata, and mount context.
- [x] Wire `SkillInstallationEngine` into production bootstrap.
- [x] Extract the bundled Skill into a controlled root and mount it at `/home/rdbot/.claude/skills/qa-playwright-cli` read-only.
- [x] Persist role, version, source, checksum, policy and install path in Docker/stage metadata without credentials; no new Skill audit table is required.
- [x] Run skill and bootstrap focused tests until green.

### Task 6: Add Project QA Profiles And Safe Web Detection

**Files:**
- Create: domain model/service/store under `rag/src/main/java/com/wish/rd/rag/qa`.
- Create: bootstrap PostgreSQL row/mapper/store/controller under existing project boundaries.
- Create: `bootstrap/src/main/resources/sql/postgres/p5_qa_evidence.sql`.
- Modify: `frontend/src/services/projectService.ts`.
- Modify: `frontend/src/pages/admin/project/ProjectListPage.tsx`.
- Modify: `frontend/vite.config.ts` and `frontend/test/viteProxy.test.ts` only if a new proxy prefix is introduced.

- [x] Cover `AUTO|REQUIRED|DISABLED`, start command, base/health URL, regression commands, and allowed hosts in domain/persistence tests.
- [x] Add project/task GET/PUT tests and reject secret literals, credential-bearing/non-HTTP URLs, and invalid hosts.
- [x] Implement a usable project QA profile dialog.
- [x] Resolve task, project, repository `.rd-bot/qa-profile.json`/`rd-bot.qa.json`, then safe detection.
- [x] Produce an ambiguity/environment blocker rather than silently disabling required browser validation.
- [x] Execute `p5_qa_evidence.sql` twice and verify both tables with `to_regclass`.

### Task 7: Implement Current, Regression, And QA-To-Coding Remediation

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`
- Modify: `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineTest.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/context/RoleContextBuilder.java`

- [x] Test `PRODUCT_DEFECT/CODING_AGENT` creates fresh Coding/QA attempt 2 with immutable QA feedback/evidence context.
- [x] Test environment/auth/infra/ambiguity/flaky never dispatch Coding.
- [x] Test the second product failure exhausts remediation and ends at human without attempt 3.
- [x] Implement the bounded loop directly in `RequirementDeliveryEngine`; never transition a terminal stage back to running.
- [x] Preserve exact manual retry behavior in `TaskRetryEngine` and keep prior evidence immutable.

### Task 8: Persist And Display QA Evidence

**Files:**
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/ObjectStorageQaEvidencePublisher.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/QaEvidenceController.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/QaEvidenceRetentionService.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskExecutionOverviewController.java`
- Modify: `frontend/src/services/rdTaskService.ts`
- Modify: `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`
- Modify: focused backend/frontend tests.

- [x] Test private object upload and authoritative SHA-256/MIME/size preservation.
- [x] Test task ownership, unknown artifact `404`, inline image/text, attachment trace/video, and rejection of arbitrary `file://` reads.
- [x] Test/display each QA attempt's current/regression outcomes, screenshots, console/network status, hashes, and trace/log downloads.
- [x] Implement task-scoped APIs, evidence retention on task delete, and an un-nested task-detail gallery.
- [x] Rebuild the static admin bundle and verify the backend-served assets.

### Task 9: Regression And Real Acceptance

**Files:**
- Update: `docs/qa/2026-07-13-qa-playwright-evidence-acceptance-plan.md`
- Create: `docs/qa/2026-07-13-qa-playwright-evidence-acceptance-report.md`
- Create: evidence under `qa-runs/qa-playwright-evidence/<run-id>/`.

- [x] Run focused tests for `skill`, `rag`, `exec`, `engine`, and `bootstrap`.
- [x] Run `./mvnw -q -pl exec -Dtest=DockerClaudeCodeExecutorTest test` and `./mvnw -q -pl bootstrap -Dtest=ProcessContainerRunnerTest test` as mandated by `AGENTS.md`.
- [x] Install changed `rag`, `exec`, `skill`, and `engine` modules to avoid stale jars.
- [x] Run frontend tests, typecheck, build, and static-bundle verification.
- [x] Run the PostgreSQL atomic state smoke test.
- [x] Build and smoke the dedicated QA image with a real HTTP app, Chromium, desktop/mobile screenshots, sanitized trace, network/console logs, and integrity manifest.
- [x] Submit the controlled web fixture through real HTTP with a real provider once; verify the success/regression evidence path live and the bounded return-to-coding path deterministically without a second provider task.
- [x] Inspect PostgreSQL stage runs, Docker metadata Skill audit, evidence artifacts, job state, and task timeline.
- [x] Verify evidence through the real admin HTTP API and browser UI.
- [x] Run secret scans, `git diff --check`, and service cleanup checks.
- [x] Record unresolved and acceptance-limited issues once in the final report without blind retry.

## Plan Self-Review

- Every requested capability maps to a task: Skill feasibility and injection (Tasks 4-5), Playwright integration (Task 4), QA-tool lessons (execution spec), current and regression validation (Tasks 1, 3, 6-7), evidence visibility (Task 8), return-to-coding (Task 7), and real acceptance (Task 9).
- No task changes CI gating or `BUG_FIX` behavior.
- Strict result fields, evidence paths, failure categories, retry semantics, storage ownership, and Docker security options use the same names throughout the plan.
- There are no placeholder implementation steps; real acceptance may report an external/provider blocker, but only after the documented single attempt.
