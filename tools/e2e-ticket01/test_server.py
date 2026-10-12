#!/usr/bin/env python3
"""Contract tests for the loopback fixture server's public HTTP surface."""

from __future__ import annotations

import json
import http.client
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
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

        released = self.post("/__ticket04/release-report-hold", {"caseId": "closed-client", "sequence": sequence})
        self.assertTrue(released["ok"])
        completed = self.wait_until(lambda snapshot: snapshot["requests"][0]["responseStatus"] is not None)
        self.assertEqual("write-failed", completed["requests"][0]["deliveryStatus"])


if __name__ == "__main__":
    unittest.main()
