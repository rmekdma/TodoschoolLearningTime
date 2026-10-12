#!/usr/bin/env python3
"""Contract tests for the loopback fixture server's public HTTP surface."""

from __future__ import annotations

import json
import threading
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


if __name__ == "__main__":
    unittest.main()
