from __future__ import annotations

import re
import unittest
from pathlib import Path


SKILL_ROOT = Path(__file__).resolve().parents[2]


class SkillContractTest(unittest.TestCase):
    def test_skill_and_references_expose_bounded_contract(self) -> None:
        skill = (SKILL_ROOT / "SKILL.md").read_text(encoding="utf-8")
        frontmatter = skill.split("---", 2)[1]
        keys = [line.split(":", 1)[0].strip() for line in frontmatter.splitlines() if ":" in line]
        self.assertEqual(set(keys), {"name", "description"})
        self.assertLess(len(skill.splitlines()), 500)
        for text in (
            "dry-run",
            "--live-test",
            "RD_BOT_AUTOPILOT_LIVE_TEST=1",
            "qa-runs/autopilot/{runId}",
            "two iterations",
            "one retry",
            "WAITING_HUMAN",
            "/approve",
            "delete",
            "merge",
            "deploy",
            "curl",
            "arbitrary shell",
        ):
            self.assertIn(text, skill)
        for reference in ("iteration-contract.md", "policy-and-stop-rules.md", "rd-bot-api-map.md"):
            self.assertIn(f"references/{reference}", skill)
            self.assertTrue((SKILL_ROOT / "references" / reference).exists())
        self.assertNotRegex(skill, re.compile(r"sk-[A-Za-z0-9]{16,}"))
        self.assertNotRegex(skill, re.compile(r"Bearer [A-Za-z0-9._-]{16,}"))

    def test_skill_requires_digest_confirmation_for_actual_project_provisioning(self) -> None:
        skill = (SKILL_ROOT / "SKILL.md").read_text(encoding="utf-8")
        for text in (
            "--live-provision",
            "--confirm-plan-sha256",
            "RD_BOT_AUTOPILOT_LIVE_PROVISION=1",
            "private personal GitHub repository",
            "provision-init",
            "provision-plan",
            "provision-confirm",
            "provision-run",
            "provision-resume",
        ):
            self.assertIn(text, skill)

    def test_openai_metadata_contains_skill_prompt(self) -> None:
        metadata = (SKILL_ROOT / "agents" / "openai.yaml").read_text(encoding="utf-8")
        self.assertIn("$rd-bot-project-autopilot", metadata)


if __name__ == "__main__":
    unittest.main()
