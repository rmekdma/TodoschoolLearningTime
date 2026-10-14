#!/usr/bin/env python3
"""Contract tests for the loopback fixture server's public HTTP surface."""

from __future__ import annotations

import json
import http.client
import threading
import time
import unittest
from datetime import datetime, timedelta, timezone
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import server


class Ticket03FixtureServerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.state = server.FixtureState("ticket03-run", "ticket03")
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(self.state))
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.httpd.server_port}"

    def tearDown(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)

    def get(self, path: str) -> dict:
        with urlopen(f"{self.base_url}{path}", timeout=3) as response:
            return json.loads(response.read())

    def post(self, path: str, body: dict, authorization: str | None = None) -> dict:
        headers = {"Content-Type": "application/json"}
        if authorization is not None:
            headers["Authorization"] = authorization
        request = Request(
            f"{self.base_url}{path}",
            data=json.dumps(body).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        with urlopen(request, timeout=3) as response:
            return json.loads(response.read())

    def test_ticket03_records_only_post_baseline_requests_and_preserves_fixture_counts(self) -> None:
        health = self.get("/__ticket03/health")
        self.assertEqual({"ok": True, "runId": "ticket03-run", "scenario": "ticket03"}, health)

        changed = self.post(
            "/__ticket03/set-report-counts",
            {"reports": {"hangeul": 6, "english": 3}},
        )
        self.assertTrue(changed["ok"])
        self.assertEqual(0, changed["afterRequestSequence"])

        report = self.post(
            "/v3/todoschool/report/daily/hangeul",
            {"userId": "ticket01-hangeul", "yyyymmdd": 20261012, "languageCode": "ko"},
            authorization="Bearer ticket01-fake-token",
        )
        self.assertTrue(report["result"])
        self.assertEqual(6, len(report["data"]["contentsHistory"]["dailyCourse"]))

        observed = self.get("/__ticket03/state")
        self.assertEqual(1, observed["sequence"])
        self.assertEqual("/v3/todoschool/report/daily/hangeul", observed["requests"][0]["path"])
        self.assertEqual(200, observed["requests"][0]["responseStatus"])
        self.assertEqual([], observed["inFlightRequests"])

        reset = self.post("/__ticket03/reset-requests", {})
        self.assertTrue(reset["ok"])
        after_reset = self.get("/__ticket03/state")
        self.assertEqual(0, after_reset["sequence"])
        self.assertEqual([], after_reset["requests"])
        self.assertEqual([], after_reset["inFlightRequests"])
        self.assertEqual({"hangeul": 6, "math": 2, "english": 3}, after_reset["reportCounts"])


class Ticket04FixtureServerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.state = server.FixtureState("ticket04-run", "ticket04")
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(self.state))
        self.httpd.daemon_threads = True
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.httpd.server_port}"

    def tearDown(self) -> None:
        self.state.release_pending_for_shutdown()
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)

    def get(self, path: str) -> dict:
        with urlopen(f"{self.base_url}{path}", timeout=3) as response:
            return json.loads(response.read())

    def post(self, path: str, body: dict, authorization: str | None = None) -> dict:
        headers = {"Content-Type": "application/json"}
        if authorization is not None:
            headers["Authorization"] = authorization
        request = Request(
            f"{self.base_url}{path}",
            data=json.dumps(body).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        with urlopen(request, timeout=3) as response:
            return json.loads(response.read())

    def wait_until(self, predicate, timeout_seconds: float = 2.0) -> dict:
        end = time.monotonic() + timeout_seconds
        while time.monotonic() < end:
            snapshot = self.get("/__ticket04/state")
            if predicate(snapshot):
                return snapshot
            time.sleep(0.01)
        self.fail("Timed out waiting for the explicitly held report request")

    def test_held_report_stays_in_flight_until_matching_explicit_release(self) -> None:
        armed = self.post(
            "/__ticket04/arm-report-hold",
            {
                "caseId": "notification-revoke",
                "path": "/v3/todoschool/report/daily/hangeul",
                "responseStatus": 200,
                "responseCount": 6,
            },
        )
        self.assertTrue(armed["ok"])
        self.assertEqual("notification-revoke", armed["caseId"])
        self.assertEqual("/v3/todoschool/report/daily/hangeul", armed["path"])

        response_holder: dict[str, object] = {}

        def request_report() -> None:
            response_holder["payload"] = self.post(
                "/v3/todoschool/report/daily/hangeul",
                {"userId": "ticket01-hangeul", "yyyymmdd": 20261012, "languageCode": "ko"},
                authorization="Bearer ticket01-fake-token",
            )

        client = threading.Thread(target=request_report, daemon=True)
        client.start()
        pending = self.wait_until(lambda snapshot: len(snapshot["inFlightRequests"]) == 1)
        held = pending["inFlightRequests"][0]
        self.assertGreater(pending["serverMonotonicNs"], 0)
        self.assertGreater(held["serverReceivedMonotonicNs"], 0)
        self.assertGreaterEqual(pending["serverMonotonicNs"], held["serverReceivedMonotonicNs"])
        self.assertEqual("notification-revoke", held["caseId"])
        self.assertEqual("/v3/todoschool/report/daily/hangeul", held["path"])
        self.assertIsNone(pending["requests"][0]["responseStatus"])

        released = self.post(
            "/__ticket04/release-report-hold",
            {"caseId": "notification-revoke", "sequence": held["sequence"]},
        )
        self.assertTrue(released["ok"])
        client.join(timeout=2)
        self.assertFalse(client.is_alive(), "The report client remained blocked after explicit release")
        payload = response_holder["payload"]
        self.assertTrue(payload["result"])
        self.assertEqual(6, len(payload["data"]["contentsHistory"]["dailyCourse"]))

        completed = self.get("/__ticket04/state")
        self.assertEqual([], completed["inFlightRequests"])
        self.assertEqual(200, completed["requests"][0]["responseStatus"])
        self.assertEqual("written", completed["requests"][0]["deliveryStatus"])
        self.assertEqual(6, completed["requests"][0]["servedReportCount"])
        self.assertEqual(1, len(completed["releasedReportHolds"]))
        self.assertEqual("notification-revoke", completed["releasedReportHolds"][0]["caseId"])
        self.post("/__ticket04/reset-requests", {})
        after_reset = self.get("/__ticket04/state")
        self.assertEqual([], after_reset["requests"])
        self.assertEqual(1, len(after_reset["releasedReportHolds"]))


    def test_explicit_unauthorized_hold_can_be_released_without_changing_fixture_counts(self) -> None:
        armed = self.post(
            "/__ticket04/arm-report-hold",
            {
                "caseId": "accessibility-revoke",
                "path": "/v3/todoschool/report/daily/english",
                "responseStatus": 401,
            },
        )
        self.assertTrue(armed["ok"])

        response_holder: dict[str, object] = {}

        def request_report() -> None:
            try:
                response_holder["payload"] = self.post(
                    "/v3/todoschool/report/daily/english",
                    {"userId": "ticket01-english", "yyyymmdd": 20261012, "languageCode": "ko"},
                    authorization="Bearer ticket01-fake-token",
                )
            except Exception as error:  # urllib raises HTTPError for the intended 401 response.
                response_holder["errorCode"] = getattr(error, "code", None)
                close = getattr(error, "close", None)
                if close is not None:
                    close()

        client = threading.Thread(target=request_report, daemon=True)
        client.start()
        pending = self.wait_until(lambda snapshot: len(snapshot["inFlightRequests"]) == 1)
        held = pending["inFlightRequests"][0]
        self.assertEqual("accessibility-revoke", held["caseId"])

        released = self.post(
            "/__ticket04/release-report-hold",
            {"caseId": "accessibility-revoke", "sequence": held["sequence"]},
        )
        self.assertTrue(released["ok"])
        client.join(timeout=2)
        self.assertFalse(client.is_alive())
        self.assertEqual(401, response_holder["errorCode"])

        completed = self.get("/__ticket04/state")
        self.assertEqual([], completed["inFlightRequests"])
        self.assertEqual(401, completed["requests"][0]["responseStatus"])
        self.assertEqual("written", completed["requests"][0]["deliveryStatus"])
        self.assertEqual({"hangeul": 2, "math": 5, "english": 7}, completed["reportCounts"])
        self.assertEqual("accessibility-revoke", completed["releasedReportHolds"][0]["caseId"])

    def test_closed_client_is_not_reported_as_an_in_flight_http_request(self) -> None:
        self.post(
            "/__ticket04/arm-report-hold",
            {
                "caseId": "closed-client",
                "path": "/v3/todoschool/report/daily/hangeul",
                "responseStatus": 200,
                "responseCount": 6,
            },
        )
        connection = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port, timeout=3)
        connection.request(
            "POST",
            "/v3/todoschool/report/daily/hangeul",
            body=json.dumps({"userId": "ticket01-hangeul", "yyyymmdd": 20261012, "languageCode": "ko"}),
            headers={"Content-Type": "application/json", "Authorization": "Bearer ticket01-fake-token"},
        )
        held = self.wait_until(lambda snapshot: len(snapshot["inFlightRequests"]) == 1)
        sequence = held["inFlightRequests"][0]["sequence"]
        connection.close()

        disconnected = self.wait_until(
            lambda snapshot: any(
                request["sequence"] == sequence and not request["clientConnected"]
                for request in snapshot["serverHeldResponses"]
            )
        )
        self.assertEqual([], disconnected["inFlightRequests"])
        server_held = disconnected["serverHeldResponses"][0]
        self.assertFalse(server_held["clientConnected"])
        self.assertIn(server_held["clientDisconnectSignal"], ("eof", "socket-exception", "socket-error"))
        self.assertGreaterEqual(server_held["clientDisconnectAfterServerReceiveMs"], 0)
        self.assertGreater(server_held["clientDisconnectMonotonicNs"], server_held["serverReceivedMonotonicNs"])
        self.assertGreaterEqual(disconnected["serverMonotonicNs"], server_held["clientDisconnectMonotonicNs"])

        released = self.post("/__ticket04/release-report-hold", {"caseId": "closed-client", "sequence": sequence})
        self.assertTrue(released["ok"])
        completed = self.wait_until(lambda snapshot: snapshot["requests"][0]["responseStatus"] is not None)
        self.assertEqual("write-failed", completed["requests"][0]["deliveryStatus"])


class Ticket05FixtureServerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.state = server.FixtureState("ticket05-run", "ticket05")
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(self.state))
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.httpd.server_port}"

    def tearDown(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)

    def get(self, path: str) -> dict:
        with urlopen(f"{self.base_url}{path}", timeout=3) as response:
            return json.loads(response.read())

    def post(self, path: str, body: dict, authorization: str | None = None) -> dict:
        headers = {"Content-Type": "application/json"}
        if authorization is not None:
            headers["Authorization"] = authorization
        request = Request(
            f"{self.base_url}{path}",
            data=json.dumps(body).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        with urlopen(request, timeout=3) as response:
            return json.loads(response.read())

    def post_report_status(self, path: str, user_id: str) -> int:
        try:
            self.post(
                path,
                {"userId": user_id, "yyyymmdd": 20261013, "languageCode": "ko"},
                authorization="Bearer ticket01-fake-token",
            )
        except HTTPError as error:
            status = error.code
            error.close()
            return status
        return 200

    def post_status(self, path: str, body: dict) -> int:
        try:
            self.post(path, body)
        except HTTPError as error:
            status = error.code
            error.close()
            return status
        return 200

    def test_one_shot_http_failures_use_real_report_route_and_the_next_request_recovers(self) -> None:
        health = self.get("/__ticket05/health")
        self.assertEqual({"ok": True, "runId": "ticket05-run", "scenario": "ticket05"}, health)

        for case_id, path, user_id, status in (
            ("temporary-500", "/v3/todoschool/report/daily/hangeul", "ticket01-hangeul", 500),
            ("expired-auth", "/v3/todoschool/report/daily/math", "ticket01-math", 401),
        ):
            armed = self.post(
                "/__ticket05/arm-report-response",
                {"caseId": case_id, "path": path, "responseStatus": status},
            )
            self.assertTrue(armed["ok"])
            self.assertEqual(case_id, armed["caseId"])
            self.assertEqual(path, armed["path"])
            self.assertEqual(status, self.post_report_status(path, user_id))

        recovered = self.post(
            "/v3/todoschool/report/daily/hangeul",
            {"userId": "ticket01-hangeul", "yyyymmdd": 20261013, "languageCode": "ko"},
            authorization="Bearer ticket01-fake-token",
        )
        self.assertTrue(recovered["result"])
        self.assertEqual(2, len(recovered["data"]["contentsHistory"]["dailyCourse"]))

        observed = self.get("/__ticket05/state")
        self.assertEqual("ticket05-run", observed["runId"])
        self.assertEqual([500, 401, 200], [request["responseStatus"] for request in observed["requests"]])
        self.assertEqual(["temporary-500", "expired-auth", None], [request.get("caseId") for request in observed["requests"]])
        self.assertTrue(all(request["headers"]["authorization"] == "Bearer ticket01-fake-token" for request in observed["requests"]))
        self.assertEqual([None, None, 2], [request["servedReportCount"] for request in observed["requests"]])

    def test_ticket05_response_injection_rejects_invalid_or_overlapping_cases(self) -> None:
        for payload in (
            {"caseId": "bad-status", "path": "/v3/todoschool/report/daily/hangeul", "responseStatus": 302},
            {"caseId": "float-status", "path": "/v3/todoschool/report/daily/hangeul", "responseStatus": 500.0},
            {"caseId": "boolean-status", "path": "/v3/todoschool/report/daily/hangeul", "responseStatus": True},
            {"caseId": "bad-path", "path": "/v3/account/signin", "responseStatus": 500},
        ):
            self.assertEqual(400, self.post_status("/__ticket05/arm-report-response", payload))

        armed = self.post(
            "/__ticket05/arm-report-response",
            {"caseId": "first", "path": "/v3/todoschool/report/daily/hangeul", "responseStatus": 500},
        )
        self.assertTrue(armed["ok"])
        self.assertEqual(409, self.post_status("/__ticket05/reset-requests", {}))
        self.assertEqual(
            400,
            self.post_status(
                "/__ticket05/arm-report-response",
                {"caseId": "second", "path": "/v3/todoschool/report/daily/math", "responseStatus": 401},
            ),
        )


class Ticket06FixtureServerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.state = server.FixtureState("ticket06-run", "ticket06")
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(self.state))
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.httpd.server_port}"

    def tearDown(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)

    def get(self, path: str) -> dict:
        with urlopen(f"{self.base_url}{path}", timeout=3) as response:
            return json.loads(response.read())

    def post(self, path: str, body: dict) -> dict:
        request = Request(
            f"{self.base_url}{path}",
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urlopen(request, timeout=3) as response:
            return json.loads(response.read())

    def test_ticket06_state_and_controls_survive_a_client_reconnect(self) -> None:
        self.assertEqual(
            {"ok": True, "runId": "ticket06-run", "scenario": "ticket06"},
            self.get("/__ticket06/health"),
        )
        changed = self.post("/__ticket06/set-report-counts", {"reports": {"hangeul": 6}})
        self.assertEqual({"hangeul": 6, "math": 5, "english": 7}, changed["reportCounts"])

        signin = self.post("/v3/account/signin", {"email": "ticket01@example.test", "password": "not-a-real-password"})
        self.assertTrue(signin["result"])
        first_state = self.get("/__ticket06/state")
        self.assertEqual("ticket06-run", first_state["runId"])
        self.assertEqual("ticket06", first_state["scenario"])
        self.assertEqual(1, first_state["sequence"])
        self.assertFalse(first_state["ticket06PreRemovalPreferencesCaptured"])
        self.assertFalse(first_state["ticket06PreRebootPreferencesCaptured"])
        acknowledged = self.post("/__ticket06/ack-pre-removal-preferences", {"runId": "ticket06-run"})
        self.assertEqual({"ok": True, "runId": "ticket06-run", "readyForTaskRemoval": True}, acknowledged)
        self.assertTrue(self.get("/__ticket06/state")["ticket06PreRemovalPreferencesCaptured"])
        self.assertEqual(400, self.post_status("/__ticket06/ack-pre-removal-preferences", {"runId": "another-run"}))

        reboot_acknowledged = self.post("/__ticket06/ack-pre-reboot-preferences", {"runId": "ticket06-run"})
        self.assertEqual({"ok": True, "runId": "ticket06-run", "readyForReboot": True}, reboot_acknowledged)
        self.assertTrue(self.get("/__ticket06/state")["ticket06PreRebootPreferencesCaptured"])
        self.assertEqual(400, self.post_status("/__ticket06/ack-pre-reboot-preferences", {"runId": "another-run"}))

        self.assertTrue(self.post("/__ticket06/reset-requests", {})["ok"])
        after_reset = self.get("/__ticket06/state")
        self.assertEqual(0, after_reset["sequence"])
        self.assertEqual([], after_reset["requests"])
        self.assertFalse(after_reset["ticket06PreRemovalPreferencesCaptured"])
        self.assertFalse(after_reset["ticket06PreRebootPreferencesCaptured"])
        self.assertEqual({"hangeul": 6, "math": 5, "english": 7}, after_reset["reportCounts"])

    def test_ticket06_task_removal_acknowledges_only_the_registered_exact_recents_task(self) -> None:
        target = {
            "runId": "ticket06-run",
            "taskId": 42,
            "taskComponent": "com.example.todoschooltime.debug/com.example.todoschooltime.MainActivity",
            "expectedAppLabel": "TodoSchool",
            "recentsCardLabelObserved": False,
            "recentsCardLabel": None,
            "recentsLabelWindowPackage": None,
            "otherTaskIds": [11, 12],
        }
        initial_state = self.get("/__ticket06/state")
        self.assertIsNone(initial_state["ticket06RecentsScreenshotSha256"])
        self.assertIsNone(initial_state["ticket06TaskRemovalTarget"])
        self.assertIsNone(initial_state["ticket06TaskRemovalGate"])
        self.assertIsNone(initial_state["ticket06TaskRemovalAbsence"])
        self.assertIsNone(initial_state["ticket06TaskRemovalAcknowledgement"])
        self.post("/__ticket06/ack-pre-removal-preferences", {"runId": "ticket06-run"})
        screenshot = "a" * 64
        self.assertEqual(400, self.post_status("/__ticket06/record-recents-screenshot", {"runId": "another-run", "screenshotSha256": screenshot}))
        self.assertEqual(400, self.post_status("/__ticket06/record-recents-screenshot", {"runId": "ticket06-run", "screenshotSha256": "bad"}))
        self.assertEqual(
            {"ok": True, "runId": "ticket06-run", "recentsScreenshotRecorded": True},
            self.post("/__ticket06/record-recents-screenshot", {"runId": "ticket06-run", "screenshotSha256": screenshot}),
        )
        self.assertEqual(400, self.post_status("/__ticket06/record-recents-screenshot", {"runId": "ticket06-run", "screenshotSha256": screenshot}))
        self.assertEqual(
            400,
            self.post_status(
                "/__ticket06/ack-task-removal",
                {
                    "runId": "ticket06-run",
                    "taskId": 42,
                    "taskComponent": target["taskComponent"],
                    "otherTaskIds": [11, 12],
                    "screenshotSha256": screenshot,
                    "confirmedAppLabel": "TodoSchool",
                },
            ),
        )

        invalid_target = dict(target, taskComponent="com.example.todoschooltime.debug/.OtherActivity")
        self.assertEqual(400, self.post_status("/__ticket06/register-task-removal-target", invalid_target))
        missing_label_fields = dict(target)
        missing_label_fields.pop("recentsLabelWindowPackage")
        self.assertEqual(400, self.post_status("/__ticket06/register-task-removal-target", missing_label_fields))
        invalid_label_observation = dict(target, recentsCardLabelObserved=True)
        self.assertEqual(400, self.post_status("/__ticket06/register-task-removal-target", invalid_label_observation))
        wrong_label_flag_type = dict(target, recentsCardLabelObserved="false")
        self.assertEqual(400, self.post_status("/__ticket06/register-task-removal-target", wrong_label_flag_type))
        fabricated_unobserved_label = dict(target, recentsCardLabel="TodoSchool")
        self.assertEqual(400, self.post_status("/__ticket06/register-task-removal-target", fabricated_unobserved_label))
        target_in_other_tasks = dict(target, otherTaskIds=[11, 42])
        self.assertEqual(400, self.post_status("/__ticket06/register-task-removal-target", target_in_other_tasks))
        duplicate_other_task = dict(target, otherTaskIds=[11, 11])
        self.assertEqual(400, self.post_status("/__ticket06/register-task-removal-target", duplicate_other_task))

        registered = self.post("/__ticket06/register-task-removal-target", target)
        self.assertEqual({"ok": True, "runId": "ticket06-run", "readyForManualDismissal": True}, registered)
        snapshot = self.get("/__ticket06/state")
        self.assertEqual(42, snapshot["ticket06TaskRemovalTarget"]["taskId"])
        self.assertEqual(target["taskComponent"], snapshot["ticket06TaskRemovalTarget"]["taskComponent"])
        self.assertEqual([11, 12], snapshot["ticket06TaskRemovalTarget"]["otherTaskIds"])
        self.assertFalse(snapshot["ticket06TaskRemovalTarget"]["recentsCardLabelObserved"])
        self.assertIsNone(snapshot["ticket06TaskRemovalTarget"]["recentsCardLabel"])
        self.assertIsNone(snapshot["ticket06TaskRemovalTarget"]["recentsLabelWindowPackage"])
        self.assertIsNone(snapshot["ticket06TaskRemovalAcknowledgement"])

        wrong_task_id = {"runId": "ticket06-run", "taskId": 43, "taskComponent": target["taskComponent"]}
        correct_ack = {
            "runId": "ticket06-run",
            "taskId": 42,
            "taskComponent": target["taskComponent"],
            "otherTaskIds": [11, 12],
            "screenshotSha256": "a" * 64,
            "confirmedAppLabel": "TodoSchool",
        }
        wrong_component = dict(correct_ack, taskComponent="com.example.todoschooltime.debug/.OtherActivity")
        wrong_other_tasks = dict(correct_ack, otherTaskIds=[12, 11])
        wrong_screenshot = dict(correct_ack, screenshotSha256="b" * 64)
        wrong_confirmed_label = dict(correct_ack, confirmedAppLabel="Another app")
        wrong_run_id = dict(correct_ack, runId="another-run")
        self.assertEqual(400, self.post_status("/__ticket06/ack-task-removal", dict(correct_ack, taskId=43)))
        self.assertEqual(400, self.post_status("/__ticket06/ack-task-removal", wrong_component))
        self.assertEqual(400, self.post_status("/__ticket06/ack-task-removal", wrong_other_tasks))
        self.assertEqual(400, self.post_status("/__ticket06/ack-task-removal", wrong_screenshot))
        self.assertEqual(400, self.post_status("/__ticket06/ack-task-removal", wrong_confirmed_label))
        self.assertEqual(400, self.post_status("/__ticket06/ack-task-removal", wrong_run_id))

        self.assertEqual(400, self.post_status("/__ticket06/observe-task-removal-absence", {
            "runId": "ticket06-run", "taskId": 42, "taskComponent": target["taskComponent"], "otherTaskIds": [11, 12]
        }))
        gate_body = {
            "runId": "ticket06-run",
            "taskId": 42,
            "taskComponent": target["taskComponent"],
            "screenshotSha256": screenshot,
            "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
        }
        registered_gate = self.post("/__ticket06/register-task-removal-gate", gate_body)
        saved_gate = registered_gate["ticket06TaskRemovalGate"]
        self.assertTrue(registered_gate["ok"])
        self.assertEqual("ticket06-run", saved_gate["runId"])
        self.assertEqual(42, saved_gate["taskId"])
        self.assertEqual(target["taskComponent"], saved_gate["taskComponent"])
        self.assertEqual(screenshot, saved_gate["screenshotSha256"])
        datetime.fromisoformat(saved_gate["createdAtUtc"].replace("Z", "+00:00"))
        self.assertEqual(400, self.post_status("/__ticket06/ack-task-removal", correct_ack))

        absence_request = {
            "runId": "ticket06-run",
            "taskId": 42,
            "taskComponent": target["taskComponent"],
            "otherTaskIds": [11, 12],
        }
        self.assertEqual(400, self.post_status("/__ticket06/observe-task-removal-absence", dict(absence_request, otherTaskIds=[12, 11])))
        absence = self.post("/__ticket06/observe-task-removal-absence", absence_request)["ticket06TaskRemovalAbsence"]
        self.assertEqual(42, absence["taskId"])
        self.assertEqual([11, 12], absence["otherTaskIds"])
        datetime.fromisoformat(absence["observedAtUtc"].replace("Z", "+00:00"))

        # Simulate a five-minute human wait; the gate's 30-minute deadline is independent of poll cadence.
        created_at = datetime.now(timezone.utc) - timedelta(minutes=5)
        absence_at = created_at + timedelta(minutes=4)
        self.state.ticket06_task_removal_gate["createdAtUtc"] = created_at.isoformat()
        self.state.ticket06_task_removal_absence["observedAtUtc"] = absence_at.isoformat()

        acknowledged = self.post("/__ticket06/ack-task-removal", correct_ack)
        self.assertEqual({"ok": True, "runId": "ticket06-run", "manualDismissalAcknowledged": True}, acknowledged)
        saved_ack = self.get("/__ticket06/state")["ticket06TaskRemovalAcknowledgement"]
        self.assertEqual("ticket06-run", saved_ack["runId"])
        self.assertEqual(42, saved_ack["taskId"])
        self.assertEqual(target["taskComponent"], saved_ack["taskComponent"])
        self.assertEqual([11, 12], saved_ack["otherTaskIds"])
        self.assertEqual(screenshot, saved_ack["screenshotSha256"])
        self.assertEqual("TodoSchool", saved_ack["confirmedAppLabel"])
        datetime.fromisoformat(saved_ack["acknowledgedAtUtc"].replace("Z", "+00:00"))
        self.assertLessEqual(self.state.ticket06_task_removal_gate["createdAtUtc"], self.state.ticket06_task_removal_absence["observedAtUtc"])
        self.assertLessEqual(self.state.ticket06_task_removal_absence["observedAtUtc"], saved_ack["acknowledgedAtUtc"])
        self.assertEqual(400, self.post_status("/__ticket06/ack-task-removal", correct_ack))

        self.post("/__ticket06/reset-requests", {})
        reset_state = self.get("/__ticket06/state")
        self.assertIsNone(reset_state["ticket06RecentsScreenshotSha256"])
        self.assertIsNone(reset_state["ticket06TaskRemovalTarget"])
        self.assertIsNone(reset_state["ticket06TaskRemovalGate"])
        self.assertIsNone(reset_state["ticket06TaskRemovalAbsence"])
        self.assertIsNone(reset_state["ticket06TaskRemovalAcknowledgement"])

        self.post("/__ticket06/ack-pre-removal-preferences", {"runId": "ticket06-run"})
        self.post("/__ticket06/record-recents-screenshot", {"runId": "ticket06-run", "screenshotSha256": screenshot})
        self.post("/__ticket06/register-task-removal-target", target)
        self.post("/__ticket06/register-task-removal-gate", {
            "runId": "ticket06-run",
            "taskId": 42,
            "taskComponent": target["taskComponent"],
            "screenshotSha256": screenshot,
            "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
        })
        expired_gate_time = datetime.now(timezone.utc) - timedelta(minutes=31)
        self.state.ticket06_task_removal_gate["createdAtUtc"] = expired_gate_time.isoformat()
        self.assertEqual(400, self.post_status("/__ticket06/observe-task-removal-absence", {
            "runId": "ticket06-run", "taskId": 42, "taskComponent": target["taskComponent"], "otherTaskIds": [11, 12]
        }))
        self.assertEqual(400, self.post_status("/__ticket06/ack-task-removal", correct_ack))

    def test_ticket06_controls_reject_invalid_counts_and_other_scenarios(self) -> None:
        self.assertEqual(400, self.post_status("/__ticket06/set-report-counts", {"reports": {"math": True}}))
        self.assertEqual(400, self.post_status("/__ticket06/set-report-counts", {"reports": {"unknown": 1}}))
        ticket05 = server.FixtureState("ticket05-run", "ticket05")
        other_httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(ticket05))
        other_thread = threading.Thread(target=other_httpd.serve_forever, daemon=True)
        other_thread.start()
        try:
            with self.assertRaises(HTTPError) as error:
                urlopen(f"http://127.0.0.1:{other_httpd.server_port}/__ticket06/state", timeout=3)
            self.assertEqual(404, error.exception.code)
            error.exception.close()
        finally:
            other_httpd.shutdown()
            other_httpd.server_close()
            other_thread.join(timeout=2)

    def post_status(self, path: str, body: dict) -> int:
        request = Request(
            f"{self.base_url}{path}",
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            urlopen(request, timeout=3)
        except HTTPError as error:
            status = error.code
            error.close()
            return status
        return 200


if __name__ == "__main__":
    unittest.main()
