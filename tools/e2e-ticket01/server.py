#!/usr/bin/env python3
"""Loopback-only fixture server for the real Ticket 01 Android HTTP path."""

from __future__ import annotations

import argparse
import json
import select
import socket
import threading
import time
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
    def __init__(self, run_id: str, scenario: str) -> None:
        self.run_id = run_id
        self.scenario = scenario
        self.lock = threading.Lock()
        self.sequence = 0
        self.requests: list[dict[str, Any]] = []
        self.released_report_holds: list[dict[str, Any]] = []
        self.armed_report_hold: dict[str, Any] | None = None
        self.active_report_hold: dict[str, Any] | None = None
        self.armed_report_response: dict[str, Any] | None = None
        self.ticket06_pre_removal_preferences_captured = False
        self.ticket06_pre_reboot_preferences_captured = False
        self.report_counts = (
            {subject: 2 for subject in SUBJECTS}
            if scenario == "ticket03"
            else {subject: details[3] for subject, details in SUBJECTS.items()}
        )
        if scenario == "ticket04":
            self.report_counts = {"hangeul": 2, "math": 5, "english": 7}

    def record(self, path: str, body_text: str, body: Any, headers: dict[str, str]) -> dict[str, Any]:
        with self.lock:
            self.sequence += 1
            received_monotonic_ns = time.monotonic_ns()
            event = {
                "sequence": self.sequence,
                "receivedAt": datetime.now(timezone.utc).isoformat(),
                "method": "POST",
                "path": path,
                "bodyText": body_text,
                "body": body,
                "headers": headers,
                "responseStatus": None,
                "servedReportCount": None,
                "serverReceivedMonotonicMs": received_monotonic_ns // 1_000_000,
                "serverReceivedMonotonicNs": received_monotonic_ns,
                "clientConnected": True,
                "clientDisconnectedAt": None,
                "clientDisconnectSignal": None,
                "clientDisconnectElapsedMs": None,
                "clientDisconnectMonotonicNs": None,
            }
            self.requests.append(event)
            return event

    def complete(
        self,
        event: dict[str, Any],
        status: int,
        served_report_count: int | None = None,
        delivery_status: str = "written",
    ) -> None:
        with self.lock:
            event["responseStatus"] = status
            event["servedReportCount"] = served_report_count
            event["deliveryStatus"] = delivery_status
            case_id = event.get("holdCaseId")
            if case_id is not None and not any(
                hold["caseId"] == case_id and hold["sequence"] == event["sequence"]
                for hold in self.released_report_holds
            ):
                self.released_report_holds.append({
                    "sequence": event["sequence"],
                    "caseId": case_id,
                    "path": event["path"],
                    "responseStatus": status,
                    "servedReportCount": served_report_count,
                    "deliveryStatus": delivery_status,
                    "clientConnected": event["clientConnected"],
                    "serverReceivedMonotonicNs": event["serverReceivedMonotonicNs"],
                    "clientDisconnectedAt": event["clientDisconnectedAt"],
                    "clientDisconnectSignal": event["clientDisconnectSignal"],
                    "clientDisconnectAfterServerReceiveMs": event.get("clientDisconnectAfterServerReceiveMs"),
                    "clientDisconnectMonotonicNs": event.get("clientDisconnectMonotonicNs"),
                    "userId": event["body"].get("userId") if isinstance(event.get("body"), dict) else None,
                    "yyyymmdd": event["body"].get("yyyymmdd") if isinstance(event.get("body"), dict) else None,
                    "languageCode": event["body"].get("languageCode") if isinstance(event.get("body"), dict) else None,
                })

    def arm_report_hold(
        self,
        case_id: str,
        path: str,
        response_status: int,
        response_count: int | None,
    ) -> dict[str, Any]:
        with self.lock:
            if self.scenario != "ticket04":
                raise ValueError("report holds are available only in Ticket 04")
            if self.armed_report_hold is not None or self.active_report_hold is not None:
                raise ValueError("a report hold is already armed or in flight")
            if not case_id or len(case_id) > 80:
                raise ValueError("caseId must be a non-empty string of at most 80 characters")
            if path not in REPORT_PATHS:
                raise ValueError("path must be one of the supported report paths")
            if response_status not in (200, 401, 403):
                raise ValueError("responseStatus must be 200, 401, or 403")
            if response_count is not None and (isinstance(response_count, bool) or not 0 <= response_count <= 100):
                raise ValueError("responseCount must be an integer from 0 to 100")
            if response_status != 200 and response_count is not None:
                raise ValueError("responseCount is only valid for a successful report response")
            self.armed_report_hold = {
                "caseId": case_id,
                "path": path,
                "responseStatus": response_status,
                "responseCount": response_count,
                "release": threading.Event(),
            }
            return {key: value for key, value in self.armed_report_hold.items() if key != "release"}

    def hold_for_report(self, path: str, event: dict[str, Any]) -> dict[str, Any] | None:
        with self.lock:
            if self.armed_report_hold is None or self.armed_report_hold["path"] != path:
                return None
            hold = self.armed_report_hold
            self.armed_report_hold = None
            self.active_report_hold = {**hold, "sequence": event["sequence"]}
            event["holdCaseId"] = hold["caseId"]
            return self.active_report_hold

    def release_report_hold(self, case_id: str, sequence: int) -> dict[str, Any]:
        with self.lock:
            hold = self.active_report_hold
            if hold is None or hold["caseId"] != case_id or hold["sequence"] != sequence:
                raise ValueError("caseId and sequence must identify the currently held report")
            hold["release"].set()
            return {"caseId": case_id, "sequence": sequence, "releaseRequested": True}

    def finish_report_hold(self, sequence: int) -> None:
        with self.lock:
            if self.active_report_hold is not None and self.active_report_hold["sequence"] == sequence:
                self.active_report_hold = None

    def mark_client_disconnected(self, event: dict[str, Any], signal: str) -> None:
        with self.lock:
            if event["clientConnected"]:
                disconnected_monotonic_ns = time.monotonic_ns()
                disconnected_elapsed_ms = disconnected_monotonic_ns // 1_000_000
                event["clientConnected"] = False
                event["clientDisconnectedAt"] = datetime.now(timezone.utc).isoformat()
                event["clientDisconnectSignal"] = signal
                event["clientDisconnectElapsedMs"] = disconnected_elapsed_ms
                event["clientDisconnectMonotonicNs"] = disconnected_monotonic_ns
                event["clientDisconnectAfterServerReceiveMs"] = max(
                    0,
                    disconnected_elapsed_ms - event["serverReceivedMonotonicMs"],
                )

    def release_pending_for_shutdown(self) -> None:
        with self.lock:
            for hold in (self.armed_report_hold, self.active_report_hold):
                if hold is not None:
                    hold["release"].set()

    def report_count(self, subject: str) -> int:
        with self.lock:
            return self.report_counts[subject]

    def set_report_counts(self, updates: dict[str, int]) -> tuple[dict[str, int], int]:
        with self.lock:
            self.report_counts.update(updates)
            return dict(self.report_counts), self.sequence

    def arm_report_response(self, case_id: str, path: str, response_status: int) -> dict[str, Any]:
        with self.lock:
            if self.scenario != "ticket05":
                raise ValueError("one-shot report responses are available only in Ticket 05")
            if self.armed_report_response is not None:
                raise ValueError("a one-shot report response is already armed")
            if not isinstance(case_id, str) or not case_id or len(case_id) > 80:
                raise ValueError("caseId must be a non-empty string of at most 80 characters")
            if path not in REPORT_PATHS:
                raise ValueError("path must be one of the supported report paths")
            if isinstance(response_status, bool) or not isinstance(response_status, int) or response_status not in (401, 403, 500):
                raise ValueError("responseStatus must be 401, 403, or 500")
            self.armed_report_response = {
                "caseId": case_id,
                "path": path,
                "responseStatus": response_status,
            }
            return dict(self.armed_report_response)

    def consume_report_response(self, path: str, event: dict[str, Any]) -> dict[str, Any] | None:
        with self.lock:
            armed = self.armed_report_response
            if armed is None or armed["path"] != path:
                return None
            self.armed_report_response = None
            event["caseId"] = armed["caseId"]
            event["injectedResponseStatus"] = armed["responseStatus"]
            return dict(armed)

    def reset_requests(self) -> None:
        with self.lock:
            if self.armed_report_hold is not None or self.active_report_hold is not None or self.armed_report_response is not None:
                raise ValueError("cannot reset request history while a response is armed or in flight")
            self.sequence = 0
            self.requests.clear()
            if self.scenario == "ticket06":
                self.ticket06_pre_removal_preferences_captured = False
                self.ticket06_pre_reboot_preferences_captured = False

    def acknowledge_ticket06_pre_removal_preferences(self, run_id: str) -> None:
        with self.lock:
            if self.scenario != "ticket06":
                raise ValueError("Ticket 06 stage acknowledgements are available only in Ticket 06")
            if run_id != self.run_id:
                raise ValueError("runId does not match the active Ticket 06 fixture")
            self.ticket06_pre_removal_preferences_captured = True

    def acknowledge_ticket06_pre_reboot_preferences(self, run_id: str) -> None:
        with self.lock:
            if self.scenario != "ticket06":
                raise ValueError("Ticket 06 stage acknowledgements are available only in Ticket 06")
            if run_id != self.run_id:
                raise ValueError("runId does not match the active Ticket 06 fixture")
            self.ticket06_pre_reboot_preferences_captured = True

    def snapshot(self) -> dict[str, Any]:
        with self.lock:
            snapshot_monotonic_ns = time.monotonic_ns()
            return {
                "runId": self.run_id,
                "scenario": self.scenario,
                "serverMonotonicNs": snapshot_monotonic_ns,
                "sequence": self.sequence,
                "ticket06PreRemovalPreferencesCaptured": self.ticket06_pre_removal_preferences_captured,
                "ticket06PreRebootPreferencesCaptured": self.ticket06_pre_reboot_preferences_captured,
                "reportCounts": dict(self.report_counts),
                "requests": list(self.requests),
                "releasedReportHolds": [dict(hold) for hold in self.released_report_holds],
                "armedReportHold": None if self.armed_report_hold is None else {
                    key: value for key, value in self.armed_report_hold.items() if key != "release"
                },
                "activeReportHold": None if self.active_report_hold is None else {
                    key: value for key, value in self.active_report_hold.items() if key != "release"
                },
                "armedReportResponse": None if self.armed_report_response is None else dict(self.armed_report_response),
                "inFlightRequests": [
                    {
                        "sequence": request["sequence"],
                        "path": request["path"],
                        "receivedAt": request["receivedAt"],
                        "caseId": request.get("holdCaseId"),
                        "serverReceivedMonotonicNs": request["serverReceivedMonotonicNs"],
                        "clientConnected": request["clientConnected"],
                    }
                    for request in self.requests
                    if request["responseStatus"] is None and request["clientConnected"]
                ],
                "serverHeldResponses": [
                    {
                        "sequence": request["sequence"],
                        "path": request["path"],
                        "receivedAt": request["receivedAt"],
                        "caseId": request.get("holdCaseId"),
                        "serverReceivedMonotonicNs": request["serverReceivedMonotonicNs"],
                        "clientConnected": request["clientConnected"],
                        "clientDisconnectedAt": request["clientDisconnectedAt"],
                        "clientDisconnectSignal": request["clientDisconnectSignal"],
                        "clientDisconnectAfterServerReceiveMs": request.get("clientDisconnectAfterServerReceiveMs"),
                        "clientDisconnectMonotonicNs": request.get("clientDisconnectMonotonicNs"),
                    }
                    for request in self.requests
                    if request["responseStatus"] is None
                ],
            }


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
                self._send_json(200, {"ok": True, "runId": state.run_id, "scenario": state.scenario})
                return
            if self.path == "/__ticket01/requests":
                self._send_json(200, state.snapshot())
                return
            if self.path == "/__ticket02/health" and state.scenario == "ticket02":
                self._send_json(200, {"ok": True, "runId": state.run_id, "scenario": state.scenario})
                return
            if self.path == "/__ticket02/state" and state.scenario == "ticket02":
                self._send_json(200, state.snapshot())
                return
            if self.path == "/__ticket03/health" and state.scenario == "ticket03":
                self._send_json(200, {"ok": True, "runId": state.run_id, "scenario": state.scenario})
                return
            if self.path == "/__ticket03/state" and state.scenario == "ticket03":
                self._send_json(200, state.snapshot())
                return
            if self.path == "/__ticket04/health" and state.scenario == "ticket04":
                self._send_json(200, {"ok": True, "runId": state.run_id, "scenario": state.scenario})
                return
            if self.path == "/__ticket04/state" and state.scenario == "ticket04":
                self._send_json(200, state.snapshot())
                return
            if self.path == "/__ticket05/health" and state.scenario == "ticket05":
                self._send_json(200, {"ok": True, "runId": state.run_id, "scenario": state.scenario})
                return
            if self.path == "/__ticket05/state" and state.scenario == "ticket05":
                self._send_json(200, state.snapshot())
                return
            if self.path == "/__ticket06/health" and state.scenario == "ticket06":
                self._send_json(200, {"ok": True, "runId": state.run_id, "scenario": state.scenario})
                return
            if self.path == "/__ticket06/state" and state.scenario == "ticket06":
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

            if state.scenario == "ticket02" and self.path == "/__ticket02/reset-requests":
                state.reset_requests()
                self._send_json(200, {"ok": True, "runId": state.run_id})
                return

            if state.scenario == "ticket02" and self.path == "/__ticket02/set-report-counts":
                reports = body.get("reports") if isinstance(body, dict) else None
                if not isinstance(reports, dict) or not reports:
                    self._send_json(400, {"error": "reports must be a non-empty object"})
                    return
                valid_updates: dict[str, int] = {}
                for subject, count in reports.items():
                    if subject not in SUBJECTS or isinstance(count, bool) or not isinstance(count, int) or not 0 <= count <= 100:
                        self._send_json(400, {"error": "reports must map supported subjects to integer counts from 0 to 100"})
                        return
                    valid_updates[subject] = count
                report_counts, after_sequence = state.set_report_counts(valid_updates)
                self._send_json(200, {
                    "ok": True,
                    "runId": state.run_id,
                    "reportCounts": report_counts,
                    "afterRequestSequence": after_sequence,
                })
                return

            if state.scenario == "ticket03" and self.path == "/__ticket03/reset-requests":
                state.reset_requests()
                self._send_json(200, {"ok": True, "runId": state.run_id})
                return

            if state.scenario == "ticket03" and self.path == "/__ticket03/set-report-counts":
                reports = body.get("reports") if isinstance(body, dict) else None
                if not isinstance(reports, dict) or not reports:
                    self._send_json(400, {"error": "reports must be a non-empty object"})
                    return
                valid_updates: dict[str, int] = {}
                for subject, count in reports.items():
                    if subject not in SUBJECTS or isinstance(count, bool) or not isinstance(count, int) or not 0 <= count <= 100:
                        self._send_json(400, {"error": "reports must map supported subjects to integer counts from 0 to 100"})
                        return
                    valid_updates[subject] = count
                report_counts, after_sequence = state.set_report_counts(valid_updates)
                self._send_json(200, {
                    "ok": True,
                    "runId": state.run_id,
                    "reportCounts": report_counts,
                    "afterRequestSequence": after_sequence,
                })
                return

            if state.scenario == "ticket04" and self.path == "/__ticket04/arm-report-hold":
                try:
                    hold = state.arm_report_hold(
                        case_id=body.get("caseId") if isinstance(body, dict) else "",
                        path=body.get("path") if isinstance(body, dict) else "",
                        response_status=body.get("responseStatus") if isinstance(body, dict) else None,
                        response_count=body.get("responseCount") if isinstance(body, dict) else None,
                    )
                except (TypeError, ValueError) as error:
                    self._send_json(400, {"error": str(error)})
                    return
                self._send_json(200, {"ok": True, "runId": state.run_id, **hold})
                return

            if state.scenario == "ticket04" and self.path == "/__ticket04/release-report-hold":
                case_id = body.get("caseId") if isinstance(body, dict) else None
                sequence = body.get("sequence") if isinstance(body, dict) else None
                if not isinstance(case_id, str) or isinstance(sequence, bool) or not isinstance(sequence, int):
                    self._send_json(400, {"error": "caseId and integer sequence are required"})
                    return
                try:
                    released = state.release_report_hold(case_id, sequence)
                except ValueError as error:
                    self._send_json(409, {"error": str(error)})
                    return
                self._send_json(200, {"ok": True, "runId": state.run_id, **released})
                return

            if state.scenario == "ticket04" and self.path == "/__ticket04/reset-requests":
                try:
                    state.reset_requests()
                except ValueError as error:
                    self._send_json(409, {"error": str(error)})
                    return
                self._send_json(200, {"ok": True, "runId": state.run_id})
                return

            if state.scenario == "ticket04" and self.path == "/__ticket04/set-report-counts":
                reports = body.get("reports") if isinstance(body, dict) else None
                if not isinstance(reports, dict) or not reports:
                    self._send_json(400, {"error": "reports must be a non-empty object"})
                    return
                valid_updates: dict[str, int] = {}
                for subject, count in reports.items():
                    if subject not in SUBJECTS or isinstance(count, bool) or not isinstance(count, int) or not 0 <= count <= 100:
                        self._send_json(400, {"error": "reports must map supported subjects to integer counts from 0 to 100"})
                        return
                    valid_updates[subject] = count
                report_counts, after_sequence = state.set_report_counts(valid_updates)
                self._send_json(200, {
                    "ok": True,
                    "runId": state.run_id,
                    "reportCounts": report_counts,
                    "afterRequestSequence": after_sequence,
                })
                return

            if state.scenario == "ticket05" and self.path == "/__ticket05/reset-requests":
                try:
                    state.reset_requests()
                except ValueError as error:
                    self._send_json(409, {"error": str(error)})
                    return
                self._send_json(200, {"ok": True, "runId": state.run_id})
                return

            if state.scenario == "ticket05" and self.path == "/__ticket05/set-report-counts":
                reports = body.get("reports") if isinstance(body, dict) else None
                if not isinstance(reports, dict) or not reports:
                    self._send_json(400, {"error": "reports must be a non-empty object"})
                    return
                valid_updates: dict[str, int] = {}
                for subject, count in reports.items():
                    if subject not in SUBJECTS or isinstance(count, bool) or not isinstance(count, int) or not 0 <= count <= 100:
                        self._send_json(400, {"error": "reports must map supported subjects to integer counts from 0 to 100"})
                        return
                    valid_updates[subject] = count
                report_counts, after_sequence = state.set_report_counts(valid_updates)
                self._send_json(200, {
                    "ok": True,
                    "runId": state.run_id,
                    "reportCounts": report_counts,
                    "afterRequestSequence": after_sequence,
                })
                return

            if state.scenario == "ticket06" and self.path == "/__ticket06/reset-requests":
                state.reset_requests()
                self._send_json(200, {"ok": True, "runId": state.run_id})
                return

            if state.scenario == "ticket06" and self.path == "/__ticket06/set-report-counts":
                reports = body.get("reports") if isinstance(body, dict) else None
                if not isinstance(reports, dict) or not reports:
                    self._send_json(400, {"error": "reports must be a non-empty object"})
                    return
                valid_updates: dict[str, int] = {}
                for subject, count in reports.items():
                    if subject not in SUBJECTS or isinstance(count, bool) or not isinstance(count, int) or not 0 <= count <= 100:
                        self._send_json(400, {"error": "reports must map supported subjects to integer counts from 0 to 100"})
                        return
                    valid_updates[subject] = count
                report_counts, after_sequence = state.set_report_counts(valid_updates)
                self._send_json(200, {
                    "ok": True,
                    "runId": state.run_id,
                    "reportCounts": report_counts,
                    "afterRequestSequence": after_sequence,
                })
                return

            if state.scenario == "ticket06" and self.path == "/__ticket06/ack-pre-removal-preferences":
                run_id = body.get("runId") if isinstance(body, dict) else None
                try:
                    state.acknowledge_ticket06_pre_removal_preferences(run_id)
                except ValueError as error:
                    self._send_json(400, {"error": str(error)})
                    return
                self._send_json(200, {"ok": True, "runId": state.run_id, "readyForTaskRemoval": True})
                return

            if state.scenario == "ticket06" and self.path == "/__ticket06/ack-pre-reboot-preferences":
                run_id = body.get("runId") if isinstance(body, dict) else None
                try:
                    state.acknowledge_ticket06_pre_reboot_preferences(run_id)
                except ValueError as error:
                    self._send_json(400, {"error": str(error)})
                    return
                self._send_json(200, {"ok": True, "runId": state.run_id, "readyForReboot": True})
                return

            if state.scenario == "ticket05" and self.path == "/__ticket05/arm-report-response":
                try:
                    armed = state.arm_report_response(
                        case_id=body.get("caseId") if isinstance(body, dict) else "",
                        path=body.get("path") if isinstance(body, dict) else "",
                        response_status=body.get("responseStatus") if isinstance(body, dict) else None,
                    )
                except (TypeError, ValueError) as error:
                    self._send_json(400, {"error": str(error)})
                    return
                self._send_json(200, {"ok": True, "runId": state.run_id, **armed})
                return

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
                subject, (_users_path, _report_path, user_id, _default_count, seconds) = report
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
                injected = state.consume_report_response(self.path, event)
                if injected is not None:
                    status = injected["responseStatus"]
                    state.complete(event, status)
                    self._send_json(status, {"error": f"ticket05 injected HTTP {status}"})
                    return
                hold = state.hold_for_report(self.path, event)
                if hold is not None:
                    while not hold["release"].wait(0.05):
                        try:
                            readable, _, exceptional = select.select([self.connection], [], [self.connection], 0)
                            if exceptional:
                                state.mark_client_disconnected(event, "socket-exception")
                            elif readable and self.connection.recv(1, socket.MSG_PEEK) == b"":
                                state.mark_client_disconnected(event, "eof")
                        except OSError:
                            state.mark_client_disconnected(event, "socket-error")
                    response_status = hold["responseStatus"]
                    response_count = hold["responseCount"]
                    if response_status == 200:
                        count = state.report_count(subject) if response_count is None else response_count
                        payload = envelope(report_data(count, seconds, expected_array))
                    else:
                        count = None
                        payload = {"error": f"ticket04 held HTTP {response_status}"}
                    try:
                        self._send_json(response_status, payload)
                    except OSError:
                        state.complete(event, response_status, served_report_count=count, delivery_status="write-failed")
                    else:
                        state.complete(event, response_status, served_report_count=count, delivery_status="written")
                    finally:
                        state.finish_report_hold(event["sequence"])
                    return
                count = state.report_count(subject)
                state.complete(event, 200, served_report_count=count)
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
    parser.add_argument("--scenario", choices=("ticket01", "ticket02", "ticket03", "ticket04", "ticket05", "ticket06"), default="ticket01")
    args = parser.parse_args()
    if args.host != "127.0.0.1":
        raise SystemExit("Ticket 01 fixture server must bind only to 127.0.0.1")
    if not 1 <= args.port <= 65535:
        raise SystemExit("port must be in 1..65535")

    server = Ticket01HttpServer((args.host, args.port), make_handler(FixtureState(args.run_id, args.scenario)))
    server.daemon_threads = True
    print(json.dumps({"ready": True, "host": args.host, "port": args.port, "runId": args.run_id, "scenario": args.scenario}), flush=True)
    try:
        server.serve_forever(poll_interval=0.2)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
