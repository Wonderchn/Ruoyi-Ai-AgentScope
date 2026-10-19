#!/usr/bin/env python3
"""P0 onboarding readiness probe for one deployed platform.

The probe is deliberately read-only. It checks the public pages, tenant
catalog, login, platform identity, and the AI gateway surfaces that are
required for a first successful chat. It never prints tokens or passwords.

Exit codes:
  0 = no FAIL checks
  1 = at least one FAIL check
  2 = invalid invocation
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


@dataclass
class Check:
    name: str
    state: str
    http: int | None = None
    code: int | None = None
    detail: str = ""
    remediation: str = ""


class Probe:
    def __init__(
        self,
        base_url: str,
        client_id: str,
        tenant_id: str,
        username: str,
        password: str | None,
        timeout: float,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.client_id = client_id
        self.tenant_id = tenant_id
        self.username = username
        self.password = password
        self.timeout = timeout

    def _request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        token: str | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, Any, str]:
        headers = {
            "Accept": "application/json",
            "ClientID": self.client_id,
            "User-Agent": "ruoyi-onboarding-readiness/1",
        }
        if extra_headers:
            headers.update(extra_headers)
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        request = urllib.request.Request(
            self.base_url + path,
            data=data,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8", "replace")
                content_type = response.headers.get("content-type", "")
                try:
                    payload = json.loads(raw)
                except json.JSONDecodeError:
                    payload = raw
                return response.status, payload, content_type
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8", "replace")
            error.close()
            try:
                payload = json.loads(raw)
            except json.JSONDecodeError:
                payload = raw
            return error.code, payload, error.headers.get("content-type", "")
        except Exception as error:  # noqa: BLE001 - report transport failures as checks
            return 0, {"_error": str(error)}, ""

    @staticmethod
    def _envelope(payload: Any) -> dict[str, Any]:
        return payload if isinstance(payload, dict) else {}

    @staticmethod
    def _data(payload: Any) -> dict[str, Any]:
        if not isinstance(payload, dict):
            return {}
        data = payload.get("data")
        return data if isinstance(data, dict) else {}

    @staticmethod
    def _error_code(payload: Any) -> str:
        data = Probe._data(payload)
        value = data.get("errorCode") or data.get("error_code")
        return value if isinstance(value, str) else ""

    @staticmethod
    def _msg(payload: Any) -> str:
        return str(Probe._envelope(payload).get("msg") or "")

    @staticmethod
    def _ok(status: int, payload: Any) -> bool:
        return status == 200 and Probe._envelope(payload).get("code") == 200

    def _web_check(self, path: str, name: str) -> Check:
        status, payload, content_type = self._request(
            "GET", path, extra_headers={"Accept": "text/html"}
        )
        if status == 200 and "text/html" in content_type.lower():
            return Check(name, "PASS", status, None, "page served")
        return Check(
            name,
            "FAIL",
            status,
            None,
            "page not served as HTML",
            "check the web container and nginx proxy",
        )

    def _tenant_check(self) -> tuple[Check, bool]:
        status, payload, _ = self._request(
            "GET", "/auth/tenant/list", extra_headers={"Referer": self.base_url + "/"}
        )
        data = self._data(payload)
        enabled = data.get("tenantEnabled") if isinstance(data.get("tenantEnabled"), bool) else None
        tenants = data.get("voList")
        if status != 200 or enabled is None or not isinstance(tenants, list):
            return (
                Check(
                    "tenant_catalog",
                    "FAIL",
                    status,
                    self._envelope(payload).get("code"),
                    "tenant catalog response is invalid",
                    "check platform /auth/tenant/list and its JSON envelope",
                ),
                False,
            )
        if not enabled:
            return Check("tenant_catalog", "PASS", status, None, "tenant mode disabled"), False
        ids = [str(item.get("tenantId")) for item in tenants if isinstance(item, dict)]
        if self.tenant_id not in ids:
            return (
                Check(
                    "tenant_catalog",
                    "FAIL",
                    status,
                    None,
                    f"tenant {self.tenant_id} is not in the catalog",
                    "create/seed the tenant or pass the correct --tenant-id",
                ),
                False,
            )
        return (
            Check(
                "tenant_catalog",
                "PASS",
                status,
                None,
                f"{len(ids)} tenant(s) available; {self.tenant_id} present",
            ),
            True,
        )

    def _login_check(self) -> tuple[Check, str | None]:
        if not self.password:
            return (
                Check(
                    "login",
                    "SKIP",
                    None,
                    None,
                    "READINESS_PASSWORD is not set",
                    "set READINESS_PASSWORD to run authenticated checks",
                ),
                None,
            )
        status, payload, _ = self._request(
            "POST",
            "/auth/login",
            {
                "tenantId": self.tenant_id,
                "username": self.username,
                "password": self.password,
                "grantType": "password",
                "clientId": self.client_id,
            },
        )
        data = self._data(payload)
        token = data.get("access_token") or data.get("token")
        if status == 200 and isinstance(token, str) and token:
            return Check("login", "PASS", status, 200, "access token issued"), token
        return (
            Check(
                "login",
                "FAIL",
                status,
                self._envelope(payload).get("code"),
                self._msg(payload) or "login rejected",
                "check tenant, username, password, clientId, and user status",
            ),
            None,
        )

    def _platform_check(self, name: str, path: str, token: str) -> Check:
        status, payload, _ = self._request("GET", path, token=token)
        if self._ok(status, payload):
            return Check(name, "PASS", status, 200, self._msg(payload) or "ok")
        return Check(
            name,
            "FAIL",
            status,
            self._envelope(payload).get("code"),
            self._error_code(payload) or self._msg(payload) or "request failed",
            "check the platform user, role, menu, and tenant permissions",
        )

    def _ai_read_check(
        self,
        name: str,
        path: str,
        token: str,
        expected_empty_code: str | None = None,
        empty_remediation: str = "publish the runtime config before expecting chat/model selection",
    ) -> Check:
        status, payload, _ = self._request("GET", path, token=token)
        if self._ok(status, payload):
            return Check(name, "PASS", status, 200, "ok")
        error_code = self._error_code(payload)
        if expected_empty_code and status == 503 and error_code == expected_empty_code:
            return Check(
                name,
                "WARN",
                status,
                503,
                error_code + ": feature is intentionally empty",
                empty_remediation,
            )
        if error_code == "MEMBERSHIP_INVALID":
            return Check(
                name,
                "FAIL",
                status,
                self._envelope(payload).get("code"),
                "AI membership facts are missing",
                "seed platform.ai_acl_epoch and platform.sys_ai_policy_revision for the tenant",
            )
        return Check(
            name,
            "FAIL",
            status,
            self._envelope(payload).get("code"),
            error_code or self._msg(payload) or "AI request failed",
            "check tenant AI permissions, ACL epoch, policy revision, and runtime config",
        )

    def run(self) -> list[Check]:
        checks: list[Check] = [
            self._web_check("/", "workbench_page"),
            self._web_check("/admin/", "admin_page"),
        ]
        tenant, tenant_enabled = self._tenant_check()
        checks.append(tenant)
        if not tenant_enabled:
            return checks

        login, token = self._login_check()
        checks.append(login)
        if not token:
            checks.extend(
                Check(name, "SKIP", None, None, "login did not produce a token")
                for name in ("identity", "routers", "ai_knowledge", "ai_agents",
                             "runtime_catalog", "runtime_settings", "agent_skills")
            )
            return checks

        checks.append(self._platform_check("identity", "/system/user/getInfo", token))
        checks.append(self._platform_check("routers", "/system/menu/getRouters", token))
        checks.append(self._ai_read_check("ai_knowledge", "/api/ai/v1/knowledge-bases", token))
        checks.append(self._ai_read_check("ai_agents", "/api/ai/v1/agent-catalog/agents", token))
        checks.append(
            self._ai_read_check(
                "runtime_catalog",
                "/api/ai/v1/runtime-config/catalog",
                token,
                expected_empty_code="CONFIG_AUTHORITY_UNAVAILABLE",
            )
        )
        checks.append(self._ai_read_check("runtime_settings", "/api/ai/v1/runtime-config/settings", token))
        checks.append(
            self._ai_read_check(
                "agent_skills",
                "/api/ai/v1/agent-catalog/agent-skills",
                token,
                expected_empty_code="AUTHORIZATION_UNAVAILABLE",
                empty_remediation="enable the agent engine only if this deployment intentionally serves skills",
            )
        )
        return checks


def summarize(checks: list[Check]) -> dict[str, Any]:
    counts: dict[str, int] = {}
    for check in checks:
        counts[check.state] = counts.get(check.state, 0) + 1
    return {
        "total": len(checks),
        "pass": counts.get("PASS", 0),
        "warn": counts.get("WARN", 0),
        "fail": counts.get("FAIL", 0),
        "skip": counts.get("SKIP", 0),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read-only onboarding readiness probe")
    parser.add_argument("--base-url", default=os.getenv("READINESS_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--client-id", default=os.getenv("READINESS_CLIENT_ID", "dev-client"))
    parser.add_argument("--tenant-id", default=os.getenv("READINESS_TENANT_ID", "000000"))
    parser.add_argument("--username", default=os.getenv("READINESS_USERNAME", "admin"))
    parser.add_argument("--password-env", default="READINESS_PASSWORD")
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--strict", action="store_true", help="treat WARN as a failed readiness gate")
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args(argv)

    password = os.getenv(args.password_env)
    probe = Probe(
        base_url=args.base_url,
        client_id=args.client_id,
        tenant_id=args.tenant_id,
        username=args.username,
        password=password,
        timeout=args.timeout,
    )
    checks = probe.run()
    summary = summarize(checks)
    for check in checks:
        print(f"{check.state:4} {check.name:18} http={check.http!s:4} code={check.code!s:4} {check.detail}")
        if check.remediation:
            print(f"      remediation: {check.remediation}")
    print("SUMMARY " + json.dumps(summary, ensure_ascii=False, separators=(",", ":")))

    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(
            json.dumps(
                {
                    "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                    "base_url": args.base_url,
                    "tenant_id": args.tenant_id,
                    "checks": [asdict(check) for check in checks],
                    "summary": summary,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
    if summary["fail"]:
        return 1
    if args.strict and summary["warn"]:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
