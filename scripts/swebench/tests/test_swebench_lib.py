"""Focused tests for paired SWE-bench workspace and prompt paths."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import swebench_lib as lib


class AgentWorkspacePathTest(unittest.TestCase):
    def test_paired_agent_workspaces_are_distinct_and_stable(self) -> None:
        root = Path("/Volumes/WishDisk/codes/swe")
        row = {"instance_id": "django__django-10914"}

        self.assertEqual(
            lib.agent_checkout_path(root, row, "claude-code"),
            root / "worktrees" / "django__django-10914",
        )
        self.assertEqual(
            lib.agent_checkout_path(root, row, "rd-bot"),
            root / "worktrees" / "rd-bot" / "django__django-10914",
        )
        self.assertNotEqual(
            lib.agent_checkout_path(root, row, "claude-code"),
            lib.agent_checkout_path(root, row, "rd-bot"),
        )

    def test_agent_prompt_paths_are_separate(self) -> None:
        output_root = Path("/tmp/swebench-lite-10")
        instance_id = "astropy__astropy-12907"

        self.assertEqual(
            lib.agent_prompt_path(output_root, instance_id, "claude-code"),
            output_root / "prompts" / "claude-code" / f"{instance_id}.md",
        )
        self.assertEqual(
            lib.agent_prompt_path(output_root, instance_id, "rd-bot"),
            output_root / "prompts" / "rd-bot" / f"{instance_id}.md",
        )


class ProjectPayloadTest(unittest.TestCase):
    def test_uses_the_immutable_swe_bench_base_commit_as_default_branch(self) -> None:
        row = {
            "repo": "django/django",
            "instance_id": "django__django-11019",
            "base_commit": "93e892bb645b16ebaf287beb5fe7f3ffe8d10408",
        }

        payload = lib.project_payload(row, "knowledge-base-11019")

        self.assertEqual(payload["defaultBranch"], row["base_commit"])


if __name__ == "__main__":
    unittest.main()
