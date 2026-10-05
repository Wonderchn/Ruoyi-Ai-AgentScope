/**
 * The SSE frame syntax now lives in the shared protocol package (`@ruoyi/events/sse`) so the
 * workbench and the admin app cannot drift apart. This file keeps the app-local import path
 * working for existing pages.
 */
export * from '@ruoyi/events/sse';
