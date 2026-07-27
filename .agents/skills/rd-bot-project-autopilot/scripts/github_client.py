"""Fixed-command GitHub CLI adapter for private personal project provisioning."""

from __future__ import annotations

import json
import re
import subprocess
from collections.abc import Callable, Mapping
from typing import Any

from .run_artifacts import redact


MAX_OUTPUT_BYTES = 2 * 1024 * 1024
DEFAULT_TIMEOUT_SECONDS = 20.0
_OWNER_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
_REPOSITORY_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,99}$")
_MARKER_RE = re.compile(r"\[autopilot:autopilot-[a-z0-9-]{1,80}:github:[0-9a-f]{12}\]")
_CREDENTIAL_RE = re.compile(
    r"\b(?:ghp|github_pat|sk|key|token)[_-][A-Za-z0-9_-]{12,}\b",
    re.IGNORECASE,
)


class GitHubPolicyError(RuntimeError):
    """Raised before a subprocess call when a GitHub action violates policy."""


class GitHubTransportError(RuntimeError):
    """Raised for a CLI timeout or launch failure; POST errors may be ambiguous."""

    def __init__(self, message: str, *, ambiguous: bool) -> None:
        super().__init__(message)
        self.ambiguous = ambiguous


class GitHubResponseError(RuntimeError):
    """Raised when gh returns a known failure or invalid bounded response."""


Runner = Callable[[list[str], float], subprocess.CompletedProcess[str]]


def _default_runner(argv: list[str], timeout_seconds: float) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        argv,
        shell=False,
        check=False,
        capture_output=True,
        text=True,
        timeout=timeout_seconds,
    )


class GitHubClient:
    """Expose only the gh commands needed by the Provision Plan protocol."""

    def __init__(self, *, runner: Runner | None = None, timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS) -> None:
        if timeout_seconds <= 0:
            raise GitHubPolicyError("GitHub CLI timeout must be positive")
        self._runner = runner or _default_runner
        self.timeout_seconds = timeout_seconds

    def authenticated_user(self) -> str:
        self._run(["gh", "auth", "status"], write=False)
        response = self._json(self._run(["gh", "api", "user"], write=False))
        login = response.get("login")
        if not isinstance(login, str) or not _OWNER_RE.fullmatch(login):
            raise GitHubResponseError("GitHub authenticated user response has no safe login")
        return login

    def create_private_repository(self, *, owner: str, name: str, description: str) -> Mapping[str, Any]:
        clean_owner = _require_owner(owner)
        clean_name = _require_repository_name(name)
        clean_description = _require_description(description)
        authenticated_owner = self.authenticated_user()
        if clean_owner.casefold() != authenticated_owner.casefold():
            raise GitHubPolicyError("repository owner must equal the authenticated personal GitHub user")
        argv = [
            "gh", "api", "--method", "POST", "/user/repos",
            "-f", f"name={clean_name}",
            "-f", f"description={clean_description}",
            "-F", "private=true",
            "-F", "auto_init=true",
        ]
        response = self._json(self._run(argv, write=True))
        return _validate_repository(response, clean_owner, clean_name)

    def get_repository(self, owner: str, name: str) -> Mapping[str, Any]:
        clean_owner = _require_owner(owner)
        clean_name = _require_repository_name(name)
        response = self._json(self._run(["gh", "api", f"/repos/{clean_owner}/{clean_name}"], write=False))
        return _validate_repository(response, clean_owner, clean_name)

    def _run(self, argv: list[str], *, write: bool) -> str:
        if not _allowed_argv(argv):
            raise GitHubPolicyError("GitHub command is outside the provisioning allowlist")
        try:
            result = self._runner(list(argv), self.timeout_seconds)
        except subprocess.TimeoutExpired as exc:
            raise GitHubTransportError("GitHub CLI timed out", ambiguous=write) from exc
        except OSError as exc:
            raise GitHubTransportError("GitHub CLI could not start", ambiguous=write) from exc
        if not isinstance(result, subprocess.CompletedProcess):
            raise GitHubResponseError("GitHub runner returned an invalid result")
        stdout = _bounded_output(result.stdout)
        stderr = _bounded_output(result.stderr)
        if result.returncode != 0:
            safe_error = str(redact(stderr, max_chars=2_000)).strip()
            message = "GitHub CLI returned a non-zero status"
            if safe_error:
                message = f"{message}: {safe_error}"
            raise GitHubResponseError(message)
        return stdout

    @staticmethod
    def _json(value: str) -> Mapping[str, Any]:
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError as exc:
            raise GitHubResponseError("GitHub CLI returned malformed JSON") from exc
        if not isinstance(parsed, Mapping):
            raise GitHubResponseError("GitHub CLI response must be a JSON object")
        return parsed


def _allowed_argv(argv: list[str]) -> bool:
    if argv == ["gh", "auth", "status"] or argv == ["gh", "api", "user"]:
        return True
    if len(argv) == 3 and argv[0:2] == ["gh", "api"]:
        path = argv[2]
        return bool(re.fullmatch(r"/repos/[A-Za-z0-9][A-Za-z0-9-]{0,38}/[a-z0-9][a-z0-9-]{0,99}", path))
    if len(argv) != 13 or argv[:5] != ["gh", "api", "--method", "POST", "/user/repos"]:
        return False
    if argv[5] != "-f" or not argv[6].startswith("name="):
        return False
    if argv[7] != "-f" or not argv[8].startswith("description="):
        return False
    return argv[9:] == ["-F", "private=true", "-F", "auto_init=true"]


def _validate_repository(value: Mapping[str, Any], owner: str, name: str) -> Mapping[str, Any]:
    remote_owner = value.get("owner")
    remote_login = remote_owner.get("login") if isinstance(remote_owner, Mapping) else None
    if not isinstance(remote_login, str) or remote_login.casefold() != owner.casefold():
        raise GitHubResponseError("GitHub repository owner does not match the authenticated user")
    if value.get("private") is not True:
        raise GitHubResponseError("GitHub repository is not private")
    if value.get("name") not in {None, name}:
        raise GitHubResponseError("GitHub repository name does not match the request")
    if value.get("full_name") not in {None, f"{owner}/{name}"}:
        raise GitHubResponseError("GitHub repository full name does not match the request")
    branch = value.get("default_branch")
    if not isinstance(branch, str) or not branch.strip() or len(branch) > 120:
        raise GitHubResponseError("GitHub repository default branch is invalid")
    return dict(redact(dict(value)))


def _require_owner(value: str) -> str:
    if not isinstance(value, str) or not _OWNER_RE.fullmatch(value):
        raise GitHubPolicyError("GitHub owner is invalid")
    return value


def _require_repository_name(value: str) -> str:
    if not isinstance(value, str) or not _REPOSITORY_RE.fullmatch(value):
        raise GitHubPolicyError("GitHub repository name is invalid")
    return value


def _require_description(value: str) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > 500:
        raise GitHubPolicyError("GitHub repository description is invalid")
    if not _MARKER_RE.search(value):
        raise GitHubPolicyError("GitHub repository description must contain the provision marker")
    if _CREDENTIAL_RE.search(value):
        raise GitHubPolicyError("GitHub repository description contains credential-shaped content")
    return value.strip()


def _bounded_output(value: Any) -> str:
    if value is None:
        return ""
    if not isinstance(value, str):
        raise GitHubResponseError("GitHub CLI output is not text")
    if len(value.encode("utf-8")) > MAX_OUTPUT_BYTES:
        raise GitHubResponseError("GitHub CLI output exceeds the safety limit")
    return value
