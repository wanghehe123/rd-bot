# RD-Bot Multi-Agent Production Acceptance Runbook

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide an operator-ready runbook for completing the real production or production-equivalent acceptance run for RD-Bot multi-agent RAG orchestration.

**Architecture:** Production acceptance is evidence-driven. Independent smoke tests generate Markdown and JSON sidecars for Feishu alerts, Skill policy, GitHub PR evidence, and other failure/recovery drills; the multi-agent smoke then queries real RD-Bot HTTP and PostgreSQL state and aggregates those sidecars into the 15-point acceptance report.

**Tech Stack:** Java 21, Maven, Spring Boot 3.5, PostgreSQL, RD-Bot HTTP admin APIs, Feishu OpenAPI, GitHub REST, Docker-backed coding execution, provider secrets supplied through environment variables.

---

## Operating Rules

- Do not put real secrets in git, Markdown reports, PR descriptions, shell history captures, or chat.
- All secret values must be passed as environment variables or local shell variables.
- Every production smoke command must set `production-evidence=true`; otherwise the result cannot be counted as real acceptance.
- `SKIPPED` reports prove that the gate rejected incomplete production inputs. They do not prove product behavior.
- `PASSED_MINIMUM_SMOKE` proves only the covered smoke path. It does not equal full 15-point acceptance.
- `PASSED_FULL_PRODUCTION_ACCEPTANCE` is the only report conclusion that can close the original goal.

## Same-Task Evidence Binding

`MultiAgentRequirementDeliveryRealSmokeTest` supports the optional property `rd.multi-agent.smoke.task-id`.
Several full-acceptance sidecars are intentionally required to bind to the same `taskId` as the main multi-agent task.

The supported operating model is:

- leave `rd.multi-agent.smoke.task-id` blank for the first main smoke so RD-Bot creates and completes a real requirement task;
- capture that task id as `RD_BOT_ACCEPTANCE_TASK_ID`;
- run every task-bound sidecar drill against the same real task id;
- pass `-Drd.multi-agent.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID"` to the final aggregation run so it validates the existing task instead of creating a second task.

If any sidecar uses a different task id, the final report must keep the related acceptance point out of `PASSED_FULL_PRODUCTION_ACCEPTANCE`.

## Required Environment Values

Set these in the operator shell. Keep values local to the terminal session.

```bash
export RD_BOT_VERSION=""
export RD_BOT_ENVIRONMENT_ID=""
export RD_BOT_EXECUTED_BY=""
export RD_BOT_BASE_URL=""

export RD_BOT_POSTGRES_URL=""
export RD_BOT_POSTGRES_PSQL_URL=""
export RD_BOT_POSTGRES_USER=""
export RD_BOT_POSTGRES_PASSWORD=""

export RD_BOT_REPOSITORY_URL=""
export RD_BOT_REPO_OWNER=""
export RD_BOT_REPO_NAME=""
export RD_BOT_BASE_BRANCH="main"

export PROVIDER_A_API_KEY=""
export PROVIDER_B_API_KEY=""
export GITHUB_PAT=""
export GITHUB_TOKEN=""

export FEISHU_APP_ID=""
export FEISHU_APP_SECRET=""
export FEISHU_CHAT_ID=""

export RD_BOT_SECRET_SCAN_NEEDLES=""
```

`RD_BOT_POSTGRES_URL` is the JDBC URL used by Maven tests, such as `jdbc:postgresql://host:5432/rd_bot`.
`RD_BOT_POSTGRES_PSQL_URL` is the `psql` URL used by operator reachability checks, such as `postgresql://host:5432/rd_bot`.

Before running smoke tests, verify all required values are non-empty:

```bash
test -n "$RD_BOT_VERSION"
test -n "$RD_BOT_ENVIRONMENT_ID"
test -n "$RD_BOT_EXECUTED_BY"
test -n "$RD_BOT_BASE_URL"
test -n "$RD_BOT_POSTGRES_URL"
test -n "$RD_BOT_POSTGRES_PSQL_URL"
test -n "$RD_BOT_POSTGRES_USER"
test -n "$RD_BOT_POSTGRES_PASSWORD"
test -n "$RD_BOT_REPOSITORY_URL"
test -n "$RD_BOT_REPO_OWNER"
test -n "$RD_BOT_REPO_NAME"
test -n "$PROVIDER_A_API_KEY"
test -n "$PROVIDER_B_API_KEY"
test -n "$GITHUB_PAT"
test -n "$GITHUB_TOKEN"
test -n "$FEISHU_APP_ID"
test -n "$FEISHU_APP_SECRET"
test -n "$FEISHU_CHAT_ID"
test -n "$RD_BOT_SECRET_SCAN_NEEDLES"
```

## Step 1: Baseline Local Regression

Run the normal local suite before touching real systems:

```bash
./mvnw -q test
git diff --check
rg -n "Failures: [1-9]|Errors: [1-9]" bootstrap/target/surefire-reports engine/target/surefire-reports rag/target/surefire-reports exec/target/surefire-reports skill/target/surefire-reports
```

Expected:

- Maven exits `0`.
- `git diff --check` exits `0`.
- The `rg` command has no matches. Exit code `1` is acceptable for no matches.

## Step 2: Verify RD-Bot And PostgreSQL Reachability

Run direct checks from the same machine that will execute Maven:

```bash
curl -fsS "$RD_BOT_BASE_URL/admin/rd-tasks" >/tmp/rd-bot-rd-tasks.json
PGPASSWORD="$RD_BOT_POSTGRES_PASSWORD" psql "$RD_BOT_POSTGRES_PSQL_URL" -U "$RD_BOT_POSTGRES_USER" -c "select 1;"
PGPASSWORD="$RD_BOT_POSTGRES_PASSWORD" psql "$RD_BOT_POSTGRES_PSQL_URL" -U "$RD_BOT_POSTGRES_USER" -c "\\dt rd_agent_stage_runs"
PGPASSWORD="$RD_BOT_POSTGRES_PASSWORD" psql "$RD_BOT_POSTGRES_PSQL_URL" -U "$RD_BOT_POSTGRES_USER" -c "\\dt rd_role_context_packages"
PGPASSWORD="$RD_BOT_POSTGRES_PASSWORD" psql "$RD_BOT_POSTGRES_PSQL_URL" -U "$RD_BOT_POSTGRES_USER" -c "\\dt rd_experience_entries"
```

Expected:

- RD-Bot HTTP returns a successful response.
- PostgreSQL accepts the connection.
- Multi-agent tables exist.

## Step 3: Run Feishu Alert Real Smoke

Run this after `RD_BOT_ACCEPTANCE_TASK_ID` has been captured from the main smoke. If the variable is blank, run Step 6 first, then return here.

```bash
test -n "$RD_BOT_ACCEPTANCE_TASK_ID"

./mvnw -pl bootstrap -am -Dtest=FeishuAlertRealSmokeTest \
  -Drd.integration.feishu-alert.enabled=true \
  -Drd.feishu.alert.smoke.production-evidence=true \
  -Drd.feishu.alert.smoke.rd-bot-version="$RD_BOT_VERSION" \
  -Drd.feishu.alert.smoke.environment-id="$RD_BOT_ENVIRONMENT_ID" \
  -Drd.feishu.alert.smoke.executed-by="$RD_BOT_EXECUTED_BY" \
  -Drd.feishu.alert.smoke.app-id="$FEISHU_APP_ID" \
  -Drd.feishu.alert.smoke.app-secret="$FEISHU_APP_SECRET" \
  -Drd.feishu.alert.smoke.chat-id="$FEISHU_CHAT_ID" \
  -Drd.feishu.alert.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
  -Drd.feishu.alert.smoke.secret-scan-needles="$RD_BOT_SECRET_SCAN_NEEDLES" \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Capture the sidecar:

```bash
export FEISHU_ALERT_EVIDENCE_JSON="$(ls -t qa-runs/multi-agent-production-acceptance/feishu-alert-production-acceptance-*.json | head -1)"
test -n "$FEISHU_ALERT_EVIDENCE_JSON"
```

Expected:

- Maven exits `0`.
- The Markdown report conclusion is not `SKIPPED`.
- The JSON sidecar has `feishuAlertEvidenceValidated=true`.
- The JSON sidecar task id equals `$RD_BOT_ACCEPTANCE_TASK_ID`.
- The report does not contain the Feishu app secret or secret scan needle values.

## Step 4: Prepare A Real Skill Source

Create or choose a real Skill source directory outside the repository worktree. It must contain at least `SKILL.md`.

```bash
export RD_BOT_SKILL_SOURCE_DIR="/opt/rd-bot/skills-src/qa-real-runner"
export RD_BOT_SKILL_INSTALL_ROOT="/opt/rd-bot/skills-installed"
export RD_BOT_SKILL_ID="qa-real-runner"
export RD_BOT_SKILL_VERSION="v1"
export RD_BOT_SKILL_CHECKSUM="$(python3 - <<'PY'
import hashlib
import os
from pathlib import Path

source = Path(os.environ["RD_BOT_SKILL_SOURCE_DIR"]).resolve()
digest = hashlib.sha256()
if source.is_dir():
    files = sorted(
        [path for path in source.rglob("*") if path.is_file()],
        key=lambda path: path.relative_to(source).as_posix(),
    )
    for path in files:
        digest.update(path.relative_to(source).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
else:
    digest.update(source.read_bytes())
print("sha256:" + digest.hexdigest())
PY
)"

test -d "$RD_BOT_SKILL_SOURCE_DIR"
test -n "$RD_BOT_SKILL_CHECKSUM"
```

Expected:

- Source directory exists.
- Checksum is `sha256:` plus 64 hex characters.

## Step 5: Run Skill Policy Real Smoke

```bash
test -n "$RD_BOT_ACCEPTANCE_TASK_ID"

./mvnw -pl bootstrap -am -Dtest=SkillPolicyRealSmokeTest \
  -Drd.integration.skill-policy.enabled=true \
  -Drd.skill.smoke.production-evidence=true \
  -Drd.skill.smoke.rd-bot-version="$RD_BOT_VERSION" \
  -Drd.skill.smoke.environment-id="$RD_BOT_ENVIRONMENT_ID" \
  -Drd.skill.smoke.executed-by="$RD_BOT_EXECUTED_BY" \
  -Drd.skill.smoke.skill-id="$RD_BOT_SKILL_ID" \
  -Drd.skill.smoke.skill-version="$RD_BOT_SKILL_VERSION" \
  -Drd.skill.smoke.skill-source-uri="file://$RD_BOT_SKILL_SOURCE_DIR" \
  -Drd.skill.smoke.skill-checksum="$RD_BOT_SKILL_CHECKSUM" \
  -Drd.skill.smoke.install-root="$RD_BOT_SKILL_INSTALL_ROOT" \
  -Drd.skill.smoke.allowed-role=QA_AGENT \
  -Drd.skill.smoke.rejected-role=REQUIREMENT_REVIEWER \
  -Drd.skill.smoke.high-risk-role=CODING_AGENT \
  -Drd.skill.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Capture the sidecar:

```bash
export SKILL_POLICY_EVIDENCE_JSON="$(ls -t qa-runs/multi-agent-production-acceptance/skill-production-acceptance-*.json | head -1)"
test -n "$SKILL_POLICY_EVIDENCE_JSON"
```

Expected:

- Maven exits `0`.
- JSON has `skillPolicyEvidenceValidated=true`.
- JSON has `skillPolicyTaskId` equal to `$RD_BOT_ACCEPTANCE_TASK_ID`.
- JSON has `installed=true`, `unauthorizedRejected=true`, `highRiskWaitingApproval=true`, and `installerCallCount=1`.
- `installPath` is absolute and ends with `$RD_BOT_SKILL_ID/$RD_BOT_SKILL_VERSION`.

## Step 6: Run Main Multi-Agent Minimum Smoke

Run the main smoke without claiming full 15-point acceptance yet. With `rd.multi-agent.smoke.task-id` left blank, this creates the real task, executes the four Agent stages, queries PostgreSQL, validates artifacts, checks secrets, and writes the main report.

```bash
PROVIDER_A_API_KEY="$PROVIDER_A_API_KEY" PROVIDER_B_API_KEY="$PROVIDER_B_API_KEY" GITHUB_TOKEN="$GITHUB_TOKEN" \
./mvnw -pl bootstrap -am -Dtest=MultiAgentRequirementDeliveryRealSmokeTest \
  -Drd.integration.multi-agent.enabled=true \
  -Drd.multi-agent.smoke.production-evidence=true \
  -Drd.multi-agent.smoke.rd-bot-version="$RD_BOT_VERSION" \
  -Drd.multi-agent.smoke.environment-id="$RD_BOT_ENVIRONMENT_ID" \
  -Drd.multi-agent.smoke.executed-by="$RD_BOT_EXECUTED_BY" \
  -Drd.multi-agent.smoke.base-url="$RD_BOT_BASE_URL" \
  -Drd.multi-agent.smoke.postgres-url="$RD_BOT_POSTGRES_URL" \
  -Drd.multi-agent.smoke.postgres-user="$RD_BOT_POSTGRES_USER" \
  -Drd.multi-agent.smoke.postgres-password="$RD_BOT_POSTGRES_PASSWORD" \
  -Drd.multi-agent.smoke.repository-url="$RD_BOT_REPOSITORY_URL" \
  -Drd.multi-agent.smoke.repo-owner="$RD_BOT_REPO_OWNER" \
  -Drd.multi-agent.smoke.repo-name="$RD_BOT_REPO_NAME" \
  -Drd.multi-agent.smoke.base-branch="$RD_BOT_BASE_BRANCH" \
  -Drd.multi-agent.smoke.expected-provider-count=2 \
  -Drd.multi-agent.smoke.provider-secret-env-names=PROVIDER_A_API_KEY,PROVIDER_B_API_KEY \
  -Drd.multi-agent.smoke.github-code-platform-mode=real \
  -Drd.multi-agent.smoke.github-auth-mode=PAT_LOCAL_SMOKE \
  -Drd.multi-agent.smoke.github-credential-env-names=GITHUB_TOKEN \
  -Drd.multi-agent.smoke.secret-scan-needles="$RD_BOT_SECRET_SCAN_NEEDLES" \
  -Drd.multi-agent.smoke.request-timeout-seconds=900 \
  -Drd.multi-agent.smoke.completion-timeout-seconds=1800 \
  -Drd.multi-agent.smoke.poll-interval-seconds=5 \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Capture the main report:

```bash
export MULTI_AGENT_REPORT_MD="$(ls -t qa-runs/multi-agent-production-acceptance/multi-agent-production-acceptance-*.md | head -1)"
test -n "$MULTI_AGENT_REPORT_MD"
rg -n "taskId：" "$MULTI_AGENT_REPORT_MD"
rg -n "结论：" "$MULTI_AGENT_REPORT_MD"
export RD_BOT_ACCEPTANCE_TASK_ID="$(awk -F'：' '/taskId：/ {print $2; exit}' "$MULTI_AGENT_REPORT_MD" | xargs)"
test -n "$RD_BOT_ACCEPTANCE_TASK_ID"
```

Expected:

- Maven exits `0`.
- Report conclusion is not `SKIPPED`.
- Report contains a non-empty `taskId`.
- Report contains evidence for real HTTP, PostgreSQL, role contexts, stage artifacts, provider attempts, PR publication, QA results, secret scan, and experience retrieval.

## Step 7: Run GitHub PR Remote Evidence Smoke

Use the task id and PR number from the main report or RD-Bot task detail. The work branch must be exactly `requirement/{taskId}`.

```bash
test -n "$RD_BOT_ACCEPTANCE_TASK_ID"
export RD_BOT_ACCEPTANCE_PR_NUMBER=""

GITHUB_PAT="$GITHUB_PAT" ./mvnw -pl bootstrap -am -Dtest=GitHubPullRequestRemoteEvidenceRealSmokeTest \
  -Drd.integration.github-pr-evidence.enabled=true \
  -Drd.github.pr-evidence.production-evidence=true \
  -Drd.github.pr-evidence.rd-bot-version="$RD_BOT_VERSION" \
  -Drd.github.pr-evidence.environment-id="$RD_BOT_ENVIRONMENT_ID" \
  -Drd.github.pr-evidence.executed-by="$RD_BOT_EXECUTED_BY" \
  -Drd.github.pr-evidence.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
  -Drd.github.pr-evidence.repo-owner="$RD_BOT_REPO_OWNER" \
  -Drd.github.pr-evidence.repo-name="$RD_BOT_REPO_NAME" \
  -Drd.github.pr-evidence.base-branch="$RD_BOT_BASE_BRANCH" \
  -Drd.github.pr-evidence.work-branch="requirement/$RD_BOT_ACCEPTANCE_TASK_ID" \
  -Drd.github.pr-evidence.pull-number="$RD_BOT_ACCEPTANCE_PR_NUMBER" \
  -Drd.github.pr-evidence.secret-scan-needles="$RD_BOT_SECRET_SCAN_NEEDLES" \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Capture the sidecar:

```bash
export GITHUB_PR_REMOTE_EVIDENCE_JSON="$(ls -t qa-runs/multi-agent-production-acceptance/github-pr-remote-evidence-production-acceptance-*.json | head -1)"
test -n "$GITHUB_PR_REMOTE_EVIDENCE_JSON"
```

Expected:

- Maven exits `0`.
- JSON has `githubPrRemoteEvidenceValidated=true`.
- JSON has `pullRequestBodyContainsExactTaskId=true`.
- JSON has `pullRequestBodyContainsArtifactLink=true`.
- JSON has `secretLeakFound=false`.

## Step 8: Generate Workflow Recovery Sidecar

Run this after the main task has completed and after an operator has captured the before-restart counts from the same task.
The restart rehearsal itself is manual: stop RD-Bot after a persisted stage, restart the same production-equivalent deployment, let the workflow continue, then run this smoke to verify real HTTP and PostgreSQL state after restart.

```bash
test -n "$RD_BOT_ACCEPTANCE_TASK_ID"
test -n "$WORKFLOW_RECOVERY_STAGE_RUN_COUNT_BEFORE_RESTART"
test -n "$WORKFLOW_RECOVERY_STAGE_EVENT_COUNT_BEFORE_RESTART"
test -n "$WORKFLOW_RECOVERY_RETRY_ATTEMPT_COUNT"
test -n "$WORKFLOW_RECOVERY_RETRY_ARTIFACT_COUNT"
test -n "$WORKFLOW_RECOVERY_STARTUP_LOG_URI"
test -n "$WORKFLOW_RECOVERY_DATABASE_SNAPSHOT_URI"

./mvnw -pl bootstrap -am -Dtest=WorkflowRecoveryRealSmokeTest \
  -Drd.integration.workflow-recovery.enabled=true \
  -Drd.workflow.recovery.smoke.production-evidence=true \
  -Drd.workflow.recovery.smoke.rd-bot-version="$RD_BOT_VERSION" \
  -Drd.workflow.recovery.smoke.environment-id="$RD_BOT_ENVIRONMENT_ID" \
  -Drd.workflow.recovery.smoke.executed-by="$RD_BOT_EXECUTED_BY" \
  -Drd.workflow.recovery.smoke.base-url="$RD_BOT_BASE_URL" \
  -Drd.workflow.recovery.smoke.postgres-url="$RD_BOT_POSTGRES_URL" \
  -Drd.workflow.recovery.smoke.postgres-user="$RD_BOT_POSTGRES_USER" \
  -Drd.workflow.recovery.smoke.postgres-password="$RD_BOT_POSTGRES_PASSWORD" \
  -Drd.workflow.recovery.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
  -Drd.workflow.recovery.smoke.stage-run-count-before-restart="$WORKFLOW_RECOVERY_STAGE_RUN_COUNT_BEFORE_RESTART" \
  -Drd.workflow.recovery.smoke.stage-event-count-before-restart="$WORKFLOW_RECOVERY_STAGE_EVENT_COUNT_BEFORE_RESTART" \
  -Drd.workflow.recovery.smoke.retry-attempt-count="$WORKFLOW_RECOVERY_RETRY_ATTEMPT_COUNT" \
  -Drd.workflow.recovery.smoke.retained-retry-artifact-count="$WORKFLOW_RECOVERY_RETRY_ARTIFACT_COUNT" \
  -Drd.workflow.recovery.smoke.startup-log-evidence-uri="$WORKFLOW_RECOVERY_STARTUP_LOG_URI" \
  -Drd.workflow.recovery.smoke.database-snapshot-evidence-uri="$WORKFLOW_RECOVERY_DATABASE_SNAPSHOT_URI" \
  -Drd.workflow.recovery.smoke.secret-scan-needles="$RD_BOT_SECRET_SCAN_NEEDLES" \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Capture the sidecar:

```bash
export WORKFLOW_RECOVERY_EVIDENCE_JSON="$(ls -t qa-runs/multi-agent-production-acceptance/workflow-recovery-production-acceptance-*.json | head -1)"
test -n "$WORKFLOW_RECOVERY_EVIDENCE_JSON"
```

Expected:

- Maven exits `0`.
- JSON has `workflowRecoveryEvidenceValidated=true`.
- JSON has `taskId` equal to `RD_BOT_ACCEPTANCE_TASK_ID`.
- JSON has `duplicateSuccessfulStageCount=0`.
- JSON has `stageRunCountAfterRestart >= stageRunCountBeforeRestart`.
- JSON has `stageEventCountAfterRestart >= stageEventCountBeforeRestart`.

## Step 9: Generate Requirement Review Blocker Sidecar

Run this after a real requirement-reviewer blocker task has been produced and bound to the acceptance task id.
The reviewer result artifact must be copied to a durable production evidence URI, and the Feishu blocker alert message id must be captured from the real alert delivery.

```bash
test -n "$RD_BOT_ACCEPTANCE_TASK_ID"
test -n "$REQUIREMENT_REVIEW_ARTIFACT_URI"
test -n "$REQUIREMENT_REVIEW_FEISHU_MESSAGE_ID"

./mvnw -pl bootstrap -am -Dtest=RequirementReviewBlockerRealSmokeTest \
  -Drd.integration.requirement-review-blocker.enabled=true \
  -Drd.requirement-review.smoke.production-evidence=true \
  -Drd.requirement-review.smoke.rd-bot-version="$RD_BOT_VERSION" \
  -Drd.requirement-review.smoke.environment-id="$RD_BOT_ENVIRONMENT_ID" \
  -Drd.requirement-review.smoke.executed-by="$RD_BOT_EXECUTED_BY" \
  -Drd.requirement-review.smoke.base-url="$RD_BOT_BASE_URL" \
  -Drd.requirement-review.smoke.postgres-url="$RD_BOT_POSTGRES_URL" \
  -Drd.requirement-review.smoke.postgres-user="$RD_BOT_POSTGRES_USER" \
  -Drd.requirement-review.smoke.postgres-password="$RD_BOT_POSTGRES_PASSWORD" \
  -Drd.requirement-review.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
  -Drd.requirement-review.smoke.review-artifact-uri="$REQUIREMENT_REVIEW_ARTIFACT_URI" \
  -Drd.requirement-review.smoke.feishu-alert-message-id="$REQUIREMENT_REVIEW_FEISHU_MESSAGE_ID" \
  -Drd.requirement-review.smoke.secret-scan-needles="$RD_BOT_SECRET_SCAN_NEEDLES" \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Capture the sidecar:

```bash
export REQUIREMENT_REVIEW_EVIDENCE_JSON="$(ls -t qa-runs/multi-agent-production-acceptance/requirement-review-blocker-production-acceptance-*.json | head -1)"
test -n "$REQUIREMENT_REVIEW_EVIDENCE_JSON"
```

Expected:

- Maven exits `0`.
- JSON has `requirementReviewBlockerEvidenceValidated=true`.
- JSON has `taskId` equal to `$RD_BOT_ACCEPTANCE_TASK_ID`.
- JSON has `role=REQUIREMENT_REVIEWER`.
- JSON has `taskStatus=FAILED_NEEDS_HUMAN`.
- JSON has `executionResultStatus=NEEDS_HUMAN`.
- JSON has `downstreamAgentsDispatched=false`.
- JSON has `feishuAlertType=STAGE_FAILED_NEEDS_HUMAN`.
- `reviewArtifactUri` is a production URI, not `mock://`, not a local relative path.

## Step 10: Generate QA Failure Blocker Sidecar

Run this after a real QA failure task has been produced and bound to the acceptance task id.
The QA report artifact and every validation log artifact must be copied to durable production evidence URIs, and the Feishu QA failure alert message id must be captured from the real alert delivery.

```bash
test -n "$RD_BOT_ACCEPTANCE_TASK_ID"
test -n "$QA_FAILURE_REPORT_ARTIFACT_URI"
test -n "$QA_FAILURE_VALIDATION_LOG_ARTIFACT_URIS"
test -n "$QA_FAILURE_FEISHU_MESSAGE_ID"

./mvnw -pl bootstrap -am -Dtest=QaFailureBlockerRealSmokeTest \
  -Drd.integration.qa-failure.enabled=true \
  -Drd.qa-failure.smoke.production-evidence=true \
  -Drd.qa-failure.smoke.rd-bot-version="$RD_BOT_VERSION" \
  -Drd.qa-failure.smoke.environment-id="$RD_BOT_ENVIRONMENT_ID" \
  -Drd.qa-failure.smoke.executed-by="$RD_BOT_EXECUTED_BY" \
  -Drd.qa-failure.smoke.base-url="$RD_BOT_BASE_URL" \
  -Drd.qa-failure.smoke.postgres-url="$RD_BOT_POSTGRES_URL" \
  -Drd.qa-failure.smoke.postgres-user="$RD_BOT_POSTGRES_USER" \
  -Drd.qa-failure.smoke.postgres-password="$RD_BOT_POSTGRES_PASSWORD" \
  -Drd.qa-failure.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
  -Drd.qa-failure.smoke.qa-report-artifact-uri="$QA_FAILURE_REPORT_ARTIFACT_URI" \
  -Drd.qa-failure.smoke.validation-log-artifact-uris="$QA_FAILURE_VALIDATION_LOG_ARTIFACT_URIS" \
  -Drd.qa-failure.smoke.feishu-alert-message-id="$QA_FAILURE_FEISHU_MESSAGE_ID" \
  -Drd.qa-failure.smoke.secret-scan-needles="$RD_BOT_SECRET_SCAN_NEEDLES" \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Capture the sidecar:

```bash
export QA_FAILURE_EVIDENCE_JSON="$(ls -t qa-runs/multi-agent-production-acceptance/qa-failure-blocker-production-acceptance-*.json | head -1)"
test -n "$QA_FAILURE_EVIDENCE_JSON"
```

Expected:

- Maven exits `0`.
- JSON has `qaFailureBlockerEvidenceValidated=true`.
- JSON has `taskId` equal to `$RD_BOT_ACCEPTANCE_TASK_ID`.
- JSON has `role=QA_AGENT`.
- JSON has `taskStatus=FAILED_NEEDS_HUMAN`.
- JSON has `qaStageStatus=FAILED_VALIDATION`.
- JSON has `failedAcceptanceCount>0`.
- JSON has `prCreated=false`.
- JSON has `successReportCreated=false`.
- JSON has `blockedBeforePrCreating=true`.
- JSON has `feishuAlertType=QA_FAILED`.
- `qaReportArtifactUri` and every validation log URI are production URIs, not `mock://`, not local relative paths.

## Step 11: Generate Docker Coding Sidecar

Run this after a real successful `CODING_AGENT` stage has been produced and bound to the acceptance task id.
The patch, result JSON, test log, and Docker metadata artifacts must be copied to durable production evidence URIs.

```bash
test -n "$RD_BOT_ACCEPTANCE_TASK_ID"
test -n "$DOCKER_CODING_PATCH_ARTIFACT_URI"
test -n "$DOCKER_CODING_RESULT_ARTIFACT_URI"
test -n "$DOCKER_CODING_TEST_LOG_ARTIFACT_URI"
test -n "$DOCKER_CODING_METADATA_ARTIFACT_URI"

./mvnw -pl bootstrap -am -Dtest=DockerCodingRealSmokeTest \
  -Drd.integration.docker-coding.enabled=true \
  -Drd.docker-coding.smoke.production-evidence=true \
  -Drd.docker-coding.smoke.rd-bot-version="$RD_BOT_VERSION" \
  -Drd.docker-coding.smoke.environment-id="$RD_BOT_ENVIRONMENT_ID" \
  -Drd.docker-coding.smoke.executed-by="$RD_BOT_EXECUTED_BY" \
  -Drd.docker-coding.smoke.base-url="$RD_BOT_BASE_URL" \
  -Drd.docker-coding.smoke.postgres-url="$RD_BOT_POSTGRES_URL" \
  -Drd.docker-coding.smoke.postgres-user="$RD_BOT_POSTGRES_USER" \
  -Drd.docker-coding.smoke.postgres-password="$RD_BOT_POSTGRES_PASSWORD" \
  -Drd.docker-coding.smoke.repository-url="$RD_BOT_REPOSITORY_URL" \
  -Drd.docker-coding.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
  -Drd.docker-coding.smoke.patch-artifact-uri="$DOCKER_CODING_PATCH_ARTIFACT_URI" \
  -Drd.docker-coding.smoke.result-artifact-uri="$DOCKER_CODING_RESULT_ARTIFACT_URI" \
  -Drd.docker-coding.smoke.test-log-artifact-uri="$DOCKER_CODING_TEST_LOG_ARTIFACT_URI" \
  -Drd.docker-coding.smoke.docker-metadata-artifact-uri="$DOCKER_CODING_METADATA_ARTIFACT_URI" \
  -Drd.docker-coding.smoke.secret-scan-needles="$RD_BOT_SECRET_SCAN_NEEDLES" \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Capture the sidecar:

```bash
export DOCKER_CODING_EVIDENCE_JSON="$(ls -t qa-runs/multi-agent-production-acceptance/docker-coding-production-acceptance-*.json | head -1)"
test -n "$DOCKER_CODING_EVIDENCE_JSON"
```

Expected:

- Maven exits `0`.
- JSON has `dockerCodingEvidenceValidated=true`.
- JSON has `taskId` equal to `$RD_BOT_ACCEPTANCE_TASK_ID`.
- JSON has `repositoryUrl` equal to `$RD_BOT_REPOSITORY_URL`.
- JSON has `role=CODING_AGENT`.
- JSON has `realDockerRun=true`.
- JSON has `patchNonEmpty=true`.
- JSON has `resultJsonValidated=true`.
- JSON has `changedFileCount>0`.
- JSON has `validationExitCode=0`.
- JSON has `testsRun>0`.
- JSON has `testsFailed=0`.
- JSON has Docker image, container id or container name, workspace path, and full commit hash.
- Patch, result JSON, test log, and Docker metadata artifact URIs are production URIs, not `mock://`, not local relative paths.

## Step 12: Generate Delivery Review Failure Sidecar

Run this after a real delivery review rejection task has been produced and bound to the acceptance task id.
The delivery review failure artifact must be copied to a durable production evidence URI, and the Feishu delivery review failure alert message id must be captured from the real alert delivery.

```bash
test -n "$RD_BOT_ACCEPTANCE_TASK_ID"
test -n "$DELIVERY_REVIEW_ARTIFACT_URI"
test -n "$DELIVERY_REVIEW_FEISHU_MESSAGE_ID"

./mvnw -pl bootstrap -am -Dtest=DeliveryReviewFailureRealSmokeTest \
  -Drd.integration.delivery-review-failure.enabled=true \
  -Drd.delivery-review-failure.smoke.production-evidence=true \
  -Drd.delivery-review-failure.smoke.rd-bot-version="$RD_BOT_VERSION" \
  -Drd.delivery-review-failure.smoke.environment-id="$RD_BOT_ENVIRONMENT_ID" \
  -Drd.delivery-review-failure.smoke.executed-by="$RD_BOT_EXECUTED_BY" \
  -Drd.delivery-review-failure.smoke.base-url="$RD_BOT_BASE_URL" \
  -Drd.delivery-review-failure.smoke.postgres-url="$RD_BOT_POSTGRES_URL" \
  -Drd.delivery-review-failure.smoke.postgres-user="$RD_BOT_POSTGRES_USER" \
  -Drd.delivery-review-failure.smoke.postgres-password="$RD_BOT_POSTGRES_PASSWORD" \
  -Drd.delivery-review-failure.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
  -Drd.delivery-review-failure.smoke.review-artifact-uri="$DELIVERY_REVIEW_ARTIFACT_URI" \
  -Drd.delivery-review-failure.smoke.feishu-alert-message-id="$DELIVERY_REVIEW_FEISHU_MESSAGE_ID" \
  -Drd.delivery-review-failure.smoke.secret-scan-needles="$RD_BOT_SECRET_SCAN_NEEDLES" \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Capture the sidecar:

```bash
export DELIVERY_REVIEW_FAILURE_EVIDENCE_JSON="$(ls -t qa-runs/multi-agent-production-acceptance/delivery-review-failure-production-acceptance-*.json | head -1)"
test -n "$DELIVERY_REVIEW_FAILURE_EVIDENCE_JSON"
```

Expected:

- Maven exits `0`.
- JSON has `deliveryReviewFailureEvidenceValidated=true`.
- JSON has `taskId` equal to `$RD_BOT_ACCEPTANCE_TASK_ID`.
- JSON has `taskStatus=REJECTED`.
- JSON has `deliveryReviewApproved=false`.
- JSON has `reviewDecision=REJECTED`.
- JSON has `reviewer=DELIVERY_REVIEWER`.
- JSON has `pullRequestPublicationAttempted=false`.
- JSON has `prCreated=false`.
- JSON has `successReportCreated=false`.
- JSON has `failureReportCreated=true`.
- JSON has `successDeliveryReportExperienceCreated=false`.
- JSON has `blockedBeforePrCreating=true`.
- JSON has `stagePullRequestUrlRejected=true`.
- JSON has `feishuAlertType=DELIVERY_REVIEW_FAILED`.
- `reviewArtifactUri` is a production URI, not `mock://`, not a local relative path.

## Step 13: Generate Observability Metrics Sidecar

Run this after the main task and GitHub PR remote evidence smoke have both completed.
This sidecar calls the real `/actuator/prometheus` endpoint, queries the production PostgreSQL audit tables by the same task id, and verifies the GitHub PR remote evidence sidecar before it can count for #14.

```bash
test -n "$RD_BOT_ACCEPTANCE_TASK_ID"
test -n "$GITHUB_PR_REMOTE_EVIDENCE_JSON"

./mvnw -pl bootstrap -am -Dtest=ObservabilityMetricsRealSmokeTest \
  -Drd.integration.observability-metrics.enabled=true \
  -Drd.observability-metrics.smoke.production-evidence=true \
  -Drd.observability-metrics.smoke.rd-bot-version="$RD_BOT_VERSION" \
  -Drd.observability-metrics.smoke.environment-id="$RD_BOT_ENVIRONMENT_ID" \
  -Drd.observability-metrics.smoke.executed-by="$RD_BOT_EXECUTED_BY" \
  -Drd.observability-metrics.smoke.base-url="$RD_BOT_BASE_URL" \
  -Drd.observability-metrics.smoke.postgres-url="$RD_BOT_POSTGRES_URL" \
  -Drd.observability-metrics.smoke.postgres-user="$RD_BOT_POSTGRES_USER" \
  -Drd.observability-metrics.smoke.postgres-password="$RD_BOT_POSTGRES_PASSWORD" \
  -Drd.observability-metrics.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
  -Drd.observability-metrics.smoke.github-pr-remote-evidence-json="$GITHUB_PR_REMOTE_EVIDENCE_JSON" \
  -Drd.observability-metrics.smoke.secret-scan-needles="$RD_BOT_SECRET_SCAN_NEEDLES" \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Capture the sidecar:

```bash
export OBSERVABILITY_METRICS_EVIDENCE_JSON="$(ls -t qa-runs/multi-agent-production-acceptance/observability-metrics-production-acceptance-*.json | head -1)"
test -n "$OBSERVABILITY_METRICS_EVIDENCE_JSON"
```

Expected:

- Maven exits `0`.
- JSON has `observabilityMetricsEvidenceValidated=true`.
- JSON has `taskId` equal to `$RD_BOT_ACCEPTANCE_TASK_ID`.
- JSON has `metricsEndpointUrl` equal to `$RD_BOT_BASE_URL/actuator/prometheus`.
- JSON has `metricsHttpStatus=200`.
- JSON has all eight required metric-present fields set to `true`.
- JSON has `stageMetricCount>=4`.
- JSON has `auditTraceQuerySucceeded=true`.
- JSON has `remotePrTraceValidated=true`.
- JSON has `auditTraceLinkCount>=8` and `taskBoundAuditTraceLinkCount>=8`.

## Step 14: Final Full Acceptance Aggregation

Run this only after all required sidecars exist and bind to the same acceptance object.

```bash
PROVIDER_A_API_KEY="$PROVIDER_A_API_KEY" PROVIDER_B_API_KEY="$PROVIDER_B_API_KEY" GITHUB_TOKEN="$GITHUB_TOKEN" \
./mvnw -pl bootstrap -am -Dtest=MultiAgentRequirementDeliveryRealSmokeTest \
  -Drd.integration.multi-agent.enabled=true \
  -Drd.multi-agent.smoke.production-evidence=true \
  -Drd.multi-agent.smoke.rd-bot-version="$RD_BOT_VERSION" \
  -Drd.multi-agent.smoke.environment-id="$RD_BOT_ENVIRONMENT_ID" \
  -Drd.multi-agent.smoke.executed-by="$RD_BOT_EXECUTED_BY" \
  -Drd.multi-agent.smoke.base-url="$RD_BOT_BASE_URL" \
  -Drd.multi-agent.smoke.postgres-url="$RD_BOT_POSTGRES_URL" \
  -Drd.multi-agent.smoke.postgres-user="$RD_BOT_POSTGRES_USER" \
  -Drd.multi-agent.smoke.postgres-password="$RD_BOT_POSTGRES_PASSWORD" \
  -Drd.multi-agent.smoke.repository-url="$RD_BOT_REPOSITORY_URL" \
  -Drd.multi-agent.smoke.repo-owner="$RD_BOT_REPO_OWNER" \
  -Drd.multi-agent.smoke.repo-name="$RD_BOT_REPO_NAME" \
  -Drd.multi-agent.smoke.base-branch="$RD_BOT_BASE_BRANCH" \
  -Drd.multi-agent.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
  -Drd.multi-agent.smoke.expected-provider-count=2 \
  -Drd.multi-agent.smoke.provider-secret-env-names=PROVIDER_A_API_KEY,PROVIDER_B_API_KEY \
  -Drd.multi-agent.smoke.github-code-platform-mode=real \
  -Drd.multi-agent.smoke.github-auth-mode=PAT_LOCAL_SMOKE \
  -Drd.multi-agent.smoke.github-credential-env-names=GITHUB_TOKEN \
  -Drd.multi-agent.smoke.feishu-alert-evidence-json="$FEISHU_ALERT_EVIDENCE_JSON" \
  -Drd.multi-agent.smoke.recovery-evidence-json="$WORKFLOW_RECOVERY_EVIDENCE_JSON" \
  -Drd.multi-agent.smoke.skill-policy-evidence-json="$SKILL_POLICY_EVIDENCE_JSON" \
  -Drd.multi-agent.smoke.requirement-review-evidence-json="$REQUIREMENT_REVIEW_EVIDENCE_JSON" \
  -Drd.multi-agent.smoke.docker-coding-evidence-json="$DOCKER_CODING_EVIDENCE_JSON" \
  -Drd.multi-agent.smoke.qa-failure-evidence-json="$QA_FAILURE_EVIDENCE_JSON" \
  -Drd.multi-agent.smoke.delivery-review-failure-evidence-json="$DELIVERY_REVIEW_FAILURE_EVIDENCE_JSON" \
  -Drd.multi-agent.smoke.github-pr-remote-evidence-json="$GITHUB_PR_REMOTE_EVIDENCE_JSON" \
  -Drd.multi-agent.smoke.observability-metrics-evidence-json="$OBSERVABILITY_METRICS_EVIDENCE_JSON" \
  -Drd.multi-agent.smoke.secret-scan-needles="$RD_BOT_SECRET_SCAN_NEEDLES" \
  -Drd.multi-agent.smoke.request-timeout-seconds=900 \
  -Drd.multi-agent.smoke.completion-timeout-seconds=1800 \
  -Drd.multi-agent.smoke.poll-interval-seconds=5 \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected:

- Maven exits `0`.
- Latest `multi-agent-production-acceptance-*.md` says `PASSED_FULL_PRODUCTION_ACCEPTANCE`.
- Matrix rows #1 through #15 are all `PASSED`.
- The report records `secretNeedleCount` but does not record secret values.

## Final Evidence Checklist

- `multi-agent-production-acceptance-*.md`
- `feishu-alert-production-acceptance-*.md` and `.json`
- `workflow-recovery-production-acceptance-*.md` and `.json`
- `skill-production-acceptance-*.md` and `.json`
- `github-pr-remote-evidence-production-acceptance-*.md` and `.json`
- workflow recovery JSON sidecar
- requirement review blocker JSON sidecar
- Docker coding JSON sidecar
- QA failure blocker JSON sidecar
- delivery review failure JSON sidecar
- observability metrics JSON sidecar
- PostgreSQL query output for stage runs, stage events, role contexts, artifacts, and experience entries
- GitHub PR URL and PR number
- Feishu message ids for required alert types

Only after this checklist is satisfied should the goal be marked complete.
