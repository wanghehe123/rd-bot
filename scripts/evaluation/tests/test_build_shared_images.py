from __future__ import annotations

import unittest

from scripts.evaluation.rd_eval_build_shared_images import BuildError, build_plan, immutable_reference


class BuildSharedImagesTest(unittest.TestCase):
    def test_build_plan_reuses_the_local_pi_base_without_a_pull_or_runtime_package_install(self) -> None:
        plan = build_plan("linux/arm64", "rd-bot/pi-agent@sha256:" + "a" * 64, "20260730-v1")

        self.assertEqual(2, len(plan))
        self.assertTrue(all(image["buildMode"] == "local-content-addressed" for image in plan))
        self.assertTrue(all(image["platform"] == "linux/arm64" for image in plan))
        self.assertTrue(all(image["createCommand"][:2] == ["docker", "create"] for image in plan))
        self.assertTrue(all(image["commitCommand"][:2] == ["docker", "commit"] for image in plan))
        self.assertTrue(all("rd-bot/pi-agent:local" in image["createCommand"] for image in plan))
        self.assertTrue(all(image["baseImage"].endswith("a" * 64) for image in plan))

    def test_immutable_reference_requires_a_repository_digest(self) -> None:
        with self.assertRaisesRegex(BuildError, "immutable repository digest"):
            immutable_reference("rd-bot/eval-agent", "latest")

        self.assertEqual(
            "rd-bot/eval-agent@sha256:" + "a" * 64,
            immutable_reference("rd-bot/eval-agent", "sha256:" + "a" * 64),
        )


if __name__ == "__main__":
    unittest.main()
