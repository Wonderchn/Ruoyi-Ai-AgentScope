import type { RunEventEnvelope, SseMessage } from '../src/utils/sse/RunStreamClient.ts';
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  buildRunStreamUrl,
  createRunEventStream,
  CursorExpiredError,
  isTerminalMessage,
  openRunStream,
  RunEventStreamIncompleteError,
  RunStreamHttpError,
  RunStreamStalledError,
} from '../src/utils/sse/RunStreamClient.ts';

const TOKEN = 'unit-test-token';
const BASE = 'https://platform.example.com/api/ai/v1';

/** Builds a Response whose body streams the given text pieces as separate chunks. */
function streamingResponse(pieces: string[], init: { status?: number } = {}): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const piece of pieces) controller.enqueue(encoder.encode(piece));
      controller.close();
    },
  });
  return new Response(body, {
    status: init.status ?? 200,
    headers: { 'content-type': 'text/event-stream' },
  });
}

interface RecordedCall {
  url: string;
  init: RequestInit;
}

function recordingFetch(response: Response | (() => Response)): { calls: RecordedCall[]; fetchImpl: typeof fetch } {
  const calls: RecordedCall[] = [];
  const fetchImpl = (async (input: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(input), init: init ?? {} });
    return typeof response === 'function' ? response() : response;
  }) as unknown as typeof fetch;
  return { calls, fetchImpl };
}

function frame(seq: number, type: string, payload: Record<string, unknown> = {}): string {
  const envelope: RunEventEnvelope = { schemaVersion: 1, runId: 'r-1', seq, type, payload };
  return `id: ${seq}\nevent: ${type}\ndata: ${JSON.stringify(envelope)}\n\n`;
}

describe('runStream: url and credential placement', () => {
  it('never puts the token in the query string', () => {
    const url = buildRunStreamUrl({ baseURL: BASE, runId: 'r-1', afterSeq: 42 });
    assert.ok(!url.includes(TOKEN));
    assert.ok(!/token|authorization|access_token/i.test(url));
    assert.equal(new URL(url).searchParams.get('afterSeq'), '42');
  });

  it('encodes the runId and tolerates a trailing slash in baseURL', () => {
    const url = buildRunStreamUrl({ baseURL: `${BASE}/`, runId: 'r/1 2' });
    assert.ok(url.startsWith(`${BASE}/runs/r%2F1%202/events`));
  });

  it('forwards extra query parameters and drops undefined ones', () => {
    const url = buildRunStreamUrl({ baseURL: BASE, runId: 'r-1' }, { tenant: 't-1', missing: undefined });
    const parsed = new URL(url);
    assert.equal(parsed.searchParams.get('tenant'), 't-1');
    assert.equal(parsed.searchParams.has('missing'), false);
  });
});

describe('runStream: request construction', () => {
  it('defaults to GET (read-only subscription) with credentials and cursor in headers', async () => {
    const { calls, fetchImpl } = recordingFetch(streamingResponse([frame(1, 'run.terminal', { status: 'SUCCEEDED' })]));
    const messages: SseMessage[] = [];
    for await (const message of await createRunEventStream({
      baseURL: BASE,
      runId: 'r-1',
      token: TOKEN,
      clientId: 'c-9',
      afterSeq: 3,
      lastEventId: '3',
      fetchImpl,
    })) messages.push(message);

    assert.equal(calls.length, 1);
    const headers = calls[0].init.headers as Record<string, string>;
    assert.equal(headers.authorization, `Bearer ${TOKEN}`);
    assert.equal(headers.ClientID, 'c-9');
    assert.equal(headers['Last-Event-ID'], '3');
    assert.equal(headers.accept, 'text/event-stream');
    assert.equal(calls[0].init.method, 'GET', 'the contract defines this endpoint as GET');
    assert.equal(calls[0].init.body, undefined, 'a GET subscription must not send a body');
    assert.ok(!calls[0].url.includes(TOKEN));
    assert.equal(new URL(calls[0].url).searchParams.get('afterSeq'), '3');
    assert.equal(messages.length, 1);
  });

  it('still supports POST with a body when explicitly requested', async () => {
    const { calls, fetchImpl } = recordingFetch(streamingResponse([frame(1, 'run.terminal')]));
    for await (const _ of await createRunEventStream({
      baseURL: BASE,
      runId: 'r-1',
      token: TOKEN,
      method: 'POST',
      body: { hello: 'world' },
      fetchImpl,
    })) {
      // drain
    }
    assert.equal(calls[0].init.method, 'POST');
    assert.equal(calls[0].init.body, JSON.stringify({ hello: 'world' }));
    const headers = calls[0].init.headers as Record<string, string>;
    assert.equal(headers['content-type'], 'application/json');
  });
});

describe('runStream: frame delivery', () => {
  it('delivers named events with parsed envelopes and a resumable cursor', async () => {
    const { fetchImpl } = recordingFetch(streamingResponse([
      frame(1, 'run.accepted', { status: 'QUEUED' }),
      ': ping\n\n',
      frame(2, 'run.output_delta', { text: 'he' }),
      frame(3, 'run.output_delta', { text: 'llo' }),
      frame(4, 'run.terminal', { status: 'SUCCEEDED' }),
    ]));
    const messages: SseMessage[] = [];
    for await (const message of await createRunEventStream({ baseURL: BASE, runId: 'r-1', token: TOKEN, fetchImpl })) {
      messages.push(message);
    }

    assert.deepEqual(messages.map((m) => m.type), ['run.accepted', '', 'run.output_delta', 'run.output_delta', 'run.terminal']);
    assert.equal(messages[1].isComment, true);
    assert.equal(messages[2].parsed?.seq, 2);
    assert.equal(messages[2].cursor, 2);
    assert.deepEqual(messages[2].parsed?.payload, { text: 'he' });
    assert.equal(isTerminalMessage(messages[4]), true);
    assert.equal(isTerminalMessage(messages[3]), false);
  });

  it('keeps multi-line data intact instead of dropping earlier lines', async () => {
    const { fetchImpl } = recordingFetch(streamingResponse(['event: run.output_delta\ndata: alpha\ndata: beta\n\n']));
    const messages: SseMessage[] = [];
    for await (const message of await createRunEventStream({ baseURL: BASE, runId: 'r-1', token: TOKEN, fetchImpl })) {
      messages.push(message);
    }
    assert.equal(messages[0].data, 'alpha\nbeta');
    assert.equal(messages[0].parsed, null, 'joined multi-line data is not JSON');
  });

  it('surfaces a non-JSON payload as data with parsed=null', async () => {
    const { fetchImpl } = recordingFetch(streamingResponse(['event: run.output_delta\ndata: not-json\n\n']));
    const messages: SseMessage[] = [];
    for await (const message of await createRunEventStream({ baseURL: BASE, runId: 'r-1', token: TOKEN, fetchImpl })) {
      messages.push(message);
    }
    assert.equal(messages[0].data, 'not-json');
    assert.equal(messages[0].parsed, null);
  });

  it('reassembles a frame split across network chunks', async () => {
    const full = frame(9, 'run.step_completed', { step: 'parse' });
    const { fetchImpl } = recordingFetch(streamingResponse([full.slice(0, 10), full.slice(10, 30), full.slice(30)]));
    const messages: SseMessage[] = [];
    for await (const message of await createRunEventStream({ baseURL: BASE, runId: 'r-1', token: TOKEN, fetchImpl })) {
      messages.push(message);
    }
    assert.equal(messages.length, 1);
    assert.equal(messages[0].parsed?.seq, 9);
  });
});

describe('runStream: failure semantics', () => {
  it('raises CursorExpiredError with lastSeq and snapshot on HTTP 410', async () => {
    const body = JSON.stringify({ code: 410, data: { errorCode: 'CURSOR_EXPIRED', lastSeq: 5231, snapshot: { status: 'SUCCEEDED' } } });
    const { fetchImpl } = recordingFetch(new Response(body, { status: 410 }));
    await assert.rejects(
      async () => {
        for await (const _ of await createRunEventStream({ baseURL: BASE, runId: 'r-1', token: TOKEN, fetchImpl })) {
          // drain
        }
      },
      (error: unknown) => {
        assert.ok(error instanceof CursorExpiredError);
        assert.equal(error.lastSeq, 5231);
        assert.deepEqual(error.snapshot, { status: 'SUCCEEDED' });
        return true;
      },
    );
  });

  it('raises RunStreamHttpError carrying the status for other failures', async () => {
    const { fetchImpl } = recordingFetch(new Response('nope', { status: 503 }));
    await assert.rejects(
      async () => {
        for await (const _ of await createRunEventStream({ baseURL: BASE, runId: 'r-1', token: TOKEN, fetchImpl })) {
          // drain
        }
      },
      (error: unknown) => {
        assert.ok(error instanceof RunStreamHttpError);
        assert.equal(error.status, 503);
        assert.equal(error.body, 'nope');
        return true;
      },
    );
  });

  it('raises RunStreamStalledError when the stream goes silent', async () => {
    const encoder = new TextEncoder();
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const body = new ReadableStream<Uint8Array>({
      start(c) {
        controller = c;
        c.enqueue(encoder.encode(frame(1, 'run.accepted')));
      },
    });
    const { fetchImpl } = recordingFetch(new Response(body, { status: 200 }));
    await assert.rejects(
      async () => {
        for await (const _ of await createRunEventStream(
          { baseURL: BASE, runId: 'r-1', token: TOKEN, fetchImpl },
          { stallTimeoutMs: 50 },
        )) {
          // drain: the second read never resolves, so the watchdog must fire
        }
      },
      (error: unknown) => error instanceof RunStreamStalledError,
    );
    // The client cancels the reader when it detects the stall, which closes the source;
    // closing it again is expected to be a no-op error, not a test failure.
    try {
      controller.close();
    }
    catch {
      // already closed by the client's stall handling
    }
  });
});

describe('runStream: reconnection', () => {
  it('treats a closed stream without a terminal frame as an interruption and resumes', async () => {
    // A live subscription starts at 1 and is cut after 2; the resume replays from the last
    // contiguous seq and continues contiguously (the contract guarantees visible seqs are
    // contiguous, so the reconnect supplies 3 and the terminal).
    let attempt = 0;
    const responses = [
      streamingResponse([frame(1, 'run.accepted'), frame(2, 'run.output_delta', { text: 'a' })]),
      streamingResponse([frame(3, 'run.output_delta', { text: 'b' }), frame(4, 'run.terminal')]),
    ];
    const calls: RecordedCall[] = [];
    const fetchImpl = (async (input: string | URL | Request, init?: RequestInit) => {
      calls.push({ url: String(input), init: init ?? {} });
      return responses[Math.min(attempt++, responses.length - 1)];
    }) as unknown as typeof fetch;

    const stream = openRunStream(
      { baseURL: BASE, runId: 'r-1', token: TOKEN, fetchImpl },
      { sleep: async () => {}, baseDelayMs: 0 },
    );
    const messages: SseMessage[] = [];
    for await (const message of stream.messages) messages.push(message);

    assert.deepEqual(messages.map((m) => m.parsed?.seq), [1, 2, 3, 4]);
    assert.equal(calls.length, 2, 'must reconnect once');
    assert.equal(new URL(calls[1].url).searchParams.get('afterSeq'), '2', 'must resume from the last applied seq');
    assert.equal(stream.appliedCursor(), 4);
  });

  it('drops replayed frames so a seq is never applied twice', async () => {
    let attempt = 0;
    const responses = [
      streamingResponse([frame(1, 'run.accepted'), frame(2, 'run.output_delta', { text: 'a' })]),
      // Overlapping replay: 2 again (already delivered → dropped), then the missing 3..4.
      streamingResponse([frame(2, 'run.output_delta', { text: 'a' }), frame(3, 'run.step_completed'), frame(4, 'run.terminal')]),
    ];
    const fetchImpl = (async () => responses[Math.min(attempt++, responses.length - 1)]) as unknown as typeof fetch;

    const stream = openRunStream(
      { baseURL: BASE, runId: 'r-1', token: TOKEN, fetchImpl },
      { sleep: async () => {} },
    );
    const seqs: number[] = [];
    for await (const message of stream.messages) {
      if (typeof message.cursor === 'number') seqs.push(message.cursor);
    }
    assert.deepEqual(seqs, [1, 2, 3, 4], 'the replayed overlap is dropped, the new frames are delivered once');
  });

  it('deduplicates when it starts from an explicit cursor', async () => {
    const fetchImpl = (async () => streamingResponse([frame(5, 'run.accepted'), frame(6, 'run.terminal')])) as unknown as typeof fetch;
    const stream = openRunStream(
      { baseURL: BASE, runId: 'r-1', token: TOKEN, afterSeq: 5, fetchImpl },
      { sleep: async () => {} },
    );
    const seqs: number[] = [];
    for await (const message of stream.messages) {
      if (typeof message.cursor === 'number') seqs.push(message.cursor);
    }
    assert.deepEqual(seqs, [6]);
  });

  it('stops after the terminal frame without reconnecting', async () => {
    const { fetchImpl, calls } = recordingFetch(streamingResponse([frame(1, 'run.terminal')]));
    const stream = openRunStream({ baseURL: BASE, runId: 'r-1', token: TOKEN, fetchImpl }, { sleep: async () => {} });
    const messages: SseMessage[] = [];
    for await (const message of stream.messages) messages.push(message);
    assert.equal(messages.length, 1);
    assert.equal(calls.length, 1);
  });

  it('throws RunEventStreamIncompleteError when retries run out without a terminal event', async () => {
    const fetchImpl = (async () => streamingResponse([frame(1, 'run.accepted')])) as unknown as typeof fetch;
    const attempts: number[] = [];
    const stream = openRunStream(
      { baseURL: BASE, runId: 'r-1', token: TOKEN, fetchImpl },
      {
        maxRetries: 2,
        baseDelayMs: 0,
        sleep: async () => {},
        onReconnect: (info) => attempts.push(info.attempt),
      },
    );
    await assert.rejects(
      async () => {
        for await (const _ of stream.messages) {
          // drain
        }
      },
      (error: unknown) => {
        assert.ok(error instanceof RunEventStreamIncompleteError, `got ${(error as Error)?.name}`);
        assert.equal(error.reason, 'retries-exhausted');
        assert.equal(error.lastSeq, 1, 'the cursor must not advance past what was applied');
        return true;
      },
      'a stream that never reached a terminal state must not end like a normal completion',
    );
    assert.deepEqual(attempts, [1, 2]);
  });

  it('withholds a terminal until the hole below it is replayed, then delivers every frame once', async () => {
    // Connection 1: 1 then 3(terminal) — 2 is lost. The terminal must NOT be delivered and
    // the cursor must NOT move past the hole. Connection 2 replays from afterSeq=1, so it
    // re-sends 2, 3 and the terminal.
    let attempt = 0;
    const responses = [
      streamingResponse([frame(1, 'run.accepted'), frame(3, 'run.terminal', { status: 'SUCCEEDED' })]),
      streamingResponse([
        frame(2, 'run.output_delta', { text: 'b' }),
        frame(3, 'run.terminal', { status: 'SUCCEEDED' }),
      ]),
    ];
    const calls: RecordedCall[] = [];
    const fetchImpl = (async (input: string | URL | Request, init?: RequestInit) => {
      calls.push({ url: String(input), init: init ?? {} });
      return responses[Math.min(attempt++, responses.length - 1)];
    }) as unknown as typeof fetch;

    const reconnects: Array<{ attempt: number; afterSeq: number | undefined; reason: string }> = [];
    const debug: string[] = [];
    const stream = openRunStream(
      { baseURL: BASE, runId: 'r-1', token: TOKEN, afterSeq: 0, fetchImpl },
      {
        baseDelayMs: 0,
        sleep: async () => {},
        onReconnect: (i) => reconnects.push({ attempt: i.attempt, afterSeq: i.afterSeq, reason: i.reason }),
        onDebug: (m) => debug.push(m),
      },
    );

    const seqs: number[] = [];
    for await (const message of stream.messages) {
      if (typeof message.cursor === 'number') seqs.push(message.cursor);
    }

    assert.deepEqual(seqs, [1, 2, 3], 'the withheld frame is replayed and every seq is delivered exactly once');
    assert.equal(calls.length, 2);
    assert.equal(new URL(calls[1].url).searchParams.get('afterSeq'), '1', 'replay resumes from the last contiguous seq');
    assert.deepEqual(reconnects, [{ attempt: 0, afterSeq: 1, reason: 'gap' }]);
    assert.equal(stream.appliedCursor(), 3);
    assert.ok(
      debug.some((m) => m.includes('terminal withheld') && m.includes('afterSeq=1')),
      `the withheld terminal must be recorded for diagnostics; got ${JSON.stringify(debug)}`,
    );
  });

  it('fails the subscription when a gap cannot be repaired', async () => {
    // The server keeps replaying without the missing seq: the subscription never becomes
    // complete, so the terminal must never be accepted.
    const fetchImpl = (async () => streamingResponse([
      frame(1, 'run.accepted'),
      frame(3, 'run.terminal', { status: 'SUCCEEDED' }),
    ])) as unknown as typeof fetch;
    const stream = openRunStream(
      { baseURL: BASE, runId: 'r-1', token: TOKEN, afterSeq: 0, fetchImpl },
      { maxResumeCycles: 2, baseDelayMs: 0, sleep: async () => {} },
    );

    const seqs: number[] = [];
    await assert.rejects(
      async () => {
        for await (const message of stream.messages) {
          if (typeof message.cursor === 'number') seqs.push(message.cursor);
        }
      },
      (error: unknown) => {
        assert.ok(error instanceof RunEventStreamIncompleteError, `got ${(error as Error)?.name}`);
        assert.equal(error.reason, 'resume-limit');
        return true;
      },
      'an unrepairable gap must not end as a completed subscription',
    );
    assert.deepEqual(seqs, [1], 'the terminal is never delivered while the hole is open');
    assert.equal(stream.appliedCursor(), 1, 'the cursor stops at the last contiguous seq');
  });

  it('treats a non-1 first frame as a hole (default cursor is 0)', async () => {
    // Without an explicit afterSeq the contract says replay from 0, so a stream that opens
    // with seq=3 (terminal) must be repaired rather than accepted.
    let attempt = 0;
    const responses = [
      streamingResponse([frame(3, 'run.terminal', { status: 'SUCCEEDED' })]),
      streamingResponse([
        frame(1, 'run.accepted'),
        frame(2, 'run.output_delta'),
        frame(3, 'run.terminal', { status: 'SUCCEEDED' }),
      ]),
    ];
    const calls: RecordedCall[] = [];
    const fetchImpl = (async (input: string | URL | Request, init?: RequestInit) => {
      calls.push({ url: String(input), init: init ?? {} });
      return responses[Math.min(attempt++, responses.length - 1)];
    }) as unknown as typeof fetch;

    const stream = openRunStream(
      { baseURL: BASE, runId: 'r-1', token: TOKEN, fetchImpl },
      { baseDelayMs: 0, sleep: async () => {} },
    );

    const seqs: number[] = [];
    for await (const message of stream.messages) {
      if (typeof message.cursor === 'number') seqs.push(message.cursor);
    }

    assert.deepEqual(seqs, [1, 2, 3], 'the missing prefix is replayed before the terminal');
    assert.equal(new URL(calls[0].url).searchParams.get('afterSeq'), '0', 'the first request uses the default cursor 0');
    assert.equal(new URL(calls[1].url).searchParams.get('afterSeq'), '0', 'repair replays from 0');
  });

  it('stops gap repair loops after maxResumeCycles', async () => {
    // A server that always jumps from 1 to 3 can never be repaired.
    const fetchImpl = (async () => streamingResponse([
      frame(1, 'run.accepted'),
      frame(3, 'run.output_delta'),
    ])) as unknown as typeof fetch;
    const stream = openRunStream(
      { baseURL: BASE, runId: 'r-1', token: TOKEN, afterSeq: 0, fetchImpl },
      { maxResumeCycles: 2, baseDelayMs: 0, sleep: async () => {} },
    );
    await assert.rejects(
      async () => {
        for await (const _ of stream.messages) {
          // drain
        }
      },
      (error: unknown) => {
        assert.ok(error instanceof RunEventStreamIncompleteError);
        assert.equal(error.reason, 'resume-limit');
        return true;
      },
    );
    assert.equal(stream.appliedCursor(), 1, 'the cursor must stay at the last contiguous seq');
  });

  it('does not reconnect after CursorExpiredError (the caller must re-authorise)', async () => {
    const fetchImpl = (async () => new Response('{"data":{"errorCode":"CURSOR_EXPIRED"}}', { status: 410 })) as unknown as typeof fetch;
    const stream = openRunStream({ baseURL: BASE, runId: 'r-1', token: TOKEN, fetchImpl }, { maxRetries: 3, sleep: async () => {} });
    await assert.rejects(
      async () => {
        for await (const _ of stream.messages) {
          // drain
        }
      },
      (error: unknown) => error instanceof CursorExpiredError,
    );
  });

  it('does not reconnect on a 4xx client error', async () => {
    let calls = 0;
    const fetchImpl = (async () => {
      calls += 1;
      return new Response('forbidden', { status: 403 });
    }) as unknown as typeof fetch;
    const stream = openRunStream({ baseURL: BASE, runId: 'r-1', token: TOKEN, fetchImpl }, { maxRetries: 3, sleep: async () => {} });
    await assert.rejects(
      async () => {
        for await (const _ of stream.messages) {
          // drain
        }
      },
      (error: unknown) => error instanceof RunStreamHttpError && error.status === 403,
    );
    assert.equal(calls, 1);
  });

  it('reconnects when the connection stalls, then delivers the remaining events', async () => {
    const encoder = new TextEncoder();
    let attempt = 0;
    const fetchImpl = (async () => {
      attempt += 1;
      if (attempt === 1) {
        const body = new ReadableStream<Uint8Array>({
          start(c) {
            c.enqueue(encoder.encode(frame(1, 'run.accepted')));
            // never closes → stall
          },
        });
        return new Response(body, { status: 200 });
      }
      return streamingResponse([frame(2, 'run.output_delta'), frame(3, 'run.terminal')]);
    }) as unknown as typeof fetch;

    const stream = openRunStream(
      { baseURL: BASE, runId: 'r-1', token: TOKEN, fetchImpl },
      { stallTimeoutMs: 50, maxRetries: 2, baseDelayMs: 0, sleep: async () => {} },
    );
    const seqs: number[] = [];
    for await (const message of stream.messages) {
      if (typeof message.cursor === 'number') seqs.push(message.cursor);
    }
    assert.deepEqual(seqs, [1, 2, 3]);
    assert.equal(attempt, 2);
  });
});
