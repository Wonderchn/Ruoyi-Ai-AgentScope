# Onboarding readiness

`scripts/ops/readiness.py` is a read-only probe for a deployed platform. It answers one question:

> Can a user log in and reach the AI surface, or is the deployment only "up"?

## Checks

- workbench `/` and admin `/admin/` return HTML
- `/auth/tenant/list` has a valid envelope and contains the requested tenant
- login succeeds for the requested tenant/client
- platform identity and menu routers are reachable
- AI knowledge-base, agent catalog, runtime settings, and skills surfaces are classified
- an intentionally empty runtime revision is reported as `WARN`, not silently treated as success

The probe never prints the access token or password. The JSON report contains status, HTTP code,
error code, and remediation text only.

## Run

```bash
export READINESS_PASSWORD='<admin password>'
python3 scripts/ops/readiness.py \
  --base-url http://127.0.0.1:8080 \
  --tenant-id 000000 \
  --username admin \
  --json-out readiness.json
```

PowerShell:

```powershell
$env:READINESS_PASSWORD = '<admin password>'
python scripts/ops/readiness.py --base-url http://127.0.0.1:8080 --tenant-id 000000 --json-out readiness.json
```

If `READINESS_PASSWORD` is absent, public checks still run and authenticated checks are
reported as `SKIP`; this is useful for a hosting/network smoke test but not a full readiness gate.

## Status meanings

| State | Meaning |
|---|---|
| `PASS` | Check succeeded |
| `WARN` | Deployment is reachable but a configured product surface is intentionally empty or disabled |
| `FAIL` | The onboarding journey is blocked |
| `SKIP` | Prerequisite absent (for example, no password or login failed) |

Exit code is `1` when any check is `FAIL`. Use `--strict` to also fail on `WARN`.

## Common remediations

- `MEMBERSHIP_INVALID`
  - The AI gateway cannot see the tenant/member facts. Check `platform.ai_acl_epoch` and
    `platform.sys_ai_policy_revision` for the tenant, then re-run the probe.
- `CONFIG_AUTHORITY_UNAVAILABLE`
  - No runtime configuration revision has been published. Publish one before expecting chat or
    model selection. This is a deployment state, not a code failure.
- `AUTHORIZATION_UNAVAILABLE` on `agent_skills`
  - The skills administration surface is gated behind the agent engine. If the deployment runs
    in workflow mode, this is expected and remains a named boundary.
- `403` on platform endpoints
  - Check the user's role/menu permissions and tenant package; do not widen permissions to make
    the probe pass.

## Tests

```bash
python -m unittest scripts/ops/test_readiness.py
```

The test suite uses a local fake HTTP server; it does not require a database, network, or model
provider.
