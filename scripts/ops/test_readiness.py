from __future__ import annotations

import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import readiness  # noqa: E402


def envelope(data):
    return {"code": 200, "msg": "操作成功", "data": data}


def make_handler(state):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            path = self.path.split("?", 1)[0]
            if path in ("/", "/admin/"):
                self.send_response(200)
                self.send_header("content-type", "text/html; charset=utf-8")
                self.end_headers()
                self.wfile.write(b"<!doctype html><html></html>")
                return
            if path == "/auth/tenant/list":
                self.reply(envelope({
                    "tenantEnabled": True,
                    "voList": state.get("tenants", [{"tenantId": "000000", "companyName": "A"}]),
                }))
                return
            if path in ("/system/user/getInfo", "/system/menu/getRouters"):
                self.reply(envelope({}))
                return
            if path == "/api/ai/v1/knowledge-bases":
                if state.get("membership_invalid"):
                    self.reply({"code": 403, "msg": "成员无效", "data": {"errorCode": "MEMBERSHIP_INVALID"}}, 403)
                else:
                    self.reply(envelope([]))
                return
            if path == "/api/ai/v1/agent-catalog/agents":
                self.reply(envelope({"agents": []}))
                return
            if path == "/api/ai/v1/runtime-config/catalog":
                if state.get("runtime_empty"):
                    self.reply({"code": 503, "msg": "运行配置不可用", "data": {"errorCode": "CONFIG_AUTHORITY_UNAVAILABLE"}}, 503)
                else:
                    self.reply(envelope({"models": []}))
                return
            if path == "/api/ai/v1/runtime-config/settings":
                self.reply(envelope({}))
                return
            if path == "/api/ai/v1/agent-catalog/agent-skills":
                if state.get("skills_disabled"):
                    self.reply({"code": 503, "msg": "授权服务不可用", "data": {"errorCode": "AUTHORIZATION_UNAVAILABLE"}}, 503)
                else:
                    self.reply(envelope([]))
                return
            self.reply({"code": 404, "msg": "not found"}, 404)

        def do_POST(self):  # noqa: N802
            if self.path.split("?", 1)[0] != "/auth/login":
                self.reply({"code": 404, "msg": "not found"}, 404)
                return
            length = int(self.headers.get("content-length", "0"))
            body = json.loads(self.rfile.read(length) or b"{}")
            if state.get("login_rejected"):
                self.reply({"code": 401, "msg": "认证失败"}, 401)
            elif body.get("tenantId") != "000000":
                self.reply({"code": 401, "msg": "认证失败"}, 401)
            else:
                self.reply(envelope({"access_token": "test-token", "token": "test-token"}))
        def reply(self, payload, status=200):
            raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("content-type", "application/json; charset=utf-8")
            self.send_header("content-length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, *_args):
            pass

    return Handler


class ReadinessProbeTest(unittest.TestCase):
    def run_probe(self, state, password="pw"):
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(state))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            probe = readiness.Probe(
                base_url=f"http://127.0.0.1:{server.server_port}",
                client_id="dev-client",
                tenant_id="000000",
                username="admin",
                password=password,
                timeout=5,
            )
            checks = probe.run()
            return checks, readiness.summarize(checks)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    @staticmethod
    def by_name(checks):
        return {check.name: check for check in checks}

    def test_happy_path(self):
        checks, summary = self.run_probe({})
        self.assertEqual(summary["fail"], 0)
        self.assertEqual(summary["warn"], 0)
        self.assertEqual(self.by_name(checks)["ai_knowledge"].state, "PASS")

    def test_membership_invalid_is_a_hard_failure(self):
        checks, summary = self.run_probe({"membership_invalid": True})
        self.assertEqual(summary["fail"], 1)
        self.assertEqual(self.by_name(checks)["ai_knowledge"].state, "FAIL")

    def test_empty_runtime_and_disabled_skills_are_warnings(self):
        checks, summary = self.run_probe({"runtime_empty": True, "skills_disabled": True})
        self.assertEqual(summary["fail"], 0)
        self.assertEqual(summary["warn"], 2)
        self.assertEqual(self.by_name(checks)["runtime_catalog"].state, "WARN")
        self.assertEqual(self.by_name(checks)["agent_skills"].state, "WARN")

    def test_missing_tenant_fails_before_login(self):
        checks, summary = self.run_probe({"tenants": []})
        self.assertEqual(summary["fail"], 1)
        self.assertEqual(self.by_name(checks)["tenant_catalog"].state, "FAIL")
        self.assertNotIn("ai_knowledge", self.by_name(checks))

    def test_login_rejection_fails_and_skips_authenticated_checks(self):
        checks, summary = self.run_probe({"login_rejected": True})
        self.assertEqual(summary["fail"], 1)
        self.assertEqual(self.by_name(checks)["login"].state, "FAIL")
        self.assertEqual(self.by_name(checks)["ai_knowledge"].state, "SKIP")

    def test_missing_password_skips_authenticated_checks(self):
        checks, summary = self.run_probe({}, password=None)
        self.assertEqual(summary["fail"], 0)
        self.assertEqual(self.by_name(checks)["login"].state, "SKIP")
        self.assertEqual(self.by_name(checks)["ai_knowledge"].state, "SKIP")


if __name__ == "__main__":
    unittest.main()
