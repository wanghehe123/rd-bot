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

    def test_load_rejects_modified_request_fingerprint(self) -> None:
        self.store.initialize(self.manifest)
        self.store.transition(RunStatus.PLANNING, "charter ready")
        self.store.freeze_plan(1, {"title": "Small change", "acceptanceCriteria": ["test passes"]})
        self.store.record_dispatch_intent(1)
        path = self.run_dir / "manifest.json"
        payload = json.loads(path.read_text())
        payload["iterations"][0]["request"]["title"] = "tampered"
        path.write_text(json.dumps(payload))
        with self.assertRaisesRegex(ManifestError, "fingerprint"):
            self.store.load()


if __name__ == "__main__":
    unittest.main()
