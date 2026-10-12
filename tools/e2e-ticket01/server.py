#!/usr/bin/env python3
"""Loopback-only fixture server for the real Ticket 01 Android HTTP path."""

from __future__ import annotations

import argparse
import json
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


TEST_EMAIL = "ticket01@example.test"
TEST_PASSWORD = "not-a-real-password"
AUTH_TOKEN = "ticket01-fake-token"
ACCOUNT_ID = "ticket01-fake-account"
CHILD_NAME = "테스트 아이"

SUBJECTS = {
    "hangeul": ("/v3/todohangeul/user", "/v3/todoschool/report/daily/hangeul", "ticket01-hangeul", 2, 61),
    "math": ("/v3/math/user", "/v3/todoschool/report/daily/math", "ticket01-math", 5, 120),
    "english": ("/v3/english/user", "/v3/todoschool/report/daily/english", "ticket01-english", 7, 59),
}
USERS_PATHS = {details[0]: (subject, details) for subject, details in SUBJECTS.items()}
REPORT_PATHS = {details[1]: (subject, details) for subject, details in SUBJECTS.items()}


class FixtureState:
    def __init__(self, run_id: str) -> None:
        self.run_id = run_id
        self.lock = threading.Lock()
        self.sequence = 0
        self.requests: list[dict[str, Any]] = []

    def record(self, path: str, body_text: str, body: Any, headers: dict[str, str]) -> dict[str, Any]:
        with self.lock:
            self.sequence += 1
            event = {
                "sequence": self.sequence,
                "receivedAt": datetime.now(timezone.utc).isoformat(),
                "method": "POST",
                "path": path,
                "bodyText": body_text,
                "body": body,
                "headers": headers,
                "responseStatus": None,
            }
            self.requests.append(event)
            return event

    def complete(self, event: dict[str, Any], status: int) -> None:
        with self.lock:
            event["responseStatus"] = status

    def snapshot(self) -> dict[str, Any]:
        with self.lock:
            return {"runId": self.run_id, "requests": list(self.requests)}


class Ticket01HttpServer(ThreadingHTTPServer):
    # Do not share the port with an unrelated or stale server process.
    allow_reuse_address = False


def envelope(data: Any) -> dict[str, Any]:
    return {"result": True, "data": data}


def report_data(count: int, seconds: int, array_name: str) -> dict[str, Any]:
    return {
        "contentsHistory": {array_name: [{"id": index + 1} for index in range(count)]},
        "learningSecondsByCategory": {"course": seconds, "etc": 999_999},
    }


def make_handler(state: FixtureState) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, _format: str, *_args: Any) -> None:
            return

        def _send_json(self, status: int, payload: Any) -> None:
            encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(encoded)

        def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            if self.path == "/__ticket01/health":
                self._send_json(200, {"ok": True, "runId": state.run_id})
                return
            if self.path == "/__ticket01/requests":
                self._send_json(200, state.snapshot())
                return
            self._send_json(404, {"error": "unknown path"})

        def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            raw_length = self.headers.get("Content-Length", "0")
            try:
                length = int(raw_length)
                body_text = self.rfile.read(length).decode("utf-8")
                body = json.loads(body_text)
            except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
                body_text = ""
                body = None

            headers = {
                "content-type": self.headers.get("Content-Type", ""),
                "accept": self.headers.get("Accept", ""),
                "authorization": self.headers.get("Authorization", ""),
            }
            event = state.record(self.path, body_text, body, headers)

            if self.path == "/v3/account/signin":
                if not isinstance(body, dict) or body.get("email") != TEST_EMAIL or body.get("password") != TEST_PASSWORD:
                    state.complete(event, 401)
                    self._send_json(401, {"error": "ticket01 fake credentials required"})
                    return
                state.complete(event, 200)
                self._send_json(200, envelope({"accountId": ACCOUNT_ID, "authToken": AUTH_TOKEN}))
                return

            if headers["authorization"] != f"Bearer {AUTH_TOKEN}":
                state.complete(event, 401)
                self._send_json(401, {"error": "ticket01 fake bearer token required"})
                return

            catalog = USERS_PATHS.get(self.path)
            if catalog is not None:
                _, (user_path, _report_path, user_id, _count, _seconds) = catalog
                request_user = body.get("user") if isinstance(body, dict) else None
                if not isinstance(request_user, dict) or request_user.get("accountId") != ACCOUNT_ID:
                    state.complete(event, 400)
                    self._send_json(400, {"error": "unexpected catalog request body"})
                    return
                state.complete(event, 200)
                self._send_json(200, envelope([{"name": CHILD_NAME, "userId": user_id}]))
                return

            report = REPORT_PATHS.get(self.path)
            if report is not None:
                subject, (_users_path, _report_path, user_id, count, seconds) = report
                expected_array = "daily" if subject == "math" else "dailyCourse"
                if (
                    not isinstance(body, dict)
                    or body.get("userId") != user_id
                    or not isinstance(body.get("yyyymmdd"), int)
                    or body.get("languageCode") != "ko"
                ):
                    state.complete(event, 400)
                    self._send_json(400, {"error": "unexpected report request body"})
                    return
                state.complete(event, 200)
                self._send_json(200, envelope(report_data(count, seconds, expected_array)))
                return

            state.complete(event, 404)
            self._send_json(404, {"error": "unknown TodoSchool API path"})

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    if args.host != "127.0.0.1":
        raise SystemExit("Ticket 01 fixture server must bind only to 127.0.0.1")
    if not 1 <= args.port <= 65535:
        raise SystemExit("port must be in 1..65535")

    server = Ticket01HttpServer((args.host, args.port), make_handler(FixtureState(args.run_id)))
    server.daemon_threads = True
    print(json.dumps({"ready": True, "host": args.host, "port": args.port, "runId": args.run_id}), flush=True)
    try:
        server.serve_forever(poll_interval=0.2)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
