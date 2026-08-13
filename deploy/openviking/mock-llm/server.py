#!/usr/bin/env python3
"""Deterministic OpenAI-compatible embedding/VLM mock for OpenViking contract tests.

This process is not a production model. It returns hash embeddings and short
summaries so a pinned OpenViking container can exercise temp upload, task poll,
and L0/L1 without cloud keys.
"""

from __future__ import annotations

import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse


DIMENSION = 32
MODEL_EMBED = "rd-bot-mock-embed"
MODEL_CHAT = "rd-bot-mock-chat"


def embedding_for(text: str) -> list[float]:
    digest = hashlib.sha256(text.encode("utf-8")).digest()
    values = []
    for index in range(DIMENSION):
        raw = digest[index % len(digest)]
        values.append((raw / 127.5) - 1.0)
    return values


def summarize(messages: list[dict]) -> str:
    texts = []
    for message in messages:
        content = message.get("content", "")
        if isinstance(content, list):
            parts = []
            for item in content:
                if isinstance(item, dict) and item.get("type") == "text":
                    parts.append(str(item.get("text", "")))
                else:
                    parts.append(str(item))
            content = "\n".join(parts)
        texts.append(str(content))
    joined = "\n".join(texts).strip()
    snippet = joined.replace("\n", " ")[:280]
    return (
        "RD-Bot mock abstract for contract tests. "
        "The document is owned by rd-bot and should stay under the wp0-contract root. "
        f"Summary: {snippet}"
    )


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        return

    def _send(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path in {"/health", "/ready"}:
            self._send(200, {"status": "ok"})
            return
        if path in {"/v1/models", "/models"}:
            self._send(
                200,
                {
                    "object": "list",
                    "data": [
                        {"id": MODEL_EMBED, "object": "model", "owned_by": "rd-bot-mock"},
                        {"id": MODEL_CHAT, "object": "model", "owned_by": "rd-bot-mock"},
                    ],
                },
            )
            return
        self._send(404, {"error": {"message": "not found", "type": "not_found"}})

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            request = json.loads(raw.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            request = {}
        path = urlparse(self.path).path
        if path in {"/v1/embeddings", "/embeddings"}:
            raw_input = request.get("input", "")
            texts = raw_input if isinstance(raw_input, list) else [raw_input]
            data = []
            for index, item in enumerate(texts):
                text = " ".join(str(part) for part in item) if isinstance(item, list) else str(item)
                data.append(
                    {
                        "object": "embedding",
                        "index": index,
                        "embedding": embedding_for(text),
                    }
                )
            self._send(
                200,
                {
                    "object": "list",
                    "model": request.get("model") or MODEL_EMBED,
                    "data": data,
                    "usage": {"prompt_tokens": 1, "total_tokens": 1},
                },
            )
            return
        if path in {"/v1/chat/completions", "/chat/completions"}:
            content = summarize(request.get("messages") or [])
            self._send(
                200,
                {
                    "id": "chatcmpl-rd-bot-mock",
                    "object": "chat.completion",
                    "model": request.get("model") or MODEL_CHAT,
                    "choices": [
                        {
                            "index": 0,
                            "message": {"role": "assistant", "content": content},
                            "finish_reason": "stop",
                        }
                    ],
                    "usage": {
                        "prompt_tokens": 8,
                        "completion_tokens": 32,
                        "total_tokens": 40,
                    },
                },
            )
            return
        self._send(404, {"error": {"message": "not found", "type": "not_found"}})


def main() -> None:
    server = ThreadingHTTPServer(("0.0.0.0", 8080), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
