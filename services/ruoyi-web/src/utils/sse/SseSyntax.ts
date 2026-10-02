/**
 * Pure Server-Sent Events syntax layer.
 *
 * Split out from the client on purpose: this file has no `fetch`, no timers and no
 * environment access, so the contract-critical behaviour (event names, ids, multi-line
 * `data:` joining, comments) can be unit tested without a browser or a network.
 *
 * Field semantics follow the WHATWG HTML "Interpreting an event stream" rules:
 * - a line starting with `:` is a comment and is ignored (surfaced separately here so a
 *   caller can use it as a heartbeat);
 * - `data:` lines are collected and joined with `\n`; one trailing `\n` is removed;
 * - `event:` sets the event type, `id:` sets the last event id, `retry:` sets the
 *   reconnection delay in milliseconds;
 * - a frame whose data buffer is empty does NOT dispatch an event;
 * - a CRLF pair is one line ending; a single CR is only a line ending when it is not
 *   followed by LF, which means a CR at the very end of a chunk must wait for the next
 *   chunk before the line can be split;
 * - **at end of stream any pending data is discarded: an event that was not terminated
 *   by a blank line is NOT dispatched** (a truncated frame must never be treated as a
 *   completed terminal event).
 */

/** One dispatched SSE event. `data` is the joined payload with no trailing newline. */
export interface SseEvent {
  /** `event:` field. Empty string means the spec default type, `message`. */
  readonly type: string;
  /** Joined `data:` payload. */
  readonly data: string;
  /** `id:` field of this event, or undefined when the frame carried no id. */
  readonly id: string | undefined;
  /** Last seen `id:` at dispatch time (persists across events, per spec). */
  readonly lastEventId: string | undefined;
  /** `retry:` value in milliseconds, or undefined. */
  readonly retryMs: number | undefined;
  /** `true` when the frame was a comment/heartbeat rather than a data event. */
  readonly isComment: boolean;
}

const CR = 0x0D;
const LF = 0x0A;
const SPACE = 0x20;

enum Field {
  Data = 0,
  Event = 1,
  Id = 2,
  Retry = 3,
  Unknown = 4,
}

function classify(name: string): Field {
  if (name === 'data')
    return Field.Data;
  if (name === 'event')
    return Field.Event;
  if (name === 'id')
    return Field.Id;
  if (name === 'retry')
    return Field.Retry;
  return Field.Unknown;
}

function isDigit(code: number): boolean {
  return code >= 0x30 && code <= 0x39;
}

/**
 * Incremental SSE frame parser.
 *
 * Feed raw text chunks with {@link push}; it returns the frames completed by that chunk
 * and buffers any partial frame. Call {@link end} when the byte stream closes: per spec
 * an unterminated frame is dropped, but trailing comment-only frames are still reported.
 */
export class SseFrameParser {
  #buffer = '';
  #dataLines: string[] = [];
  #comments: string[] = [];
  #type = '';
  #id: string | undefined;
  #lastEventId: string | undefined;
  #retryMs: number | undefined;
  #sawFirstChunk = false;

  /** Events completed by this chunk, in order. */
  push(chunk: string): SseEvent[] {
    if (chunk.length === 0)
      return [];

    // Strip a UTF-8 BOM that may lead the very first chunk of the body.
    let text = chunk;
    if (!this.#sawFirstChunk) {
      this.#sawFirstChunk = true;
      if (text.charCodeAt(0) === 0xFEFF)
        text = text.slice(1);
    }

    this.#buffer += text;
    const events: SseEvent[] = [];
    let start = 0;

    for (let i = 0; i < this.#buffer.length; i++) {
      const code = this.#buffer.charCodeAt(i);

      if (code === LF) {
        // A preceding CR already consumed this pair, so a bare LF terminates the line.
        this.#handleLine(this.#buffer.slice(start, i), events);
        start = i + 1;
        continue;
      }

      if (code === CR) {
        const next = i + 1;
        if (next === this.#buffer.length) {
          // The CR may be the first half of a CRLF that arrives in the next chunk.
          // Stop here and leave it buffered; an unterminated CR is re-examined later.
          break;
        }
        if (this.#buffer.charCodeAt(next) === LF) {
          this.#handleLine(this.#buffer.slice(start, i), events);
          start = next + 1;
          i = next;
          continue;
        }
        // A lone CR (not followed by LF) terminates the line by itself.
        this.#handleLine(this.#buffer.slice(start, i), events);
        start = next;
      }
    }

    this.#buffer = this.#buffer.slice(start);
    return events;
  }

  /**
   * End of stream.
   *
   * Per spec: "Once the end of the file is reached, any pending data must be discarded.
   * (If the file ends in the middle of an event, before the final empty line, the
   * incomplete event is not dispatched.)" — so no data event is produced here.
   *
   * Two kinds of trailing bytes are still worth reporting:
   * - a trailing CR is a valid line ending even at EOF, so the line before it is processed
   *   (which also means a comment that ended with CR is reported);
   * - a comment line that never got its terminator is reported, because comments carry
   *   liveness information and never run state.
   */
  end(): SseEvent[] {
    const events: SseEvent[] = [];
    const pendingCR = this.#buffer.length > 0 && this.#buffer.charCodeAt(this.#buffer.length - 1) === CR;

    if (pendingCR) {
      // Treat the trailing CR as a terminator.
      this.#handleLine(this.#buffer.slice(0, -1), events);
    }
    else if (this.#buffer.length > 0 && this.#buffer.startsWith(':')) {
      this.#comments.push(this.#commentValue(this.#buffer));
    }

    // Everything not terminated by a blank line is dropped.
    this.#buffer = '';
    this.#dataLines = [];
    this.#type = '';
    this.#id = undefined;
    events.push(...this.#takeComments());
    return events;
  }

  /** Value of a comment line (everything after the leading colon, minus one space). */
  #commentValue(line: string): string {
    const colon = line.indexOf(':');
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.charCodeAt(0) === SPACE)
      value = value.slice(1);
    return value;
  }

  #handleLine(line: string, events: SseEvent[]): void {
    if (line.length === 0) {
      this.#dispatch(events);
      return;
    }

    const colon = line.indexOf(':');
    const name = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.charCodeAt(0) === SPACE)
      value = value.slice(1);

    if (name.length === 0) {
      // A line that is just ":" (or ": text") is a comment/heartbeat.
      this.#comments.push(value);
      return;
    }

    switch (classify(name)) {
      case Field.Data:
        this.#dataLines.push(value);
        break;
      case Field.Event:
        this.#type = value;
        break;
      case Field.Id:
        // The spec ignores an id containing NUL.
        if (!value.includes('\u0000'))
          this.#id = value;
        break;
      case Field.Retry:
        if (value.length > 0) {
          let allDigits = true;
          for (let i = 0; i < value.length; i++) {
            if (!isDigit(value.charCodeAt(i))) {
              allDigits = false;
              break;
            }
          }
          if (allDigits)
            this.#retryMs = Number(value);
        }
        break;
      default:
        // Unknown fields are ignored by spec.
        break;
    }
  }

  #takeComments(): SseEvent[] {
    if (this.#comments.length === 0)
      return [];
    const comments = this.#comments;
    this.#comments = [];
    return comments.map(data => ({
      type: '',
      data,
      id: this.#id,
      lastEventId: this.#lastEventId,
      retryMs: this.#retryMs,
      isComment: true,
    }));
  }

  #dispatch(events: SseEvent[]): void {
    // Comments are reported before the frame they appeared in (they only carry
    // liveness information, not run state).
    events.push(...this.#takeComments());

    const type = this.#type;
    const hasData = this.#dataLines.length > 0;

    // The event being dispatched now reports the id seen *before* this frame; the frame's
    // own id becomes the cursor for subsequent events (the buffer is not reset by spec).
    const previousId = this.#lastEventId;
    if (this.#id !== undefined)
      this.#lastEventId = this.#id;

    if (hasData) {
      events.push({
        type,
        data: this.#dataLines.join('\n'),
        id: this.#id,
        lastEventId: previousId,
        retryMs: this.#retryMs,
        isComment: false,
      });
    }
    // Per spec, a frame with an empty data buffer dispatches nothing — an `event:` field
    // without `data:` must not turn into an event.

    this.#id = undefined;
    this.#dataLines = [];
    this.#type = '';
  }
}

/** Convenience wrapper for a complete, already-buffered body. */
export function parseSseText(text: string): SseEvent[] {
  const parser = new SseFrameParser();
  return [...parser.push(text), ...parser.end()];
}
