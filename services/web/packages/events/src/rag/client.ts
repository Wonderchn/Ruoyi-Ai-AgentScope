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
import { AiApiError, assertIdentity, identityJson } from './transport';

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
  /** Injectable browser upload channel for protocol tests. */
  xhrFactory?: () => XMLHttpRequest;
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
  /**
   * WP-037A 视图字段：工具**实际结果**（后端以结构化 JSON 返回；动作未结束时为 `null`）。
   *
   * 刻意声明为 `unknown` 而**不是** `Record<string, unknown>`：后端 `parseJsonOrNull` 把 jsonb
   * 文本解析成结构化 JSON，**NULL/空白 → `null`**（`AgentActionViewTest.nullResultStaysNull` 钉的
   * 就是"未结束 ≠ 空对象"）。写成 `Record<...>` 会诱导消费方把 `null` 当 `{}`
   * —— 那正是"结果是空对象"与"还没有结果"混为一谈。消费方必须先判 `null` 再窄化。
   */
  result?: unknown;
  /** WP-037A 视图字段：副作用动作的幂等身份（空串不渲染）。 */
  operationKey?: string;
}

export interface RunAccepted {
  runId: string;
  status: string;
  replayed?: boolean;
}

export interface ConversationView {
  conversationId: string;
  title: string;
  lastTime?: string;
}

export interface ConversationMessage {
  id: string;
  role: string;
  content: string;
  messageStatus: string;
  createTime?: string;
}

export interface ReconciliationView {
  action: AgentAction;
  evidence: Array<{ seq: number; actor_member: string; evidence_hash: string; external_id?: string; finality: string; created_at: string }>;
}

/** Mirrors P2RuntimeProperties.Upload defaults; the server remains authoritative. */
export function validatePdfUpload(file: Pick<File, 'size' | 'type'>): void {
  if (file.size <= 0 || file.size > 50 * 1024 * 1024)
    throw new Error('PDF 必须非空且不超过 50 MiB');
  if (file.type.split(';', 1)[0].trim().toLowerCase() !== 'application/pdf')
    throw new Error('仅支持 PDF 文件');
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

  function getReconciliation(runId: string, actionId: string) {
    return json<ReconciliationView>(`/api/ai/v1/runs/${encodeURIComponent(runId)}/reconciliations/${encodeURIComponent(actionId)}`);
  }

  function listConversations(offset = 0, limit = 100) {
    return json<ConversationView[]>(`/api/ai/v1/conversations?offset=${offset}&limit=${limit}`);
  }

  function listConversationMessages(conversationId: string, offset = 0, limit = 100) {
    return json<ConversationMessage[]>(`/api/ai/v1/conversations/${encodeURIComponent(conversationId)}/messages?offset=${offset}&limit=${limit}`);
  }

  function exportConversation(conversationId: string, signal?: AbortSignal) {
    return binary(`/api/ai/v1/conversations/${encodeURIComponent(conversationId)}/export`, 'application/x-ndjson', signal);
  }

  async function binary(path: string, mime: string, signal?: AbortSignal): Promise<Blob> {
    const identity = deps.identity();
    const check = () => assertIdentity(identity, deps.identity);
    check();
    const response = await (deps.fetcher ?? fetch)(`${base}${path}`, {
      headers: { Authorization: `Bearer ${identity.token ?? ''}`, ClientID: deps.clientId ?? '' },
      signal,
    });
    check();
    if (!response.ok) {
      const envelope = await response.json().catch(() => null);
      check();
      if (response.status === 401 || envelope?.code === 401)
        deps.onAuthExpired();
      throw new AiApiError(response.status, envelope?.data?.errorCode ?? envelope?.msg ?? `当前资源不可访问 (${response.status})`);
    }
    if (response.headers.get('content-type')?.split(';', 1)[0].trim().toLowerCase() !== mime)
      throw new AiApiError(response.status, '资源响应类型不符合协议');
    const blob = await response.blob();
    check();
    if (blob.size > 50 * 1024 * 1024)
      throw new Error('来源超过下载上限');
    return blob;
  }

  function downloadSource(docId: string, versionId: string, signal?: AbortSignal): Promise<Blob> {
    return binary(`/api/ai/v1/documents/${encodeURIComponent(docId)}/source?versionId=${encodeURIComponent(versionId)}`, 'application/pdf', signal);
  }

  /** Dedicated streaming upload (with progress); returns the server-issued docId/uploadId/versionId. */
  function uploadDocument(kbId: string, file: File, onProgress?: (percent: number) => void, uploadKey: string = newRequestId(), signal?: AbortSignal, docId?: string): Promise<UploadResult> {
    const identity = deps.identity();
    return new Promise((resolve, reject) => {
      validatePdfUpload(file);
      assertIdentity(identity, deps.identity);
      const form = new FormData();
      form.append('kbId', kbId);
      if (docId)
        form.append('docId', docId);
      form.append('file', file, file.name);
      const xhr = deps.xhrFactory?.() ?? new XMLHttpRequest();
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
      xhr.setRequestHeader('Authorization', `Bearer ${identity.token ?? ''}`);
      xhr.setRequestHeader('ClientID', deps.clientId ?? '');
      xhr.setRequestHeader('Idempotency-Key', uploadKey);
      xhr.upload.onprogress = (event) => {
        try {
          assertIdentity(identity, deps.identity);
        }
        catch (error) {
          reject(error);
          xhr.abort();
          return;
        }
        if (event.lengthComputable && onProgress) {
          onProgress(Math.round((event.loaded / event.total) * 100));
        }
      };
      xhr.onload = () => {
        try {
          assertIdentity(identity, deps.identity);
          const body = JSON.parse(xhr.responseText || '{}');
          if (xhr.status === 401 || body?.code === 401) {
            deps.onAuthExpired();
            throw new AiApiError(401, '登录状态已失效');
          }
          if (xhr.status === 201 && body?.code === 200 && body.data?.docId && body.data?.uploadId && body.data?.versionId) {
            resolve(body.data as UploadResult);
          }
          else {
            reject(new AiApiError(xhr.status, body?.data?.errorCode ?? `upload failed (${xhr.status})`));
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
    getReconciliation,
    listConversations,
    listConversationMessages,
    exportConversation,
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
