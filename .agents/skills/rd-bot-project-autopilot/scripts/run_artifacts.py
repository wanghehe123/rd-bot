"""Bounded, redacted artifact storage for one autopilot run."""

from __future__ import annotations

import json
import os
import re
import tempfile
from pathlib import Path
from typing import Any


class ArtifactError(RuntimeError):
    """Raised when an artifact path or write cannot be handled safely."""


MAX_TEXT_CHARS = 100_000
_TRUNCATION_SUFFIX = "...[TRUNCATED]"
SECRET_KEYS = {
    "authorization",
    "api_key",
    "apikey",
    "token",
    "access_token",
    "refresh_token",
    "password",
    "secret",
    "credential",
    "cookie",
}
_NORMALIZED_SECRET_KEYS = {re.sub(r"[-_]", "", secret) for secret in SECRET_KEYS}
_BEARER_RE = re.compile(r"\bBearer\s+[^\s,;]+", re.IGNORECASE)
_URL_USERINFO_RE = re.compile(r"(https?://)[^/\s:@]+(?::[^@/\s]*)?@", re.IGNORECASE)
_API_KEY_RE = re.compile(r"\b(?:sk|key|token)[-_][A-Za-z0-9_-]{12,}\b", re.IGNORECASE)


def _truncate(value: str, max_chars: int) -> str:
    if len(value) <= max_chars:
        return value
    if max_chars <= len(_TRUNCATION_SUFFIX):
        return _TRUNCATION_SUFFIX[:max_chars]
    return value[: max_chars - len(_TRUNCATION_SUFFIX)] + _TRUNCATION_SUFFIX


def _redact_string(value: str, max_chars: int) -> str:
    value = _BEARER_RE.sub("Bearer [REDACTED]", value)
    value = _URL_USERINFO_RE.sub(r"\1[REDACTED]@", value)
    value = _API_KEY_RE.sub("[REDACTED]", value)
    return _truncate(value, max_chars)


def redact(value: Any, *, max_chars: int = MAX_TEXT_CHARS) -> Any:
    """Return a bounded copy with common credentials and secret fields removed."""

    if isinstance(value, dict):
        result: dict[Any, Any] = {}
        for key, item in value.items():
            normalized_key = re.sub(r"[-_]", "", key.lower()) if isinstance(key, str) else ""
            if normalized_key in _NORMALIZED_SECRET_KEYS:
                result[key] = "[REDACTED]"
            else:
                result[key] = redact(item, max_chars=max_chars)
        return result
    if isinstance(value, list):
        return [redact(item, max_chars=max_chars) for item in value]
    if isinstance(value, tuple):
        return tuple(redact(item, max_chars=max_chars) for item in value)
    if isinstance(value, str):
        return _redact_string(value, max_chars)
    return value


class RunArtifacts:
    """Write only beneath an allocated run directory using atomic replacement."""

    def __init__(self, run_dir: str | os.PathLike[str]) -> None:
        self.run_dir = Path(run_dir).expanduser().resolve()
        self.run_dir.mkdir(parents=True, exist_ok=True)

    def _child(self, relative_path: str | os.PathLike[str]) -> Path:
        candidate = (self.run_dir / Path(relative_path)).resolve()
        try:
            candidate.relative_to(self.run_dir)
        except ValueError as exc:
            raise ArtifactError("artifact path is outside run directory") from exc
        return candidate

    def _atomic_write(self, path: Path, payload: bytes) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
        try:
            with os.fdopen(fd, "wb") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_name, path)
        except Exception as exc:
            try:
                os.unlink(temp_name)
            except FileNotFoundError:
                pass
            raise ArtifactError(f"failed to write artifact: {path}") from exc
        return path

    def write_text(self, relative_path: str | os.PathLike[str], text: str) -> Path:
        path = self._child(relative_path)
        safe_text = redact(text)
        return self._atomic_write(path, str(safe_text).encode("utf-8"))

    def write_json(self, relative_path: str | os.PathLike[str], value: Any) -> Path:
        path = self._child(relative_path)
        payload = json.dumps(redact(value), ensure_ascii=False).encode("utf-8") + b"\n"
        return self._atomic_write(path, payload)

    def append_jsonl(self, relative_path: str | os.PathLike[str], value: Any) -> Path:
        """Append one bounded JSON line; callers should use this only for run-local ledgers."""

        path = self._child(relative_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        line = json.dumps(redact(value), ensure_ascii=False).encode("utf-8") + b"\n"
        try:
            with path.open("ab") as handle:
                handle.write(line)
                handle.flush()
                os.fsync(handle.fileno())
        except OSError as exc:
            raise ArtifactError(f"failed to append artifact: {path}") from exc
        return path
