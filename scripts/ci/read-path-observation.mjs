#!/usr/bin/env node
// Observe endpoint latency before choosing a browser acceptance window. A wider
// observation window never relaxes the product's independent request deadline.
import { pathToFileURL } from 'node:url';

export function summarizeReadPath(artifactSha256, samples, clientDeadlineMs = 30_000) {
  if (!/^[a-f0-9]{64}$/i.test(artifactSha256 ?? ''))
    throw new Error('A full product artifact SHA256 is required');
  if (!Number.isFinite(clientDeadlineMs) || clientDeadlineMs <= 0 || !samples.length)
    throw new Error('A positive client deadline and nonempty samples are required');
  for (const sample of samples) {
    if (!Number.isFinite(sample.elapsedMs) || sample.elapsedMs <= 0)
      throw new Error('Each sample needs measured positive latency');
    if (sample.status === 200 && sample.code === 200 && (!Number.isInteger(sample.rows) || sample.rows < 0))
      throw new Error('Successful reads need an explicit row count');
  }
  const backendReturnedData = samples.every(s => s.status === 200 && s.code === 200 && s.json === true);
  const worstElapsedMs = Math.max(...samples.map(s => s.elapsedMs));
  return {
    artifactSha256: artifactSha256.toUpperCase(),
    samples,
    backendReturnedData,
    performanceVerdict: backendReturnedData && worstElapsedMs < clientDeadlineMs ? 'PASS' : 'FAIL',
    clientDeadlineMs,
    worstElapsedMs,
    recommendedObservationWindowMs: Math.ceil((Math.max(worstElapsedMs, clientDeadlineMs) * 2 + 20_000) / 1000) * 1000,
    pageVerdict: 'NOT_RUN',
  };
}

async function main() {
  const env = process.env;
  if (!env.ACCEPTANCE_BASE_URL || !env.ACCEPTANCE_TOKEN || !env.ACCEPTANCE_CLIENT_ID)
    throw new Error('Set ACCEPTANCE_BASE_URL, ACCEPTANCE_TOKEN and ACCEPTANCE_CLIENT_ID');
  if (!/^[a-f0-9]{64}$/i.test(env.ARTIFACT_SHA256 ?? ''))
    throw new Error('Set ARTIFACT_SHA256 before collecting runtime evidence');
  const url = new URL('/api/ai/v1/knowledge-bases', env.ACCEPTANCE_BASE_URL);
  const samples = [];
  // Credentials and response rows are never written to the observation record.
  for (let i = 0; i < 3; i++) {
    const start = performance.now();
    try {
      const response = await fetch(url, {
        headers: { Authorization: `Bearer ${env.ACCEPTANCE_TOKEN}`, ClientID: env.ACCEPTANCE_CLIENT_ID },
        signal: AbortSignal.timeout(300_000),
        redirect: 'error',
      });
      const json = (response.headers.get('content-type') ?? '').includes('application/json');
      const body = json ? await response.json() : null;
      samples.push({ status: response.status, code: body?.code ?? null, json,
        rows: Array.isArray(body?.data) ? body.data.length : null, elapsedMs: performance.now() - start });
    } catch {
      samples.push({ status: 0, code: null, json: false, rows: null, elapsedMs: performance.now() - start });
    }
  }
  const record = summarizeReadPath(env.ARTIFACT_SHA256, samples);
  console.log(JSON.stringify(record, null, 2));
  process.exitCode = record.performanceVerdict === 'PASS' ? 0 : 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)
  await main();
