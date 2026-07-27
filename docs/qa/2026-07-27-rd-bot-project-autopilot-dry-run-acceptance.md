# RD-Bot Project Autopilot Dry-Run Acceptance

Date: 2026-07-27

## Scope

This is a read-only forward test of the repo-scoped Skill at
`.agents/skills/rd-bot-project-autopilot`. The selected existing RD-Bot project
was `7485660426127151104` (`SWE-bench Lite django__django-10924`). No live opt-in
was present and no mutating endpoint was called.

## Commands and evidence

1. `autopilot.py projects --base-url http://127.0.0.1:18080` returned a bounded
   JSON project page using the local GET API.
2. `autopilot.py init --mode dry-run --project-id 7485660426127151104` created
   run `autopilot-dry-20260727072900` under the ignored root
   `qa-runs/autopilot/autopilot-dry-20260727072900`.
3. `freeze-plan` consumed `iterations/01-plan-input.json`, then `dispatch`
   reached `DRY_RUN_COMPLETED` without a POST.
4. `validate_manifest.py` returned `OK` and `verify-workspace` returned
   `WORKSPACE_UNCHANGED`.

## Result

| Check | Result |
| --- | --- |
| Manifest terminal state | `DRY_RUN_COMPLETED` |
| Request ledger | `[]` (no POST entries) |
| Workspace before SHA-256 | `630cc8acc687a49aa3f3af916dbfee477df8a2b3f36bc8f3a2328110346afc5b` |
| Workspace after SHA-256 | `630cc8acc687a49aa3f3af916dbfee477df8a2b3f36bc8f3a2328110346afc5b` |
| Runtime artifacts | `manifest.json`, `iterations/01-plan-input.json` |
| Skill discovery | Explicit repo-local path verified; no global installation performed |
| Secret/full-prompt scan | No secret-like sample values; artifacts contain bounded plan metadata only |

The complete Skill suite ran with 44 tests passing. The local Skill creator
validator and metadata generator could not run because this Python environment
does not provide the optional `yaml` module; frontmatter, metadata, references,
and contract tests were validated without that dependency.
