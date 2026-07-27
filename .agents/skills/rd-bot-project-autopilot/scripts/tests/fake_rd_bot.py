from __future__ import annotations

import json
import threading
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


class FakeRdBotServer:
    """Small deterministic HTTP server used only by the client contract tests."""

    def __init__(self) -> None:
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args: object) -> None:
                return

            def do_GET(self) -> None:  # noqa: N802
                owner._handle(self)

            def do_POST(self) -> None:  # noqa: N802
                owner._handle(self)

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread: threading.Thread | None = None
        self.requests: list[dict[str, object]] = []
        self._responses: deque[tuple[int, dict[str, str], bytes]] = deque()

    @property
    def base_url(self) -> str:
        host, port = self._server.server_address[:2]
        return f"http://{host}:{port}"

    def __enter__(self) -> "FakeRdBotServer":
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *_args: object) -> None:
        self._server.shutdown()
        self._server.server_close()
        if self._thread:
            self._thread.join(timeout=2)

    def queue_json(self, body: object, status: int = 200, headers: dict[str, str] | None = None) -> None:
        payload = json.dumps(body).encode("utf-8")
        response_headers = {"Content-Type": "application/json", **(headers or {})}
        self._responses.append((status, response_headers, payload))

    def queue_redirect(self) -> None:
        self._responses.append((302, {"Location": "https://example.com/"}, b""))

    def queue_response(self, status: int, body: bytes, headers: dict[str, str] | None = None) -> None:
        self._responses.append((status, headers or {}, body))

    def _handle(self, handler: BaseHTTPRequestHandler) -> None:
        length = int(handler.headers.get("Content-Length", "0"))
        raw_body = handler.rfile.read(length) if length else b""
        parsed_body: object = None
        if raw_body:
            try:
                parsed_body = json.loads(raw_body.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                parsed_body = "<non-json>"
        self.requests.append(
            {
                "method": handler.command,
                "path": urlsplit(handler.path).path,
                "query": urlsplit(handler.path).query,
                "json": parsed_body,
            }
        )
        status, headers, payload = self._responses.popleft() if self._responses else (
            200,
            {"Content-Type": "application/json"},
            b'{"data": {}}',
        )
        handler.send_response(status)
        for key, value in headers.items():
            handler.send_header(key, value)
        handler.send_header("Content-Length", str(len(payload)))
        handler.end_headers()
        if payload:
            handler.wfile.write(payload)
