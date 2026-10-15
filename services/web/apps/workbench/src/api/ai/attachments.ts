import type { RequestIdentity } from '@ruoyi/events/rag';
import { withBase } from '../session/paths';

export interface ChatAttachment {
  id: string;
  version: number;
  filename: string;
  mimeType: string;
  sizeBytes: number;
  sha256: string;
}
export const ATTACHMENT_MAX_BYTES = 5 * 1024 * 1024;
export const ATTACHMENT_TYPES = ['text/plain', 'application/pdf', 'image/png', 'image/jpeg'];

export function validateAttachment(file: Pick<File, 'name' | 'type' | 'size'>): void {
  if (!file.name || !ATTACHMENT_TYPES.includes(file.type) || file.size < 1 || file.size > ATTACHMENT_MAX_BYTES)
    throw new Error('附件仅支持 PNG、JPEG、PDF、UTF-8 文本，单个上限 5 MiB');
}

export function createAttachmentClient(options: {
  baseUrl?: string;
  clientId?: string;
  identity: () => RequestIdentity;
  onAuthExpired: () => void;
}) {
  function headers(identity: RequestIdentity): Record<string, string> {
    if (!identity.token)
      throw new Error('请登录后上传或查看附件');
    return { Authorization: `Bearer ${identity.token}`, ...(options.clientId ? { clientid: options.clientId } : {}) };
  }
  return {
    upload(file: File, progress: (percent: number) => void): Promise<ChatAttachment> {
      validateAttachment(file);
      const identity = options.identity();
      return new Promise((resolve, reject) => {
        const request = new XMLHttpRequest();
        request.open('POST', withBase(options.baseUrl, '/api/ai/v1/chat/attachments'));
        Object.entries(headers(identity)).forEach(([key, value]) => request.setRequestHeader(key, value));
        request.upload.onprogress = event => progress(event.lengthComputable ? Math.round(100 * event.loaded / event.total) : 0);
        request.onerror = () => reject(new Error('附件上传失败，请重试'));
        request.onload = () => {
          if (identity.epoch !== options.identity().epoch) {
            reject(new Error('登录身份已变更，请重新上传附件'));
            return;
          }
          if (request.status === 401)
            options.onAuthExpired();
          try {
            const body = JSON.parse(request.responseText);
            if (request.status !== 201 || body.code !== 201 || !body.data?.id)
              throw new Error(body.message || body.msg || '附件上传失败');
            resolve(body.data);
          }
          catch (error) { reject(error); }
        };
        const body = new FormData();
        body.append('file', file);
        request.send(body);
      });
    },
    async download(attachment: ChatAttachment): Promise<Blob> {
      const identity = options.identity();
      const response = await fetch(withBase(options.baseUrl,
        `/api/ai/v1/chat/attachments/${encodeURIComponent(attachment.id)}/content?version=${attachment.version}`),
      { headers: headers(identity), cache: 'no-store' });
      if (response.status === 401)
        options.onAuthExpired();
      if (!response.ok)
        throw new Error('附件不可用或访问权限已变更');
      const blob = await response.blob();
      if (identity.epoch !== options.identity().epoch)
        throw new Error('登录身份已变更');
      return blob;
    },
  };
}
