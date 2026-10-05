/**
 * RAG protocol surface shared by the frontend apps.
 *
 * - `logic`    : pure request/response shaping and citation helpers (no I/O)
 * - `transport`: identity-scoped JSON envelope handling (a late response can never affect a
 *                newer identity)
 * - `client`   : `createRagApi(deps)` — the P2 endpoints with the identity source injected
 */
export * from './logic';
export * from './transport';
export * from './client';
