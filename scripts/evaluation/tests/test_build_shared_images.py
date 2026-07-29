from __future__ import annotations

import io
import tarfile
import unittest
from pathlib import Path

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

    def test_oracle_plan_embeds_a_hashed_offline_verifier_script(self) -> None:
        plan = build_plan("linux/arm64", "rd-bot/pi-agent@sha256:" + "a" * 64, "20260730-v1")
        oracle = next(image for image in plan if image["role"] == "oracle")

        self.assertEqual(["docker", "cp", "-"], oracle["copyCommand"][:3])
        self.assertTrue(oracle["copyCommand"][-1].endswith(":/opt/rd-pi-bridge"))
        self.assertTrue(oracle["oracleScriptSha256"].startswith("sha256:"))

    def test_oracle_verifier_is_copied_as_a_root_owned_read_only_member(self) -> None:
        plan = build_plan("linux/arm64", "rd-bot/pi-agent@sha256:" + "a" * 64, "20260730-v1")
        oracle = next(image for image in plan if image["role"] == "oracle")

        with tarfile.open(fileobj=io.BytesIO(oracle["copyArchive"]), mode="r:") as archive:
            members = archive.getmembers()
            self.assertEqual(["rd_eval_oracle.py"], [member.name for member in members])
            member = members[0]
            self.assertEqual(0, member.uid)
            self.assertEqual(0, member.gid)
            self.assertEqual("root", member.uname)
            self.assertEqual("root", member.gname)
            self.assertEqual(0o444, member.mode)
            self.assertEqual(0, member.mtime)
            extracted = archive.extractfile(member)
            self.assertIsNotNone(extracted)
            source = Path(__file__).resolve().parents[1] / "rd_eval_oracle.py"
            self.assertEqual(source.read_bytes(), extracted.read())

    def test_oracle_copy_archive_is_byte_identical_across_builds(self) -> None:
        first = next(
            image
            for image in build_plan("linux/arm64", "rd-bot/pi-agent@sha256:" + "a" * 64, "20260730-v1")
            if image["role"] == "oracle"
        )
        second = next(
            image
            for image in build_plan("linux/arm64", "rd-bot/pi-agent@sha256:" + "b" * 64, "20260730-v2")
            if image["role"] == "oracle"
        )

        self.assertEqual(first["copyArchive"], second["copyArchive"])


if __name__ == "__main__":
    unittest.main()
