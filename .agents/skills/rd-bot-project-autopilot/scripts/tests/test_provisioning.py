from __future__ import annotations

import unittest

from scripts.provisioning import ProvisionPlanError, build_provision_plan, plan_digest, validate_provision_plan


class ProvisionPlanTest(unittest.TestCase):
    def test_plan_is_canonical_private_and_contains_two_hashed_documents(self) -> None:
        plan = build_provision_plan(
            run_id="autopilot-web-123",
            idea="Build a habit tracker web page",
            success_criteria=["The page can add a habit", "Focused tests pass"],
            github_owner="alice",
        )

        self.assertEqual("rd-bot-autopilot-provision-plan/v1", plan["schemaVersion"])
        self.assertEqual("alice", plan["repository"]["owner"])
        self.assertEqual("private", plan["repository"]["visibility"])
        self.assertTrue(plan["repository"]["autoInit"])
        self.assertTrue(plan["repository"]["marker"].startswith("[autopilot:autopilot-web-123:github:"))
        self.assertEqual(2, len(plan["documents"]))
        self.assertEqual(
            ["project-charter.md", "delivery-brief.md"],
            [item["sourceName"] for item in plan["documents"]],
        )
        self.assertEqual(plan_digest(plan), plan_digest(dict(plan)))
        self.assertEqual(plan, validate_provision_plan(plan))

    def test_plan_rejects_public_visibility_and_credential_shaped_idea(self) -> None:
        with self.assertRaisesRegex(ProvisionPlanError, "private"):
            build_provision_plan(
                run_id="autopilot-web-123",
                idea="Build a page",
                success_criteria=["Focused tests pass"],
                github_owner="alice",
                visibility="public",
            )
        with self.assertRaisesRegex(ProvisionPlanError, "credential"):
            build_provision_plan(
                run_id="autopilot-web-123",
                idea="Use token_abcdefghijklmnopqrstuvwxyz in the landing page",
                success_criteria=["Focused tests pass"],
                github_owner="alice",
            )

    def test_plan_requires_a_run_suffixed_safe_repository_name(self) -> None:
        with self.assertRaisesRegex(ProvisionPlanError, "repository"):
            build_provision_plan(
                run_id="autopilot-web-123",
                idea="Build a page",
                success_criteria=["Focused tests pass"],
                github_owner="alice",
                repo_name="not-suffixed",
            )


if __name__ == "__main__":
    unittest.main()
