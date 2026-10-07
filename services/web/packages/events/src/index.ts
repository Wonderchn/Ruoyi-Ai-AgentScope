/**
 * @ruoyi/events — the AI protocol layer shared by the frontend apps.
 *
 * Extracted from the workbench app so the admin app and the workbench cannot drift apart on
 * the wire contract (SSE framing, run stream resumption, identity-scoped responses).
 */
export * from './sse/index';
export * from './rag/index';
