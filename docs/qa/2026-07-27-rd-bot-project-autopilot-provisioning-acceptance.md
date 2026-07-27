# RD-Bot Project Autopilot Provisioning Acceptance (2026-07-27)

## Scope

Extend the repo-scoped `rd-bot-project-autopilot` Skill from dry-run/live-test
iteration to guarded project provisioning: one private personal GitHub
repository, one RD-Bot knowledge base with two generated Markdown documents,
and one enabled RD-Bot project, all before the existing bounded iteration
protocol. Implementation plan:
`docs/superpowers/plans/2026-07-27-rd-bot-project-autopilot-provisioning.md`.

## What was verified

### Unit and contract tests (all green)

```
PYTHONPYCACHEPREFIX=/tmp/rd-bot-pycache PYTHONPATH=. python3 -m unittest discover -s scripts/tests -t .
Ran 81 tests ... OK
```

Coverage highlights:

- `test_provisioning.py`: canonical Provision Plan is private-only, marker
  bound, digest stable, and rejects public visibility, owner mismatch, and
  credential-shaped input.
- `test_github_client.py`: only `gh auth status`, `gh api user`, fixed
  `POST /user/repos`, and `GET /repos/{owner}/{name}` are executable; no shell,
  clone, push, or generic passthrough exists.
- `test_iteration_state.py`: Manifest v2 requires the exact digest before any
  live intent; an ambiguous write can never be resent after `WAITING_HUMAN`.
- `test_rd_bot_client.py` / `test_http_contract.py`: live-provision POST routes
  are limited to `/knowledge-base`, `/knowledge-base/{id}/docs/write`, and
  `/admin/projects`, each schema-validated and double-gated.
- `test_provisioning_workflow.py`: intent-first ordering
  (`github.create -> rd.create_kb -> rd.write_doc x2 -> rd.create_project`),
  ambiguous GitHub create reconciles with exactly one read and no second
  write, an unverifiable source write stops at `WAITING_HUMAN` with
  `AMBIGUOUS_SOURCE_WRITE`, dry-run touches no client, and the runtime gate
  rejects `live_enabled=False`.
- `test_autopilot_cli.py`: `provision-run` fails closed without
  `--live-provision`, without a persisted `provision-confirm` receipt, with a
  wrong `--confirm-plan-sha256`, or without
  `RD_BOT_AUTOPILOT_LIVE_PROVISION=1` — all before any client is constructed.
- `test_skill_contract.py`: SKILL.md documents the two-phase digest
  confirmation, the private-personal repository boundary, and all five
  `provision-*` commands.

### Static checks

```
python3 -m compileall -q scripts          # OK
python3 -m json.tool assets/iteration-manifest.schema.json  # OK
git diff --check                          # OK
```

### Actual dry-run (no remote write)

Run `autopilot-provision-dry-20260727` under `qa-runs/autopilot/`:

1. `provision-init` created the v2 Manifest with empty `projectId` at `DRAFT`.
2. `provision-plan --github-owner wish233` froze the canonical plan,
   `planSha256=92d265ed13cd0c959595d418cf0177ff1e79293c58bb81a789acf2f2448106ae`,
   status `PLAN_READY`.
3. `provision-run` finished at `DRY_RUN_COMPLETED` without constructing a
   GitHub or RD-Bot client.
4. `verify-workspace` printed `WORKSPACE_UNCHANGED`.

## Boundaries confirmed

- Real provisioning requires all of: `--live-provision`, a persisted
  `provision-confirm --plan-sha256` receipt, the same digest repeated via
  `--confirm-plan-sha256`, and `RD_BOT_AUTOPILOT_LIVE_PROVISION=1`.
- Repository owner must equal the authenticated `gh` user; organization,
  public, or internal repositories are refused before any subprocess call.
- Every remote create persists a Manifest intent first and binds only an
  exact, marker-verified read-back; `provision-resume` is reconciliation-only.
- No automatic cleanup: failures report created IDs and URLs as a manual
  cleanup list.

## Not verified in this pass

- A real `live-provision` run against GitHub and a running RD-Bot instance.
  This intentionally requires a user-supplied task plus the final digest
  confirmation and was not executed.
