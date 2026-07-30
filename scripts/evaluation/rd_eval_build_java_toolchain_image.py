#!/usr/bin/env python3
"""Build one reusable Java benchmark toolchain layer before any offline Trial starts."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path
from typing import Any


class BuildError(RuntimeError):
    """Raised when the local Java toolchain cannot be content-addressed and attested."""


_DIGEST = re.compile(r"sha256:[a-f0-9]{64}$")
_BASE_IMAGE = re.compile(r"[a-z0-9][a-z0-9._/-]*@sha256:[a-f0-9]{64}$")
_TAG = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")

_INSTALL_JAVA17 = (
    "set -eu; export DEBIAN_FRONTEND=noninteractive; apt-get update; "
    "apt-get install -y --no-install-recommends openjdk-17-jdk maven; "
    "rm -rf /var/lib/apt/lists/*"
)

_TEMURIN21_URL = (
    "https://github.com/adoptium/temurin21-binaries/releases/download/"
    "jdk-21.0.12%2B8/OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.12_8.tar.gz"
)
_TEMURIN21_SHA256 = "eba38e871b02d407897bfe017ea35352dfc1420ef6d2112425b0c67325ca509d"

_INSTALL_JAVA21 = (
    "set -eu; export DEBIAN_FRONTEND=noninteractive; apt-get update; "
    "apt-get install -y --no-install-recommends maven curl ca-certificates; "
    "rm -rf /var/lib/apt/lists/*; "
    f"curl -fsSL '{_TEMURIN21_URL}' -o /tmp/jdk21.tar.gz; "
    f"echo '{_TEMURIN21_SHA256}  /tmp/jdk21.tar.gz' | sha256sum -c -; "
    "mkdir -p /opt/jdk-21; tar -xzf /tmp/jdk21.tar.gz -C /opt/jdk-21 --strip-components=1; "
    "rm -f /tmp/jdk21.tar.gz; /opt/jdk-21/bin/java -version"
)

_TOOLCHAINS = {
    "java17": {
        "install": _INSTALL_JAVA17,
        "env": "RD_EVAL_OFFLINE=1 PIP_NO_INDEX=1 PIP_DISABLE_PIP_VERSION_CHECK=1 npm_config_offline=true YARN_ENABLE_NETWORK=0",
        "reportName": "JAVA_17_MAVEN",
    },
    "java21": {
        "install": _INSTALL_JAVA21,
        "env": "RD_EVAL_OFFLINE=1 PIP_NO_INDEX=1 PIP_DISABLE_PIP_VERSION_CHECK=1 npm_config_offline=true YARN_ENABLE_NETWORK=0 JAVA_HOME=/opt/jdk-21 PATH=/opt/jdk-21/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "reportName": "JAVA_21_MAVEN",
    },
}


def build_plan(
    platform: str,
    base_image: str,
    tag: str,
    *,
    local_base_tag: str = "rd-bot/pi-agent:local",
    image_repository: str = "rd-bot/coding-eval-java17",
    toolchain: str = "java17",
) -> dict[str, Any]:
    """Return a fixed prebuild plan; no agent or Oracle invokes its install shell."""

    safe_platform = str(platform or "").strip().lower()
    if safe_platform not in {"linux/arm64", "linux/amd64"}:
        raise BuildError("platform must be linux/arm64 or linux/amd64")
    safe_base = str(base_image or "").strip().lower()
    if not _BASE_IMAGE.fullmatch(safe_base):
        raise BuildError("base image must be an immutable repository digest")
    safe_tag = str(tag or "").strip()
    if not _TAG.fullmatch(safe_tag):
        raise BuildError("build tag contains unsupported characters")
    safe_local_base = str(local_base_tag or "").strip().lower()
    if not re.fullmatch(r"[a-z0-9][a-z0-9._/-]*:[a-z0-9][a-z0-9._-]*", safe_local_base):
        raise BuildError("local base tag contains unsupported characters")
    safe_repository = str(image_repository or "").strip().lower().rstrip("/")
    if not re.fullmatch(r"[a-z0-9][a-z0-9._/-]*", safe_repository):
        raise BuildError("image repository contains unsupported characters")
    safe_toolchain = str(toolchain or "").strip().lower()
    if safe_toolchain not in _TOOLCHAINS:
        raise BuildError("toolchain must be one of: " + ", ".join(sorted(_TOOLCHAINS)))
    spec = _TOOLCHAINS[safe_toolchain]
    container_name = "rd-eval-build-" + safe_toolchain + "-" + safe_tag.lower()
    image_tag = safe_repository + ":" + safe_tag
    toolchain_label = "rd.evaluation.toolchain=" + safe_toolchain
    return {
        "platform": safe_platform,
        "baseImage": safe_base,
        "tag": image_tag,
        "buildMode": "local-content-addressed",
        "buildPullPolicy": "never",
        "installCommandText": spec["install"],
        "createCommand": [
            "docker", "create", "--name", container_name, "--user", "0:0", "--entrypoint", "/bin/sh",
            "--label", "rd.evaluation.kind=coding-benchmark", "--label", toolchain_label,
            safe_local_base, "-c", spec["install"],
        ],
        "installCommand": ["docker", "start", "-a", container_name],
        "commitCommand": [
            "docker", "commit",
            "--change", "ENV " + spec["env"],
            "--change", "LABEL rd.evaluation.kind=coding-benchmark " + toolchain_label,
            container_name, image_tag,
        ],
        "cleanupCommand": ["docker", "rm", container_name],
    }


def _run(command: list[str]) -> str:
    completed = subprocess.run(command, check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if completed.returncode != 0:
        raise BuildError(completed.stdout.strip() or "Docker command failed")
    return completed.stdout


def _inspect(tag: str) -> dict[str, Any]:
    output = _run(["docker", "image", "inspect", tag, "--format", "{{json .}}"])
    try:
        value = json.loads(output)
    except json.JSONDecodeError as error:
        raise BuildError("Docker returned an unreadable image inspection payload") from error
    if not isinstance(value, dict):
        raise BuildError("Docker returned an unreadable image inspection payload")
    return value


def _verify_local_base(local_base_tag: str, base_image: str, platform: str) -> None:
    inspected = _inspect(local_base_tag)
    digests = {str(value).lower() for value in inspected.get("RepoDigests", [])}
    if base_image not in digests:
        raise BuildError("local base tag does not resolve to the required immutable repository digest")
    actual_platform = str(inspected.get("Os", "")).lower() + "/" + str(inspected.get("Architecture", "")).lower()
    if actual_platform != platform:
        raise BuildError(f"local base platform {actual_platform} does not match frozen platform {platform}")


def build_java_toolchain_image(
    platform: str,
    base_image: str,
    tag: str,
    output_path: Path,
    *,
    local_base_tag: str = "rd-bot/pi-agent:local",
    image_repository: str = "rd-bot/coding-eval-java17",
    toolchain: str = "java17",
) -> dict[str, Any]:
    """Install Java only during preparation, then attest a reusable immutable local layer."""

    plan = build_plan(platform, base_image, tag, local_base_tag=local_base_tag,
                      image_repository=image_repository, toolchain=toolchain)
    _verify_local_base(local_base_tag, plan["baseImage"], plan["platform"])
    created = False
    try:
        _run(plan["createCommand"])
        created = True
        _run(plan["installCommand"])
        _run(plan["commitCommand"])
    finally:
        if created:
            _run(plan["cleanupCommand"])
    inspection = _inspect(plan["tag"])
    image_id = str(inspection.get("Id", "")).lower()
    if not _DIGEST.fullmatch(image_id):
        raise BuildError("built Java toolchain image does not have a content digest")
    reference = plan["tag"].rsplit(":", 1)[0] + "@" + image_id
    report = {
        "platform": plan["platform"],
        "buildMode": plan["buildMode"],
        "buildPullPolicy": plan["buildPullPolicy"],
        "baseImage": plan["baseImage"],
        "image": {"tag": plan["tag"], "imageId": image_id, "reference": reference,
                  "toolchain": _TOOLCHAINS[str(toolchain).strip().lower()]["reportName"]},
    }
    destination = Path(output_path).resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Build an immutable Java coding benchmark toolchain image.")
    parser.add_argument("--platform", default="linux/arm64")
    parser.add_argument("--base-image", default="rd-bot/pi-agent@sha256:07dcbd9d3f1603c4fd71e1e4802568edaf16a5f5a59c9ad111407a53e8616d9a")
    parser.add_argument("--tag", default="20260730-java17-v1")
    parser.add_argument("--local-base-tag", default="rd-bot/pi-agent:local")
    parser.add_argument("--image-repository", default="rd-bot/coding-eval-java17")
    parser.add_argument("--toolchain", default="java17", choices=sorted(_TOOLCHAINS))
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    build_java_toolchain_image(
        args.platform, args.base_image, args.tag, Path(args.output),
        local_base_tag=args.local_base_tag, image_repository=args.image_repository,
        toolchain=args.toolchain,
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BuildError as error:
        print(f"java toolchain build error: {error}")
        raise SystemExit(2)
