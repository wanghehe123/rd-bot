# RD-Bot Project Autopilot Provisioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Let the repo-scoped Skill turn a confirmed idea into a new private personal GitHub repository, a bound RD-Bot knowledge base/project, generated project documents, and one explicitly submitted RD-Bot requirement.

**Architecture:** Keep the existing host-side Skill and task lifecycle intact. Add a pure canonical Provision Plan, a fixed-command gh adapter, versioned Manifest v2 provisioning transitions, and a narrow RD-Bot client extension. Every remote write is intent-first, detail-verified, and resumable without blind retries.

**Tech Stack:** Python 3 standard library, gh CLI, existing RD-Bot Admin HTTP API, unittest, fcntl locks, JSON Schema.

---

### Task 1: Add Canonical Provision Plan Primitives

**Files:**
- Create: .agents/skills/rd-bot-project-autopilot/scripts/provisioning.py
- Create: .agents/skills/rd-bot-project-autopilot/scripts/tests/test_provisioning.py

- [ ] **Step 1: Write the failing pure-plan tests**

~~~python
from scripts.provisioning import ProvisionPlanError, build_provision_plan, plan_digest


def test_plan_is_canonical_private_and_marked() -> None:
    plan = build_provision_plan(
        run_id="autopilot-web-123",
        idea="Build a habit tracker web page",
        success_criteria=["The page can add a habit", "Focused tests pass"],
        github_owner="alice",
    )
    assert plan["repository"]["owner"] == "alice"
    assert plan["repository"]["visibility"] == "private"
    assert plan["repository"]["autoInit"] is True
    assert len(plan["documents"]) == 2
    assert plan_digest(plan) == plan_digest(dict(plan))


def test_plan_rejects_public_owner_mismatch_and_external_sources() -> None:
    with pytest.raises(ProvisionPlanError):
        build_provision_plan("autopilot-web-123", "idea", ["passes"], "alice", visibility="public")
~~~

- [ ] **Step 2: Run the test to verify it fails**

Run: PYTHONPYCACHEPREFIX=/tmp/rd-bot-pycache PYTHONPATH=. python3 -m unittest scripts.tests.test_provisioning -v

Expected: import failure because scripts.provisioning does not exist.

- [ ] **Step 3: Implement the deterministic plan**

~~~python
PLAN_SCHEMA_VERSION = "rd-bot-autopilot-provision-plan/v1"


def canonical_json(value: Mapping[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def plan_digest(plan: Mapping[str, Any]) -> str:
    return hashlib.sha256(canonical_json(plan).encode("utf-8")).hexdigest()


def build_provision_plan(
    run_id: str, idea: str, success_criteria: Sequence[str], github_owner: str,
    *, repo_name: str | None = None,
) -> dict[str, Any]:
    normalized = validate_provision_inputs(run_id, idea, success_criteria, github_owner, repo_name)
    plan = build_private_repository_plan(normalized)
    plan["documents"] = render_generated_documents(plan)
    return attach_plan_markers_and_hashes(plan)
~~~

Generate a run-suffixed GitHub slug, a private autoInit repository, a marker, RD-Bot project fields, a first requirement plan, and exactly project-charter.md plus delivery-brief.md. Validate owner, slug, name, criteria and SHA-256 each UTF-8 Markdown document. Only permit source user_idea or autopilot_generated; redact and reject credential-shaped input.

- [ ] **Step 4: Run the test to verify it passes**

Run the Step 2 command. Expected: all Provision Plan tests pass with no network or subprocess call.

- [ ] **Step 5: Commit**

~~~bash
git add .agents/skills/rd-bot-project-autopilot/scripts/provisioning.py .agents/skills/rd-bot-project-autopilot/scripts/tests/test_provisioning.py
git commit -m "feat(autopilot): add canonical provision plan"
~~~

### Task 2: Add a Fixed GitHub CLI Adapter

**Files:**
- Create: .agents/skills/rd-bot-project-autopilot/scripts/github_client.py
- Create: .agents/skills/rd-bot-project-autopilot/scripts/tests/test_github_client.py

- [ ] **Step 1: Write failing command-policy tests**

~~~python
def test_creates_only_a_private_auto_initialized_personal_repo() -> None:
    runner = RecordingRunner([
        completed(["gh", "auth", "status"], 0, "", ""),
        completed(["gh", "api", "user"], 0, '{"login":"alice"}', ""),
        completed([], 0, '{"full_name":"alice/habit-tracker","private":true,"owner":{"login":"alice"},"default_branch":"main"}', ""),
    ])
    client = GitHubClient(runner=runner)
    repo = client.create_private_repository(
        owner="alice", name="habit-tracker", description="[autopilot:run:github:abc]"
    )
    assert repo["private"] is True
    assert [call[:4] for call in runner.calls] == [
        ["gh", "auth", "status"], ["gh", "api", "user"], ["gh", "api", "--method", "POST"],
    ]


def test_refuses_org_public_arbitrary_args_and_ambiguous_timeout() -> None:
    with pytest.raises(GitHubPolicyError):
        GitHubClient(runner=RecordingRunner()).create_private_repository(
            owner="other-org", name="x", description="marker"
        )
~~~

- [ ] **Step 2: Run RED**

Run: PYTHONPYCACHEPREFIX=/tmp/rd-bot-pycache PYTHONPATH=. python3 -m unittest scripts.tests.test_github_client -v

Expected: import failure because scripts.github_client does not exist.

- [ ] **Step 3: Implement the only allowed subprocess calls**

~~~python
class GitHubClient:
    def authenticated_user(self) -> str:
        self._run(["gh", "auth", "status"], write=False)
        return require_login(self._run(["gh", "api", "user"], write=False))

    def create_private_repository(self, *, owner: str, name: str, description: str) -> Mapping[str, Any]:
        require_authenticated_owner(owner, self.authenticated_user())
        return self._run(private_create_argv(name, description), write=True)

    def get_repository(self, owner: str, name: str) -> Mapping[str, Any]:
        return self._run(["gh", "api", f"/repos/{owner}/{name}"], write=False)

    def _run(self, argv: list[str], *, write: bool) -> Mapping[str, Any]:
        # subprocess.run(argv, shell=False, timeout=20, capture_output=True, text=True)
        result = self._runner(argv, timeout=self.timeout_seconds)
        return parse_bounded_json(result, write=write)
~~~

Allow only gh auth status, gh api user, gh api --method POST /user/repos, and gh api /repos/{owner}/{name}. The create command is fixed to private, auto_init=true, and a bounded marker description. Enforce authenticated owner equality, default branch, private visibility, JSON/output bounds, redaction, and command timeout. Only a POST timeout/OSError is ambiguous; known API failures are not retried.

- [ ] **Step 4: Run GREEN**

Run the Step 2 command. Expected: tests prove no shell, clone, push, token handling, or generic gh passthrough exists.

- [ ] **Step 5: Commit**

~~~bash
git add .agents/skills/rd-bot-project-autopilot/scripts/github_client.py .agents/skills/rd-bot-project-autopilot/scripts/tests/test_github_client.py
git commit -m "feat(autopilot): add guarded GitHub provisioning client"
~~~

### Task 3: Add Manifest V2 Provisioning State and Resume Guards

**Files:**
- Modify: .agents/skills/rd-bot-project-autopilot/scripts/iteration_state.py
- Modify: .agents/skills/rd-bot-project-autopilot/assets/iteration-manifest.schema.json
- Modify: .agents/skills/rd-bot-project-autopilot/scripts/tests/test_iteration_state.py

- [ ] **Step 1: Write failing v2 state tests**

~~~python
def test_v2_requires_exact_digest_before_live_intent(self) -> None:
    store = ManifestStore(self.run_dir)
    store.initialize(create_provision_manifest("autopilot-web-123", "live-provision", "idea", ["passes"]))
    digest = store.freeze_provision_plan(plan)
    with self.assertRaisesRegex(ManifestError, "digest"):
        store.confirm_provision("0" * 64)
    store.confirm_provision(digest)
    store.record_provision_intent("github", {"owner": "alice", "name": "habit-tracker"})
    self.assertEqual(store.load()["status"], "GH_INTENT")


def test_ambiguous_v2_write_cannot_be_resent(self) -> None:
    store.record_provision_intent("github", request)
    store.set_waiting_human("AMBIGUOUS_GITHUB_CREATE", {"resumeStatus": "GH_INTENT"})
    with self.assertRaises(ManifestError):
        store.record_provision_intent("github", request)
~~~

- [ ] **Step 2: Run RED**

Run: PYTHONPYCACHEPREFIX=/tmp/rd-bot-pycache PYTHONPATH=. python3 -m unittest scripts.tests.test_iteration_state -v

Expected: missing v2 manifest constructor and provisioning methods.

- [ ] **Step 3: Implement v2 while preserving v1**

~~~python
class RunStatus(str, Enum):
    PLAN_READY = "PLAN_READY"
    LIVE_CONFIRMED = "LIVE_CONFIRMED"
    GH_INTENT = "GH_INTENT"
    GH_BOUND = "GH_BOUND"
    KB_INTENT = "KB_INTENT"
    KB_BOUND = "KB_BOUND"
    SOURCES_INTENT = "SOURCES_INTENT"
    SOURCES_BOUND = "SOURCES_BOUND"
    RD_PROJECT_INTENT = "RD_PROJECT_INTENT"
    RD_PROJECT_BOUND = "RD_PROJECT_BOUND"


def create_provision_manifest(run_id: str, mode: str, idea: str, success_criteria: list[str]) -> dict[str, Any]:
    return new_v2_manifest(run_id, mode, idea, success_criteria)


class ManifestStore:
    def freeze_provision_plan(self, plan: Mapping[str, Any]) -> str:
        return self._freeze_validated_v2_plan(validate_provision_plan(plan))

    def confirm_provision(self, digest: str) -> dict[str, Any]:
        return self._confirm_exact_v2_digest(digest)

    def record_provision_intent(self, resource: str, request: Mapping[str, Any]) -> dict[str, Any]:
        return self._record_v2_resource_intent(resource, canonical_request(request))

    def bind_provision_resource(self, resource: str, resource_view: Mapping[str, Any]) -> dict[str, Any]:
        return self._bind_verified_v2_resource(resource, redact(dict(resource_view)))
~~~

Use strict rd-bot-autopilot/v2 nested provisioning plan/resources/receipt fields. Keep the v1 validator and mode behavior unchanged. Persist every intent with the existing atomic write and flock. WAITING_HUMAN only resumes to the exact safe status saved in the approval object; it never enables a resend.

- [ ] **Step 4: Update Schema and run GREEN**

Add JSON Schema oneOf shapes for v1 and v2, then rerun Step 2. Expected: legacy and v2 state tests pass.

- [ ] **Step 5: Commit**

~~~bash
git add .agents/skills/rd-bot-project-autopilot/scripts/iteration_state.py .agents/skills/rd-bot-project-autopilot/assets/iteration-manifest.schema.json .agents/skills/rd-bot-project-autopilot/scripts/tests/test_iteration_state.py
git commit -m "feat(autopilot): add resumable provisioning manifest"
~~~

### Task 4: Extend the RD-Bot Client With Exact Project and Knowledge Routes

**Files:**
- Modify: .agents/skills/rd-bot-project-autopilot/scripts/rd_bot_client.py
- Modify: .agents/skills/rd-bot-project-autopilot/scripts/tests/test_rd_bot_client.py
- Modify: .agents/skills/rd-bot-project-autopilot/scripts/tests/test_http_contract.py

- [ ] **Step 1: Write failing typed-route tests**

~~~python
def test_live_provision_allows_only_exact_project_and_knowledge_payloads(self) -> None:
    client = SafeRdBotClient(
        server.base_url, mode="live-provision", live_flag=True,
        environ={"RD_BOT_AUTOPILOT_LIVE_PROVISION": "1"},
    )
    server.queue_json({"id": "kb-1", "name": "Habit tracker"})
    client.create_knowledge_base({"name": "Habit tracker", "description": "[autopilot:run:kb:abc]"})
    self.assertEqual(server.requests[-1]["path"], "/knowledge-base")


def test_rejects_external_document_source_unknown_project_field_and_old_live_opt_in(self) -> None:
    with self.assertRaises(ClientPolicyError):
        client.write_knowledge_document("kb-1", {"sourceUri": "https://example.com"})
~~~

- [ ] **Step 2: Run RED**

Run: PYTHONPYCACHEPREFIX=/tmp/rd-bot-pycache PYTHONPATH=. python3 -m unittest scripts.tests.test_rd_bot_client scripts.tests.test_http_contract -v

Expected: missing provisioning route methods and mode.

- [ ] **Step 3: Implement exact allowlists**

~~~python
def create_knowledge_base(self, payload: Mapping[str, Any]) -> Any:
    return self.request("POST", "/knowledge-base", dict(payload))

def write_knowledge_document(self, knowledge_base_id: str, payload: Mapping[str, Any]) -> Any:
    return self.request("POST", f"/knowledge-base/{safe_id(knowledge_base_id)}/docs/write", dict(payload))

def get_knowledge_base(self, knowledge_base_id: str) -> Any:
    return self.request("GET", f"/knowledge-base/{safe_id(knowledge_base_id)}")

def list_knowledge_documents(self, knowledge_base_id: str) -> Any:
    return self.request("GET", f"/knowledge-base/{safe_id(knowledge_base_id)}/docs")

def create_project(self, payload: Mapping[str, Any]) -> Any:
    return self.request("POST", "/admin/projects", dict(payload))
~~~

Add only the design-specified GET/POST routes. Validate exact payload keys, Markdown mime type, bounded generated content, STRUCTURE_AWARE, private GitHub URL/owner/name consistency, default branch, enabled project and knowledge base ID. live-provision requires both the CLI flag and RD_BOT_AUTOPILOT_LIVE_PROVISION=1; preserve the old live-test gate.

- [ ] **Step 4: Run GREEN and Commit**

Run the Step 2 command, then:

~~~bash
git add .agents/skills/rd-bot-project-autopilot/scripts/rd_bot_client.py .agents/skills/rd-bot-project-autopilot/scripts/tests/test_rd_bot_client.py .agents/skills/rd-bot-project-autopilot/scripts/tests/test_http_contract.py
git commit -m "feat(autopilot): allowlist project provisioning APIs"
~~~

### Task 5: Implement Provisioning Orchestration and Reconciliation

**Files:**
- Modify: .agents/skills/rd-bot-project-autopilot/scripts/workflow.py
- Create: .agents/skills/rd-bot-project-autopilot/scripts/tests/test_provisioning_workflow.py

- [ ] **Step 1: Write failing workflow tests with spies**

~~~python
def test_confirmed_live_provision_records_intent_before_each_write_and_binds_resources() -> None:
    workflow = ProvisioningWorkflow(store, rd_client, github_client)
    result = workflow.provision()
    assert result["status"] == RunStatus.READY.value
    assert calls == ["github.create", "rd.create_kb", "rd.write_doc", "rd.write_doc", "rd.create_project"]
    assert result["projectId"] == "7480000000000000000"


def test_github_timeout_reconciles_one_exact_repo_without_resend() -> None:
    github_client.create_error = GitHubTransportError("timeout", ambiguous=True)
    github_client.exact_repo = matching_repo
    result = workflow.provision()
    assert github_client.create_calls == 1
    assert result["status"] == RunStatus.GH_BOUND.value
~~~

- [ ] **Step 2: Run RED**

Run: PYTHONPYCACHEPREFIX=/tmp/rd-bot-pycache PYTHONPATH=. python3 -m unittest scripts.tests.test_provisioning_workflow -v

Expected: missing ProvisioningWorkflow.

- [ ] **Step 3: Implement the narrow workflow**

~~~python
class ProvisioningWorkflow:
    def __init__(self, store: ManifestStore, rd_client: Any, github_client: Any) -> None:
        self.store, self.rd_client, self.github_client = store, rd_client, github_client

    def provision(self) -> dict[str, Any]:
        return self._advance_from_status(self.store.load()["status"])

    def resume_provision(self) -> dict[str, Any]:
        return self._reconcile_only(self.store.load()["status"])

    def _reconcile_github(self) -> dict[str, Any]:
        return bind_one_or_wait(self.store, "github", self.github_client.get_repository)

    def _reconcile_knowledge_base(self) -> dict[str, Any]:
        return bind_one_or_wait(self.store, "knowledgeBase", self.rd_client.get_knowledge_base)

    def _reconcile_documents(self) -> dict[str, Any]:
        return bind_document_hashes_or_wait(self.store, self.rd_client.list_knowledge_documents)

    def _reconcile_project(self) -> dict[str, Any]:
        return bind_one_or_wait(self.store, "project", self.rd_client.get_project)
~~~

For every create, record intent before the remote call, retain only safe IDs, GET/read it back, and compare exact marker/fingerprint/immutable fields. Ambiguous outcomes reconcile exactly one candidate only; zero, multiple or mismatch becomes resumable WAITING_HUMAN. After project binding, continue through existing AutopilotWorkflow so requirement creation remains autoExecute=false followed by exactly one submit.

- [ ] **Step 4: Run GREEN and Commit**

Run: PYTHONPYCACHEPREFIX=/tmp/rd-bot-pycache PYTHONPATH=. python3 -m unittest scripts.tests.test_provisioning_workflow scripts.tests.test_workflow -v

Then:

~~~bash
git add .agents/skills/rd-bot-project-autopilot/scripts/workflow.py .agents/skills/rd-bot-project-autopilot/scripts/tests/test_provisioning_workflow.py
git commit -m "feat(autopilot): provision project before task dispatch"
~~~

### Task 6: Expose Fixed Provisioning CLI Commands

**Files:**
- Modify: .agents/skills/rd-bot-project-autopilot/scripts/autopilot.py
- Modify: .agents/skills/rd-bot-project-autopilot/scripts/tests/test_autopilot_cli.py

- [ ] **Step 1: Write failing CLI tests**

~~~python
def test_provision_commands_require_digest_and_never_write_in_dry_run(self) -> None:
    self.assertEqual(main(["--run-dir", run_dir, "provision-run"]), 2)
    self.assertEqual(main(["--run-dir", run_dir, "provision-run", "--live-provision"]), 2)


def test_provision_init_requires_no_existing_project_id(self) -> None:
    self.assertEqual(main([
        "--run-dir", run_dir, "provision-init", "--run-id", "autopilot-web-123",
        "--idea", "Build a page", "--success-criterion", "tests pass",
    ]), 0)
~~~

- [ ] **Step 2: Run RED**

Run: PYTHONPYCACHEPREFIX=/tmp/rd-bot-pycache PYTHONPATH=. python3 -m unittest scripts.tests.test_autopilot_cli -v

Expected: parser rejects provision-* commands.

- [ ] **Step 3: Implement fixed commands**

~~~text
provision-init --run-id --idea --success-criterion [--mode dry-run|live-provision]
provision-plan --plan-file
provision-confirm --plan-sha256
provision-run [--live-provision]
provision-resume [--live-provision]
~~~

Make provision-plan validate/persist canonical JSON and print its digest without a remote write. Make provision-run require --live-provision, persisted exact confirmation and the environment opt-in before it creates a write-capable client. Do not accept arbitrary gh, URL, or HTTP arguments.

- [ ] **Step 4: Run GREEN and Commit**

Run the Step 2 command, then:

~~~bash
git add .agents/skills/rd-bot-project-autopilot/scripts/autopilot.py .agents/skills/rd-bot-project-autopilot/scripts/tests/test_autopilot_cli.py
git commit -m "feat(autopilot): expose guarded project provisioning CLI"
~~~

### Task 7: Update Skill Contract and Verify

**Files:**
- Modify: .agents/skills/rd-bot-project-autopilot/SKILL.md
- Modify: .agents/skills/rd-bot-project-autopilot/agents/openai.yaml
- Modify: .agents/skills/rd-bot-project-autopilot/references/iteration-contract.md
- Modify: .agents/skills/rd-bot-project-autopilot/references/policy-and-stop-rules.md
- Modify: .agents/skills/rd-bot-project-autopilot/references/rd-bot-api-map.md
- Modify: .agents/skills/rd-bot-project-autopilot/scripts/tests/test_skill_contract.py
- Create: docs/qa/2026-07-27-rd-bot-project-autopilot-provisioning-acceptance.md

- [ ] **Step 1: Write failing Skill-contract assertions**

~~~python
def test_skill_requires_digest_confirmation_for_actual_project_provisioning(self) -> None:
    skill = SKILL.read_text(encoding="utf-8")
    self.assertIn("--live-provision", skill)
    self.assertIn("--confirm-plan-sha256", skill)
    self.assertIn("private personal GitHub repository", skill)
~~~

- [ ] **Step 2: Run RED**

Run: PYTHONPYCACHEPREFIX=/tmp/rd-bot-pycache PYTHONPATH=. python3 -m unittest scripts.tests.test_skill_contract -v

Expected: documentation lacks the provision command contract.

- [ ] **Step 3: Document and run full verification**

Document the exact two-phase interaction, private-personal boundary, marker reconciliation, generated-document provenance, resume, cleanup report, and all prohibited actions. Update API maps and schema references without adding a generic shell/HTTP fallback.

Run:

~~~bash
PYTHONPYCACHEPREFIX=/tmp/rd-bot-pycache PYTHONPATH=. python3 -m unittest discover -s scripts/tests -v
PYTHONPYCACHEPREFIX=/tmp/rd-bot-pycache PYTHONPATH=. python3 -m compileall -q scripts
python3 -m json.tool assets/iteration-manifest.schema.json >/dev/null
git diff --check
~~~

Then run one actual dry-run only. Do not create a remote repository without a user-supplied task and final digest confirmation.

- [ ] **Step 4: Commit docs and QA evidence**

~~~bash
git add -f docs/qa/2026-07-27-rd-bot-project-autopilot-provisioning-acceptance.md
git add .agents/skills/rd-bot-project-autopilot/SKILL.md .agents/skills/rd-bot-project-autopilot/agents/openai.yaml .agents/skills/rd-bot-project-autopilot/references .agents/skills/rd-bot-project-autopilot/scripts/tests/test_skill_contract.py
git commit -m "docs(autopilot): document guarded project provisioning"
~~~
