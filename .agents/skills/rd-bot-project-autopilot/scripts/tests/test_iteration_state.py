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
    create_provision_manifest,
    request_fingerprint,
)
from scripts.provisioning import build_provision_plan


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
        self.store.freeze_plan(1, {"title": "Small change", "acceptanceCriteria": ["test passes", "lint passes"]})
        intent = self.store.record_dispatch_intent(1)
        on_disk = json.loads((self.run_dir / "manifest.json").read_text())
        self.assertEqual(on_disk["status"], "DISPATCH_INTENT")
        self.assertEqual(on_disk["iterations"][0]["requestFingerprint"], intent["requestFingerprint"])
        self.assertIsNone(on_disk["iterations"][0]["taskId"])

    def test_second_active_task_is_rejected(self) -> None:
        self.store.initialize(self.manifest)
        self.store.transition(RunStatus.PLANNING, "charter ready")
        self.store.freeze_plan(1, {"title": "First", "acceptanceCriteria": ["one", "two"]})
        self.store.record_dispatch_intent(1)
        self.store.bind_task(1, "7480000000000000001")
        with self.assertRaisesRegex(ManifestError, "active task"):
            self.store.freeze_plan(2, {"title": "Second", "acceptanceCriteria": ["two", "three"]})

    def test_request_fingerprint_is_canonical(self) -> None:
        left = request_fingerprint({"b": 2, "a": 1})
        right = request_fingerprint({"a": 1, "b": 2})
        self.assertEqual(left, right)

    def test_request_ledger_keeps_only_safe_fields(self) -> None:
        self.store.initialize(self.manifest)
        self.store.append_request_ledger({
            "method": "GET",
            "path": "/admin/projects",
            "requestSha256": "a" * 64,
            "status": 200,
            "timestamp": "2026-07-27T00:00:00Z",
            "authorization": "should not persist",
        })
        entry = self.store.load()["requestLedger"][0]
        self.assertEqual(set(entry), {"method", "path", "requestSha256", "status", "timestamp"})

    def test_load_rejects_modified_request_fingerprint(self) -> None:
        self.store.initialize(self.manifest)
        self.store.transition(RunStatus.PLANNING, "charter ready")
        self.store.freeze_plan(1, {"title": "Small change", "acceptanceCriteria": ["test passes", "lint passes"]})
        self.store.record_dispatch_intent(1)
        path = self.run_dir / "manifest.json"
        payload = json.loads(path.read_text())
        payload["iterations"][0]["request"]["title"] = "tampered"
        path.write_text(json.dumps(payload))
        with self.assertRaisesRegex(ManifestError, "fingerprint"):
            self.store.load()

    def test_legacy_v1_manifest_without_workspace_flag_loads_as_unverified(self) -> None:
        self.store.initialize(self.manifest)
        path = self.run_dir / "manifest.json"
        payload = json.loads(path.read_text())
        payload.pop("workspaceVerified")
        path.write_text(json.dumps(payload))
        self.assertFalse(self.store.load()["workspaceVerified"])

    def test_v2_requires_exact_digest_before_live_provision_intent(self) -> None:
        plan = build_provision_plan(
            run_id="autopilot-web-123",
            idea="Build a habit tracker web page",
            success_criteria=["A focused automated test passes"],
            github_owner="alice",
        )
        manifest = create_provision_manifest(
            run_id="autopilot-web-123",
            mode="live-provision",
            idea="Build a habit tracker web page",
            success_criteria=["A focused automated test passes"],
        )
        self.store.initialize(manifest)
        digest = self.store.freeze_provision_plan(plan)
        self.assertEqual(RunStatus.PLAN_READY.value, self.store.load()["status"])
        with self.assertRaisesRegex(ManifestError, "digest"):
            self.store.confirm_provision("0" * 64)
        self.store.confirm_provision(digest)
        self.store.record_provision_intent("github", dict(plan["repository"]))
        self.assertEqual(RunStatus.GH_INTENT.value, self.store.load()["status"])

    def test_v2_ambiguous_write_cannot_be_resent_or_generic_transitioned(self) -> None:
        plan = build_provision_plan(
            run_id="autopilot-web-124",
            idea="Build a habit tracker web page",
            success_criteria=["A focused automated test passes"],
            github_owner="alice",
        )
        self.store.initialize(create_provision_manifest(
            run_id="autopilot-web-124",
            mode="live-provision",
            idea="Build a habit tracker web page",
            success_criteria=["A focused automated test passes"],
        ))
        self.store.confirm_provision(self.store.freeze_provision_plan(plan))
        self.store.record_provision_intent("github", dict(plan["repository"]))
        self.store.set_waiting_human("AMBIGUOUS_GITHUB_CREATE", {"type": "AMBIGUOUS_GITHUB_CREATE"})
        with self.assertRaisesRegex(ManifestError, "WAITING_HUMAN"):
            self.store.record_provision_intent("github", dict(plan["repository"]))
        with self.assertRaisesRegex(ManifestError, "provisioning"):
            self.store.transition(RunStatus.GH_INTENT, "unsafe resend")


if __name__ == "__main__":
    unittest.main()
