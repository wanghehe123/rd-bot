# Multi-Agent RAG Orchestration Review Batch Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the current RD-Bot multi-agent RAG orchestration worktree into reviewable functional batches without changing behavior or hiding the missing production-environment validation.

**Architecture:** The current work turns RD-Bot into a RAG evidence collection and multi-agent orchestration engine. Core orchestration belongs in `engine`, role-scoped context packaging belongs in `rag`, executable worker contracts and result validation belong in `exec`, reusable skill installation policy belongs in `skill`, and concrete persistence/external adapters stay in `bootstrap`.

**Tech Stack:** Java 21, Spring Boot 3.5, Maven multi-module build, PostgreSQL SQL scripts, Feishu IM adapter, GitHub/PR smoke tests, Docker executor contracts, Markdown production acceptance artifacts.

---

## Current State

- Branch: `main`
- Base commit for this worktree snapshot: `45cffcc5`
- Status: dirty worktree with tracked and untracked changes.
- Constraint: do not stage, commit, push, or create PR unless explicitly requested.
- Latest local verification already run for the phase closeout:
  - `./mvnw -q test` exited `0` on 2026-07-04 10:23 Asia/Shanghai.
  - `git diff --check` exited `0`.
  - `rg -n "Failures: [1-9]|Errors: [1-9]" bootstrap/target/surefire-reports engine/target/surefire-reports rag/target/surefire-reports exec/target/surefire-reports skill/target/surefire-reports` had no matches.
  - Production smoke reports remain `SKIPPED` because real production parameters are absent.

## Review Batch 1: Design And Acceptance Documents

**Purpose:** Review the technical direction and production acceptance criteria before reviewing code.

**Files:**

- `docs/superpowers/plans/2026-07-01-multi-agent-rag-orchestration-technical-design.md`
- `docs/superpowers/plans/2026-07-01-multi-agent-rag-orchestration-production-acceptance.md`
- `docs/superpowers/plans/2026-07-03-multi-agent-rag-orchestration-phase-closeout.md`
- `docs/superpowers/plans/2026-07-03-multi-agent-rag-orchestration-review-batches.md`

**Review focus:**

- Confirm Java only owns orchestration and evidence gates, not low-level AI infra.
- Confirm each of the 15 acceptance points has a real production acceptance standard.
- Confirm `SKIPPED` production smoke reports cannot be mistaken for passed production validation.

**Verification:**

```bash
git check-ignore -v docs/superpowers/plans/2026-07-01-multi-agent-rag-orchestration-technical-design.md
git check-ignore -v docs/superpowers/plans/2026-07-01-multi-agent-rag-orchestration-production-acceptance.md
git check-ignore -v docs/superpowers/plans/2026-07-03-multi-agent-rag-orchestration-phase-closeout.md
git check-ignore -v docs/superpowers/plans/2026-07-03-multi-agent-rag-orchestration-review-batches.md
```

Expected: each file is visible through `.gitignore` reverse rules for `docs/superpowers/plans/*.md`.

**Suggested commit message if authorized later:**

```bash
git commit -m "docs: document multi-agent orchestration acceptance"
```

## Review Batch 2: Core Agent Orchestration Domain

**Purpose:** Review the engine-owned state machine, stage planning, role model, alert port, experience port, and requirement delivery orchestration.

**Files:**

- `engine/pom.xml`
- `engine/src/main/java/com/wish/rd/engine/agent/`
- `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/RequirementExecutionRequest.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryReviewResult.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryReviewer.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/RequirementPullRequestPublication.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/RequirementPullRequestPublishCommand.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/RequirementPullRequestPublisherPort.java`
- `engine/src/test/java/com/wish/rd/engine/agent/`
- `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineTest.java`
- `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineInjectionTest.java`
- `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryReviewerTest.java`

**Review focus:**

- `engine` should orchestrate stages and depend on ports, not concrete Feishu/GitHub/Docker SDKs.
- Stage transitions should prevent illegal or duplicate dispatch.
- Delivery review should block PR publication when QA or delivery-review evidence fails.
- Requirement delivery should preserve role artifacts and experience entries.

**Verification:**

```bash
./mvnw -q -pl engine -am test
```

Expected: exit `0`.

**Suggested commit message if authorized later:**

```bash
git commit -m "feat(engine): add multi-agent requirement orchestration"
```

## Review Batch 3: Role-Scoped RAG Context

**Purpose:** Review the role-specific context package model and task-scoped context storage.

**Files:**

- `rag/src/main/java/com/wish/rd/rag/context/`
- `rag/src/test/java/com/wish/rd/rag/context/`
- `rag/src/main/java/com/wish/rd/rag/runtime/RagStreamTaskRegistry.java`

**Review focus:**

- Context packages must be task-scoped and role-scoped.
- Requirement reviewer, solution architect, coding agent, and QA agent should not all receive the same evidence package.
- Context packages should expose omitted evidence, acceptance criteria, risk hints, and content hashes for auditability.

**Verification:**

```bash
./mvnw -q -pl rag test
```

Expected: exit `0`.

**Suggested commit message if authorized later:**

```bash
git commit -m "feat(rag): add role-scoped context packages"
```

## Review Batch 4: Execution Result Contracts

**Purpose:** Review execution-plane result validation and asset/alert type extensions used by the orchestrator.

**Files:**

- `exec/src/main/java/com/wish/rd/exec/repair/RepairAssetType.java`
- `exec/src/main/java/com/wish/rd/exec/repair/alert/RepairAlertType.java`
- `exec/src/main/java/com/wish/rd/exec/repair/result/AgentRoleResultValidation.java`
- `exec/src/main/java/com/wish/rd/exec/repair/result/AgentRoleResultValidator.java`
- `exec/src/test/java/com/wish/rd/exec/repair/RepairAssetTypeTest.java`
- `exec/src/test/java/com/wish/rd/exec/repair/alert/RepairAlertTypeTest.java`
- `exec/src/test/java/com/wish/rd/exec/repair/result/AgentRoleResultValidatorTest.java`

**Review focus:**

- The exec module should validate worker outputs without hard-coding one model provider.
- QA and solution-plan results should be structured enough for downstream review and audit.
- Alert and artifact type additions should remain backwards compatible.

**Verification:**

```bash
./mvnw -q -pl exec test
```

Expected: exit `0`.

**Suggested commit message if authorized later:**

```bash
git commit -m "feat(exec): validate multi-agent result artifacts"
```

## Review Batch 5: Skill Installation And Policy

**Purpose:** Review reusable skill descriptors, policy gate, install engine, and local installer adapter.

**Files:**

- `skill/pom.xml`
- `skill/src/main/java/com/wish/rd/skill/`
- `skill/src/test/java/com/wish/rd/skill/`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/skill/LocalFileSystemSkillInstaller.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/skill/LocalFileSystemSkillInstallerTest.java`

**Review focus:**

- Skill metadata must include id, version, source URI, checksum, allowed roles, risk level, and description.
- Unauthorized roles must be rejected before installer execution.
- High-risk skills must wait for approval or be rejected before installer execution.
- File-system installer must verify checksum and install into a predictable path.

**Verification:**

```bash
./mvnw -q -pl skill,bootstrap -am -Dtest=SkillInstallationEngineTest,RoleAllowlistSkillPolicyGateTest,LocalFileSystemSkillInstallerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: exit `0`.

**Suggested commit message if authorized later:**

```bash
git commit -m "feat(skill): add role-aware skill installation policy"
```

## Review Batch 6: Bootstrap Persistence And SQL

**Purpose:** Review PostgreSQL persistence adapters for role contexts, stage runs, artifacts, and experience entries.

**Files:**

- `bootstrap/src/main/resources/sql/postgres/p1_multi_agent_orchestration.sql`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/PostgresAgentStageArtifactStore.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/PostgresAgentStageRunStore.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/PostgresRoleContextPackageStore.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/PostgresWorkflowExperienceStore.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RdAgentStageArtifactRow.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RdAgentStageEventRow.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RdAgentStageRunRow.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RdExperienceEntryRow.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RdRoleContextPackageRow.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RdAgentStageArtifactMapper.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RdAgentStageRunMapper.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RdExperienceEntryMapper.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RdRoleContextPackageMapper.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/PostgresAgentStageArtifactStoreTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/PostgresAgentStageRunStoreTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/PostgresRoleContextPackageStoreTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/PostgresWorkflowExperienceStoreTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/MultiAgentOrchestrationSqlPolicyTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresPersistenceCrudIntegrationTest.java`

**Review focus:**

- SQL should be project-managed and PostgreSQL-oriented.
- Stage runs and events should be durable and resumable.
- Artifact and experience rows should store hashes, summaries, metadata JSON, and source artifact links.
- Store tests should cover persistence behavior and JSON field handling.

**Verification:**

```bash
./mvnw -q -pl bootstrap -am -Dtest=PostgresAgentStageArtifactStoreTest,PostgresAgentStageRunStoreTest,PostgresRoleContextPackageStoreTest,PostgresWorkflowExperienceStoreTest,MultiAgentOrchestrationSqlPolicyTest,PostgresPersistenceCrudIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: exit `0` for local/gated tests. Real PostgreSQL integration still requires explicit `rd.integration.postgres.enabled=true` and database properties.

**Suggested commit message if authorized later:**

```bash
git commit -m "feat(bootstrap): persist multi-agent workflow evidence"
```

## Review Batch 7: Bootstrap Runtime Adapters

**Purpose:** Review Spring wiring, execution adapters, Feishu alert adapter, GitHub PR publisher adapter, settings changes, and application configuration.

**Files:**

- `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/rag/RagSettingsController.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/EngineRequirementExecutorAdapter.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/EngineRequirementExecutorConfiguration.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/EngineAgentWorkflowAlertSink.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/EngineRequirementPullRequestPublisherAdapter.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImClient.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImProperties.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImRepairAlertSink.java`
- `bootstrap/src/main/resources/application.yaml`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/RagSettingsControllerTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskControllerTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/executor/EngineRequirementExecutorAdapterTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/executor/EngineRequirementExecutorConfigurationTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/executor/EngineAgentWorkflowAlertSinkTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/executor/EngineRequirementPullRequestPublisherAdapterTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/feishu/im/FeishuImMessageControllerTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/feishu/im/FeishuImRepairAlertSinkBeanWiringTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/feishu/im/FeishuImRepairAlertSinkTest.java`

**Review focus:**

- Bootstrap should only adapt HTTP/configuration/external systems.
- Feishu alert sink should never log or persist secrets.
- GitHub publication should remain behind the code-platform port.
- Configuration defaults should allow local startup while requiring explicit production flags for real smoke tests.

**Verification:**

```bash
./mvnw -q -pl bootstrap -am -Dtest=RagSettingsControllerTest,RdTaskControllerTest,EngineRequirementExecutorAdapterTest,EngineRequirementExecutorConfigurationTest,EngineAgentWorkflowAlertSinkTest,EngineRequirementPullRequestPublisherAdapterTest,FeishuImMessageControllerTest,FeishuImRepairAlertSinkBeanWiringTest,FeishuImRepairAlertSinkTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: exit `0`.

**Suggested commit message if authorized later:**

```bash
git commit -m "feat(bootstrap): wire multi-agent delivery adapters"
```

## Review Batch 8: Production Acceptance Smoke Gates

**Purpose:** Review production smoke preconditions, Markdown reports, JSON sidecar validators, and negative evidence gates.

**Files:**

- `bootstrap/src/test/java/com/wish/rd/bootstrap/MultiAgentRequirementDeliveryRealSmokeTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/MultiAgentProductionAcceptanceProfile.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/MultiAgentProductionAcceptanceReport.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/MultiAgentProductionAcceptanceProfileTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/MultiAgentProductionAcceptanceReportTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuAlertProductionAcceptanceProfile.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuAlertProductionAcceptanceReport.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/FeishuAlertRealSmokeTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/GitHubCodePlatformProductionAcceptanceReport.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/GitHubCodePlatformRealSmokeTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/GitHubPullRequestRemoteEvidenceRealSmokeTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/SkillProductionAcceptanceProfile.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/SkillProductionAcceptanceReport.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/SkillPolicyRealSmokeTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/*ProductionAcceptanceProfile.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/*ProductionAcceptanceReport.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/*RealSmokeTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/*RealSmokePreconditionsTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/*EvidenceFile.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/*EvidenceFileTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/*EvidenceReport.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/*EvidenceReportTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/ProductionSmokePreconditions.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/ProductionSmokePreconditionsTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/ProductionEvidenceUris.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/ProductionAcceptanceDocumentTest.java`

**Review focus:**

- Missing real production parameters must fail/skip with report output, not silently pass.
- A sidecar from another task, another environment, another execution user, another PR URL, or another work branch must not count.
- Secret scan inputs should be represented by counts and env names, not raw secret values.
- `PASSED` should only appear where that exact real smoke proves the acceptance point.

**Verification:**

```bash
./mvnw -q -pl bootstrap -am -Dtest=MultiAgentProductionAcceptanceProfileTest,MultiAgentProductionAcceptanceReportTest,FeishuAlertProductionAcceptanceReportTest,GitHubCodePlatformProductionAcceptanceReportTest,GitHubPullRequestRemoteEvidenceReportTest,SkillProductionAcceptanceProfileTest,SkillProductionAcceptanceReportTest,ProductionSmokePreconditionsTest,ProductionAcceptanceDocumentTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: exit `0`.

**Suggested commit message if authorized later:**

```bash
git commit -m "test: add production acceptance smoke evidence gates"
```

## Full Local Verification Before Any PR

Run these after review batches are staged or before opening a PR:

```bash
./mvnw -q test
git diff --check
rg -n "Failures: [1-9]|Errors: [1-9]" bootstrap/target/surefire-reports engine/target/surefire-reports rag/target/surefire-reports exec/target/surefire-reports skill/target/surefire-reports
```

Expected:

- `./mvnw -q test` exits `0`.
- `git diff --check` exits `0`.
- `rg` has no matches; exit code `1` is acceptable for no matches.

## Production Validation Still Required

The work cannot be called fully complete until these real commands are run with real environment values and produce passed reports:

```bash
./mvnw -pl bootstrap -am -Dtest=FeishuAlertRealSmokeTest \
  -Drd.integration.feishu-alert.enabled=true \
  -Drd.feishu.alert.smoke.production-evidence=true \
  -Drd.feishu.alert.smoke.rd-bot-version=<rd-bot-version> \
  -Drd.feishu.alert.smoke.environment-id=<production-environment-id> \
  -Drd.feishu.alert.smoke.executed-by=<operator> \
  -Drd.feishu.alert.smoke.app-id=<feishu-app-id> \
  -Drd.feishu.alert.smoke.app-secret=<feishu-app-secret> \
  -Drd.feishu.alert.smoke.chat-id=<feishu-chat-id> \
  -Drd.feishu.alert.smoke.secret-scan-needles=<secret-scan-needles> \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -pl bootstrap -am -Dtest=SkillPolicyRealSmokeTest \
  -Drd.integration.skill-policy.enabled=true \
  -Drd.skill.smoke.production-evidence=true \
  -Drd.skill.smoke.rd-bot-version=<rd-bot-version> \
  -Drd.skill.smoke.environment-id=<production-environment-id> \
  -Drd.skill.smoke.executed-by=<operator> \
  -Drd.skill.smoke.skill-id=<skill-id> \
  -Drd.skill.smoke.skill-version=<skill-version> \
  -Drd.skill.smoke.skill-source-uri=file:///opt/rd-bot/skills-src/<skill-id> \
  -Drd.skill.smoke.skill-checksum=sha256:<checksum> \
  -Drd.skill.smoke.install-root=/opt/rd-bot/skills-installed \
  -Drd.skill.smoke.allowed-role=QA_AGENT \
  -Drd.skill.smoke.rejected-role=REQUIREMENT_REVIEWER \
  -Drd.skill.smoke.high-risk-role=CODING_AGENT \
  -Dsurefire.failIfNoSpecifiedTests=false test

GITHUB_PAT=<secret> ./mvnw -pl bootstrap -am -Dtest=GitHubPullRequestRemoteEvidenceRealSmokeTest \
  -Drd.integration.github-pr-evidence.enabled=true \
  -Drd.github.pr-evidence.production-evidence=true \
  -Drd.github.pr-evidence.rd-bot-version=<rd-bot-version> \
  -Drd.github.pr-evidence.environment-id=<production-environment-id> \
  -Drd.github.pr-evidence.executed-by=<operator> \
  -Drd.github.pr-evidence.task-id=<task-id> \
  -Drd.github.pr-evidence.repo-owner=<repo-owner> \
  -Drd.github.pr-evidence.repo-name=<repo-name> \
  -Drd.github.pr-evidence.base-branch=<base-branch> \
  -Drd.github.pr-evidence.work-branch=requirement/<task-id> \
  -Drd.github.pr-evidence.pull-number=<pull-number> \
  -Drd.github.pr-evidence.secret-scan-needles=<secret-scan-needles> \
  -Dsurefire.failIfNoSpecifiedTests=false test

PROVIDER_A_API_KEY=<secret> PROVIDER_B_API_KEY=<secret> GITHUB_TOKEN=<secret> \
./mvnw -pl bootstrap -am -Dtest=MultiAgentRequirementDeliveryRealSmokeTest \
  -Drd.integration.multi-agent.enabled=true \
  -Drd.multi-agent.smoke.production-evidence=true \
  -Drd.multi-agent.smoke.rd-bot-version=<rd-bot-version> \
  -Drd.multi-agent.smoke.environment-id=<production-environment-id> \
  -Drd.multi-agent.smoke.executed-by=<operator> \
  -Drd.multi-agent.smoke.base-url=<rd-bot-base-url> \
  -Drd.multi-agent.smoke.postgres-url=<postgres-jdbc-url> \
  -Drd.multi-agent.smoke.postgres-user=<postgres-user> \
  -Drd.multi-agent.smoke.postgres-password=<postgres-password> \
  -Drd.multi-agent.smoke.repository-url=<repository-url> \
  -Drd.multi-agent.smoke.repo-owner=<repo-owner> \
  -Drd.multi-agent.smoke.repo-name=<repo-name> \
  -Drd.multi-agent.smoke.expected-provider-count=2 \
  -Drd.multi-agent.smoke.provider-secret-env-names=PROVIDER_A_API_KEY,PROVIDER_B_API_KEY \
  -Drd.multi-agent.smoke.github-code-platform-mode=real \
  -Drd.multi-agent.smoke.github-auth-mode=PAT_LOCAL_SMOKE \
  -Drd.multi-agent.smoke.github-credential-env-names=GITHUB_TOKEN \
  -Drd.multi-agent.smoke.feishu-alert-evidence-json=<feishu-alert-evidence-json> \
  -Drd.multi-agent.smoke.recovery-evidence-json=<workflow-recovery-evidence-json> \
  -Drd.multi-agent.smoke.skill-policy-evidence-json=<skill-policy-evidence-json> \
  -Drd.multi-agent.smoke.requirement-review-evidence-json=<requirement-review-evidence-json> \
  -Drd.multi-agent.smoke.docker-coding-evidence-json=<docker-coding-evidence-json> \
  -Drd.multi-agent.smoke.qa-failure-evidence-json=<qa-failure-evidence-json> \
  -Drd.multi-agent.smoke.delivery-review-failure-evidence-json=<delivery-review-failure-evidence-json> \
  -Drd.multi-agent.smoke.github-pr-remote-evidence-json=<github-pr-remote-evidence-json> \
  -Drd.multi-agent.smoke.observability-metrics-evidence-json=<observability-metrics-evidence-json> \
  -Drd.multi-agent.smoke.secret-scan-needles=<secret-scan-needles> \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Use the full property list from `docs/superpowers/plans/2026-07-03-multi-agent-rag-orchestration-phase-closeout.md`; do not replace real provider, Feishu, GitHub, PostgreSQL, Docker, or RD-Bot HTTP validation with mocks.

## PR Description Draft

```markdown
## Summary

- Adds a role-scoped RAG evidence and multi-agent orchestration engine for requirement delivery.
- Introduces reviewer, architect, coding, and QA stages with durable stage runs, artifacts, alerts, review gates, PR publication evidence, skill policy, and experience capture.
- Adds production acceptance documentation and property-gated real smoke tests that write Markdown/JSON evidence reports.

## Verification

- `./mvnw -q test`
- `git diff --check`
- `rg -n "Failures: [1-9]|Errors: [1-9]" bootstrap/target/surefire-reports engine/target/surefire-reports rag/target/surefire-reports exec/target/surefire-reports skill/target/surefire-reports`

## Production Acceptance Status

- Current real production acceptance is not complete.
- Current production smoke reports remain `SKIPPED` because required production-equivalent environment values and credentials were not available.
- The missing real acceptance reports include Feishu alert, workflow recovery, Skill policy, requirement review blocker, Docker coding, QA failure blocker, delivery review failure, GitHub PR remote, and observability metrics sidecars.
- The PR should not claim production validation until the real smoke commands listed in the closeout document produce passed reports.
```

## Recommended Review Order

1. Review Batch 1: docs and acceptance criteria.
2. Review Batch 2: engine orchestration.
3. Review Batch 3: RAG role context.
4. Review Batch 4: exec result contracts.
5. Review Batch 5: skill policy.
6. Review Batch 6: PostgreSQL persistence.
7. Review Batch 7: bootstrap runtime adapters.
8. Review Batch 8: production acceptance smoke gates.
9. Run full local verification.
10. Decide whether to submit the phase result as-is or provide real production parameters for the final acceptance run.
