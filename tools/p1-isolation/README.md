# P1 production-chain synthetic acceptance

The runner uses actual platform and AI jars, real platform login and production RSA delegation. It creates a new owner-labeled PostgreSQL 17/pgvector, Redis and S3 mock project. Existing business containers and databases are never used. Evidence and temporary credentials stay outside the checkout. The temporary PKCS8 key is deleted during cleanup.

```powershell
powershell -NoProfile -File tools/p1-isolation/run.ps1 `
  -Unit P1.4 -Mode Integration -Backends pg `
  -RemoteHost root@synthetic-host `
  -EvidenceDir D:\acceptance\p1
```

`Integration` currently supports only the final `P1.4` migration stage. It applies the unchanged platform V1–V4 and AI V1–V6 SQL bytes to its own database and writes `tenants.sql`, `resources.sql`, HTTP results, sanitized JVM logs and a source manifest. Earlier integration stages are refused explicitly. `Unit` supports the seven listed units and requires fresh XML for every named class with zero failures, errors and skips.

The production-chain cases cover login/proxy 200, authenticated cross-tenant versus missing-resource 404 with business row hashes unchanged, stale pv 409, an actual in-flight HTTP request whose av becomes stale before permit acquire, two AI nodes reading the same ACTIVE set, expired ACTIVE records still blocking drain, pending acquire rejection, the actual coordinator timing out with pv unchanged, service release followed by coordinator drain and a single pv bump with both nodes OPEN, an unavailable node leaving the coordinator PENDING without a pv bump, and actual state/memory Mapper/service reads in the owned database. The shared-set case registers a real AI/platform permit and then expires its synthetic AI lease for fault injection.

The live Java harnesses run in separate JVMs using dependencies extracted from the built product jars. They add no product test endpoint. The state test deliberately uses a common display user ID and session/key in two trusted synthetic principals to verify the composite scope. This is separate from the real login fixtures, whose same-named users have distinct canonical member IDs.

The synthetic environment sets the gateway timeout to 10 seconds because it runs three JVMs and remote database tunnels on one host; production defaults are unchanged. Service calls retain their own bounded timeouts. `-SkipBuild` means no build PASS is inferred: use it only after verifying the exact current sources and jars. Results are scoped to individual cases; `fullP1Pass` remains false. Output-stream fencing, automatic policy mutation prepare/drain, source-reference revocation for memory, full download/export HTTP paths and real migration readiness require additional acceptance.

Exit codes: `0` = all planned cases passed, `1` = failure, `3` = at least one NOT_RUN. Unavailable prerequisites never produce a PASS. Cleanup stops only runner-owned PIDs, removes only its own Compose project/volumes, checks for labeled leftovers, and then deletes its temporary work directory.

Archive and count fresh Surefire reports after each verified build:

```powershell
python tools/p1-isolation/collect-xml.py --repo D:\repo `
  --output D:\acceptance\xml --side ai --since 2026-10-02T08:00:00Z
```

The collector deduplicates fully qualified class/test names, fails on conflicting duplicate outcomes, records excluded stale XML, preserves report hashes, and writes an explicit skipped list. Builds using `-DskipTests` or `-Dmaven.test.skip` cannot establish acceptance; individual skipped cases are recorded explicitly and never counted as passed.
