from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.iteration_state import ManifestStore, RunStatus
from scripts.autopilot import main


PROJECT_ID = "7480000000000000000"


def git(root: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=root, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def plan() -> dict[str, object]:
    return {
        "title": "Add a bounded status summary",
        "goal": "Give operators a compact status summary.",
        "nonGoals": ["No deployment"],
        "expectedResult": "The admin status summary is visible and tested.",
        "acceptanceCriteria": ["The endpoint returns 200", "The focused test passes"],
        "materials": [],
        "whyNow": "The project needs a small iteration.",
    }


class FakeCliClient:
    def __init__(self, *_args: object, **_kwargs: object) -> None:
        self.create_calls = 0

    def list_projects(self, **_kwargs: object) -> dict[str, object]:
        return {"data": [{"projectId": PROJECT_ID, "name": "Test"}]}

    def get_project(self, project_id: str) -> dict[str, object]:
        return {"data": {"projectId": project_id, "name": "Test"}}

    def create_requirement(self, _payload: object) -> object:
        self.create_calls += 1
        raise AssertionError("CLI test fake must not create")


class AutopilotCliTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repo"
        self.root.mkdir()
        git(self.root, "init", "-q")
        (self.root / "tracked.txt").write_text("clean\n", encoding="utf-8")
        git(self.root, "add", "tracked.txt")
        git(self.root, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-m", "initial")
        self.run_dir = self.root / "qa-runs" / "autopilot" / "autopilot-cli"
        self.env = {"PATH": "/usr/bin:/bin"}

    def tearDown(self) -> None:
        self.temp.cleanup()

    def args(self, command: str, *extra: str) -> list[str]:
        return ["--repo-root", str(self.root), "--run-dir", str(self.run_dir), command, *extra]

    def init_run(self, mode: str = "dry-run") -> int:
        return main(
            self.args(
                "init",
                "--run-id",
                "autopilot-cli",
                "--mode",
                mode,
                "--project-id",
                PROJECT_ID,
                "--idea",
                "Build a bounded summary",
                "--success-criterion",
                "The focused test passes",
            ),
            environ=self.env,
        )

    def test_init_rejects_run_directory_outside_qa_runs_autopilot(self) -> None:
        result = main(
            ["--repo-root", str(self.root), "--run-dir", str(self.root / "tmp"), "init", "--run-id", "autopilot-cli",
             "--mode", "dry-run", "--project-id", PROJECT_ID, "--idea", "x", "--success-criterion", "y"],
            environ=self.env,
        )
        self.assertNotEqual(result, 0)
        self.assertFalse((self.root / "tmp").exists())

    def test_live_init_records_mode_without_post(self) -> None:
        self.assertEqual(self.init_run("live-test"), 0)
        manifest = json.loads((self.run_dir / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["mode"], "live-test")

    def test_dry_run_dispatch_finishes_without_post(self) -> None:
        self.assertEqual(self.init_run(), 0)
        plan_file = Path(self.temp.name) / "plan.json"
        plan_file.write_text(json.dumps(plan()), encoding="utf-8")
        self.assertEqual(main(self.args("freeze-plan", "--plan-file", str(plan_file)), environ=self.env), 0)
        self.assertEqual(main(self.args("dispatch", "--iteration", "1"), environ=self.env), 0)
        self.assertEqual(ManifestStore(self.run_dir).load()["status"], RunStatus.DRY_RUN_COMPLETED.value)

    def test_live_dispatch_requires_environment_opt_in_before_network(self) -> None:
        self.assertEqual(self.init_run("live-test"), 0)
        plan_file = Path(self.temp.name) / "plan.json"
        plan_file.write_text(json.dumps(plan()), encoding="utf-8")
        self.assertEqual(main(self.args("freeze-plan", "--plan-file", str(plan_file)), environ=self.env), 0)
        result = main(self.args("dispatch", "--iteration", "1", "--live-test"), environ=self.env)
        self.assertNotEqual(result, 0)
        self.assertEqual(ManifestStore(self.run_dir).load()["status"], RunStatus.READY.value)

    def test_projects_and_project_use_typed_client(self) -> None:
        with patch("scripts.autopilot.SafeRdBotClient", FakeCliClient):
            self.assertEqual(main(["projects"], environ=self.env), 0)
            self.assertEqual(main(self.args("projects"), environ=self.env), 0)
            self.assertEqual(main(self.args("project", "--project-id", PROJECT_ID), environ=self.env), 0)

    def test_verify_workspace_detects_existing_change(self) -> None:
        self.assertEqual(self.init_run(), 0)
        (self.root / "tracked.txt").write_text("changed\n", encoding="utf-8")
        self.assertNotEqual(main(self.args("verify-workspace"), environ=self.env), 0)

    def test_verify_workspace_ignores_run_artifacts_even_when_unignored(self) -> None:
        self.assertEqual(self.init_run(), 0)
        self.assertEqual(main(self.args("verify-workspace"), environ=self.env), 0)

    def test_record_decision_complete_requires_successful_evaluation(self) -> None:
        self.assertEqual(self.init_run(), 0)
        store = ManifestStore(self.run_dir)
        manifest = store.load()
        manifest["status"] = "LEARNING"
        manifest["iterations"] = [{
            "iterationNo": 1,
            "phase": "LEARNING",
            "taskId": "7480000000000000001",
            "evaluationRunId": None,
            "evaluation": None,
            "retryCount": 0,
        }]
        # Directly exercise the CLI guard against an incomplete terminal evaluation.
        store.path.write_text(json.dumps(manifest), encoding="utf-8")
        decision = Path(self.temp.name) / "decision.json"
        decision.write_text(json.dumps({"decision": "COMPLETE", "reason": "done", "evidenceIds": []}), encoding="utf-8")
        self.assertNotEqual(main(self.args("record-decision", "--decision-file", str(decision)), environ=self.env), 0)


class ProvisionCliTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repo"
        self.root.mkdir()
        git(self.root, "init", "-q")
        (self.root / "tracked.txt").write_text("clean\n", encoding="utf-8")
        git(self.root, "add", "tracked.txt")
        git(self.root, "-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-m", "initial")
        self.run_dir = self.root / "qa-runs" / "autopilot" / "autopilot-web-123"
        self.env = {"PATH": "/usr/bin:/bin"}

    def tearDown(self) -> None:
        self.temp.cleanup()

    def args(self, command: str, *extra: str) -> list[str]:
        return ["--repo-root", str(self.root), "--run-dir", str(self.run_dir), command, *extra]

    def provision_init(self, mode: str = "dry-run") -> int:
        return main(
            self.args(
                "provision-init",
                "--run-id",
                "autopilot-web-123",
                "--mode",
                mode,
                "--idea",
                "Build a habit tracker web page",
                "--success-criterion",
                "The page supports one habit check-in",
                "--success-criterion",
                "A focused automated test passes",
            ),
            environ=self.env,
        )

    def freeze_plan(self) -> str:
        plan_file = Path(self.temp.name) / "provision-plan.json"
        result = main(
            self.args("provision-plan", "--plan-file", str(plan_file), "--github-owner", "alice"),
            environ=self.env,
        )
        self.assertEqual(result, 0)
        return ManifestStore(self.run_dir).load()["provisioning"]["planSha256"]

    def test_provision_init_requires_no_existing_project_id(self) -> None:
        self.assertEqual(self.provision_init(), 0)
        manifest = ManifestStore(self.run_dir).load()
        self.assertEqual(manifest["schemaVersion"], "rd-bot-autopilot/v2")
        self.assertEqual(manifest["projectId"], "")
        self.assertEqual(manifest["status"], RunStatus.DRAFT.value)

    def test_provision_plan_freezes_canonical_plan_and_persists_digest(self) -> None:
        self.assertEqual(self.provision_init(), 0)
        digest = self.freeze_plan()
        manifest = ManifestStore(self.run_dir).load()
        self.assertEqual(manifest["status"], RunStatus.PLAN_READY.value)
        self.assertRegex(digest, r"^[0-9a-f]{64}$")

    def test_provision_run_dry_run_completes_without_remote_clients(self) -> None:
        self.assertEqual(self.provision_init(), 0)
        self.freeze_plan()
        self.assertEqual(main(self.args("provision-run"), environ=self.env), 0)
        self.assertEqual(ManifestStore(self.run_dir).load()["status"], RunStatus.DRY_RUN_COMPLETED.value)

    def test_provision_confirm_requires_the_exact_digest(self) -> None:
        self.assertEqual(self.provision_init("live-provision"), 0)
        digest = self.freeze_plan()
        self.assertEqual(main(self.args("provision-confirm", "--plan-sha256", "0" * 64), environ=self.env), 2)
        self.assertEqual(main(self.args("provision-confirm", "--plan-sha256", digest), environ=self.env), 0)
        self.assertEqual(ManifestStore(self.run_dir).load()["status"], RunStatus.LIVE_CONFIRMED.value)

    def test_provision_run_requires_flag_digest_and_environment_before_any_client(self) -> None:
        self.assertEqual(self.provision_init("live-provision"), 0)
        digest = self.freeze_plan()
        self.assertEqual(main(self.args("provision-run"), environ=self.env), 2)
        self.assertEqual(main(self.args("provision-run", "--live-provision"), environ=self.env), 2)
        self.assertEqual(main(self.args("provision-confirm", "--plan-sha256", digest), environ=self.env), 0)
        self.assertEqual(
            main(self.args("provision-run", "--live-provision", "--confirm-plan-sha256", digest), environ=self.env),
            2,
        )
        self.assertEqual(ManifestStore(self.run_dir).load()["status"], RunStatus.LIVE_CONFIRMED.value)

    def test_provision_run_rejects_a_wrong_confirmation_digest(self) -> None:
        self.assertEqual(self.provision_init("live-provision"), 0)
        digest = self.freeze_plan()
        self.assertEqual(main(self.args("provision-confirm", "--plan-sha256", digest), environ=self.env), 0)
        env = dict(self.env, RD_BOT_AUTOPILOT_LIVE_PROVISION="1")
        result = main(
            self.args("provision-run", "--live-provision", "--confirm-plan-sha256", "0" * 64),
            environ=env,
        )
        self.assertEqual(result, 2)
        self.assertEqual(ManifestStore(self.run_dir).load()["status"], RunStatus.LIVE_CONFIRMED.value)


if __name__ == "__main__":
    unittest.main()
