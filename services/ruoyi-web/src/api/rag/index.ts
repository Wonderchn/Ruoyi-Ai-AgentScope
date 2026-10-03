/**
 * P2 RAG 前端 API：知识库 / 文档上传 / 摄入任务 / 统一 run（受理、快照、取消、恢复、SSE）。
 *
 * 契约：`/api/ai/v1/**` 经平台网关（真实登录 + 委托）；上传走专用流式通道；
 * 事件流使用已验证的 RunStreamClient（连续 seq / 游标 / 410 快照）。
 */
import type { RunSubmitBody } from './logic';
import { useUserStore } from '@/stores';
import { identityJson } from './transport';

export * from './logic';

function json<T>(path: string, body?: unknown, extra?: Record<string, string>): Promise<T> {
  const store = useUserStore();
  const identity = { token: store.token, epoch: store.authEpoch };
  return identityJson<T>(`${import.meta.env.VITE_API_URL ?? ''}${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${identity.token ?? ''}`, 'ClientID': import.meta.env.VITE_CLIENT_ID, ...extra },
    body: body === undefined ? undefined : JSON.stringify(body),
  }, identity, () => ({ token: store.token, epoch: store.authEpoch }), () => store.handleAuthExpired());
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
  steps?: Array<{ stepId: string; stepName: string; state: string; at?: string }>;
  allowedActions?: string[];
}

export function listKnowledgeBases() {
  return json<KnowledgeBaseView[]>('/api/ai/v1/knowledge-bases');
}

export function createKnowledgeBase(name: string, embeddingModel?: string, collectionName?: string) {
  return json<KnowledgeBaseView>('/api/ai/v1/knowledge-bases', { name, embeddingModel, collectionName });
}

export function listDocuments(kbId: string) {
  return json<DocumentView[]>(`/api/ai/v1/knowledge-bases/${encodeURIComponent(kbId)}/documents`);
}

export function getDocument(docId: string) {
  return json<DocumentView>(`/api/ai/v1/documents/${encodeURIComponent(docId)}/meta`);
}

export async function downloadSource(docId: string, versionId: string, signal?: AbortSignal): Promise<Blob> {
  const store = useUserStore();
  const identity = { token: store.token, epoch: store.authEpoch };
  const check = () => {
    if (store.authEpoch !== identity.epoch || store.token !== identity.token)
      throw new DOMException('Request identity changed', 'AbortError');
  };
  const response = await fetch(`${import.meta.env.VITE_API_URL ?? ''}/api/ai/v1/documents/${encodeURIComponent(docId)}/source?versionId=${encodeURIComponent(versionId)}`, {
    headers: { Authorization: `Bearer ${identity.token ?? ''}`, ClientID: import.meta.env.VITE_CLIENT_ID },
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

/** 专用流式上传（带进度）；返回服务端生成的 docId/uploadId/versionId。 */
export function uploadDocument(kbId: string, file: File, onProgress?: (percent: number) => void, uploadKey: string = crypto.randomUUID(), signal?: AbortSignal, docId?: string): Promise<UploadResult> {
  const token = useUserStore().token;
  const base = (import.meta.env.VITE_API_URL as string | undefined) ?? '';
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
    xhr.setRequestHeader('ClientID', import.meta.env.VITE_CLIENT_ID);
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

export interface RunAccepted {
  runId: string;
  status: string;
  replayed?: boolean;
}

/** 正式受理 rag.chat 等 run；幂等键进 Idempotency-Key 头。 */
export function submitRun(body: RunSubmitBody, idempotencyKey: string): Promise<RunAccepted> {
  return json('/api/ai/v1/runs', body, { 'Idempotency-Key': idempotencyKey });
}

/** 摄入任务：正式 document.ingest run（同键同体同 run）。 */
export function createIngestion(docId: string, uploadId: string, idempotencyKey: string): Promise<IngestionResult> {
  return json(
    `/api/ai/v1/documents/${encodeURIComponent(docId)}/ingestions`,
    { uploadId },
    { 'Idempotency-Key': idempotencyKey },
  );
}

export function getRun(runId: string): Promise<RunSnapshot> {
  return json<RunSnapshot>(`/api/ai/v1/runs/${encodeURIComponent(runId)}`);
}

export function cancelRun(runId: string, expectedVersion?: number): Promise<RunSnapshot> {
  return json(`/api/ai/v1/runs/${encodeURIComponent(runId)}/cancel`, { expectedVersion });
}

export function resumeRun(runId: string, expectedVersion: number): Promise<RunSnapshot> {
  return json(`/api/ai/v1/runs/${encodeURIComponent(runId)}/resume`, { expectedVersion });
}
