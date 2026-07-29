from __future__ import annotations

import unittest

from scripts.evaluation.rd_eval_build_java_toolchain_image import BuildError, build_plan


class BuildJavaToolchainImageTest(unittest.TestCase):
    def test_plan_builds_one_reusable_java17_layer_from_the_attested_local_pi_base(self) -> None:
        plan = build_plan(
            "linux/arm64",
            "rd-bot/pi-agent@sha256:" + "a" * 64,
            "20260730-java17-v1",
        )

        self.assertEqual("local-content-addressed", plan["buildMode"])
        self.assertEqual("linux/arm64", plan["platform"])
        self.assertEqual(["docker", "create"], plan["createCommand"][:2])
        self.assertIn("--user", plan["createCommand"])
        self.assertIn("--entrypoint", plan["createCommand"])
        self.assertIn("apt-get update", plan["createCommand"][-1])
        self.assertEqual(["docker", "start", "-a"], plan["installCommand"][:3])
        self.assertEqual(["docker", "commit"], plan["commitCommand"][:2])
        self.assertIn("openjdk-17-jdk", plan["installCommandText"])
        self.assertIn("maven", plan["installCommandText"])

    def test_plan_rejects_unpinned_base_or_unsupported_platform(self) -> None:
        with self.assertRaisesRegex(BuildError, "immutable repository digest"):
            build_plan("linux/arm64", "rd-bot/pi-agent:latest", "v1")
        with self.assertRaisesRegex(BuildError, "linux/arm64 or linux/amd64"):
            build_plan("darwin/arm64", "rd-bot/pi-agent@sha256:" + "a" * 64, "v1")


if __name__ == "__main__":
    unittest.main()
