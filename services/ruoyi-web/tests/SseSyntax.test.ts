import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { parseSseText, SseFrameParser } from '../src/utils/sse/SseSyntax.ts';

describe('sseSyntax: frame parsing', () => {
  it('parses a named event with an id and a JSON payload', () => {
    const events = parseSseText('id: 1\nevent: run.accepted\ndata: {"seq":1}\n\n');
    assert.equal(events.length, 1);
    assert.equal(events[0].type, 'run.accepted');
    assert.equal(events[0].id, '1');
    assert.equal(events[0].data, '{"seq":1}');
    assert.equal(events[0].isComment, false);
  });

  it('joins repeated data lines with a newline (spec) without truncating', () => {
    const events = parseSseText('event: run.output_delta\ndata: line-one\ndata: line-two\n\n');
    assert.equal(events.length, 1);
    assert.equal(events[0].data, 'line-one\nline-two');
  });

  it('strips exactly one leading space after the colon', () => {
    assert.equal(parseSseText('data:  two-spaces\n\n')[0].data, ' two-spaces');
    assert.equal(parseSseText('data:no-space\n\n')[0].data, 'no-space');
  });

  it('treats a colon line as a comment heartbeat and keeps it out of payloads', () => {
    const events = parseSseText(':\n\ndata: real\n\n');
    assert.equal(events.length, 2);
    assert.equal(events[0].isComment, true);
    assert.equal(events[0].data, '');
    assert.equal(events[1].isComment, false);
    assert.equal(events[1].data, 'real');
  });

  it('carries a comment payload when the heartbeat has text', () => {
    const events = parseSseText(': keep-alive\n\n');
    assert.equal(events[0].isComment, true);
    assert.equal(events[0].data, 'keep-alive');
  });

  it('keeps the frame id and the resume cursor distinct', () => {
    const events = parseSseText('id: 7\ndata: a\n\ndata: b\n\n');
    assert.equal(events[0].id, '7');
    assert.equal(events[0].lastEventId, undefined, 'the first frame has no previous id');
    assert.equal(events[1].id, undefined, 'the second frame carried no id of its own');
    assert.equal(events[1].lastEventId, '7', 'the previous id must remain the resume cursor');
  });

  it('ignores an id containing NUL and keeps the previous cursor', () => {
    const events = parseSseText('id: 3\ndata: a\n\nid: bad\u0000id\ndata: b\n\n');
    assert.equal(events[0].id, '3');
    assert.equal(events[1].id, undefined, 'a NUL id must not be adopted');
    assert.equal(events[1].data, 'b');
    assert.equal(events[1].lastEventId, '3', 'the previous cursor must survive');
  });

  it('accepts retry only when it is a non-empty integer', () => {
    const withRetry = parseSseText('retry: 1500\ndata: a\n\n');
    assert.equal(withRetry[0].retryMs, 1500);
    // A retry directive alone carries no payload, so it dispatches nothing (spec).
    assert.deepEqual(parseSseText('retry: 1500\n\n'), []);
    assert.deepEqual(parseSseText('retry: abc\n\n'), []);
    assert.deepEqual(parseSseText('retry:\n\n'), []);
  });

  it('dispatches an event-type-only frame with an empty payload', () => {
    const events = parseSseText('event: run.terminal\n\n');
    assert.equal(events.length, 1);
    assert.equal(events[0].type, 'run.terminal');
    assert.equal(events[0].data, '');
  });

  it('ignores frames with no data, no type and no id', () => {
    assert.deepEqual(parseSseText('foo: bar\n\n'), []);
    assert.deepEqual(parseSseText('\n\n\n'), []);
  });

  it('handles CRLF and bare CR terminators', () => {
    assert.equal(parseSseText('data: a\r\n\r\n')[0].data, 'a');
    assert.equal(parseSseText('data: b\r\r')[0].data, 'b');
    assert.equal(parseSseText('data: c\r\ndata: d\r\n\r\n')[0].data, 'c\nd');
  });

  it('dispatches a trailing unterminated frame at end of stream', () => {
    const parser = new SseFrameParser();
    assert.deepEqual(parser.push('data: no-terminator'), []);
    const tail = parser.end();
    assert.equal(tail.length, 1);
    assert.equal(tail[0].data, 'no-terminator');
  });

  it('reassembles frames split across chunk boundaries', () => {
    const parser = new SseFrameParser();
    const first = parser.push('id: 12\nev');
    const second = parser.push('ent: run.step_completed\ndata: {"seq":12}');
    const third = parser.push('\n\n');
    assert.deepEqual([first.length, second.length, third.length], [0, 0, 1]);
    assert.equal(third[0].type, 'run.step_completed');
    assert.equal(third[0].data, '{"seq":12}');
  });

  it('strips a leading BOM from the first chunk only', () => {
    const parser = new SseFrameParser();
    assert.equal(parser.push('\uFEFFdata: first\n\n')[0].data, 'first');
    assert.equal(parser.push('data: \uFEFFsecond\n\n')[0].data, '\uFEFFsecond');
  });

  it('emits several events from one chunk and honours a final empty line', () => {
    const events = parseSseText('data: 1\n\ndata: 2\n\ndata: 3\n');
    assert.deepEqual(events.map((e) => e.data), ['1', '2', '3']);
  });
});
