/**
 * Workbench binding of the shared RAG protocol client.
 *
 * The protocol itself lives in `@ruoyi/events/rag` (shared with the admin app); this module
 * only supplies the workbench identity source — the Pinia user store — and re-exports the
 * bound functions under the names the pages already use.
 *
 * Contract: `/api/ai/v1/**` through the platform gateway (real login + delegation); uploads
 * use the dedicated streaming channel; the event stream uses the verified RunStreamClient
 * (continuous seq / cursor / 410 snapshot).
 */
import { createRagApi } from '@ruoyi/events/rag';
import { useUserStore } from '@/stores';

export * from '@ruoyi/events/rag';

const api = createRagApi({
  baseUrl: import.meta.env.VITE_API_URL,
  clientId: import.meta.env.VITE_CLIENT_ID,
  identity: () => {
    const store = useUserStore();
    return { token: store.token, epoch: store.authEpoch };
  },
  onAuthExpired: () => useUserStore().handleAuthExpired(),
});

export const {
  listKnowledgeBases,
  createKnowledgeBase,
  listDocuments,
  getDocument,
  listAgentActions,
  approveAgentAction,
  queryAgentAction,
  downloadSource,
  uploadDocument,
  submitRun,
  createIngestion,
  getRun,
  cancelRun,
  resumeRun,
} = api;
