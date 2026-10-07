import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { summarizeReadPath } from './read-path-observation.mjs';

const sha = 'a'.repeat(64);
const good = { status: 200, code: 200, json: true, rows: 15, elapsedMs: 200 };

test('healthy data is still not a browser pass', () => {
  const record = summarizeReadPath(sha, [good]);
  assert.equal(record.performanceVerdict, 'PASS');
  assert.equal(record.pageVerdict, 'NOT_RUN');
  assert.equal(record.samples[0].rows, 15);
});

test('95.6 seconds with 15 rows fails latency even with a longer observation window', () => {
  const record = summarizeReadPath(sha, [{ ...good, elapsedMs: 95_600 }]);
  assert.equal(record.backendReturnedData, true);
  assert.equal(record.performanceVerdict, 'FAIL');
  assert.ok(record.recommendedObservationWindowMs > 95_600);
  assert.equal(record.clientDeadlineMs, 30_000);
});

test('status and envelope failures never count as data', () => {
  for (const sample of [{ ...good, status: 503 }, { ...good, code: 403 }, { ...good, json: false }]) {
    const record = summarizeReadPath(sha, [sample]);
    assert.equal(record.backendReturnedData, false);
    assert.equal(record.performanceVerdict, 'FAIL');
  }
});

test('success requires measured rows and a full artifact hash', () => {
  assert.throws(() => summarizeReadPath('aabb', [good]));
  assert.throws(() => summarizeReadPath(sha, [{ ...good, rows: null }]));
  assert.throws(() => summarizeReadPath(sha, []));
});

test('the slowest repeat controls the deadline boundary', () => {
  assert.equal(summarizeReadPath(sha, [good, { ...good, elapsedMs: 30_000 }]).performanceVerdict, 'FAIL');
  assert.equal(summarizeReadPath(sha, [good, { ...good, elapsedMs: 29_999 }]).performanceVerdict, 'PASS');
});
