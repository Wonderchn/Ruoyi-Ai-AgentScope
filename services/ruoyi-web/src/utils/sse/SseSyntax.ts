/**
 * Pure Server-Sent Events syntax layer.
 *
 * Split out from the client on purpose: this file has no `fetch`, no timers and no
 * environment access, so the contract-critical behaviour (event names, ids, multi-line
 * `data:` joining, comments) can be unit tested without a browser or a network.
 *
 * Field semantics follow the WHATWG HTML "Server-Sent Events" parsing rules:
 * - a line starting with `:` is a comment and is ignored;
 * - `data:` lines are collected and joined with `\n`; one trailing `\n` is removed;
 * - `event:` sets the event type, `id:` sets the last event id, `retry:` sets the
 *   reconnection delay in milliseconds.
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

/**
 * A lookup table is used instead of `switch`/`startsWith` because this parser runs per
 * line on a hot stream and the field set is fixed by the spec.
 */
const enum Field {
  Data = 0,
  Event = 1,
  Id = 2,
  Retry = 3,
  Comment = 4,
  Unknown = 5,
}

function classify(name: string): Field {
  if (name === 'data') return Field.Data;
  if (name === 'event') return Field.Event;
  if (name === 'id') return Field.Id;
  if (name === 'retry') return Field.Retry;
  return Field.Unknown;
}

function isDigit(code: number): boolean {
  return code >= 0x30 && code <= 0x39;
}

/**
 * Incremental SSE frame parser.
 *
 * Feed it raw text chunks with {@link push}; it returns the events completed by that
 * chunk and buffers any partial frame. Call {@link end} when the byte stream closes so a
 * trailing unterminated frame is still dispatched (the spec dispatches on EOF).
 */
export class SseFrameParser {
  #buffer = '';
  #dataLines: string[] = [];
  #type = '';
  #id: string | undefined;
  #lastEventId: string | undefined;
  #retryMs: number | undefined;
  #sawFirstChunk = false;

  /** Events completed by this chunk, in order. */
  push(chunk: string): SseEvent[] {
    if (chunk.length === 0) return [];

    // Strip a UTF-8 BOM that may lead the very first chunk of the body.
    let text = chunk;
    if (!this.#sawFirstChunk) {
      this.#sawFirstChunk = true;
      if (text.charCodeAt(0) === 0xFEFF) text = text.slice(1);
    }

    this.#buffer += text;
    const events: SseEvent[] = [];
    let start = 0;

    for (let i = 0; i < this.#buffer.length; i++) {
      const code = this.#buffer.charCodeAt(i);
      if (code !== LF && code !== CR) continue;

      // CRLF counts as a single terminator; a lone CR also terminates.
      let next = i + 1;
      if (code === CR && this.#buffer.charCodeAt(next) === LF) next++;

      this.#handleLine(this.#buffer.slice(start, i), events);
      start = next;
      i = next - 1;
      if (this.#buffer.length === start) break;
    }

    this.#buffer = this.#buffer.slice(start);
    return events;
  }

  /** Flush at end of stream: dispatches a trailing unterminated frame, per spec. */
  end(): SseEvent[] {
    const events: SseEvent[] = [];
    if (this.#buffer.length > 0) {
      const line = this.#buffer;
      this.#buffer = '';
      this.#handleLine(line, events);
    }
    this.#dispatch(events);
    return events;
  }

  #handleLine(line: string, events: SseEvent[]): void {
    if (line.length === 0) {
      this.#dispatch(events);
      return;
    }

    const colon = line.indexOf(':');
    const name = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.charCodeAt(0) === SPACE) value = value.slice(1);

    if (name.length === 0) {
      // A line that is just ":" (or ": text") is a comment/heartbeat.
      this.#pushComment(value, events);
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
        // The spec ignores an id containing NUL; we treat it as "no id".
        if (!value.includes('\u0000')) this.#id = value;
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
          if (allDigits) this.#retryMs = Number(value);
        }
        break;
      default:
        // Unknown fields are ignored by spec.
        break;
    }
  }

  #pushComment(value: string, events: SseEvent[]): void {
    events.push({
      type: '',
      data: value,
      id: this.#id,
      lastEventId: this.#lastEventId,
      retryMs: this.#retryMs,
      isComment: true,
    });
  }

  #dispatch(events: SseEvent[]): void {
    const hasData = this.#dataLines.length > 0;
    const type = this.#type;

    if (!hasData && type === '' && this.#id === undefined) return;

    if (hasData) {
      events.push({
        type,
        data: this.#dataLines.join('\n'),
        id: this.#id,
        lastEventId: this.#lastEventId,
        retryMs: this.#retryMs,
        isComment: false,
      });
    }
    else if (type !== '') {
      // An `event:`-only frame still dispatches, with an empty payload.
      events.push({
        type,
        data: '',
        id: this.#id,
        lastEventId: this.#lastEventId,
        retryMs: this.#retryMs,
        isComment: false,
      });
    }

    // Per spec the id persists for subsequent events even if this frame had none, while
    // the *frame* id applies only to the event just dispatched. Conflating the two would
    // make a later frame report an id it never carried.
    if (this.#id !== undefined) this.#lastEventId = this.#id;
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
