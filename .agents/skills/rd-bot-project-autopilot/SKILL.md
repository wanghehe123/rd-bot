---
name: rd-bot-project-autopilot
description: Run a bounded, evidence-driven RD-Bot project iteration from an idea. Use when an AI should provision a new private personal GitHub repository plus a bound RD-Bot knowledge base and project, or plan one small requirement, dispatch it through an existing RD-Bot project, observe task and role evidence, trigger task-run evaluation, and decide whether to stop or run one more iteration. Default to dry-run and never approve, merge, deploy, delete, or expose secrets.
---

# RD-Bot Project Autopilot

Read `references/policy-and-stop-rules.md` before any live action.
Read `references/iteration-contract.md` before creating or resuming a run.
Read `references/rd-bot-api-map.md` only when interpreting an API response.

Use `scripts/autopilot.py` for all state and RD-Bot actions. Do not replace it with raw HTTP, shell, or Git mutation commands.

The workflow is project-level orchestration. It is separate from role Skills installed by RD-Bot's `SkillInstallationEngine`.

## Iterate on an existing project

1. Resolve the local RD-Bot origin and one project.
2. Allocate `qa-runs/autopilot/{runId}` and capture the read-only workspace fingerprint.
3. Initialize or resume the versioned Manifest.
4. Turn the idea into one bounded plan with measurable acceptance criteria.
5. Run `freeze-plan`, then stop after `DRY_RUN_COMPLETED` in dry-run mode.
6. For live-test, require both `--live-test` and `RD_BOT_AUTOPILOT_LIVE_TEST=1` before any write.
7. Use the guarded CLI for create, submit, observe, one retry, and task-run evaluation.
8. Decide only from stored task, stage, Attempt, and evaluation evidence.
9. Enforce a maximum of two iterations, one active task, and one retry per task.
10. Stop at approvals, unknown states, budget limits, `WAITING_HUMAN`, or ambiguous writes.
11. Verify the workspace fingerprint and render the final report.

## Provision a new project from an idea

When no RD-Bot project exists yet, the Skill can turn one confirmed idea into a
new private personal GitHub repository, a bound RD-Bot knowledge base with two
generated Markdown documents, and one enabled RD-Bot project. The two-phase
protocol is:

1. `provision-init --run-id --idea --success-criterion [--mode dry-run|live-provision]` creates the v2 Manifest without a project ID.
2. `provision-plan --plan-file [--github-owner]` freezes the canonical Provision Plan and prints its SHA-256 digest. No remote write happens.
3. Show the frozen plan and digest to the user. Real provisioning requires the user to confirm the exact digest.
4. `provision-confirm --plan-sha256 <digest>` persists the confirmation receipt; any plan change invalidates it.
5. `provision-run --live-provision --confirm-plan-sha256 <digest>` performs the writes only when `RD_BOT_AUTOPILOT_LIVE_PROVISION=1` is also set. Every create persists an intent first, is verified by an exact read-back with the run marker, and is bound before the next write.
6. `provision-resume [--live-provision --confirm-plan-sha256 <digest>]` is reconciliation-only after `WAITING_HUMAN`; it never resends an ambiguous write.
7. After the project binds, continue with the normal iteration steps above; requirement creation remains `autoExecute=false` followed by exactly one submit.

The repository owner must equal the authenticated `gh` user. Organization,
public, or internal repositories, clone/push, collaborator or secret changes,
external-source ingestion, and automatic cleanup of created resources are all
refused. On failure, report the created IDs and URLs as a manual cleanup list.

Never call `/admin/rd-tasks/{taskId}/approve`, delete, cancel, merge, deploy, arbitrary URLs, arbitrary shell, raw `curl`, or Git mutation commands. Never store credentials, full prompts, or private chain-of-thought in run artifacts; user-visible summaries and bounded evidence references are sufficient.
