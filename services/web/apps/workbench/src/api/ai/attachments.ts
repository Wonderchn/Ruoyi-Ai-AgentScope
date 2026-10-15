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

/** MIME essence：忽略参数（`text/plain;charset=utf-8` 与 `text/plain` 等价）。 */
function mimeEssence(value: string): string {
  return (value.split(';', 1)[0] ?? '').trim().toLowerCase();
}

/**
 * 平台 API 映射把 Long 一律序列化为字符串（`PlatformObjectMapperConfig`）：纯十进制串是
 * 线上合法形态，等价接受并归一化为数字；数字必须是整数 ≥1；其余形态（0/小数/带符号/非数字）拒绝。
 *
 * <p>真机锚（2026-10-16）：上传响应线上为 `{"version":"1","sizeBytes":"8"}`，
 * 不归一化会让 blob 严格比较与回传 refs 双双失效。</p>
 */
export function toAttachmentInteger(value: unknown): number | undefined {
  if (typeof value === 'number')
    return Number.isInteger(value) && value >= 1 ? value : undefined;
  if (typeof value === 'string' && /^[1-9]\d*$/.test(value)) {
    const parsed = Number(value);
    return Number.isSafeInteger(parsed) ? parsed : undefined;
  }
  return undefined;
}

/**
 * 最终 Blob 的严格核验（F04-HANDOFF 缺口 6）。
 *
 * 服务端只在哈希/版本一致时才释放字节，但**传输侧**也要把住最后一道：
 * 非空且字节数 = 登记值、MIME essence 匹配、sha256 一致。
 * `crypto.subtle` 不可用时 **fail-closed** —— 宁可不显示，也不显示未核验内容。
 */
async function verifyDownloadedBlob(blob: Blob, attachment: ChatAttachment): Promise<void> {
  if (blob.size < 1 || blob.size !== attachment.sizeBytes)
    throw new Error('附件内容校验失败：字节数不符');
  if (mimeEssence(blob.type) !== mimeEssence(attachment.mimeType))
    throw new Error('附件内容校验失败：类型不符');
  const subtle = (globalThis as { crypto?: Crypto }).crypto?.subtle;
  if (!subtle)
    throw new Error('当前环境不支持附件内容校验，已拒绝显示');
  const digest = await subtle.digest('SHA-256', await blob.arrayBuffer());
  const hex = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
  if (hex !== attachment.sha256.trim().toLowerCase())
    throw new Error('附件内容校验失败：摘要不符');
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
            // 真机锚（2026-10-16）：平台 Long→字符串策略下线上形态为 {"version":"1","sizeBytes":"8"}；
            // 在本边界归一化为数字并 fail-closed（非法形态拒绝，不落假成功）。
            const version = toAttachmentInteger(body.data.version);
            const sizeBytes = toAttachmentInteger(body.data.sizeBytes);
            if (version === undefined || sizeBytes === undefined)
              throw new Error('附件上传响应字段不合法');
            resolve({ ...body.data, version, sizeBytes });
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
      await verifyDownloadedBlob(blob, attachment);
      return blob;
    },
  };
}
