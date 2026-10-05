/**
 * P2 RAG protocol client: knowledge bases / document upload / ingestion / unified runs
 * (admission, snapshot, cancel, resume, SSE).
 *
 * Contract: `/api/ai/v1/**` through the platform gateway (real login + delegation); uploads
 * use the dedicated streaming channel; the event stream uses the verified RunStreamClient
 * (continuous seq / cursor / 410 snapshot).
 *
 * This module is app-agnostic: the token source, base URL, client id and the
 * auth-expired callback are injected, so the workbench (Pinia user store) and the admin app
 * can bind their own identity source without duplicating the protocol.
 */
import type { RequestIdentity } from './transport';
import type { RunSubmitBody } from './logic';
import { newRequestId } from './logic';
import { identityJson } from './transport';

export interface RagApiDeps {
  /** API base URL (VITE_API_URL in the app); empty string means same origin. */
  baseUrl?: string;
  /** Client id header value (VITE_CLIENT_ID in the app). */
  clientId?: string;
  /** Current identity at call time; the response may only affect the identity that sent it. */
  identity: () => RequestIdentity;
  /** Called when the gateway reports the session is gone (401). */
  onAuthExpired: () => void;
  /** Injectable fetch for tests. */
  fetcher?: typeof fetch;
}

export interface KnowledgeBaseView {
  kbId: string;
  name: string;
  status?: string;
  collectionName?: string;
  embeddingModel?: string;
}

export interface DocumentView {
  docId: string;
  kbId: string;
  name: string;
  publishedVersionId?: string | null;
  tombstoned?: boolean;
  createdAt?: string;
}

export interface UploadResult {
  uploadId: string;
  docId: string;
  versionId: string;
  sha256: string;
  sizeBytes: number;
  state: string;
}

export interface IngestionResult {
  runId: string;
  status: string;
  docId: string;
  uploadId: string;
  versionId: string;
  replayed: boolean;
}

export interface RunSnapshot {
  runId: string;
  action?: string;
  status: string;
  attempt?: number;
  version?: number;
  nextSeq?: number;
  errorCode?: string | null;
  terminalResult?: unknown;
  input?: { text: string; mode: 'read' | 'sandbox'; ticket?: { title: string; details: string } };
  resourceRefs?: Array<{ ref: string; version: number }>;
  steps?: Array<{ stepId: string; stepName: string; state: string; at?: string }>;
  allowedActions?: string[];
}

export interface AgentAction {
  actionId: string;
  tool: string;
  toolVersion: string;
  args: Record<string, unknown>;
  argsHash: string;
  target: string;
  approvalVersion: number;
  state: string;
  externalId?: string;
  version: number;
}

export interface RunAccepted {
  runId: string;
  status: string;
  replayed?: boolean;
}

export function createRagApi(deps: RagApiDeps) {
  const base = deps.baseUrl ?? '';

  function json<T>(path: string, body?: unknown, extra?: Record<string, string>): Promise<T> {
    const identity = deps.identity();
    return identityJson<T>(`${base}${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${identity.token ?? ''}`,
        'ClientID': deps.clientId ?? '',
        ...extra,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    }, identity, deps.identity, deps.onAuthExpired, deps.fetcher);
  }

  function listKnowledgeBases() {
    return json<KnowledgeBaseView[]>('/api/ai/v1/knowledge-bases');
  }

  function createKnowledgeBase(name: string, embeddingModel?: string, collectionName?: string) {
    return json<KnowledgeBaseView>('/api/ai/v1/knowledge-bases', { name, embeddingModel, collectionName });
  }

  function listDocuments(kbId: string) {
    return json<DocumentView[]>(`/api/ai/v1/knowledge-bases/${encodeURIComponent(kbId)}/documents`);
  }

  function getDocument(docId: string) {
    return json<DocumentView>(`/api/ai/v1/documents/${encodeURIComponent(docId)}/meta`);
  }

  function listAgentActions(runId: string) {
    return json<AgentAction[]>(`/api/ai/v1/runs/${encodeURIComponent(runId)}/actions`);
  }

  function approveAgentAction(runId: string, action: AgentAction, decision: 'ALLOW' | 'DENY') {
    return json<AgentAction>(`/api/ai/v1/runs/${encodeURIComponent(runId)}/approvals`, {
      actionId: action.actionId,
      argsHash: action.argsHash,
      toolVersion: action.toolVersion,
      target: action.target,
      approvalVersion: action.approvalVersion,
      decision,
    });
  }

  function queryAgentAction(runId: string, actionId: string) {
    return json<{ action: AgentAction; finality: 'FOUND' | 'UNKNOWN' }>(`/api/ai/v1/runs/${encodeURIComponent(runId)}/reconciliations/${encodeURIComponent(actionId)}/query`, {});
  }

  async function downloadSource(docId: string, versionId: string, signal?: AbortSignal): Promise<Blob> {
    const identity = deps.identity();
    const check = () => {
      const now = deps.identity();
      if (now.epoch !== identity.epoch || now.token !== identity.token)
        throw new DOMException('Request identity changed', 'AbortError');
    };
    const response = await (deps.fetcher ?? fetch)(`${base}/api/ai/v1/documents/${encodeURIComponent(docId)}/source?versionId=${encodeURIComponent(versionId)}`, {
      headers: { Authorization: `Bearer ${identity.token ?? ''}`, ClientID: deps.clientId ?? '' },
      signal,
    });
    check();
    if (!response.ok || !response.headers.get('content-type')?.startsWith('application/pdf'))
      throw new Error(`当前引用不可访问 (${response.status})`);
    const blob = await response.blob();
    check();
    if (blob.size > 50 * 1024 * 1024)
      throw new Error('来源超过下载上限');
    return blob;
  }

  /** Dedicated streaming upload (with progress); returns the server-issued docId/uploadId/versionId. */
  function uploadDocument(kbId: string, file: File, onProgress?: (percent: number) => void, uploadKey: string = newRequestId(), signal?: AbortSignal, docId?: string): Promise<UploadResult> {
    const token = deps.identity().token;
    return new Promise((resolve, reject) => {
      const form = new FormData();
      form.append('kbId', kbId);
      if (docId)
        form.append('docId', docId);
      form.append('file', file, file.name);
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${base}/api/ai/v1/documents/uploads`);
      xhr.timeout = 120000;
      const abort = () => xhr.abort();
      xhr.onabort = () => reject(new DOMException('Upload cancelled', 'AbortError'));
      xhr.ontimeout = () => reject(new Error('upload timeout; retry the same request'));
      xhr.onloadend = () => signal?.removeEventListener('abort', abort);
      signal?.addEventListener('abort', abort, { once: true });
      if (signal?.aborted) {
        reject(new DOMException('Upload cancelled', 'AbortError'));
        return;
      }
      xhr.setRequestHeader('Authorization', `Bearer ${token ?? ''}`);
      xhr.setRequestHeader('ClientID', deps.clientId ?? '');
      xhr.setRequestHeader('Idempotency-Key', uploadKey);
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable && onProgress) {
          onProgress(Math.round((event.loaded / event.total) * 100));
        }
      };
      xhr.onload = () => {
        try {
          const body = JSON.parse(xhr.responseText || '{}');
          if (xhr.status === 201 && body?.code === 200 && body.data?.docId && body.data?.uploadId && body.data?.versionId) {
            resolve(body.data as UploadResult);
          }
          else {
            reject(new Error(body?.data?.errorCode ?? `upload failed (${xhr.status})`));
          }
        }
        catch (error) {
          reject(error instanceof Error ? error : new Error('upload response invalid'));
        }
      };
      xhr.onerror = () => reject(new Error('upload network error'));
      xhr.send(form);
    });
  }

  /** Formally admit a rag.chat style run; the idempotency key travels in Idempotency-Key. */
  function submitRun(body: RunSubmitBody, idempotencyKey: string): Promise<RunAccepted> {
    return json('/api/ai/v1/runs', body, { 'Idempotency-Key': idempotencyKey });
  }

  /** Ingestion: a formal document.ingest run (same key + same body => same run). */
  function createIngestion(docId: string, uploadId: string, idempotencyKey: string): Promise<IngestionResult> {
    return json(
      `/api/ai/v1/documents/${encodeURIComponent(docId)}/ingestions`,
      { uploadId },
      { 'Idempotency-Key': idempotencyKey },
    );
  }

  function getRun(runId: string): Promise<RunSnapshot> {
    return json<RunSnapshot>(`/api/ai/v1/runs/${encodeURIComponent(runId)}`);
  }

  function cancelRun(runId: string, expectedVersion?: number): Promise<RunSnapshot> {
    return json(`/api/ai/v1/runs/${encodeURIComponent(runId)}/cancel`, { expectedVersion });
  }

  function resumeRun(runId: string, expectedVersion: number): Promise<RunSnapshot> {
    return json(`/api/ai/v1/runs/${encodeURIComponent(runId)}/resume`, { expectedVersion });
  }

  return {
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
  };
}

export type RagApi = ReturnType<typeof createRagApi>;
