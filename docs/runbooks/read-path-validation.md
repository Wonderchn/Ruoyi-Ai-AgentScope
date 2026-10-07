# Read-path validation

JSON requests have a 30-second deadline, including response-body decoding. Caller
cancellation also aborts transport. Identity changes invalidate late results and
late authentication failures cannot expire a new session. Downloads and SSE use
their separate streaming contracts.

Before browser acceptance, measure the endpoint with the same identity and record
the artifact SHA256, HTTP status, integer envelope code, latency and returned row
count. Set `ACCEPTANCE_BASE_URL`, `ACCEPTANCE_TOKEN`, `ACCEPTANCE_CLIENT_ID` and
`ARTIFACT_SHA256` in the process environment, then run:

```bash
node scripts/ci/read-path-observation.mjs > read-path-observation.json
```

The observer records three reads and a suggested observation window. It emits no
credentials or row contents. HTTP/envelope failures and reads reaching the product
deadline fail its performance verdict. The browser verdict remains `NOT_RUN`:
an endpoint response cannot prove that a page rendered it. A longer browser window
does not convert a slow endpoint into a performance pass. Verify visible timeout
feedback separately; never classify an expired observation window as a page defect
without checking the endpoint and the product deadline first.

The independent migration contract in `scripts/ci/unified-schema-contract.json`
lists supported platform and AI versions plus required domain tables. Missing,
duplicate or extra versions fail. Update that reviewed contract with any intended
new migration. The legacy database and native-runtime checks continue to stage
platform V1–V6 only; the required unified check covers both fresh installation and
upgrade, retained rows, archived history, runtime privileges and default-denied
agent catalog grants.

The unified check also compiles the platform's production P2 projection and
vector queries and executes them on both database paths using rollback-only
synthetic fixtures. It verifies a newly published chunk is returned, tenant and
document scopes exclude other content, and publication changes, tombstones,
staging, missing embeddings and closed registries prevent stale delivery. HTTP
retrieval reads `ai_document*`; it does not fall back to the legacy vector index.

Native runtime CI preserves the actual platform/AI JAR SHA256 values, checkout
revision, PR head revision, migration hashes and individual case verdicts as an
artifact. Its separate-service N1–N3 checks do not stand in for an embedded page
test or an endpoint latency benchmark. N4/N5 retain their explicit `NOT_RUN`
reasons when their external dependencies are absent.
Verdicts use a JSON encoder; CI rejects malformed records, duplicate cases,
summary mismatches and absent or skipped mandatory N1–N3 cases.
