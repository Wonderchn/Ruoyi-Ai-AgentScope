/**
 * WP-036 / F15：**私有文档预览页**（`/preview/doc/:docId`）的纯逻辑层。
 *
 * ## 可达性（读源码核实，不是猜）
 *
 * | 事实 | 证据 |
 * | --- | --- |
 * | 路由已登记 | `AiGatewayController.ROUTES`：`GET /documents/{id}/source` → `document.download`；`GET /documents/{id}/meta` → `document.read` |
 * | 内层 handler | `AiResourceController`（类映射 `/internal/ai/v1`）由 `AiEmbeddedRagConfiguration` `@Bean` 注册 |
 * | 权限已播种 | `ai:document:download`（V4）、`ai:document:read`（V4） |
 * | **不需要新建客户端** | 共享 `@ruoyi/events/rag` 的 `createRagApi()` 已有 `downloadSource(docId, versionId, signal) -> Blob` 与 `getDocument(docId)` |
 *
 * ⇒ 本模块**只做视图逻辑**（query 解析 + 状态机 + 授权失败分类），二进制下载与身份/epoch
 * 全部走共享客户端（C7：禁止第二套客户端；二进制响应尤其不能自己再写一份 fetch）。
 *
 * ## 本层要防的两个错
 *
 * 1. **"取到 0 字节"不得被画成预览成功**。`Blob` 存在（truthy）不代表有内容；
 *    用 `blob.size > 0` 做锚点，否则"空文件/空响应"会渲染成一个空白 PDF 视图，
 *    看起来像"页面正常但文档是空白页"——那是把失败画成成功。
 * 2. **授权失败不得被画成"文档不存在"**。私有 URL 的 401/403 与 404 语义不同
 *    （C7：私有 URL、二进制响应、来源跳转都要复核授权）：401 要重新登录、
 *    403 要说清缺哪个权限、404 才是"不存在或不属于本租户本成员"（后端刻意不区分存在性）。
 */
import { classifyWriteFailure } from './conversation-writes';

/** 预览页 query（引用跳转定位用 `page`）。 */
export interface PreviewQuery { docId: string; versionId: string;
  /** 起始页，**从 1 开始**；缺省 1。 */
  page: number; }

/** query 非法：不发请求（`docId` 是路径变量，缺失时页面根本不该发起下载）。 */
export class PreviewQueryError extends Error {
  readonly field: 'docId' | 'versionId' | 'page';

  constructor(field: 'docId' | 'versionId' | 'page', message: string) {
    super(message);
    this.name = 'PreviewQueryError';
    this.field = field;
  }
}

/**
 * 解析预览参数。
 *
 * - `docId`：来自路径变量，**必须非空**（`Long` id 保持字符串，不做 `Number` 转换）；
 * - `versionId`：可选（缺省时后端取当前版本）；
 * - `page`：可选，**必须是 ≥1 的整数**。`page=0`/`page=1.5`/`page=abc` **显式拒绝**，
 *   而不是静默回落成 1 —— 静默回落会让"引用跳转到了第 0 页"这种上游 bug 变成
 *   "看起来跳对了"（与 `getChatList` 拒绝 `undefined` 会话 id 同一原则）。
 */
export function parsePreviewQuery(raw: { docId?: unknown; versionId?: unknown; page?: unknown }): PreviewQuery {
  const docId = raw.docId === null || raw.docId === undefined ? '' : String(raw.docId).trim();
  if (docId === '')
    throw new PreviewQueryError('docId', '文档 id 缺失：无法预览');
  const versionId = raw.versionId === null || raw.versionId === undefined ? '' : String(raw.versionId).trim();

  let page = 1;
  if (raw.page !== undefined && raw.page !== null && String(raw.page).trim() !== '') {
    const text = String(raw.page).trim();
    if (!/^\d+$/.test(text))
      throw new PreviewQueryError('page', `页码必须是正整数（收到 ${JSON.stringify(raw.page)}）`);
    page = Number(text);
    if (!Number.isSafeInteger(page) || page < 1)
      throw new PreviewQueryError('page', `页码必须 ≥ 1（收到 ${page}）`);
  }
  return { docId, versionId, page };
}

/** 预览结果（来自共享客户端）。 */
export type PreviewOutcome
  = { kind: 'loading' }
    | { kind: 'loaded'; blob: Blob; page: number }
    | { kind: 'failed'; error: unknown };

export type PreviewState
  = { kind: 'loading' }
    | { kind: 'ready'; page: number; objectUrlCreated: true }
    | { kind: 'forbidden'; message: string; hint: string }
    | { kind: 'auth-expired'; message: string; hint: string }
    | { kind: 'not-found'; message: string; hint: string }
    | { kind: 'empty-binary'; message: string; hint: string }
    | { kind: 'error'; message: string; hint: string };

/**
 * 状态机：`ready` **只能**由"成功响应 + 非空二进制"到达。
 *
 * `empty-binary` 是独立状态，不是 `ready`：0 字节的响应要么是上游失败被压成 200，
 * 要么是对象存储里真的空文件 —— 两种情况都必须让用户看见，而不是渲染一个空白页。
 */
export function toPreviewState(outcome: PreviewOutcome): PreviewState {
  if (outcome.kind === 'loading')
    return { kind: 'loading' };

  if (outcome.kind === 'loaded') {
    const size = typeof outcome.blob?.size === 'number' ? outcome.blob.size : -1;
    if (size <= 0) {
      return {
        kind: 'empty-binary',
        message: `来源响应为空（${size < 0 ? '未报告长度' : `${size} 字节`}）。`,
        hint: '这不是"文档是空白页"：空响应按失败处理，请核对来源版本与对象存储登记。',
      };
    }
    return { kind: 'ready', page: outcome.page, objectUrlCreated: true };
  }

  const failure = classifyWriteFailure(outcome.error);
  switch (failure.kind) {
    case 'forbidden':
      return {
        kind: 'forbidden',
        message: '没有下载该文档的权限（需要 ai:document:download）。',
        hint: '权限需授到角色，且租户套餐 menu_ids 必须包含对应 AI 菜单（G-28）。',
      };
    case 'auth-expired':
      return { kind: 'auth-expired', message: '登录状态已失效，请重新登录。', hint: '重新登录后回到本页即可。' };
    case 'not-found':
      return {
        kind: 'not-found',
        message: '文档不存在、不属于当前租户或已被删除。',
        hint: '后端刻意不区分"不存在"与"无权访问"（不泄露存在性）。',
      };
    case 'unavailable':
      return { kind: 'error', message: '授权服务暂不可用（503）。', hint: '可重试；这不是"文档不存在"。' };
    default:
      return { kind: 'error', message: failure.message || '预览加载失败。', hint: '未渲染任何内容。' };
  }
}

/** 是否允许渲染预览（结构上把"失败不得画成成功"钉死）。 */
export function showsPreview(state: PreviewState): boolean {
  return state.kind === 'ready';
}

/** 是否可重试。 */
export function canRetryPreview(state: PreviewState): boolean {
  return state.kind === 'error';
}

/**
 * 对象 URL 的生命周期：`revoke` 必须与 `create` 配对。
 *
 * 抽成纯函数是为了让"切文档/卸载时到底撤销了没有"可被断言 ——
 * 泄漏的 object URL 会一直占住整份 PDF 的内存（大文件上是真问题）。
 */
export function releaseObjectUrl(url: string, revoke: (value: string) => void): string {
  if (url !== '')
    revoke(url);
  return '';
}
