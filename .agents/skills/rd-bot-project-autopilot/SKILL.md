---
name: rd-bot-project-autopilot
description: Run a bounded, evidence-driven RD-Bot project iteration from an idea. Use when an AI should plan one small requirement, dispatch it through an existing RD-Bot project, observe task and role evidence, trigger task-run evaluation, and decide whether to stop or run one more iteration. Default to dry-run and never approve, merge, deploy, delete, or expose secrets.
---

# RD-Bot Project Autopilot

Read `references/policy-and-stop-rules.md` before any live action.
Read `references/iteration-contract.md` before creating or resuming a run.
Read `references/rd-bot-api-map.md` only when interpreting an API response.

Use `scripts/autopilot.py` for all state and RD-Bot actions. Do not replace it with raw HTTP, shell, or Git mutation commands.

The workflow is project-level orchestration. It is separate from role Skills installed by RD-Bot's `SkillInstallationEngine`.

1. Resolve the local RD-Bot origin and one project.
2. Allocate `qa-runs/autopilot/{runId}` and capture the read-only workspace fingerprint.
3. Initialize or resume the versioned Manifest.
4. Turn the idea into one bounded plan with measurable acceptance criteria.
5. Run `freeze-plan`, then stop after `DRY_RUN_COMPLETED` in dry-run mode.
6. For live-test, require both `--live-test` and `RD_BOT_AUTOPILOT_LIVE_TEST=1` before any write.
7. Use the guarded CLI for create, submit, observe, one retry, and task-run evaluation.
8. Decide only from stored task, stage, Attempt, and evaluation evidence.
9. Stop at approvals, unknown states, budget limits, or ambiguous writes.
10. Verify the workspace fingerprint and render the final report.

Never call `/admin/rd-tasks/{taskId}/approve`, delete, cancel, merge, deploy, arbitrary URLs, arbitrary shell, or Git mutation commands. Never store credentials, full prompts, or private chain-of-thought in run artifacts.
