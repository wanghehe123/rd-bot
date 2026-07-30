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

    def test_plan_builds_a_java21_layer_from_a_pinned_temurin_tarball(self) -> None:
        plan = build_plan(
            "linux/arm64",
            "rd-bot/pi-agent@sha256:" + "a" * 64,
            "20260730-java21-v1",
            toolchain="java21",
            image_repository="rd-bot/coding-eval-java21",
        )

        self.assertIn("OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.12_8.tar.gz", plan["installCommandText"])
        self.assertIn("eba38e871b02d407897bfe017ea35352dfc1420ef6d2112425b0c67325ca509d", plan["installCommandText"])
        self.assertIn("sha256sum -c", plan["installCommandText"])
        self.assertIn("maven", plan["installCommandText"])
        self.assertNotIn("openjdk-17-jdk", plan["installCommandText"])
        commit_env = " ".join(plan["commitCommand"])
        self.assertIn("JAVA_HOME=/opt/jdk-21", commit_env)
        self.assertIn("/opt/jdk-21/bin", commit_env)
        self.assertIn("rd.evaluation.toolchain=java21", commit_env)

    def test_plan_rejects_an_unknown_toolchain(self) -> None:
        with self.assertRaisesRegex(BuildError, "toolchain must be one of"):
            build_plan(
                "linux/arm64",
                "rd-bot/pi-agent@sha256:" + "a" * 64,
                "v1",
                toolchain="java25",
            )


if __name__ == "__main__":
    unittest.main()
