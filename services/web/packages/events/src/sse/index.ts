/**
 * SSE protocol assets shared by the frontend apps.
 *
 * - `SseSyntax`      : frame parsing (comments, repeated data lines, ids) with no dependencies
 * - `RunStreamClient`: the verified run event stream client (continuous seq, cursor,
 *                      410 snapshot fallback, identity re-check per frame)
 */
export * from './SseSyntax';
export * from './RunStreamClient';
