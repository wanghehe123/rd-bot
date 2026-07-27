# RD-Bot Project Autopilot Skill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a repo-scoped, test-only Agent Skill that turns one project idea into at most two evidence-driven RD-Bot requirement-delivery iterations without bypassing human approval, merge, deployment, or budget boundaries.

**Architecture:** Keep project-level management outside RD-Bot as `.agents/skills/rd-bot-project-autopilot`. A Manager Agent supplies bounded plans and decisions, while standard-library Python scripts enforce the versioned Manifest, exact HTTP allowlist, double live-write opt-in, crash reconciliation, task/evaluation observation, one retry, workspace integrity, and evidence artifacts. Reuse existing RD-Bot Admin APIs; do not change Java, SQL, frontend, or the four-role delivery engine.

**Tech Stack:** Agent Skills (`SKILL.md`, `agents/openai.yaml`), Python 3 standard library (`argparse`, `urllib`, `json`, `pathlib`, `unittest`), existing RD-Bot Admin REST APIs, JSON Schema as a checked-in contract, Markdown QA evidence.

---

## Source Of Truth

- Approved design: `docs/superpowers/specs/2026-07-27-rd-bot-project-autopilot-design.md`
- Current requirement task API: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskController.java`
- Current stage overview/trace API: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskExecutionOverviewController.java`
- Current structured retry API: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/TaskRetryController.java`
- Current task-run evaluation API: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/evaluation/EvaluationController.java`
- Current task lifecycle: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`

The implementation must not modify any of the Java files above. They are references for the client contract.

## Premium Review Revisions

The repository-required premium architecture review returned `revise`. This plan incorporates every mandatory revision:

1. Persist a versioned dispatch intent before any POST.
2. Use one execution path only: create with `autoExecute=false`, bind the returned `taskId`, then submit exactly once.
3. Reconcile ambiguous creation by exact title marker, project ID, request fingerprint, and task detail; zero or multiple exact matches become `WAITING_HUMAN` and are never resent automatically.
4. Require both `--live-test` and `RD_BOT_AUTOPILOT_LIVE_TEST=1` before any POST.
5. Enforce exact origin, method/path allowlist, request schemas, redirect rejection, bounded responses, unknown-state fail-closed behavior, and secret redaction in code.
6. Snapshot the dirty worktree before and after execution; the runner may only write inside its allocated `qa-runs/autopilot/{runId}` directory and may not run Git mutation commands.
7. Validate repo-local discovery for Codex and retain explicit path invocation as fallback.

## File Map

Create only these implementation files:

```text
.agents/skills/rd-bot-project-autopilot/
├── SKILL.md
├── agents/openai.yaml
├── assets/iteration-manifest.schema.json
├── references/iteration-contract.md
├── references/policy-and-stop-rules.md
├── references/rd-bot-api-map.md
└── scripts/
    ├── __init__.py
    ├── autopilot.py
    ├── iteration_state.py
    ├── rd_bot_client.py
    ├── run_artifacts.py
    ├── validate_manifest.py
    ├── workspace_guard.py
    ├── workflow.py
    └── tests/
        ├── __init__.py
        ├── fake_rd_bot.py
        ├── test_autopilot_cli.py
        ├── test_http_contract.py
        ├── test_iteration_state.py
        ├── test_rd_bot_client.py
        ├── test_run_artifacts.py
        ├── test_skill_contract.py
        ├── test_workspace_guard.py
        └── test_workflow.py
```

Create these evidence files during validation:

```text
docs/qa/2026-07-27-rd-bot-project-autopilot-dry-run-acceptance.md
docs/qa/2026-07-27-rd-bot-project-autopilot-live-test-acceptance.md
```

Runtime evidence remains ignored under `qa-runs/autopilot/{runId}/`.

Do not create a README, installation guide, new dependency manifest, database migration, controller, frontend component, or generic HTTP/shell proxy.

## Fixed Runtime Contracts

### Manifest states

```text
DRAFT
PLANNING
READY
DRY_RUN_COMPLETED
DISPATCH_INTENT
TASK_BOUND
SUBMIT_INTENT
OBSERVING
RETRY_INTENT
EVALUATION_INTENT
EVALUATING
LEARNING
WAITING_HUMAN
COMPLETED
BOUNDED_STOP
```

### Hard limits

```text
maxIterations = 2
maxActiveTasks = 1
maxRetriesPerTask = 1
maxElapsedMinutes <= 120
maxTokenBudget <= 200000
HTTP response body <= 2 MiB
trace/log artifact excerpt <= 100000 characters
```

### Write actions

The only allowed POST actions are:

```text
POST /admin/rd-tasks/requirements
POST /admin/rd-tasks/{numericTaskId}/submit
POST /admin/rd-tasks/{numericTaskId}/retry
POST /admin/rd-tasks/{numericTaskId}/evaluations
```

Every POST requires `mode=live-test`, the `--live-test` CLI flag, and `RD_BOT_AUTOPILOT_LIVE_TEST=1`. There is no code path for `/approve`, delete, cancel, merge, deploy, arbitrary URLs, arbitrary methods, shell commands, or Git mutation.

### Task state handling

```text
COMPLETED -> create/observe task-run evaluation
FAILED_RETRYABLE -> one structured retry may be considered
WAITING_APPROVAL -> WAITING_HUMAN
FAILED_NEEDS_HUMAN -> WAITING_HUMAN
REJECTED -> WAITING_HUMAN in the prototype
MERGED -> WAITING_HUMAN because the prototype never merges
CANCELLED | DEAD_LETTERED | DELETED -> BOUNDED_STOP
all documented active states -> continue bounded observation
unknown state -> WAITING_HUMAN
```

---

### Task 1: Scaffold The Repo-Scoped Skill

**Files:**
- Create: `.agents/skills/rd-bot-project-autopilot/SKILL.md`
- Create: `.agents/skills/rd-bot-project-autopilot/agents/openai.yaml`
- Create: `.agents/skills/rd-bot-project-autopilot/scripts/__init__.py`
- Create: `.agents/skills/rd-bot-project-autopilot/references/`
- Create: `.agents/skills/rd-bot-project-autopilot/assets/`

- [ ] **Step 1: Initialize with the required skill creator**

Run:

```bash
python3 /Users/wish233/.codex/skills/.system/skill-creator/scripts/init_skill.py \
  rd-bot-project-autopilot \
  --path .agents/skills \
  --resources scripts,references,assets \
  --interface 'display_name=RD-Bot Project Autopilot' \
  --interface 'short_description=Run bounded evidence-driven RD-Bot iterations' \
  --interface 'default_prompt=Use $rd-bot-project-autopilot to turn this idea into a bounded RD-Bot project iteration.'
```

Expected: `.agents/skills/rd-bot-project-autopilot/` is created with `SKILL.md` and `agents/openai.yaml`.

- [ ] **Step 2: Replace the generated Skill body with a minimal valid contract**

Write exactly this initial content to `SKILL.md`:

```markdown
---
name: rd-bot-project-autopilot
description: Run a bounded, evidence-driven RD-Bot project iteration from an idea. Use when an AI should plan one small requirement, dispatch it through an existing RD-Bot project, observe task and role evidence, trigger task-run evaluation, and decide whether to stop or run one more iteration. Default to dry-run and never approve, merge, deploy, delete, or expose secrets.
---

# RD-Bot Project Autopilot

Read `references/policy-and-stop-rules.md` before any live action.
Read `references/iteration-contract.md` before creating or resuming a run.
Read `references/rd-bot-api-map.md` only when interpreting an API response.

Use `scripts/autopilot.py` for all state and RD-Bot actions. Do not replace it with raw HTTP, shell, or Git mutation commands.
```

- [ ] **Step 3: Add Python package markers**

Create empty UTF-8 files:

```text
.agents/skills/rd-bot-project-autopilot/scripts/__init__.py
.agents/skills/rd-bot-project-autopilot/scripts/tests/__init__.py
```

- [ ] **Step 4: Validate the initial package**

Run:

```bash
python3 /Users/wish233/.codex/skills/.system/skill-creator/scripts/quick_validate.py \
  .agents/skills/rd-bot-project-autopilot
```

Expected: `Skill is valid!`

- [ ] **Step 5: Commit the scaffold**

```bash
git add .agents/skills/rd-bot-project-autopilot
git commit -m "feat(autopilot): scaffold bounded project skill"
```

Expected: the commit contains only the Skill scaffold.

### Task 2: Implement The Versioned Manifest And State Machine

**Files:**
- Create: `.agents/skills/rd-bot-project-autopilot/assets/iteration-manifest.schema.json`
- Create: `.agents/skills/rd-bot-project-autopilot/scripts/iteration_state.py`
- Create: `.agents/skills/rd-bot-project-autopilot/scripts/validate_manifest.py`
- Test: `.agents/skills/rd-bot-project-autopilot/scripts/tests/test_iteration_state.py`

- [ ] **Step 1: Write failing state-machine tests**

Create `test_iteration_state.py` with these cases:

```python
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.iteration_state import (
    ManifestError,
    ManifestStore,
    RunStatus,
    create_manifest,
    request_fingerprint,
)


class ManifestStateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.run_dir = Path(self.temp.name) / "autopilot-test"
        self.store = ManifestStore(self.run_dir)
        self.manifest = create_manifest(
            run_id="autopilot-test",
            mode="dry-run",
            project_id="7480000000000000000",
            idea="Add a bounded status summary",
            success_criteria=["A focused automated test passes"],
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_defaults_are_hard_bounded(self) -> None:
        self.assertEqual(self.manifest["limits"]["maxIterations"], 2)
        self.assertEqual(self.manifest["limits"]["maxActiveTasks"], 1)
        self.assertEqual(self.manifest["limits"]["maxRetriesPerTask"], 1)

    def test_invalid_transition_fails_closed(self) -> None:
        self.store.initialize(self.manifest)
        with self.assertRaisesRegex(ManifestError, "DRAFT -> OBSERVING"):
            self.store.transition(RunStatus.OBSERVING, "skip planning")

    def test_dispatch_intent_is_persisted_before_task_binding(self) -> None:
        self.store.initialize(self.manifest)
        self.store.transition(RunStatus.PLANNING, "charter ready")
        self.store.freeze_plan(1, {"title": "Small change", "acceptanceCriteria": ["test passes"]})
        intent = self.store.record_dispatch_intent(1)
        on_disk = json.loads((self.run_dir / "manifest.json").read_text())
        self.assertEqual(on_disk["status"], "DISPATCH_INTENT")
        self.assertEqual(on_disk["iterations"][0]["requestFingerprint"], intent["requestFingerprint"])
        self.assertIsNone(on_disk["iterations"][0]["taskId"])

    def test_second_active_task_is_rejected(self) -> None:
        self.store.initialize(self.manifest)
        self.store.transition(RunStatus.PLANNING, "charter ready")
        self.store.freeze_plan(1, {"title": "First", "acceptanceCriteria": ["one"]})
        self.store.record_dispatch_intent(1)
        self.store.bind_task(1, "7480000000000000001")
        with self.assertRaisesRegex(ManifestError, "active task"):
            self.store.freeze_plan(2, {"title": "Second", "acceptanceCriteria": ["two"]})

    def test_request_fingerprint_is_canonical(self) -> None:
        left = request_fingerprint({"b": 2, "a": 1})
        right = request_fingerprint({"a": 1, "b": 2})
        self.assertEqual(left, right)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the test and verify the intended failure**

Run:

```bash
PYTHONPATH=.agents/skills/rd-bot-project-autopilot \
python3 -m unittest \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_iteration_state.py -v
```

Expected: FAIL with `ModuleNotFoundError: No module named 'scripts.iteration_state'`.

- [ ] **Step 3: Add the versioned JSON Schema contract**

Create `assets/iteration-manifest.schema.json` with Draft 2020-12, `additionalProperties: false`, and these required top-level fields:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "rd-bot-autopilot/v1",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "schemaVersion", "runId", "mode", "projectId", "idea",
    "successCriteria", "status", "limits", "usage", "iterations",
    "pendingApproval", "stopReason", "createdAt", "updatedAt"
  ],
  "properties": {
    "schemaVersion": {"const": "rd-bot-autopilot/v1"},
    "runId": {"type": "string", "pattern": "^autopilot-[a-z0-9-]{1,80}$"},
    "mode": {"enum": ["dry-run", "live-test"]},
    "projectId": {"type": "string", "pattern": "^[0-9]{1,64}$"},
    "idea": {"type": "string", "minLength": 1, "maxLength": 4000},
    "successCriteria": {
      "type": "array", "minItems": 1, "maxItems": 10,
      "items": {"type": "string", "minLength": 1, "maxLength": 1000}
    },
    "status": {
      "enum": [
        "DRAFT", "PLANNING", "READY", "DRY_RUN_COMPLETED",
        "DISPATCH_INTENT", "TASK_BOUND", "SUBMIT_INTENT", "OBSERVING",
        "RETRY_INTENT", "EVALUATION_INTENT", "EVALUATING", "LEARNING",
        "WAITING_HUMAN", "COMPLETED", "BOUNDED_STOP"
      ]
    },
    "limits": {
      "type": "object", "additionalProperties": false,
      "required": ["maxIterations", "maxActiveTasks", "maxRetriesPerTask", "maxElapsedMinutes", "maxTokenBudget"],
      "properties": {
        "maxIterations": {"type": "integer", "minimum": 1, "maximum": 2},
        "maxActiveTasks": {"const": 1},
        "maxRetriesPerTask": {"type": "integer", "minimum": 0, "maximum": 1},
        "maxElapsedMinutes": {"type": "integer", "minimum": 1, "maximum": 120},
        "maxTokenBudget": {"type": "integer", "minimum": 1, "maximum": 200000}
      }
    },
    "usage": {"type": "object"},
    "iterations": {"type": "array", "maxItems": 2},
    "pendingApproval": {"type": ["object", "null"]},
    "stopReason": {"type": "string"},
    "createdAt": {"type": "string"},
    "updatedAt": {"type": "string"}
  }
}
```

- [ ] **Step 4: Implement the minimal state module**

Implement `iteration_state.py` with these public types and guards:

```python
class RunStatus(str, Enum):
    DRAFT = "DRAFT"
    PLANNING = "PLANNING"
    READY = "READY"
    DRY_RUN_COMPLETED = "DRY_RUN_COMPLETED"
    DISPATCH_INTENT = "DISPATCH_INTENT"
    TASK_BOUND = "TASK_BOUND"
    SUBMIT_INTENT = "SUBMIT_INTENT"
    OBSERVING = "OBSERVING"
    RETRY_INTENT = "RETRY_INTENT"
    EVALUATION_INTENT = "EVALUATION_INTENT"
    EVALUATING = "EVALUATING"
    LEARNING = "LEARNING"
    WAITING_HUMAN = "WAITING_HUMAN"
    COMPLETED = "COMPLETED"
    BOUNDED_STOP = "BOUNDED_STOP"


ALLOWED_TRANSITIONS = {
    RunStatus.DRAFT: {RunStatus.PLANNING},
    RunStatus.PLANNING: {RunStatus.READY, RunStatus.BOUNDED_STOP, RunStatus.WAITING_HUMAN},
    RunStatus.READY: {RunStatus.DRY_RUN_COMPLETED, RunStatus.DISPATCH_INTENT},
    RunStatus.DISPATCH_INTENT: {RunStatus.TASK_BOUND, RunStatus.WAITING_HUMAN},
    RunStatus.TASK_BOUND: {RunStatus.SUBMIT_INTENT},
    RunStatus.SUBMIT_INTENT: {RunStatus.OBSERVING, RunStatus.WAITING_HUMAN},
    RunStatus.OBSERVING: {
        RunStatus.RETRY_INTENT, RunStatus.EVALUATION_INTENT,
        RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP,
    },
    RunStatus.RETRY_INTENT: {RunStatus.OBSERVING, RunStatus.WAITING_HUMAN},
    RunStatus.EVALUATION_INTENT: {RunStatus.EVALUATING, RunStatus.WAITING_HUMAN},
    RunStatus.EVALUATING: {RunStatus.LEARNING, RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP},
    RunStatus.LEARNING: {RunStatus.PLANNING, RunStatus.COMPLETED, RunStatus.BOUNDED_STOP, RunStatus.WAITING_HUMAN},
}
```

Implement `ManifestStore` using `manifest.json.tmp`, `flush`, `os.fsync`, and `os.replace`. Every mutating method must load, validate, mutate a copy, update `updatedAt`, validate again, and atomically write. `freeze_plan()` must create exactly one iteration with the marker:

```python
marker = f"[autopilot:{manifest['runId']}:{iteration_no}]"
exact_title = f"{marker} {plan['title'].strip()}"
```

`record_dispatch_intent()` must store the exact request, canonical SHA-256 fingerprint, `phase="DISPATCH_INTENT"`, and `taskId=None` before returning.

- [ ] **Step 5: Add the standalone validator**

Implement `validate_manifest.py` as a thin CLI:

```python
def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest")
    args = parser.parse_args()
    ManifestStore(Path(args.manifest).parent).load()
    print(f"OK: {args.manifest}")
    return 0
```

- [ ] **Step 6: Run focused tests**

Run the Step 2 command again.

Expected: all five tests PASS.

- [ ] **Step 7: Commit the manifest boundary**

```bash
git add .agents/skills/rd-bot-project-autopilot/assets/iteration-manifest.schema.json \
  .agents/skills/rd-bot-project-autopilot/scripts/iteration_state.py \
  .agents/skills/rd-bot-project-autopilot/scripts/validate_manifest.py \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_iteration_state.py
git commit -m "feat(autopilot): add resumable iteration manifest"
```

### Task 3: Protect Artifacts, Secrets, And The Dirty Worktree

**Files:**
- Create: `.agents/skills/rd-bot-project-autopilot/scripts/run_artifacts.py`
- Create: `.agents/skills/rd-bot-project-autopilot/scripts/workspace_guard.py`
- Test: `.agents/skills/rd-bot-project-autopilot/scripts/tests/test_run_artifacts.py`
- Test: `.agents/skills/rd-bot-project-autopilot/scripts/tests/test_workspace_guard.py`

- [ ] **Step 1: Write failing artifact tests**

Cover these exact assertions:

```python
class RunArtifactsTest(unittest.TestCase):
    def test_rejects_path_escape(self):
        with self.assertRaisesRegex(ArtifactError, "outside run directory"):
            RunArtifacts(self.run_dir).write_text("../escape.txt", "bad")

    def test_redacts_secret_keys_and_bearer_values(self):
        value = redact({"authorization": "Bearer abcdefghijklmnop", "safe": "ok"})
        self.assertEqual(value["authorization"], "[REDACTED]")
        self.assertEqual(value["safe"], "ok")

    def test_truncates_large_text(self):
        value = redact({"message": "x" * 120_000})
        self.assertLessEqual(len(value["message"]), 100_020)
        self.assertTrue(value["message"].endswith("[TRUNCATED]"))
```

- [ ] **Step 2: Write failing workspace guard tests**

Initialize a temporary Git repository, commit `clean.txt`, dirty `existing.txt`, capture the snapshot, modify `existing.txt`, and assert `assert_unchanged()` raises. Then create a file under the configured ignored run directory and assert it does not change the snapshot.

- [ ] **Step 3: Verify both tests fail because modules are absent**

Run:

```bash
PYTHONPATH=.agents/skills/rd-bot-project-autopilot \
python3 -m unittest \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_run_artifacts.py \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_workspace_guard.py -v
```

Expected: FAIL with missing `scripts.run_artifacts` and `scripts.workspace_guard`.

- [ ] **Step 4: Implement run-directory containment and redaction**

`RunArtifacts` must resolve every child path and require `child.is_relative_to(run_dir)`. It must write JSON/text atomically and cap excerpts at `100_000` characters. Redact keys matching this case-insensitive set:

```python
SECRET_KEYS = {
    "authorization", "api_key", "apikey", "token", "access_token",
    "refresh_token", "password", "secret", "credential", "cookie",
}
```

Also redact string patterns matching Bearer credentials, `sk-` credentials, and URL user-info. Preserve IDs, hashes, status, timestamps, bounded summaries, and evidence URIs.

- [ ] **Step 5: Implement a read-only worktree fingerprint**

`workspace_guard.py` may execute only these Git commands through argument arrays:

```text
git rev-parse --show-toplevel
git diff --binary --no-ext-diff HEAD
git ls-files --others --exclude-standard -z
```

Hash the tracked diff plus the sorted path/content hash of every untracked, non-ignored file. Persist only the final SHA-256 and path count. Do not invoke `git add`, `commit`, `checkout`, `reset`, `clean`, `stash`, `switch`, or shell execution.

- [ ] **Step 6: Run focused tests**

Run the Step 3 command again.

Expected: all artifact and workspace tests PASS.

- [ ] **Step 7: Commit the safety foundation**

```bash
git add .agents/skills/rd-bot-project-autopilot/scripts/run_artifacts.py \
  .agents/skills/rd-bot-project-autopilot/scripts/workspace_guard.py \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_run_artifacts.py \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_workspace_guard.py
git commit -m "feat(autopilot): protect run artifacts and worktree"
```

### Task 4: Build The Fail-Closed RD-Bot HTTP Client

**Files:**
- Create: `.agents/skills/rd-bot-project-autopilot/scripts/rd_bot_client.py`
- Create: `.agents/skills/rd-bot-project-autopilot/scripts/tests/fake_rd_bot.py`
- Test: `.agents/skills/rd-bot-project-autopilot/scripts/tests/test_rd_bot_client.py`

- [ ] **Step 1: Create a deterministic fake server**

Implement `FakeRdBotServer` with `ThreadingHTTPServer`, an in-memory request ledger, queued responses, and context-manager start/stop. The handler must record method, path, parsed JSON, and return configured status/body/headers. Include a redirect scenario returning `302 Location: https://example.com/`.

- [ ] **Step 2: Write failing transport-safety tests**

Cover these cases:

```python
def test_rejects_non_loopback_origin(self):
    with self.assertRaisesRegex(ClientPolicyError, "loopback"):
        SafeRdBotClient("https://example.com", mode="dry-run", live_flag=False)

def test_dry_run_rejects_every_post(self):
    client = SafeRdBotClient(self.server.base_url, mode="dry-run", live_flag=False)
    with self.assertRaisesRegex(ClientPolicyError, "live-test"):
        client.create_requirement(valid_request())
    self.assertEqual(self.server.requests, [])

def test_live_mode_requires_independent_environment_opt_in(self):
    client = SafeRdBotClient(self.server.base_url, mode="live-test", live_flag=True, environ={})
    with self.assertRaisesRegex(ClientPolicyError, "RD_BOT_AUTOPILOT_LIVE_TEST"):
        client.submit_task("7480000000000000001")

def test_rejects_redirect(self):
    self.server.queue_redirect()
    with self.assertRaisesRegex(ApiTransportError, "redirect"):
        self.client.get_project("7480000000000000000")

def test_rejects_response_larger_than_two_mebibytes(self):
    self.server.queue_json({"data": "x" * (2 * 1024 * 1024)})
    with self.assertRaisesRegex(ApiResponseError, "response too large"):
        self.client.get_project("7480000000000000000")

def test_unknown_path_is_denied_before_network(self):
    with self.assertRaisesRegex(ClientPolicyError, "not allowlisted"):
        self.client.request("POST", "/admin/rd-tasks/1/approve", {})
    self.assertEqual(self.server.requests, [])
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
PYTHONPATH=.agents/skills/rd-bot-project-autopilot \
python3 -m unittest \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_rd_bot_client.py -v
```

Expected: FAIL because `rd_bot_client.py` does not exist.

- [ ] **Step 4: Implement fixed-origin and route policy**

Implement three exception types: `ClientPolicyError`, `ApiTransportError`, and `ApiResponseError`.

Normalize the base URL once. Require HTTP(S), loopback host, no user-info, path, query, or fragment. Build one opener with a redirect handler that raises instead of following redirects.

Use anchored regular expressions for the Fixed Runtime Contracts above. Query strings are allowed only for the list routes and must be constructed internally with `urllib.parse.urlencode`; callers cannot pass raw query strings.

- [ ] **Step 5: Implement request and response bounds**

Read at most `2 * 1024 * 1024 + 1` bytes. Reject an oversized or non-JSON response. Raise `ApiResponseError` for HTTP responses with status/body preview and `ApiTransportError(ambiguous=True)` for timeout, reset, or connection loss where the server may have accepted a POST.

The request ledger callback receives only:

```python
{
    "method": method,
    "path": path,
    "requestSha256": sha256(canonical_json(payload)),
    "status": status,
    "timestamp": utc_now(),
}
```

Never store headers or raw request bodies in the ledger.

- [ ] **Step 6: Implement typed methods and payload validation**

Add exact methods:

```python
get_project(project_id)
list_projects(keyword=None, page=1, page_size=100)
list_tasks(project_id, keyword, page=1, page_size=100)
get_task(task_id)
get_timeline(task_id)
get_execution_overview(task_id)
get_execution_trace(task_id, stage_run_id)
get_retry_preview(task_id)
get_retry_history(task_id)
create_requirement(payload)
submit_task(task_id)
retry_task(task_id, payload)
list_evaluations(keyword, page=1, page_size=100)
create_task_evaluation(task_id, payload)
get_evaluation(run_id)
```

Validate numeric IDs, 1-120 character task titles, non-empty expected result, 2-6 acceptance criteria, `autoExecute is False`, non-negative token override, and material content limits before network access.

- [ ] **Step 7: Run focused tests**

Run the Step 3 command again.

Expected: all client tests PASS and the fake server ledger contains no denied requests.

- [ ] **Step 8: Commit the HTTP boundary**

```bash
git add .agents/skills/rd-bot-project-autopilot/scripts/rd_bot_client.py \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/fake_rd_bot.py \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_rd_bot_client.py
git commit -m "feat(autopilot): add fail-closed RD-Bot client"
```

### Task 5: Implement Dispatch Intent, Exact Reconciliation, And Submit-Once

**Files:**
- Create: `.agents/skills/rd-bot-project-autopilot/scripts/workflow.py`
- Test: `.agents/skills/rd-bot-project-autopilot/scripts/tests/test_workflow.py`

- [ ] **Step 1: Write failing dispatch tests**

Use a fake client and real `ManifestStore` to cover:

1. Manifest reaches `DISPATCH_INTENT` before `create_requirement()` is invoked.
2. Created payload always has `autoExecute=False`.
3. Successful create binds and verifies exactly one numeric `taskId` before submit.
4. Ambiguous create reconciles by `projectId` and the full exact title marker.
5. One exact list match is rejected when task detail has a different title, project, expected result, acceptance criteria, or token budget.
6. Zero or multiple exact matches transition to `WAITING_HUMAN` and never resend.
7. Submit is called exactly once after `SUBMIT_INTENT` is persisted.
8. Ambiguous submit observes detail/timeline; a task beyond `CREATED` resumes observation, while a still-created task becomes `WAITING_HUMAN` without resubmission.

Use this spy assertion in the first test:

```python
def create_requirement(self, payload):
    on_disk = json.loads((self.run_dir / "manifest.json").read_text())
    assert on_disk["status"] == "DISPATCH_INTENT"
    self.create_calls.append(payload)
    return {"taskId": "7480000000000000001", "status": "CREATED", **payload}
```

- [ ] **Step 2: Run tests to verify workflow is missing**

Run:

```bash
PYTHONPATH=.agents/skills/rd-bot-project-autopilot \
python3 -m unittest \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_workflow.py -v
```

Expected: FAIL with missing `scripts.workflow`.

- [ ] **Step 3: Implement plan-to-request conversion**

Implement `AutopilotWorkflow.freeze_plan(plan)` to require:

```python
PLAN_KEYS = {
    "title", "goal", "nonGoals", "expectedResult",
    "acceptanceCriteria", "materials", "whyNow",
}
```

Construct the exact title as `[autopilot:{runId}:{iteration}] {title}`. Add a bounded manual-text material containing the iteration goal and evidence references. Store the exact request and its fingerprint in Manifest before any POST.

- [ ] **Step 4: Implement create reconciliation**

On ambiguous create outcome:

1. Call `list_tasks(projectId, marker)` with bounded read-only retry for at most 15 seconds.
2. Filter locally for `record.title == exactTitle`, `record.projectId == projectId`, and `record.taskType == "REQUIREMENT"`.
3. Fetch detail for each candidate and repeat exact checks.
4. Accept one candidate only.
5. Record zero/multiple as `pendingApproval.type="AMBIGUOUS_TASK_CREATION"`, transition to `WAITING_HUMAN`, and return without another POST.

Do not claim exactly-once creation. Record `reconciled=true` when a task is recovered.

- [ ] **Step 5: Implement submit-once reconciliation**

Persist `SUBMIT_INTENT` with timestamp and request hash, then call submit once. If transport is ambiguous, read detail and timeline. Treat a status other than `CREATED` or a submitted/execution event as evidence that dispatch began. Otherwise transition to `WAITING_HUMAN` with `AMBIGUOUS_TASK_SUBMISSION`.

- [ ] **Step 6: Run focused tests**

Run the Step 2 command again.

Expected: all dispatch, reconciliation, and submit-once tests PASS.

- [ ] **Step 7: Commit the dispatch workflow**

```bash
git add .agents/skills/rd-bot-project-autopilot/scripts/workflow.py \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_workflow.py
git commit -m "feat(autopilot): reconcile task dispatch safely"
```

### Task 6: Observe Tasks, Allow One Retry, And Run Evaluation

**Files:**
- Modify: `.agents/skills/rd-bot-project-autopilot/scripts/workflow.py`
- Modify: `.agents/skills/rd-bot-project-autopilot/scripts/tests/test_workflow.py`

- [ ] **Step 1: Add failing observation tests**

Add tests for:

```python
ACTIVE_TASK_STATES = {
    "CREATED", "MATERIAL_COLLECTING", "MATERIAL_READY", "CONTEXT_BUILDING",
    "CONTEXT_READY", "PLAN_GENERATING", "PLAN_GENERATED", "WAITING_POLICY",
    "SEARCHING", "EXECUTING", "VALIDATING", "PR_CREATING", "COMMITTED",
    "REPORTING", "RECOVERING",
}
```

Assertions:

- `WAITING_APPROVAL`, `FAILED_NEEDS_HUMAN`, `REJECTED`, and `MERGED` become `WAITING_HUMAN`.
- `CANCELLED`, `DEAD_LETTERED`, and `DELETED` become `BOUNDED_STOP`.
- an unknown task status becomes `WAITING_HUMAN`.
- polling stops at the Manifest deadline using an injected clock/sleeper.
- every observation stores the task detail, timeline, execution overview, latest stage per role, and bounded trace references.
- the latest Attempt is used for display without deleting old Attempt records.

- [ ] **Step 2: Add failing retry tests**

Allow automatic retry only when:

```python
task_status == "FAILED_RETRYABLE"
and retries_used == 0
and retry_preview["sourceTaskVersion"] > 0
and retry_preview["failurePhase"] in {
    "RAG", "AGENT_ROLE", "DETERMINISTIC_REVIEW", "AI_REVIEW", "PR_PUBLICATION"
}
```

Persist `RETRY_INTENT` before POST. Use an exact operator note marker. On ambiguous response, reconcile via retry history by task ID, source version, operator note, and Attempt. Zero/multiple matches become `WAITING_HUMAN`; never POST retry twice.

- [ ] **Step 3: Add failing evaluation tests**

For `COMPLETED` tasks:

1. Persist `EVALUATION_INTENT` before POST.
2. Create one task-run evaluation with `judgeProvider="NONE"`, `judgeLimit=0`, `timeoutSeconds=90`, and empty baseline.
3. On ambiguous response, list evaluations with the task title marker and filter for `config.source == "TASK_RUN"` and exact `config.taskId`.
4. Accept exactly one candidate created after the intent timestamp; zero/multiple become `WAITING_HUMAN`.
5. Poll only known statuses and stop on `SUCCEEDED`, `FAILED`, or `CANCELLED`.
6. Store `overallPassed`, counts, metrics hash/preview, timeline, and artifact references.

- [ ] **Step 4: Run the expanded tests and verify failure**

Run the Task 5 test command.

Expected: FAIL on missing observation, retry, and evaluation methods.

- [ ] **Step 5: Implement bounded polling and evidence capture**

Add injected `clock`, `sleep`, `poll_interval_seconds`, and `deadline_epoch`. Check elapsed and token budgets before every poll and before every write action. Unknown states fail closed.

Write evidence only through `RunArtifacts`. Store complete IDs and hashes, but bounded previews. Never store authorization headers, full prompts, private reasoning, or unrestricted trace content.

- [ ] **Step 6: Implement retry and evaluation intents**

Follow the exact rules from Steps 2 and 3. The state transition after a successful retry is `RETRY_INTENT -> OBSERVING`; after evaluation creation it is `EVALUATION_INTENT -> EVALUATING`; after a terminal evaluation it is `EVALUATING -> LEARNING`.

- [ ] **Step 7: Run focused tests**

Run the Task 5 test command again.

Expected: all workflow tests PASS, including unknown-state and timeout cases.

- [ ] **Step 8: Commit observation and evaluation**

```bash
git add .agents/skills/rd-bot-project-autopilot/scripts/workflow.py \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_workflow.py
git commit -m "feat(autopilot): observe retry and evaluate iterations"
```

### Task 7: Add The Deterministic CLI And Resume Commands

**Files:**
- Create: `.agents/skills/rd-bot-project-autopilot/scripts/autopilot.py`
- Test: `.agents/skills/rd-bot-project-autopilot/scripts/tests/test_autopilot_cli.py`

- [ ] **Step 1: Write failing CLI tests**

Invoke `main(argv, environ)` directly and cover:

```text
init
projects
project
freeze-plan
dispatch
observe
retry
evaluate
record-decision
report
verify-workspace
```

Required tests:

- `init` rejects a run directory outside `qa-runs/autopilot`.
- `init --mode live-test` records live mode but makes no POST.
- `dispatch --live-test` without the environment opt-in fails before network.
- `dispatch` in dry-run records `DRY_RUN_COMPLETED` and makes no POST.
- every mutating command in live mode requires both opt-ins.
- resuming an existing Run with an active task observes it instead of creating another.
- `record-decision NEXT_ITERATION` is rejected after iteration 2.
- `record-decision COMPLETE` requires a terminal successful evaluation with evidence.
- `verify-workspace` fails when the before/after fingerprint differs.

- [ ] **Step 2: Run tests and verify the CLI is missing**

Run:

```bash
PYTHONPATH=.agents/skills/rd-bot-project-autopilot \
python3 -m unittest \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_autopilot_cli.py -v
```

Expected: FAIL with missing `scripts.autopilot`.

- [ ] **Step 3: Implement argparse without generic execution hooks**

Each subcommand must map to one named Python function. Do not accept a URL path, HTTP method, shell string, Git command, Python expression, or plugin name from the CLI.

Use these shared arguments:

```text
--run-dir qa-runs/autopilot/{runId}
--base-url http://127.0.0.1:18080
--live-test
```

`--live-test` is accepted only by commands that may POST. It is not inferred from the Manifest.

- [ ] **Step 4: Implement validated plan and decision inputs**

`freeze-plan` reads a JSON file with the exact `PLAN_KEYS`. `record-decision` accepts only:

```json
{
  "decision": "COMPLETE | NEXT_ITERATION | STOP | WAITING_HUMAN",
  "reason": "bounded explanation",
  "evidenceIds": ["task/stage/evaluation IDs already present in Manifest"],
  "nextGoal": "required only for NEXT_ITERATION"
}
```

Reject invented evidence IDs. `report` renders the Manifest into Markdown and labels the result as `GOAL_ACHIEVED`, `BOUNDED_STOP`, or `HUMAN_REQUIRED`.

- [ ] **Step 5: Run focused CLI tests**

Run the Step 2 command again.

Expected: all CLI tests PASS.

- [ ] **Step 6: Commit the CLI**

```bash
git add .agents/skills/rd-bot-project-autopilot/scripts/autopilot.py \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_autopilot_cli.py
git commit -m "feat(autopilot): add guarded iteration CLI"
```

### Task 8: Finish Skill Instructions And Progressive References

**Files:**
- Modify: `.agents/skills/rd-bot-project-autopilot/SKILL.md`
- Regenerate: `.agents/skills/rd-bot-project-autopilot/agents/openai.yaml`
- Create: `.agents/skills/rd-bot-project-autopilot/references/iteration-contract.md`
- Create: `.agents/skills/rd-bot-project-autopilot/references/policy-and-stop-rules.md`
- Create: `.agents/skills/rd-bot-project-autopilot/references/rd-bot-api-map.md`
- Test: `.agents/skills/rd-bot-project-autopilot/scripts/tests/test_skill_contract.py`

- [ ] **Step 1: Write contract tests before expanding documentation**

The test must parse `SKILL.md`, `agents/openai.yaml`, and references and assert:

- frontmatter contains only `name` and `description`;
- Skill body is under 500 lines;
- default prompt includes `$rd-bot-project-autopilot`;
- `dry-run`, both live opt-ins, exact run root, two iterations, one retry, and `WAITING_HUMAN` are documented;
- `/approve`, delete, merge, deploy, raw `curl`, and arbitrary shell are explicitly forbidden;
- all three references are linked directly from `SKILL.md`;
- no secret-like sample values match `sk-[A-Za-z0-9]{16,}` or `Bearer [A-Za-z0-9._-]{16,}`.

- [ ] **Step 2: Run the contract test and verify failure**

Run:

```bash
PYTHONPATH=.agents/skills/rd-bot-project-autopilot \
python3 -m unittest \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_skill_contract.py -v
```

Expected: FAIL because references and final rules are absent.

- [ ] **Step 3: Write the final concise Skill workflow**

Keep `SKILL.md` procedural and under 250 lines. Its imperative workflow must be:

1. Read policy and iteration contract.
2. Resolve the repo root and allocate one contained run directory.
3. Capture the initial workspace fingerprint.
4. Initialize or resume Manifest.
5. Produce a project charter and one bounded plan.
6. Call `freeze-plan`.
7. Stop after `DRY_RUN_COMPLETED` in dry-run.
8. For live-test, require both opt-ins and use `dispatch`, `observe`, optional `retry`, then `evaluate`.
9. Make a decision using only stored evidence IDs.
10. Verify the workspace fingerprint and render the final report.

The Skill must say that user-visible summaries are acceptable, but private chain-of-thought must never be requested or persisted.

- [ ] **Step 4: Write references with exact contracts**

`iteration-contract.md` documents Manifest fields, state transitions, marker/fingerprint rules, crash points, and resume behavior.

`policy-and-stop-rules.md` documents the double opt-in, endpoint allowlist, status mapping, hard limits, no-Git-mutation rule, redaction, and human gates.

`rd-bot-api-map.md` documents request/response fields and source locations for the existing project, task, execution-overview, trace, retry, and evaluation APIs. It must state that backend code is the source of truth and unknown fields/states fail closed.

- [ ] **Step 5: Regenerate OpenAI metadata from the final Skill**

Run:

```bash
python3 /Users/wish233/.codex/skills/.system/skill-creator/scripts/generate_openai_yaml.py \
  .agents/skills/rd-bot-project-autopilot \
  --interface 'display_name=RD-Bot Project Autopilot' \
  --interface 'short_description=Run bounded evidence-driven RD-Bot iterations' \
  --interface 'default_prompt=Use $rd-bot-project-autopilot to turn this idea into a bounded RD-Bot project iteration.'
```

- [ ] **Step 6: Validate metadata and contract**

Run:

```bash
python3 /Users/wish233/.codex/skills/.system/skill-creator/scripts/quick_validate.py \
  .agents/skills/rd-bot-project-autopilot
PYTHONPATH=.agents/skills/rd-bot-project-autopilot \
python3 -m unittest \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_skill_contract.py -v
```

Expected: `Skill is valid!` and all contract tests PASS.

- [ ] **Step 7: Commit the final Skill contract**

```bash
git add .agents/skills/rd-bot-project-autopilot/SKILL.md \
  .agents/skills/rd-bot-project-autopilot/agents/openai.yaml \
  .agents/skills/rd-bot-project-autopilot/references \
  .agents/skills/rd-bot-project-autopilot/scripts/tests/test_skill_contract.py
git commit -m "docs(autopilot): define safe project iteration workflow"
```

### Task 9: Add Crash-Resume And HTTP Contract Coverage

**Files:**
- Create: `.agents/skills/rd-bot-project-autopilot/scripts/tests/test_http_contract.py`
- Modify as failures require: `.agents/skills/rd-bot-project-autopilot/scripts/iteration_state.py`
- Modify as failures require: `.agents/skills/rd-bot-project-autopilot/scripts/rd_bot_client.py`
- Modify as failures require: `.agents/skills/rd-bot-project-autopilot/scripts/workflow.py`

- [ ] **Step 1: Add the creation crash matrix**

Use the fake HTTP server to simulate these exact checkpoints:

```text
before POST
during POST connection reset
after server create before response
after response before task bind
during exact-marker reconciliation
```

Assert that the request ledger contains at most one create POST, recovery accepts one verified exact match, and zero/multiple matches stop human.

- [ ] **Step 2: Add the submit/retry/evaluation crash matrix**

For each write action, simulate connection reset after server acceptance. Assert that the workflow reconciles through task state/timeline, retry history, or evaluation list and never sends a second POST automatically.

- [ ] **Step 3: Add hostile transport cases**

Test malformed JSON, oversized body, redirect, non-loopback origin, unknown endpoint, unknown task state, unknown evaluation state, timeout, HTTP 409, and HTTP 500. Assert fail-closed state and redacted bounded artifacts.

- [ ] **Step 4: Run all Skill unit/contract tests**

Run:

```bash
PYTHONPATH=.agents/skills/rd-bot-project-autopilot \
python3 -m unittest discover \
  -s .agents/skills/rd-bot-project-autopilot/scripts/tests -v
```

Expected: all tests PASS with no network access outside the local fake server.

- [ ] **Step 5: Scan for forbidden capabilities and secret samples**

Run:

```bash
rg -n 'subprocess.*(add|commit|checkout|reset|clean|stash|switch)|/approve|DeleteMapping|merge|deploy|eval\(|exec\(|shell=True' \
  .agents/skills/rd-bot-project-autopilot
rg -n '(sk-[A-Za-z0-9]{16,}|Bearer [A-Za-z0-9._-]{16,})' \
  .agents/skills/rd-bot-project-autopilot
```

Expected: the first command finds only explicit documentation/tests asserting forbidden actions, not executable paths; the second command finds no secret-like values.

- [ ] **Step 6: Commit contract hardening**

```bash
git add .agents/skills/rd-bot-project-autopilot
git commit -m "test(autopilot): cover crash recovery and safety policy"
```

### Task 10: Perform A Real Read-Only Dry Run And Forward Test

**Files:**
- Create: `docs/qa/2026-07-27-rd-bot-project-autopilot-dry-run-acceptance.md`
- Runtime evidence: `qa-runs/autopilot/{runId}/` (ignored)

- [ ] **Step 1: Confirm the local service and choose one existing project using GET only**

Run:

```bash
export RD_BOT_BASE_URL=http://127.0.0.1:18080
unset RD_BOT_AUTOPILOT_LIVE_TEST
python3 .agents/skills/rd-bot-project-autopilot/scripts/autopilot.py \
  projects --base-url "$RD_BOT_BASE_URL"
```

Expected: a bounded JSON list of existing projects and no POST. Select one disposable test project's numeric ID from that output and export it as `RD_BOT_AUTOPILOT_PROJECT_ID`. The next command must reject an unset or non-numeric value.

- [ ] **Step 2: Capture request counts before the dry run**

Run:

```bash
export RUN_ID="autopilot-dry-$(date -u +%Y%m%d%H%M%S)"
export RUN_DIR="qa-runs/autopilot/$RUN_ID"
python3 .agents/skills/rd-bot-project-autopilot/scripts/autopilot.py init \
  --run-dir "$RUN_DIR" \
  --base-url "$RD_BOT_BASE_URL" \
  --mode dry-run \
  --project-id "$RD_BOT_AUTOPILOT_PROJECT_ID" \
  --idea "Add a bounded admin status summary" \
  --success-criterion "A focused automated test verifies the status summary"
```

Expected: Manifest status `DRAFT`, a workspace-before hash, and an empty request ledger.

- [ ] **Step 3: Freeze one plan and finish dry-run**

Write `$RUN_DIR/iterations/01-plan-input.json` with this exact dry-run fixture:

```json
{
  "title": "Add bounded admin status summary",
  "goal": "Expose one concise status summary using existing project data",
  "nonGoals": ["No database migration", "No deployment"],
  "expectedResult": "A bounded status summary with focused automated coverage",
  "acceptanceCriteria": [
    "The summary uses existing persisted data",
    "A focused automated test passes"
  ],
  "materials": [],
  "whyNow": "This is a small reversible validation task"
}
```

Then run:

```bash
python3 .agents/skills/rd-bot-project-autopilot/scripts/autopilot.py \
  freeze-plan --run-dir "$RUN_DIR" --plan-file "$RUN_DIR/iterations/01-plan-input.json"
python3 .agents/skills/rd-bot-project-autopilot/scripts/autopilot.py \
  dispatch --run-dir "$RUN_DIR"
```

Expected: Manifest reaches `DRY_RUN_COMPLETED`; the request ledger contains no POST.

- [ ] **Step 4: Verify the workspace and artifacts**

Run the validator and `verify-workspace`. Confirm only the ignored Run directory changed and artifacts contain no secrets or full prompts.

- [ ] **Step 5: Forward-test discovery with a fresh Codex worker**

Give a fresh worker only this request:

```text
Use $rd-bot-project-autopilot at .agents/skills/rd-bot-project-autopilot to turn
"add a bounded admin status summary" into a dry-run plan for the selected RD-Bot project.
Do not create a live task.
```

Success requires correct automatic or explicit-path Skill loading, zero POSTs, a valid Manifest, and a bounded plan. If implicit discovery is unavailable, record that explicit path invocation is the supported prototype fallback; do not install globally.

- [ ] **Step 6: Write the dry-run QA report**

Record commands, selected project ID, Run ID, Manifest terminal state, request ledger summary, workspace before/after hashes, test totals, Skill discovery result, and artifact paths. Do not include secrets or full prompts.

- [ ] **Step 7: Commit only the reviewable QA report**

```bash
git add -f docs/qa/2026-07-27-rd-bot-project-autopilot-dry-run-acceptance.md
git commit -m "test(autopilot): record read-only dry-run acceptance"
```

### Task 11: Run One Disposable Bounded Live Test

**Files:**
- Create: `docs/qa/2026-07-27-rd-bot-project-autopilot-live-test-acceptance.md`
- Runtime evidence: `qa-runs/autopilot/{runId}/` (ignored)

- [ ] **Step 1: Stop for explicit operator inputs**

Before any POST, obtain confirmation of:

```text
RD_BOT_AUTOPILOT_PROJECT_ID is the numeric ID selected and approved in Task 10
RD_BOT_BASE_URL=http://127.0.0.1:18080
the project repository/branch is disposable or isolated
PR creation is acceptable; merge and deployment remain forbidden
```

Do not continue when any input is missing.

- [ ] **Step 2: Set the independent live opt-in**

Run only after Step 1:

```bash
export RD_BOT_AUTOPILOT_LIVE_TEST=1
```

Pass `--live-test` separately to every write-capable CLI command. Neither opt-in may imply the other.

- [ ] **Step 3: Execute one small first iteration**

Create with `autoExecute=false`, bind the task, submit once, and observe it. Confirm the request ledger contains only the allowlisted create and submit POSTs.

- [ ] **Step 4: Exercise evaluation and bounded decision**

If the task reaches `COMPLETED`, create one task-run evaluation and observe it. If it reaches `FAILED_RETRYABLE`, allow one structured retry only when the preview passes the fixed policy. Any other failure or approval state stops the Run.

- [ ] **Step 5: Do not force a second iteration**

Run iteration 2 only when the first evaluation genuinely leaves an unmet success criterion and the Manager Agent produces a non-duplicate, smaller requirement with evidence IDs from iteration 1. Otherwise finish after iteration 1.

- [ ] **Step 6: Verify hard boundaries**

Confirm:

```text
iterations <= 2
active tasks <= 1
retries per task <= 1
no /approve, DELETE, merge, deploy, evaluation retry, or arbitrary route
workspace fingerprint unchanged
no secrets/full prompts/private reasoning in artifacts
```

- [ ] **Step 7: Write the live-test QA report**

Record task IDs, stage Run IDs, Attempts, task statuses, retry checkpoint if any, evaluation Run ID/result, PR or patch reference, request ledger counts, stop reason, workspace hashes, and unresolved blockers.

- [ ] **Step 8: Commit only the QA report**

```bash
git add -f docs/qa/2026-07-27-rd-bot-project-autopilot-live-test-acceptance.md
git commit -m "test(autopilot): record bounded live acceptance"
```

### Task 12: Final Review And Prototype Handoff

**Files:**
- Modify only if a verified issue requires it: `.agents/skills/rd-bot-project-autopilot/**`
- Review: `docs/qa/2026-07-27-rd-bot-project-autopilot-*-acceptance.md`

- [ ] **Step 1: Run the complete verification suite**

```bash
python3 /Users/wish233/.codex/skills/.system/skill-creator/scripts/quick_validate.py \
  .agents/skills/rd-bot-project-autopilot
PYTHONPATH=.agents/skills/rd-bot-project-autopilot \
python3 -m unittest discover \
  -s .agents/skills/rd-bot-project-autopilot/scripts/tests -v
git diff --check
```

Expected: valid Skill, all tests PASS, and no whitespace errors.

- [ ] **Step 2: Review the full implementation against the approved spec**

Map every design acceptance criterion to a test or live artifact. Explicitly verify that task completion alone never produces `GOAL_ACHIEVED`; a successful evaluation and cited success criteria are required.

- [ ] **Step 3: Request a code review**

Use the `requesting-code-review` workflow. Findings must lead, ordered by severity, with exact file/line references. Fix all correctness and safety findings before proceeding.

- [ ] **Step 4: Re-run tests after review fixes**

Run the Step 1 commands again. Expected: all checks remain green.

- [ ] **Step 5: Confirm commit and worktree scope**

```bash
git log --oneline --decorate -12
git status --short
git diff --name-only HEAD~12..HEAD
```

Expected: Autopilot commits contain only the planned Skill and QA reports; pre-existing unrelated worktree changes remain present and untouched.

- [ ] **Step 6: Produce the handoff summary**

Report:

- Skill path and explicit invocation fallback;
- dry-run and live-test commands;
- test totals and real Run IDs;
- hard safety boundaries;
- whether the prototype is ready to remain external or has evidence to justify a future native `AutonomousProjectRun` design.

Do not install the Skill globally or register it in RD-Bot's role Skill registry during this plan.

## Self-Review Checklist

- [ ] Every approved design requirement maps to a Task 1-12 step.
- [ ] No task modifies Java, SQL, frontend, or existing dirty files.
- [ ] Every write is covered by double opt-in and an exact action method.
- [ ] Task creation, submit, retry, and evaluation each persist intent before POST and reconcile ambiguous outcomes without automatic resend.
- [ ] Dry-run is read-only in executable tests, not only documentation.
- [ ] Unknown task/evaluation states fail closed.
- [ ] Repo-local Skill discovery and explicit fallback are tested.
- [ ] Runtime artifacts stay ignored; reviewable QA Markdown is committed separately.
- [ ] The plan contains no unfinished implementation marker or unconstrained command hook.
