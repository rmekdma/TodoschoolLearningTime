#!/usr/bin/env python3
"""Contract tests for the loopback fixture server's public HTTP surface."""

from __future__ import annotations

import json
import threading
import unittest
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

    def test_ticket06_exposes_run_scoped_reboot_controls_over_http(self) -> None:
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
        self.assertFalse(first_state["ticket06PreRebootPreferencesCaptured"])

        reboot_acknowledged = self.post("/__ticket06/ack-pre-reboot-preferences", {"runId": "ticket06-run"})
        self.assertEqual({"ok": True, "runId": "ticket06-run", "readyForReboot": True}, reboot_acknowledged)
        self.assertTrue(self.get("/__ticket06/state")["ticket06PreRebootPreferencesCaptured"])
        self.assertEqual(400, self.post_status("/__ticket06/ack-pre-reboot-preferences", {"runId": "another-run"}))

        self.assertTrue(self.post("/__ticket06/reset-requests", {})["ok"])
        after_reset = self.get("/__ticket06/state")
        self.assertEqual(0, after_reset["sequence"])
        self.assertEqual([], after_reset["requests"])
        self.assertFalse(after_reset["ticket06PreRebootPreferencesCaptured"])
        self.assertEqual({"hangeul": 6, "math": 5, "english": 7}, after_reset["reportCounts"])

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
