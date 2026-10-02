# Synthetic fixture contract

`generate.ps1 -Mode Validate -OutputDir <absolute outside-checkout path>` validates the shared JSON contract, exact counts, tenant/member identities, data scope controls and input hashes. `Generate` additionally copies the three synthetic input files with unchanged bytes into that output directory. Neither mode connects to a database or executes SQL.

The contract is shared with `P1FixtureContractTest`. Its successful validation establishes input consistency only. Production-chain execution uses `tools/p1-isolation/run.ps1`, its final-stage fixtures and separately owned databases; legacy fixture counts cannot stand in for runtime isolation or evidence of no existing business data.
