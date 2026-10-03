import type { SseEvent } from './SseSyntax';
import { SseFrameParser } from './SseSyntax';

/**
 * The versioned run/SSE envelope this client speaks. It mirrors the contract drafted in
 * the integration Spec (run events: `schemaVersion`, `runId`, `seq`, `type`, `payload`).
 */
export interface RunEventEnvelope {
  schemaVersion: number;
  eventId?: string;
  runId?: string;
  attemptId?: string;
  seq?: number;
  type?: string;
  at?: string;
  payload?: unknown;
}

/** One message produced by {@link createRunEventStream}. */
export interface SseMessage {
  /** SSE `event:` name. Empty string means the spec default type. */
  type: string;
  /** Raw `data:` payload as received (never re-joined or truncated). */
  data: string;
  /** `id:` of this frame when the server sent one. */
  id: string | undefined;
  /** Cursor to resume from: the envelope `seq` when present, otherwise the SSE `id`. */
  cursor: string | number | undefined;
  /** Parsed envelope, or null when the payload was not JSON. */
  parsed: RunEventEnvelope | null;
  /** True for heartbeat/comment frames (`:` lines) — no payload. */
  isComment: boolean;
  /** Monotonic receive timestamp (ms). */
  receivedAt: number;
}

/** Thrown when the server reports that the requested cursor fell out of the retention window. */
export class CursorExpiredError extends Error {
  readonly lastSeq: number | undefined;
  readonly snapshot: unknown;

  constructor(message: string, lastSeq?: number, snapshot?: unknown) {
    super(message);
    this.name = 'CursorExpiredError';
    this.lastSeq = lastSeq;
    this.snapshot = snapshot;
  }
}

/** Thrown when no frame (including heartbeats) arrived within the stall timeout. */
export class RunStreamStalledError extends Error {
  constructor(silentForMs: number, timeoutMs: number) {
    super(`run stream was silent for ${silentForMs}ms (timeout ${timeoutMs}ms)`);
    this.name = 'RunStreamStalledError';
  }
}

/** Thrown when a run stream ended without ever carrying a terminal event. */
export class RunEventStreamIncompleteError extends Error {
  readonly lastSeq: number | undefined;
  readonly connections: number;
  readonly reason: 'retries-exhausted' | 'resume-limit';

  constructor(lastSeq: number | undefined, connections: number, reason: 'retries-exhausted' | 'resume-limit') {
    super(
      `run event stream ended without a terminal event after ${connections} connection(s)`
      + ` (last applied seq: ${lastSeq ?? 'none'}, reason: ${reason})`,
    );
    this.name = 'RunEventStreamIncompleteError';
    this.lastSeq = lastSeq;
    this.connections = connections;
    this.reason = reason;
  }
}

/** Why a frame was rejected by the envelope contract gate. */
export type RunEventProtocolViolation = 'not-json' | 'schema-version' | 'run-id' | 'seq' | 'sse-id' | 'type';

/**
 * Thrown when a frame violates the versioned run/SSE envelope contract: the payload is
 * not the agreed envelope, names another run, or carries no usable cursor. This is a
 * server-side contract breach, so it is never retried and — critically — a terminal
 * frame that fails it never ends the subscription as if the run were complete.
 */
export class RunEventStreamProtocolError extends Error {
  readonly reason: RunEventProtocolViolation;

  constructor(reason: RunEventProtocolViolation, detail: string) {
    super(`run event stream violates the envelope contract (${reason}): ${detail}`);
    this.name = 'RunEventStreamProtocolError';
    this.reason = reason;
  }
}

/** Thrown when the HTTP response was not a usable event stream. */
export class RunStreamHttpError extends Error {
  readonly status: number;
  readonly body: string;

  constructor(status: number, body: string, message?: string) {
    super(message ?? `run stream request failed with HTTP ${status}`);
    this.name = 'RunStreamHttpError';
    this.status = status;
    this.body = body;
  }
}

export interface RunStreamRequest {
  baseURL: string;
  runId: string;
  /** User token; sent as `authorization: Bearer <token>`, never in the URL. */
  token: string;
  clientId?: string;
  /**
   * Resume cursor: the highest `seq` already delivered by the caller. Defaults to `0`,
   * which is the contract's "replay from the start of the visible events" (a non-1 first
   * frame is therefore treated as a hole, never as a legitimate live start).
   */
  afterSeq?: number;
  /** Explicit SSE resumption header; `afterSeq` is appended to the query when set. */
  lastEventId?: string;
  method?: 'GET' | 'POST';
  body?: unknown;
  fetchImpl?: typeof fetch;
  headers?: Record<string, string>;
}

export interface RunStreamOptions {
  /** Max silence before {@link RunStreamStalledError}. Must exceed the server heartbeat interval. */
  stallTimeoutMs?: number;
  /** Invoked for every comment/heartbeat frame. */
  onHeartbeat?: () => void;
  /** Extra query parameters for every (re)connect. */
  query?: Record<string, string | number | undefined>;
  signal?: AbortSignal;
  /**
   * Diagnostic hook. Disabled by default; pass a function to trace connection and
   * watchdog decisions when debugging a stalled stream in the field.
   */
  onDebug?: (message: string) => void;
}

const DEFAULT_STALL_TIMEOUT_MS = 45_000;

/** Upper bound on transport cleanup; `cancel()` can stay pending on a wrapped body. */
const CLEANUP_TIMEOUT_MS = 250;

/** Minimal shape of a stream read result, so the client does not depend on DOM lib typings. */
interface StreamReadResult {
  done: boolean;
  value?: Uint8Array;
}

/**
 * Builds the SSE url. The token is deliberately never a query parameter — credentials
 * travel in the `authorization` header only (see the SSE pre-gate evidence).
 */
export function buildRunStreamUrl(
  request: Pick<RunStreamRequest, 'baseURL' | 'runId' | 'afterSeq'>,
  query?: Record<string, string | number | undefined>,
): string {
  const base = request.baseURL.endsWith('/') ? request.baseURL.slice(0, -1) : request.baseURL;
  const url = new URL(`${base}/runs/${encodeURIComponent(request.runId)}/events`, globalThis.location?.origin);
  if (request.afterSeq !== undefined)
    url.searchParams.set('afterSeq', String(request.afterSeq));
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value !== undefined)
      url.searchParams.set(key, String(value));
  }
  return url.toString();
}

function parseEnvelope(data: string): RunEventEnvelope | null {
  if (data.length === 0)
    return null;
  try {
    const parsed: unknown = JSON.parse(data);
    if (parsed !== null && typeof parsed === 'object')
      return parsed as RunEventEnvelope;
    return null;
  }
  catch {
    return null;
  }
}

function toMessage(event: SseEvent): SseMessage {
  const parsed = event.isComment ? null : parseEnvelope(event.data);
  const seq = typeof parsed?.seq === 'number' ? parsed.seq : undefined;
  return {
    type: event.type,
    data: event.data,
    id: event.id,
    cursor: seq ?? event.lastEventId,
    parsed,
    isComment: event.isComment,
    receivedAt: Date.now(),
  };
}

/** The envelope shape the state machine may accept: run identity, cursor and type verified. */
export type VerifiedRunEventEnvelope = RunEventEnvelope & { runId: string; seq: number; type: string };

/** The only envelope `schemaVersion` this client knows how to interpret. */
const SUPPORTED_SCHEMA_VERSION = 1;

/**
 * Contract gate for every business frame, applied before the state machine accepts it.
 *
 * A frame proves nothing about the run while its envelope is unverifiable, so all of the
 * following must hold before a frame is delivered — a terminal frame in particular never
 * gets to vouch for a complete subscription on its own:
 * - `schemaVersion` is the one this client speaks (a newer version has semantics this
 *   client must not guess at);
 * - `runId` names exactly the run that was subscribed to — a foreign-run frame must never
 *   be merged into this stream, let alone end it;
 * - `seq` is a safe positive integer, because it doubles as the persistence cursor;
 * - the SSE `id:` agrees with `seq` when the frame carries one (contract: `id: <seq>`).
 *   A frame with no `id:` at all is tolerated: the cursor comes from the envelope `seq`.
 * - the SSE `event:` name and the envelope `type` are both present and identical. They
 *   are two markers of the same event (contract: `event: <type>`), and terminality is
 *   classified from this verified type — a disagreeing marker could otherwise smuggle a
 *   terminal past the other, or hide one behind it.
 *
 * Throws {@link RunEventStreamProtocolError} on the first violation.
 */
function validateRunEventEnvelope(message: SseMessage, expectedRunId: string): VerifiedRunEventEnvelope {
  const envelope = message.parsed;
  if (envelope === null) {
    throw new RunEventStreamProtocolError('not-json', `event '${message.type || '(default)'}' has no JSON envelope`);
  }
  if (envelope.schemaVersion !== SUPPORTED_SCHEMA_VERSION) {
    throw new RunEventStreamProtocolError(
      'schema-version',
      `expected schemaVersion ${SUPPORTED_SCHEMA_VERSION}, got ${String(envelope.schemaVersion)}`,
    );
  }
  if (typeof envelope.runId !== 'string' || envelope.runId === '') {
    throw new RunEventStreamProtocolError('run-id', 'the envelope does not name a run');
  }
  if (envelope.runId !== expectedRunId) {
    throw new RunEventStreamProtocolError('run-id', `subscription is for ${expectedRunId}, frame names ${envelope.runId}`);
  }
  const seq = envelope.seq;
  if (typeof seq !== 'number' || !Number.isSafeInteger(seq) || seq < 1) {
    throw new RunEventStreamProtocolError('seq', `expected a safe positive integer, got ${String(seq)}`);
  }
  if (message.id !== undefined && message.id !== String(seq)) {
    throw new RunEventStreamProtocolError('sse-id', `SSE id '${message.id}' does not match seq ${seq}`);
  }
  if (typeof envelope.type !== 'string' || envelope.type === '' || message.type === '') {
    throw new RunEventStreamProtocolError(
      'type',
      'the frame must carry both the SSE event name and a non-empty envelope type',
    );
  }
  if (envelope.type !== message.type) {
    throw new RunEventStreamProtocolError('type', `SSE event '${message.type}' but envelope type '${envelope.type}'`);
  }
  return envelope as VerifiedRunEventEnvelope;
}

/**
 * A live single-connection event stream.
 *
 * Implemented as an explicit async iterator rather than an async generator so that the
 * watchdog/read/cleanup transitions are visible and individually reviewable.
 */
export interface RunEventConnection extends AsyncIterator<SseMessage, undefined>, AsyncIterable<SseMessage> {
  next: () => Promise<IteratorResult<SseMessage, undefined>>;
  /** Stops the connection and releases the underlying response body. */
  return: () => Promise<IteratorResult<SseMessage, undefined>>;
}

/**
 * Opens one HTTP connection and turns its body into {@link SseMessage}s.
 *
 * Guarantees that mirror the run/SSE contract:
 * - multi-line `data:` is joined with `\n` (no truncation to the last line);
 * - `id:` is preserved on the message so the caller can resume from it;
 * - comment/heartbeat frames are surfaced separately and never mixed into payloads;
 * - a silent stream raises {@link RunStreamStalledError} instead of hanging forever;
 * - a non-2xx response raises {@link RunStreamHttpError} / {@link CursorExpiredError}
 *   before any frame is produced, so the caller can distinguish it from a dropped stream.
 *
 * Cleanup is time-boxed: cancelling a body that still has a read in flight can stay
 * pending, and that must never delay the error or the final message.
 */
export async function createRunEventStream(
  request: RunStreamRequest,
  options: RunStreamOptions = {},
): Promise<RunEventConnection> {
  const fetchImpl = request.fetchImpl ?? globalThis.fetch;
  if (typeof fetchImpl !== 'function')
    throw new Error('no fetch implementation available');

  const url = buildRunStreamUrl(request, options.query);
  const headers: Record<string, string> = {
    'accept': 'text/event-stream',
    'cache-control': 'no-cache',
    ...request.headers,
  };
  if (request.token)
    headers.authorization = `Bearer ${request.token}`;
  if (request.clientId)
    headers.ClientID = request.clientId;
  // Merging (not replacing) the header map is required: a naive per-request override
  // would drop `authorization` and silently de-authenticate the stream.
  if (request.lastEventId)
    headers['Last-Event-ID'] = request.lastEventId;

  // The run/SSE contract defines this endpoint as a read, so GET is the default.
  // POST stays available for deployments that need a request body.
  const method = request.method ?? 'GET';
  const init: RequestInit = { method, headers, signal: options.signal };
  if (method === 'POST' && request.body !== undefined) {
    headers['content-type'] = 'application/json';
    init.body = JSON.stringify(request.body);
  }

  const response = await fetchImpl(url, init);

  if (response.status === 410) {
    const body = await response.text().catch(() => '');
    let lastSeq: number | undefined;
    let snapshot: unknown;
    try {
      const parsed: unknown = JSON.parse(body);
      if (parsed !== null && typeof parsed === 'object') {
        const data = (parsed as { data?: unknown }).data;
        if (data !== null && typeof data === 'object') {
          const candidate = (data as { lastSeq?: unknown }).lastSeq;
          if (typeof candidate === 'number')
            lastSeq = candidate;
          snapshot = (data as { snapshot?: unknown }).snapshot;
        }
      }
    }
    catch {
      // Keep the raw body available on the error for diagnostics.
    }
    throw new CursorExpiredError('run event cursor is outside the retention window', lastSeq, snapshot);
  }

  if (!response.ok || response.body === null) {
    const body = await response.text().catch(() => '');
    throw new RunStreamHttpError(response.status, body);
  }

  const stallTimeoutMs = options.stallTimeoutMs ?? DEFAULT_STALL_TIMEOUT_MS;
  const parser = new SseFrameParser();
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  const trace = options.onDebug;

  let lastActivity = Date.now();
  let queue: SseMessage[] = [];
  let finished = false;
  let failure: Error | undefined;
  let cleanup: Promise<void> | undefined;

  /**
   * Releases the connection at most once, and never blocks on the transport.
   *
   * `ReadableStream.cancel()` can stay pending indefinitely on a wrapped body while a read
   * is in flight. Cleanup must therefore never be awaited unguarded — an unbounded cleanup
   * was the reason a stalled stream once failed to surface its error.
   */
  const releaseConnection = (): Promise<void> => {
    cleanup ??= (async () => {
      try {
        await Promise.race([
          reader.cancel(),
          new Promise<void>((resolve) => {
            setTimeout(resolve, CLEANUP_TIMEOUT_MS);
          }),
        ]);
      }
      catch {
        // The stream may already be errored; nothing useful to do.
      }
      try {
        reader.releaseLock();
      }
      catch {
        // Already released.
      }
    })();
    return cleanup;
  };

  /**
   * Waits for the next chunk, bounded by the stall budget.
   *
   * The budget is measured from the last frame that arrived, not from the start of the
   * read: a stream that delivers heartbeats on time must never be considered stalled,
   * however long the run itself takes.
   */
  const awaitChunk = async (): Promise<{ kind: 'timeout' } | { kind: 'read'; result: StreamReadResult }> => {
    const idleMs = Math.max(0, stallTimeoutMs - (Date.now() - lastActivity));
    let watchdog: ReturnType<typeof setTimeout> | undefined;
    const timeout = new Promise<{ kind: 'timeout' }>((resolve) => {
      // Deliberately not unref'd: a watchdog that can be garbage-quiet is not a watchdog.
      watchdog = setTimeout(() => {
        resolve({ kind: 'timeout' });
      }, idleMs);
    });

    trace?.(`awaiting chunk with ${idleMs}ms of budget`);
    const outcome = await Promise.race([
      reader.read().then(result => ({ kind: 'read' as const, result })),
      timeout,
    ]);

    if (watchdog !== undefined)
      clearTimeout(watchdog);

    if (outcome.kind === 'timeout') {
      trace?.('stall detected; cancelling reader');
      try {
        // Time-boxed on purpose: the stall error must surface even if cancel never settles.
        await Promise.race([
          reader.cancel(),
          new Promise<void>((resolve) => {
            setTimeout(resolve, CLEANUP_TIMEOUT_MS);
          }),
        ]);
      }
      catch {
        // The stream may already be errored; the stall error is what matters.
      }
    }
    return outcome;
  };

  /** Advances until at least one message is queued, or the stream/error is terminal. */
  const pull = async (): Promise<void> => {
    for (;;) {
      if (queue.length > 0 || finished || failure)
        return;

      const outcome = await awaitChunk();

      if (outcome.kind === 'timeout') {
        finished = true;
        failure = new RunStreamStalledError(Date.now() - lastActivity, stallTimeoutMs);
        // Fire-and-forget: cleanup must not delay the error the caller needs.
        void releaseConnection();
        return;
      }

      const { done, value } = outcome.result;
      if (done) {
        finished = true;
        const tail = parser.end();
        if (tail.length > 0) {
          lastActivity = Date.now();
          queue = tail.map(toMessage);
        }
        void releaseConnection();
        return;
      }

      lastActivity = Date.now();
      const text = decoder.decode(value, { stream: true });
      const events = parser.push(text);
      if (events.length > 0) {
        for (const event of events) {
          if (event.isComment)
            options.onHeartbeat?.();
        }
        queue = events.map(toMessage);
        return;
      }
      // This chunk held no complete frame yet; keep waiting within the same stall budget.
    }
  };

  const connection: RunEventConnection = {
    async next(): Promise<IteratorResult<SseMessage, undefined>> {
      for (;;) {
        if (queue.length > 0) {
          const message = queue.shift() as SseMessage;
          return { done: false, value: message };
        }
        if (failure)
          throw failure;
        if (finished) {
          await releaseConnection();
          return { done: true, value: undefined };
        }
        await pull();
      }
    },
    async return(): Promise<IteratorResult<SseMessage, undefined>> {
      finished = true;
      queue = [];
      await releaseConnection();
      return { done: true, value: undefined };
    },
    [Symbol.asyncIterator](): AsyncIterator<SseMessage, undefined> {
      return this;
    },
  };

  return connection;
}

export interface ReconnectingRunStreamOptions extends RunStreamOptions {
  /** Maximum consecutive failed connections before giving up. `Infinity` retries forever. */
  maxRetries?: number;
  /**
   * Maximum *successful* connections used to repair sequence gaps. A gap resume is not a
   * failure, so it does not consume `maxRetries`; this bound stops a pathological server
   * from looping forever.
   */
  maxResumeCycles?: number;
  /** Base backoff; doubles per attempt, capped by `maxBackoffMs`. */
  baseDelayMs?: number;
  maxBackoffMs?: number;
  /** Client-provided backoff for tests; defaults to `setTimeout`. */
  sleep?: (ms: number) => Promise<void>;
  /**
   * Called before each new connection. `reason` distinguishes a sequence-gap repair from
   * an error retry, because the two have different budgets.
   */
  onReconnect?: (info: { attempt: number; afterSeq: number | undefined; delayMs: number; reason: 'gap' | 'error' | 'incomplete' }) => void;
}

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

export interface RunEventStream {
  /**
   * Reconnecting stream.
   *
   * Ends normally only after a terminal frame. If the run never reaches a terminal state
   * and reconnection is exhausted, the iterator **throws**
   * {@link RunEventStreamIncompleteError} — a caller must never mistake "we stopped
   * trying" for "the run finished".
   */
  messages: AsyncGenerator<SseMessage, void, undefined>;
  /**
   * Highest **contiguously delivered** cursor. It only advances when every visible `seq`
   * up to that point has been handed to the consumer, so it is safe to use as a resume
   * cursor: a hole never raises it.
   */
  appliedCursor: () => number | undefined;
}

/**
 * Raw terminal classification for one frame: it ORs the two type markers (the SSE
 * `event:` name and the envelope `type`) and trusts whichever is present.
 *
 * The contract treats a closed stream as *not* proof of completion: only `run.terminal`
 * (or an equivalent terminal type) ends the run. A dropped connection is therefore a
 * reconnect, never a silent success.
 *
 * Because it ORs the markers, it must only drive contract decisions on frames whose
 * envelope already passed {@link validateRunEventEnvelope}, which requires the two
 * markers to be present and identical; {@link openRunStream} therefore classifies
 * terminality from the verified envelope `type` after the gate instead of calling this.
 */
export function isTerminalMessage(message: SseMessage): boolean {
  if (message.isComment)
    return false;
  if (message.type === 'run.terminal')
    return true;
  // Tolerate the envelope carrying the type instead of the SSE event name.
  return message.parsed?.type === 'run.terminal';
}

/**
 * Wraps a single-connection stream with envelope validation, cursor persistence,
 * de-duplication and bounded reconnection. Duplicate frames (a replay overlapping what
 * the caller already applied) are dropped so consumers never render the same `seq` twice.
 *
 * Every business frame must present a verifiable envelope (`schemaVersion`, the
 * subscribed `runId`, a safe positive integer `seq`, an SSE `id` that agrees with it, and
 * an SSE `event:` name identical to the envelope `type`) before it is accepted; a
 * violation throws {@link RunEventStreamProtocolError} instead of being retried — and a
 * terminal frame is never accepted as proof of completion while its envelope fails that
 * gate.
 */
export function openRunStream(
  request: Omit<RunStreamRequest, 'afterSeq'> & { afterSeq?: number },
  options: ReconnectingRunStreamOptions = {},
): RunEventStream {
  const maxRetries = options.maxRetries ?? 5;
  const maxResumeCycles = options.maxResumeCycles ?? 20;
  const baseDelayMs = options.baseDelayMs ?? 500;
  const maxBackoffMs = options.maxBackoffMs ?? 15_000;
  const sleep = options.sleep ?? defaultSleep;
  const seen = new Set<string | number>();
  // The contract defines the default cursor as 0 ("replay from the start of the visible
  // events"). Starting from the first frame instead would silently accept a sparse stream.
  const startCursor = request.afterSeq ?? 0;
  let applied = startCursor;
  seen.add(startCursor);

  async function* iterate(): AsyncGenerator<SseMessage, void, undefined> {
    let attempt = 0;
    let resumeCycles = 0;
    let connections = 0;

    for (;;) {
      // `sawTerminal`  – the terminal frame was delivered *after* the stream was complete
      // `repairFrom`   – a sequence hole was observed; replay from this cursor
      // `pending`      – a withheld repair request (a frame that arrived above a hole)
      let sawTerminal = false;
      let repairFrom: number | undefined;
      let pending: { seq: number; terminal: boolean } | undefined;
      let lastError: unknown;

      // Highest contiguous sequence the client holds. It starts from the *delivered*
      // cursor (not from the constant start cursor): on a reconnect everything up to
      // `applied` has already been handed to the consumer, so the stream legitimately
      // resumes at `applied + 1`. `startCursor` only defines where a fresh subscription
      // begins, which is why a first frame that is not `startCursor + 1` is a hole.
      let contiguous = applied;
      const delivered = new Set<number>();

      connections += 1;
      try {
        const connection = await createRunEventStream({ ...request, afterSeq: applied }, options);
        try {
          for await (const message of connection) {
            if (message.isComment) {
              yield message;
              continue;
            }

            // Envelope gate first: a frame without a verified run identity and cursor is
            // protocol garbage, and a terminal that fails here must end as an explicit
            // protocol error — never as a delivered frame that completes the subscription.
            const envelope = validateRunEventEnvelope(message, request.runId);
            // Terminality is judged from the verified type only: the gate proved the SSE
            // event name and the envelope type are both present and identical, so a
            // disagreeing marker can neither smuggle a terminal past the other nor hide
            // one behind it.
            const terminal = envelope.type === 'run.terminal';
            const seq = envelope.seq;

            if (seen.has(seq))
              continue; // overlap with an earlier connection

            if (seq > contiguous + 1) {
              // A hole below this frame. Stop reading and replay from the last contiguous
              // seq: the server re-sends everything after that cursor, so the withheld
              // frame (pending.seq) comes back and passes the contiguity check on replay.
              // A terminal frame is withheld too — it only proves the server finished,
              // not that this subscription saw every visible event.
              pending = { seq, terminal };
              repairFrom = contiguous;
              options.onDebug?.(
                `sequence hole before seq=${seq}${terminal ? ' (terminal withheld)' : ''}; `
                + `replaying from afterSeq=${contiguous}`,
              );
              break;
            }

            seen.add(seq);
            delivered.add(seq);

            // Advance the contiguous watermark over frames already in hand.
            let watermark = contiguous;
            for (;;) {
              const next = watermark + 1;
              if (!delivered.has(next))
                break;
              watermark = next;
              delivered.delete(next);
            }
            contiguous = watermark;

            if (watermark > applied)
              applied = watermark;
            if (seq > watermark)
              options.onDebug?.(`seq=${seq} delivered ahead of the watermark (hole open)`);

            yield message;
            if (terminal) {
              // Reaching here means no hole is open below this terminal.
              sawTerminal = true;
              break;
            }
          }
        }
        finally {
          // Release the connection even when the consumer breaks out early.
          await connection.return();
        }
      }
      catch (error) {
        if (options.signal?.aborted)
          throw error;
        if (error instanceof CursorExpiredError)
          throw error;
        // A contract breach is deterministic: replaying would return the same broken
        // frames, so retrying only delays an error the caller must see.
        if (error instanceof RunEventStreamProtocolError)
          throw error;
        if (error instanceof RunStreamHttpError && error.status >= 400 && error.status < 500)
          throw error;
        lastError = error;
      }

      if (sawTerminal)
        return;

      if (repairFrom !== undefined) {
        // Gap repair is not a failure, so it does not consume the error retry budget.
        resumeCycles += 1;
        if (resumeCycles > maxResumeCycles) {
          options.onDebug?.(
            `gap repair exhausted after ${resumeCycles - 1} cycle(s); withheld seq=${
              pending?.seq ?? 'unknown'} - subscription is NOT complete`,
          );
          throw new RunEventStreamIncompleteError(applied, connections, 'resume-limit');
        }
        attempt = 0;
        options.onReconnect?.({ attempt: 0, afterSeq: repairFrom, delayMs: 0, reason: 'gap' });
        continue;
      }

      if (attempt >= maxRetries) {
        // Exhausted without ever seeing a terminal event. The run's outcome is unknown, so
        // this must not look like a normal completion.
        throw new RunEventStreamIncompleteError(applied, connections, 'retries-exhausted');
      }

      const delayMs = Math.min(maxBackoffMs, baseDelayMs * 2 ** attempt);
      attempt += 1;
      options.onReconnect?.({ attempt, afterSeq: applied, delayMs, reason: 'error' });
      if (lastError !== undefined)
        options.onDebug?.(`reconnecting after error: ${String(lastError)}`);
      await sleep(delayMs);
      if (options.signal?.aborted)
        return;
    }
  }

  return { messages: iterate(), appliedCursor: () => applied };
}
