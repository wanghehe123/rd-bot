from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.evaluation.rd_eval_freeze_fresh_selection import (
    FreshSelectionError,
    freeze_fresh_selection,
)


def _pool(sha: str, base: str, language: str, difficulty: str, production_files: int) -> dict[str, object]:
    return {
        "caseId": "rd-bot--" + sha[:10],
        "fixCommit": sha,
        "baseCommit": base,
        "language": language,
        "difficulty": difficulty,
        "productionFileCount": production_files,
        "subject": "fix: address " + sha[:6],
        "leakageAudit": {"verdict": "CLEAN"},
        "freshnessEvidence": {"kind": "PRIVATE_TASK", "reference": "private repository commit " + sha},
    }


class FreezeFreshSelectionTest(unittest.TestCase):
    def test_the_selection_is_frozen_with_a_seed_provenance_and_reasons(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            pool = [
                _pool("a" * 40, "1" * 40, "JAVA", "MEDIUM", 5),
                _pool("b" * 40, "2" * 40, "JAVA", "EASY", 2),
                _pool("c" * 40, "3" * 40, "TSJS", "HARD", 9),
            ]
            destination = Path(temp_dir) / "fresh-selection.json"

            selection = freeze_fresh_selection(
                pool, ["rd-bot--" + s[:10] for s in ("a" * 40, "b" * 40, "c" * 40)],
                seed=20260730, destination=destination,
            )

            self.assertEqual("rd-eval-fresh-selection-v1", selection["schemaVersion"])
            self.assertEqual(20260730, selection["seed"])
            self.assertEqual(3, len(selection["cases"]))
            self.assertEqual("FRESH_PRIMARY", selection["cases"][0]["slice"])
            self.assertEqual("PRIVATE_TASK", selection["cases"][0]["freshnessEvidence"]["kind"])
            self.assertTrue(destination.read_text(encoding="utf-8").startswith("{"))

    def test_the_selection_must_cover_both_languages_and_every_difficulty_band(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            pool = [
                _pool("a" * 40, "1" * 40, "JAVA", "MEDIUM", 5),
                _pool("b" * 40, "2" * 40, "JAVA", "MEDIUM", 5),
                _pool("c" * 40, "3" * 40, "TSJS", "MEDIUM", 5),
            ]

            with self.assertRaisesRegex(FreshSelectionError, "language|difficulty"):
                freeze_fresh_selection(
                    pool, ["rd-bot--" + s[:10] for s in ("a" * 40, "b" * 40, "c" * 40)],
                    seed=1, destination=Path(temp_dir) / "out.json",
                )

    def test_a_case_without_a_clean_leakage_audit_cannot_be_frozen(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            leaked = _pool("a" * 40, "1" * 40, "JAVA", "MEDIUM", 5)
            leaked["leakageAudit"] = {"verdict": "LEAK"}
            clean = _pool("b" * 40, "2" * 40, "TSJS", "HARD", 9)
            easy = _pool("c" * 40, "3" * 40, "JAVA", "EASY", 2)

            with self.assertRaisesRegex(FreshSelectionError, "leakage"):
                freeze_fresh_selection(
                    [leaked, clean, easy],
                    ["rd-bot--" + s[:10] for s in ("a" * 40, "b" * 40, "c" * 40)],
                    seed=1, destination=Path(temp_dir) / "out.json",
                )

    def test_a_selected_case_must_come_from_the_pool_and_carry_an_immutable_commits(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            pool = [
                _pool("a" * 40, "1" * 40, "JAVA", "MEDIUM", 5),
                _pool("b" * 40, "2" * 40, "JAVA", "EASY", 2),
                _pool("c" * 40, "3" * 40, "TSJS", "HARD", 9),
            ]

            with self.assertRaisesRegex(FreshSelectionError, "pool"):
                freeze_fresh_selection(
                    pool, ["rd-bot--zzzzzzzzzz", "rd-bot--bbbbbbbbbb", "rd-bot--cccccccccc"],
                    seed=1, destination=Path(temp_dir) / "out.json",
                )
            bad = _pool("d" * 40, "not-a-commit", "JAVA", "HARD", 9)
            with self.assertRaisesRegex(FreshSelectionError, "baseCommit"):
                freeze_fresh_selection(
                    [bad] + pool, ["rd-bot--" + s[:10] for s in ("d" * 40, "a" * 40, "c" * 40)],
                    seed=1, destination=Path(temp_dir) / "out2.json",
                )

    def test_an_existing_destination_is_never_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            pool = [
                _pool("a" * 40, "1" * 40, "JAVA", "MEDIUM", 5),
                _pool("b" * 40, "2" * 40, "JAVA", "EASY", 2),
                _pool("c" * 40, "3" * 40, "TSJS", "HARD", 9),
            ]
            destination = Path(temp_dir) / "fresh-selection.json"
            destination.write_text("{}\n", encoding="utf-8")

            with self.assertRaisesRegex(FreshSelectionError, "must not already exist"):
                freeze_fresh_selection(
                    pool, ["rd-bot--" + s[:10] for s in ("a" * 40, "b" * 40, "c" * 40)],
                    seed=1, destination=destination,
                )

    def test_cli_runs_directly_from_the_repository_root(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            pool_path = root / "pool.json"
            pool_path.write_text(json.dumps([
                _pool("a" * 40, "1" * 40, "JAVA", "MEDIUM", 5),
                _pool("b" * 40, "2" * 40, "JAVA", "EASY", 2),
                _pool("c" * 40, "3" * 40, "TSJS", "HARD", 9),
            ]), encoding="utf-8")
            selection = root / "selection.json"
            script = Path(__file__).parents[1] / "rd_eval_freeze_fresh_selection.py"

            completed = subprocess.run(
                [
                    sys.executable, str(script),
                    "--pool", str(pool_path),
                    "--select", "rd-bot--aaaaaaaaaa,rd-bot--bbbbbbbbbb,rd-bot--cccccccccc",
                    "--seed", "20260730",
                    "--output", str(selection),
                ],
                cwd=Path(__file__).parents[3],
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )

            self.assertEqual(0, completed.returncode, completed.stdout)
            self.assertTrue(selection.is_file())


if __name__ == "__main__":
    unittest.main()
