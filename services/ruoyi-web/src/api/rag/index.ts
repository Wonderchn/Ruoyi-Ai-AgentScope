/**
 * P2 RAG 前端 API：知识库 / 文档上传 / 摄入任务 / 统一 run（受理、快照、取消、恢复、SSE）。
 *
 * 契约：`/api/ai/v1/**` 经平台网关（真实登录 + 委托）；上传走专用流式通道；
 * 事件流使用已验证的 RunStreamClient（连续 seq / 游标 / 410 快照）。
 */
import type { RunSubmitBody } from './logic';
import { useUserStore } from '@/stores';
import { get, post } from '@/utils/request';

export * from './logic';

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
  return get<KnowledgeBaseView[]>('/api/ai/v1/knowledge-bases').json();
}

export function createKnowledgeBase(name: string, embeddingModel?: string, collectionName?: string) {
  return post<KnowledgeBaseView>('/api/ai/v1/knowledge-bases', { name, embeddingModel, collectionName }).json();
}

export function listDocuments(kbId: string) {
  return get<DocumentView[]>(`/api/ai/v1/knowledge-bases/${encodeURIComponent(kbId)}/documents`).json();
}

export function getDocument(docId: string) {
  return get<DocumentView>(`/api/ai/v1/documents/${encodeURIComponent(docId)}/meta`).json();
}

/** 专用流式上传（带进度）；返回服务端生成的 docId/uploadId/versionId。 */
export function uploadDocument(kbId: string, file: File, onProgress?: (percent: number) => void): Promise<UploadResult> {
  const token = useUserStore().token;
  const base = (import.meta.env.VITE_API_URL as string | undefined) ?? '';
  return new Promise((resolve, reject) => {
    const form = new FormData();
    form.append('kbId', kbId);
    form.append('file', file, file.name);
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${base}/api/ai/v1/documents/uploads`);
    xhr.setRequestHeader('Authorization', `Bearer ${token ?? ''}`);
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && onProgress) {
        onProgress(Math.round((event.loaded / event.total) * 100));
      }
    };
    xhr.onload = () => {
      try {
        const body = JSON.parse(xhr.responseText || '{}');
        if (xhr.status === 201 || body?.code === 200) {
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
  return post('/api/ai/v1/runs', body, { headers: { 'Idempotency-Key': idempotencyKey } }).json();
}

/** 摄入任务：正式 document.ingest run（同键同体同 run）。 */
export function createIngestion(docId: string, uploadId: string, idempotencyKey: string): Promise<IngestionResult> {
  return post(
    `/api/ai/v1/documents/${encodeURIComponent(docId)}/ingestions`,
    { uploadId },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  ).json();
}

export function getRun(runId: string): Promise<RunSnapshot> {
  return get<RunSnapshot>(`/api/ai/v1/runs/${encodeURIComponent(runId)}`).json();
}

export function cancelRun(runId: string, expectedVersion?: number): Promise<RunSnapshot> {
  return post(`/api/ai/v1/runs/${encodeURIComponent(runId)}/cancel`, { expectedVersion }).json();
}

export function resumeRun(runId: string, expectedVersion: number): Promise<RunSnapshot> {
  return post(`/api/ai/v1/runs/${encodeURIComponent(runId)}/resume`, { expectedVersion }).json();
}
