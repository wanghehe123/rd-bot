#!/usr/bin/env python3
"""Build and attest the two thin, offline coding-benchmark runtime layers."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path
from typing import Any


_DIGEST = re.compile(r"sha256:[a-f0-9]{64}$")
_BASE_IMAGE = re.compile(r"[a-z0-9][a-z0-9._/-]*@sha256:[a-f0-9]{64}$")
_TAG = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")
_REPOSITORY = re.compile(r"[a-z0-9][a-z0-9._/-]*$")


class BuildError(RuntimeError):
    """Raised when a shared benchmark runtime cannot be built and attested."""


def immutable_reference(repository: str, digest: str) -> str:
    """Return a content-addressed reference without permitting a mutable tag."""

    safe_repository = str(repository or "").strip().lower()
    safe_digest = str(digest or "").strip().lower()
    if not _REPOSITORY.fullmatch(safe_repository) or ":" in safe_repository or "@" in safe_repository:
        raise BuildError("repository must be an untagged safe repository name")
    if not _DIGEST.fullmatch(safe_digest):
        raise BuildError("image must use an immutable repository digest")
    return safe_repository + "@" + safe_digest


def build_plan(
    platform: str,
    base_image: str,
    tag: str,
    repository_prefix: str = "rd-bot/coding-eval",
    local_base_tag: str = "rd-bot/pi-agent:local",
) -> list[dict[str, Any]]:
    """Produce local content-addressed plans without BuildKit or a registry pull.

    Docker Desktop's BuildKit resolves a private local ``rd-bot/*`` base against
    a remote registry even with ``--pull=false``.  A create/commit layer keeps
    the already-attested local image store as the only build input.
    """

    safe_platform = str(platform or "").strip().lower()
    if safe_platform not in {"linux/arm64", "linux/amd64"}:
        raise BuildError("platform must be linux/arm64 or linux/amd64")
    safe_base = str(base_image or "").strip().lower()
    if not _BASE_IMAGE.fullmatch(safe_base):
        raise BuildError("base image must be an immutable repository digest")
    safe_tag = str(tag or "").strip()
    if not _TAG.fullmatch(safe_tag):
        raise BuildError("build tag contains unsupported characters")
    prefix = str(repository_prefix or "").strip().lower().rstrip("/")
    if not _REPOSITORY.fullmatch(prefix):
        raise BuildError("repository prefix contains unsupported characters")
    safe_local_base_tag = str(local_base_tag or "").strip().lower()
    if not re.fullmatch(r"[a-z0-9][a-z0-9._/-]*:[a-z0-9][a-z0-9._-]*", safe_local_base_tag):
        raise BuildError("local base tag contains unsupported characters")
    images: list[dict[str, Any]] = []
    for role in ("agent", "oracle"):
        image_tag = f"{prefix}-{role}:{safe_tag}"
        container_name = f"rd-eval-build-{role}-{safe_tag.lower()}"
        changes = [
            "--change", "ENV PIP_NO_INDEX=1 PIP_DISABLE_PIP_VERSION_CHECK=1 npm_config_offline=true YARN_ENABLE_NETWORK=0 RD_EVAL_OFFLINE=1",
            "--change", f"LABEL rd.evaluation.kind=coding-benchmark rd.evaluation.role={role}",
        ]
        images.append({
            "role": role,
            "tag": image_tag,
            "baseImage": safe_base,
            "platform": safe_platform,
            "buildMode": "local-content-addressed",
            "createCommand": [
                "docker", "create", "--name", container_name,
                "--label", "rd.evaluation.kind=coding-benchmark",
                "--label", f"rd.evaluation.role={role}",
                safe_local_base_tag,
            ],
            "commitCommand": ["docker", "commit", *changes, container_name, image_tag],
            "cleanupCommand": ["docker", "rm", container_name],
        })
    return images


def _run(command: list[str]) -> str:
    completed = subprocess.run(command, check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if completed.returncode != 0:
        raise BuildError(completed.stdout.strip() or "docker command failed")
    return completed.stdout


def _inspect_image(tag: str) -> dict[str, Any]:
    output = _run(["docker", "image", "inspect", tag, "--format", "{{json .}}"])
    try:
        value = json.loads(output)
    except json.JSONDecodeError as error:
        raise BuildError("Docker returned an unreadable image inspection payload") from error
    if not isinstance(value, dict):
        raise BuildError("Docker returned an unreadable image inspection payload")
    return value


def _verify_local_base(local_base_tag: str, required_digest: str, platform: str) -> None:
    inspection = _inspect_image(local_base_tag)
    digests = inspection.get("RepoDigests", [])
    if not isinstance(digests, list) or required_digest not in {str(value).lower() for value in digests}:
        raise BuildError("local base tag does not resolve to the required immutable repository digest")
    actual_platform = str(inspection.get("Os", "")).lower() + "/" + str(inspection.get("Architecture", "")).lower()
    if actual_platform != platform:
        raise BuildError(f"local base platform {actual_platform} does not match frozen platform {platform}")


def _attested_reference(tag: str, inspection: dict[str, Any]) -> str:
    repository = tag.rsplit(":", 1)[0]
    repo_digests = inspection.get("RepoDigests", [])
    if isinstance(repo_digests, list):
        matching = [str(value).lower() for value in repo_digests if str(value).lower().startswith(repository + "@sha256:")]
        if matching:
            return matching[0]
    image_id = str(inspection.get("Id", "")).lower()
    return immutable_reference(repository, image_id)


def build_shared_images(
    platform: str,
    base_image: str,
    tag: str,
    output_path: Path,
    repository_prefix: str = "rd-bot/coding-eval",
    local_base_tag: str = "rd-bot/pi-agent:local",
) -> dict[str, Any]:
    """Build the shared layers without pulling, then write their content attestations."""

    plan = build_plan(platform, base_image, tag, repository_prefix, local_base_tag)
    _verify_local_base(local_base_tag, base_image.lower(), platform)
    results: list[dict[str, str]] = []
    for image in plan:
        created = False
        try:
            _run(image["createCommand"])
            created = True
            _run(image["commitCommand"])
        finally:
            if created:
                _run(image["cleanupCommand"])
        inspection = _inspect_image(image["tag"])
        image_id = str(inspection.get("Id", "")).lower()
        if not _DIGEST.fullmatch(image_id):
            raise BuildError("built image does not have a content digest")
        results.append({
            "role": image["role"],
            "tag": image["tag"],
            "reference": _attested_reference(image["tag"], inspection),
            "imageId": image_id,
            "baseImage": image["baseImage"],
            "platform": platform,
        })
    report = {"platform": platform, "images": results, "buildMode": "local-content-addressed", "buildPullPolicy": "never"}
    destination = Path(output_path).resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Build RD-Bot shared coding benchmark images without Docker pulls.")
    parser.add_argument("--platform", default="linux/arm64")
    parser.add_argument("--base-image", default="rd-bot/pi-agent@sha256:07dcbd9d3f1603c4fd71e1e4802568edaf16a5f5a59c9ad111407a53e8616d9a")
    parser.add_argument("--tag", default="20260730-v3")
    parser.add_argument("--repository-prefix", default="rd-bot/coding-eval")
    parser.add_argument("--local-base-tag", default="rd-bot/pi-agent:local")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    build_shared_images(
        args.platform, args.base_image, args.tag, Path(args.output), args.repository_prefix, args.local_base_tag
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BuildError as error:
        print(f"shared image build error: {error}")
        raise SystemExit(2)
