# =============================================================================
# relay.py — RD-Bot Evaluation V2 One-Shot HTTP Relay (aiohttp)
# =============================================================================
# Minimal, security-hardened HTTP relay for the Evaluation V2 network topology.
#
# Design principles (spec §7.3):
#   1. One-shot auth: each trial gets a fresh bearer token; the relay does
#      not maintain session state between requests.
#   2. Frozen profile: at startup the relay reads model + upstream_url +
#      allowed_paths from a JSON file written by the host orchestrator.
#      Once loaded, the profile is immutable — no dynamic re-configuration.
#   3. Path / method whitelist: only POST requests to explicitly listed
#      paths are forwarded; everything else returns 403.
#   4. Credential isolation: the upstream API key lives only in the relay's
#      own environment variable RD_RELAY_UPSTREAM_API_KEY (set by the host
#      before container start, never stored in an image layer).  The agent
#      container never sees this key.
#   5. Optional secondary upstream: when profile.fallback is present and
#      RD_RELAY_FALLBACK_API_KEY is set, quota/auth failures on the primary
#      are retried once against the fallback provider (cost-saving degrade).
#   6. Minimal logging: logs only (remote_addr, method, path, status, model).
#      Request bodies, response bodies, and credential values are never logged.
#
# Usage:
#   python3 relay.py
#     [listens on 0.0.0.0:8765 by default; override with RD_RELAY_PORT env]
#
# Required environment variables (set by host orchestrator):
#   RD_RELAY_TOKEN          — one-shot bearer token for this trial
#   RD_RELAY_PROFILE_PATH   — path to frozen profile JSON (default /etc/relay/profile.json)
#   RD_RELAY_UPSTREAM_API_KEY — upstream API key (host-side; never in image layers)
#   RD_RELAY_FALLBACK_API_KEY — optional secondary upstream API key
#   RD_RELAY_PORT           — optional; defaults to 8765
#   RD_RELAY_TOKEN_FILE     — optional; if set, the file is removed at startup
#
# Profile JSON schema:
#   {
#     "model": "gpt-4o",
#     "upstream_url": "https://api.openai.com/v1",
#     "allowed_paths": ["/v1/chat/completions", "/v1/models"],
#     "fallback": {
#       "name": "deepseek",
#       "model": "deepseek-v4-flash",
#       "upstream_url": "https://api.deepseek.com"
#     }
#   }
# =============================================================================

import asyncio
import json
import logging
import os
import sys
import time
from typing import Optional, Set, Tuple

import aiohttp
from aiohttp import web

from relay_compat import remap_developer_to_system_role

# ── logging ────────────────────────────────────────────────────────────────────
# Logs are written to stdout in structured key=value format so the host
# log aggregator can parse them.  NO credential values, request bodies,
# or response bodies are ever emitted.
log = logging.getLogger("relay")
log.setLevel(logging.INFO)

# Primary upstream statuses that warrant a one-shot fallback retry AFTER primary retries.
_FALLBACK_HTTP_STATUSES = frozenset({401, 402, 403, 429, 500, 502, 503, 504})
# Transient statuses: retry primary before considering fallback.
_RETRYABLE_HTTP_STATUSES = frozenset({429, 500, 502, 503, 504})
_PRIMARY_MAX_ATTEMPTS = 3
_PRIMARY_RETRY_BACKOFF_SECONDS = (0.4, 1.0)
_QUOTA_MARKERS = (
    "insufficient_quota",
    "quota",
    "rate_limit",
    "rate limit",
    "billing",
    "balance",
    "credit",
    "payment required",
    "exceeded your current quota",
)


class MinimalLogFormatter(logging.Formatter):
    """Emit only timestamp + level + message; never format request/response bodies."""

    def format(self, record: logging.LogRecord) -> str:
        ts = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(record.created))
        subsec = f"{record.msecs:.3f}"[:12]
        return f'{ts}.{subsec}Z  relay  {record.levelname:<7}  {record.getMessage()}'


def _setup_logging() -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(MinimalLogFormatter())
    log.addHandler(handler)
    # Suppress aiohttp access log (too verbose; we emit our own minimal logs).


# ── profile loading ────────────────────────────────────────────────────────────

class FallbackUpstream:
    """Optional secondary upstream used after primary quota/auth failure."""

    def __init__(self, raw: dict) -> None:
        self.name: str = str(raw.get("name") or "fallback").strip() or "fallback"
        self.model: str = str(raw.get("model") or "").strip()
        self.upstream_url: str = str(raw.get("upstream_url") or "").rstrip("/")
        if not self.model or not self.upstream_url:
            raise ValueError("fallback must contain non-empty 'model' and 'upstream_url'")


class FrozenProfile:
    """Immutable, frozen relay configuration loaded once at startup."""

    def __init__(self, profile_path: str) -> None:
        with open(profile_path, "r", encoding="utf-8") as fh:
            raw = json.load(fh)

        self.model: str = raw["model"]
        self.upstream_url: str = raw["upstream_url"].rstrip("/")
        self.allowed_paths: Set[str] = set(raw.get("allowed_paths", []))
        self.fallback: Optional[FallbackUpstream] = None
        fallback_raw = raw.get("fallback")
        if isinstance(fallback_raw, dict) and fallback_raw:
            self.fallback = FallbackUpstream(fallback_raw)

        if not self.model or not self.upstream_url:
            raise ValueError("profile must contain non-empty 'model' and 'upstream_url'")
        if not self.allowed_paths:
            raise ValueError("profile must contain at least one path in 'allowed_paths'")

        log.info(
            'profile.loaded  model="%s"  upstream="%s"  fallback="%s"  allowed_paths=%r',
            self.model,
            self.upstream_url,
            self.fallback.name if self.fallback else "",
            sorted(self.allowed_paths),
        )

    def is_path_allowed(self, path: str) -> bool:
        return path in self.allowed_paths


# ── token management ───────────────────────────────────────────────────────────

def _clear_token_from_env(token: str) -> None:
    """
    Overwrite the RD_RELAY_TOKEN value in the current process environment
    with a sentinel before the relay starts handling requests.
    Also remove RD_RELAY_TOKEN_FILE if it points to a writable path.
    """
    os.environ["RD_RELAY_TOKEN"] = "\u2757REDEEMED\u2757"
    token_file = os.environ.get("RD_RELAY_TOKEN_FILE", "")
    if token_file and os.path.exists(token_file):
        try:
            os.unlink(token_file)
            log.info("token.file.removed  path=%s", token_file)
        except OSError as exc:
            log.warning("token.file.remove.failed  path=%s  error=%s", token_file, exc)
    log.info("token.cleared  status=consumed")


def _validate_bearer(request: web.Request, expected_token: str) -> Optional[web.Response]:
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        log.warning("auth.rejected  reason=missing_bearer  remote=%s", request.remote)
        return web.json_response(
            {"error": "unauthorized", "message": "Authorization Bearer token required"},
            status=401,
            headers={"Content-Type": "application/json"},
        )
    provided = auth[len("Bearer "):].strip()
    if provided != expected_token:
        log.warning("auth.rejected  reason=token_mismatch  remote=%s", request.remote)
        return web.json_response(
            {"error": "unauthorized", "message": "Invalid relay token"},
            status=401,
            headers={"Content-Type": "application/json"},
        )
    return None


def should_fallback(status: int, body: bytes) -> bool:
    """Return True when a primary upstream response should trigger fallback."""
    if status in _FALLBACK_HTTP_STATUSES:
        return True
    if status < 400:
        return False
    try:
        text = body.decode("utf-8", errors="ignore").lower()
    except Exception:
        return False
    return any(marker in text for marker in _QUOTA_MARKERS)


def should_retry_primary(status: int, body: bytes) -> bool:
    """Return True when the primary upstream failure looks transient and worth retrying."""
    if status in _RETRYABLE_HTTP_STATUSES:
        return True
    if status < 400:
        return False
    try:
        text = body.decode("utf-8", errors="ignore").lower()
    except Exception:
        return False
    return "rate_limit" in text or "rate limit" in text or "timeout" in text or "temporarily" in text


def rewrite_request_model(body: bytes, model: str) -> bytes:
    """Force the OpenAI-compatible request body to use the selected upstream model."""
    if not body or not model:
        return body
    try:
        payload = json.loads(body)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return body
    if not isinstance(payload, dict):
        return body
    if payload.get("model") == model:
        return body
    payload["model"] = model
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


# ── upstream forwarding ────────────────────────────────────────────────────────

async def _post_upstream(
    upstream_url: str,
    upstream_path: str,
    upstream_api_key: str,
    headers: dict,
    body: bytes,
) -> Tuple[int, bytes]:
    url = f"{upstream_url}{upstream_path}"
    upstream_headers = dict(headers)
    upstream_headers["Authorization"] = f"Bearer {upstream_api_key}"
    upstream_headers["Content-Type"] = "application/json"

    async with aiohttp.ClientSession() as session:
        async with session.request(
            method="POST",
            url=url,
            headers=upstream_headers,
            data=body,
            timeout=aiohttp.ClientTimeout(total=120),
            allow_redirects=False,
        ) as resp:
            return resp.status, await resp.read()


async def _forward_to_upstream(
    request: web.Request,
    profile: FrozenProfile,
    upstream_api_key: str,
    fallback_api_key: str,
) -> web.Response:
    """
    Forward the validated request to the upstream LLM API.
    Strips any Authorization header from the incoming request (agent must not
    supply credentials — the relay injects them from its own env).
    Adds X-Forwarded-For for traceability.
    Retries the primary upstream on transient failures before optional fallback.
    """
    upstream_path = request.path  # already validated as allowed by caller

    # Never forward hop-by-hop / client Host headers: DeepSeek (and most CDNs)
    # reject requests whose Host does not match the upstream authority (seen as HTTP 418).
    drop_headers = {
        "authorization",
        "host",
        "content-length",
        "transfer-encoding",
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailers",
        "upgrade",
        "cookie",
    }
    upstream_headers = {}
    for key, val in request.headers.items():
        if key.lower() in drop_headers:
            continue
        upstream_headers[key] = val
    upstream_headers["X-Forwarded-For"] = request.remote or "unknown"

    body = await request.read() if request.can_read_body else b""
    body = remap_developer_to_system_role(body)
    primary_body = rewrite_request_model(body, profile.model)

    upstream_status = 502
    upstream_body = b'{"error":"upstream_error","message":"primary upstream not attempted"}'
    for attempt in range(1, _PRIMARY_MAX_ATTEMPTS + 1):
        try:
            upstream_status, upstream_body = await _post_upstream(
                profile.upstream_url,
                upstream_path,
                upstream_api_key,
                upstream_headers,
                primary_body,
            )
            log.info(
                "upstream.response  status=%d  model=%s  path=%s  provider=primary  attempt=%d",
                upstream_status,
                profile.model,
                upstream_path,
                attempt,
            )
        except aiohttp.ClientError as exc:
            log.error(
                "upstream.error  error=%s  model=%s  path=%s  attempt=%d",
                exc,
                profile.model,
                upstream_path,
                attempt,
            )
            upstream_status, upstream_body = 502, json.dumps(
                {"error": "upstream_error", "message": str(exc)}
            ).encode("utf-8")
        except asyncio.TimeoutError:
            log.error(
                "upstream.timeout  model=%s  path=%s  attempt=%d",
                profile.model,
                upstream_path,
                attempt,
            )
            upstream_status, upstream_body = 504, json.dumps(
                {"error": "timeout", "message": "Upstream request timed out after 120s"}
            ).encode("utf-8")

        if upstream_status < 400:
            break
        if attempt >= _PRIMARY_MAX_ATTEMPTS or not should_retry_primary(upstream_status, upstream_body):
            break
        backoff = _PRIMARY_RETRY_BACKOFF_SECONDS[min(attempt - 1, len(_PRIMARY_RETRY_BACKOFF_SECONDS) - 1)]
        log.info(
            "upstream.retry  model=%s  path=%s  next_attempt=%d  backoff_s=%.1f  last_status=%d",
            profile.model,
            upstream_path,
            attempt + 1,
            backoff,
            upstream_status,
        )
        await asyncio.sleep(backoff)

    can_fallback = (
        profile.fallback is not None
        and bool(fallback_api_key)
        and should_fallback(upstream_status, upstream_body)
    )
    if can_fallback:
        assert profile.fallback is not None
        fallback_body = rewrite_request_model(body, profile.fallback.model)
        try:
            fallback_status, fallback_resp = await _post_upstream(
                profile.fallback.upstream_url,
                upstream_path,
                fallback_api_key,
                upstream_headers,
                fallback_body,
            )
            log.info(
                "upstream.response  status=%d  model=%s  path=%s  provider=fallback(%s)  primary_status=%d",
                fallback_status,
                profile.fallback.model,
                upstream_path,
                profile.fallback.name,
                upstream_status,
            )
            upstream_status = fallback_status
            upstream_body = fallback_resp
        except aiohttp.ClientError as fallback_exc:
            log.error(
                "upstream.fallback.error  error=%s  model=%s  path=%s",
                fallback_exc,
                profile.fallback.model,
                upstream_path,
            )
        except asyncio.TimeoutError:
            log.error(
                "upstream.fallback.timeout  model=%s  path=%s",
                profile.fallback.model,
                upstream_path,
            )

    return web.Response(
        body=upstream_body,
        status=upstream_status,
        headers={"Content-Type": "application/json"},
    )


# ── route handler ─────────────────────────────────────────────────────────────

async def relay_handler(request: web.Request) -> web.Response:
    """
    Single handler for all non-health requests.
    Enforces: bearer token + path whitelist + POST-only.
    Forwards allowed requests to the upstream LLM.
    """
    token = request.app["relay_token"]
    profile: FrozenProfile = request.app["profile"]
    upstream_key = request.app["upstream_api_key"]
    fallback_key = request.app.get("fallback_api_key", "")

    # ── 1. Token validation ──────────────────────────────────────────────────
    token_error = _validate_bearer(request, token)
    if token_error is not None:
        return token_error

    # ── 2. Method whitelist (POST only) ───────────────────────────────────────
    if request.method != "POST":
        log.warning("method.rejected  method=%s  path=%s  remote=%s",
                    request.method, request.path, request.remote)
        return web.json_response(
            {"error": "forbidden", "message": f"Method {request.method} not allowed; only POST is permitted"},
            status=403,
            headers={"Content-Type": "application/json"},
        )

    # ── 3. Path whitelist ─────────────────────────────────────────────────────
    if not profile.is_path_allowed(request.path):
        log.warning("path.rejected  path=%s  remote=%s  allowed_paths=%s",
                    request.path, request.remote, sorted(profile.allowed_paths))
        return web.json_response(
            {"error": "forbidden", "message": f"Path {request.path} is not in the allowed paths list"},
            status=403,
            headers={"Content-Type": "application/json"},
        )

    # ── 4. CONNECT and redirect are blocked at the aiohttp level ─────────────
    # aiohttp.ClientSession.request with allow_redirects=False above handles
    # redirect blocking.  CONNECT is handled by never creating a TCP tunnel.

    # ── 5. Forward to upstream (with optional fallback) ───────────────────────
    return await _forward_to_upstream(request, profile, upstream_key, fallback_key)


async def health_handler(request: web.Request) -> web.Response:
    """Simple liveness probe — no auth required."""
    return web.Response(status=200, text="OK")


# ── application factory ───────────────────────────────────────────────────────

def create_app(
    token: str,
    profile_path: str,
    upstream_api_key: str,
    fallback_api_key: str = "",
) -> web.Application:
    """
    Build the aiohttp application.  Called once at startup.
    Token is consumed immediately; profile is loaded and frozen.
    """
    app = web.Application()

    app["relay_token"] = token
    app["upstream_api_key"] = upstream_api_key
    app["fallback_api_key"] = fallback_api_key or ""
    app["profile"] = FrozenProfile(profile_path)

    app.router.add_get("/health", health_handler)
    # All other paths (including GET /, POST /, etc.) go to the relay handler.
    # This ensures that non-allowed paths return 403 rather than 404.
    app.router.add_route("*", "/{tail:.*}", relay_handler)

    return app


# ── entrypoint ────────────────────────────────────────────────────────────────

def main() -> None:
    _setup_logging()

    # ── read required env vars ────────────────────────────────────────────────
    token = os.environ.get("RD_RELAY_TOKEN", "")
    if not token:
        log.critical("RD_RELAY_TOKEN is not set; refusing to start")
        sys.exit(1)

    profile_path = os.environ.get("RD_RELAY_PROFILE_PATH", "/etc/relay/profile.json")
    if not os.path.isfile(profile_path):
        log.critical("RD_RELAY_PROFILE_PATH=%s does not exist or is not a file", profile_path)
        sys.exit(1)

    upstream_api_key = os.environ.get("RD_RELAY_UPSTREAM_API_KEY", "")
    if not upstream_api_key:
        log.warning("RD_RELAY_UPSTREAM_API_KEY is empty; upstream requests will fail")
    fallback_api_key = os.environ.get("RD_RELAY_FALLBACK_API_KEY", "")

    # ── clear one-shot token from process environment ─────────────────────────
    _clear_token_from_env(token)
    log.info(
        "relay.starting  version=1.1.0  profile_path=%s  upstream_key_present=%s  fallback_key_present=%s",
        profile_path,
        bool(upstream_api_key),
        bool(fallback_api_key),
    )

    # ── build and run app ────────────────────────────────────────────────────
    app = create_app(token, profile_path, upstream_api_key, fallback_api_key)

    port = int(os.environ.get("RD_RELAY_PORT", "8765"))
    host = os.environ.get("RD_RELAY_HOST", "0.0.0.0")

    log.info("relay.listening  host=%s  port=%d", host, port)
    web.run_app(app, host=host, port=port, print=None, access_log=None)


if __name__ == "__main__":
    main()
