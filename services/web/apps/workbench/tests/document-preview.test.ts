/**
 * WP-036 / F15 私有文档预览页判据。
 *
 * 两条核心（都在"失败不得被画成成功"这一族）：
 * 1. **0 字节响应不得进入 `ready`** —— 否则空白 PDF 视图会被当成"预览成功"；
 * 2. **401/403/404 三个授权失败必须可区分**，且都不得落到 `ready`。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  canRetryPreview,
  parsePreviewQuery,
  PreviewQueryError,
  releaseObjectUrl,
  showsPreview,
  toPreviewState,
} from '../src/api/ai/document-preview.ts';

const SNOWFLAKE = '2076944338398593026';

function blob(size: number): Blob {
  return { size } as unknown as Blob;
}

describe('parsePreviewQuery：路径变量与页码都要显式校验', () => {
  it('最小入参：docId 必需，page 缺省 1，versionId 空串', () => {
    assert.deepEqual(parsePreviewQuery({ docId: SNOWFLAKE }), { docId: SNOWFLAKE, versionId: '', page: 1 });
  });

  it('docId 保持字符串（雪花 Long 不做 Number 转换）', () => {
    const parsed = parsePreviewQuery({ docId: SNOWFLAKE });
    assert.equal(typeof parsed.docId, 'string');
    assert.equal(parsed.docId, SNOWFLAKE);
  });

  it('docId 缺失/空白 → 抛错（不发一个必然 404 的下载）', () => {
    assert.throws(() => parsePreviewQuery({}), PreviewQueryError);
    assert.throws(() => parsePreviewQuery({ docId: '' }), PreviewQueryError);
    assert.throws(() => parsePreviewQuery({ docId: '   ' }), PreviewQueryError);
    assert.throws(() => parsePreviewQuery({ docId: null }), PreviewQueryError);
  });

  it('page 必须是 ≥1 的整数：0 / 负数 / 小数 / 非数字**全部显式拒绝**（不静默回落成 1）', () => {
    for (const bad of [0, -1, 1.5, 'abc', '0', '-3', '1.5', 'NaN']) {
      assert.throws(() => parsePreviewQuery({ docId: 'd', page: bad }), PreviewQueryError, `page=${String(bad)} 必须被拒`);
    }
  });

  it('page 合法值：1 / 12 / "7" 都通过（字符串数字来自 query）', () => {
    assert.equal(parsePreviewQuery({ docId: 'd', page: 1 }).page, 1);
    assert.equal(parsePreviewQuery({ docId: 'd', page: 12 }).page, 12);
    assert.equal(parsePreviewQuery({ docId: 'd', page: '7' }).page, 7);
  });

  it('page 空串/null/undefined 视为缺省（引用可能不带页码）', () => {
    assert.equal(parsePreviewQuery({ docId: 'd', page: '' }).page, 1);
    assert.equal(parsePreviewQuery({ docId: 'd', page: null }).page, 1);
    assert.equal(parsePreviewQuery({ docId: 'd', page: '  ' }).page, 1);
  });

  it('versionId 可选，给出时保留（引用跳转要固定版本）', () => {
    assert.equal(parsePreviewQuery({ docId: 'd', versionId: 'v-9' }).versionId, 'v-9');
  });
});

describe('toPreviewState：ready 只能由"成功 + 非空二进制"到达', () => {
  it('loading → loading，不显示预览', () => {
    const state = toPreviewState({ kind: 'loading' });
    assert.equal(state.kind, 'loading');
    assert.equal(showsPreview(state), false);
  });

  it('成功且非空 → ready（带页码）', () => {
    const state = toPreviewState({ kind: 'loaded', blob: blob(2048), page: 7 });
    assert.equal(state.kind, 'ready');
    assert.equal(showsPreview(state), true);
    if (state.kind === 'ready')
      assert.equal(state.page, 7);
  });

  it('**0 字节 → empty-binary，而不是 ready**（否则空白视图会被当成预览成功）', () => {
    const state = toPreviewState({ kind: 'loaded', blob: blob(0), page: 1 });
    assert.equal(state.kind, 'empty-binary');
    assert.notEqual(state.kind, 'ready');
    assert.equal(showsPreview(state), false);
    assert.match((state as { hint: string }).hint, /不是"文档是空白页"/);
  });

  it('blob 没有 size（形状异常）→ empty-binary，不放行', () => {
    const state = toPreviewState({ kind: 'loaded', blob: {} as Blob, page: 1 });
    assert.equal(state.kind, 'empty-binary');
    assert.equal(showsPreview(state), false);
  });

  it('403 → forbidden（说清缺哪个权限），不得落到 ready/not-found', () => {
    const state = toPreviewState({ kind: 'failed', error: { status: 403, errorCode: 'FORBIDDEN' } });
    assert.equal(state.kind, 'forbidden');
    assert.equal(showsPreview(state), false);
    assert.match((state as { message: string }).message, /ai:document:download/);
  });

  it('401 → auth-expired；404 → not-found；两者可区分（私有 URL 的授权语义）', () => {
    const unauthorized = toPreviewState({ kind: 'failed', error: { status: 401 } });
    const missing = toPreviewState({ kind: 'failed', error: { status: 404, errorCode: 'RESOURCE_NOT_FOUND_OR_FORBIDDEN' } });
    assert.equal(unauthorized.kind, 'auth-expired');
    assert.equal(missing.kind, 'not-found');
    assert.notEqual(unauthorized.kind, missing.kind);
    assert.match((missing as { hint: string }).hint, /不泄露存在性/);
  });

  it('503 → error（可重试），并明说不是"文档不存在"', () => {
    const state = toPreviewState({ kind: 'failed', error: { status: 503, errorCode: 'AUTHORIZATION_UNAVAILABLE' } });
    assert.equal(state.kind, 'error');
    assert.equal(canRetryPreview(state), true);
    assert.match((state as { hint: string }).hint, /不是"文档不存在"/);
  });

  it('锚点：穷举所有失败分类，都不得落到 ready', () => {
    const failures = [
      { status: 400, errorCode: 'BAD_REQUEST' },
      { status: 401, errorCode: 'AUTH_REQUIRED' },
      { status: 403, errorCode: 'FORBIDDEN' },
      { status: 404, errorCode: 'RESOURCE_NOT_FOUND_OR_FORBIDDEN' },
      { status: 409, errorCode: 'POLICY_VERSION_STALE' },
      { status: 503, errorCode: 'AUTHORIZATION_UNAVAILABLE' },
      { status: 500, errorCode: 'INTERNAL_ERROR' },
      { status: -1, errorCode: '' },
    ];
    for (const error of failures) {
      const state = toPreviewState({ kind: 'failed', error });
      assert.notEqual(state.kind, 'ready', `${JSON.stringify(error)} 不得渲染预览`);
      assert.equal(showsPreview(state), false);
    }
    // 0 字节同样不得渲染
    assert.equal(showsPreview(toPreviewState({ kind: 'loaded', blob: blob(0), page: 1 })), false);
    // 只有非空才渲染（正例必须在同一组里，否则"全部不放行"也能通过）
    assert.equal(showsPreview(toPreviewState({ kind: 'loaded', blob: blob(1), page: 1 })), true);
  });
});

describe('releaseObjectUrl：create/revoke 必须配对', () => {
  it('有 URL 时撤销并返回空串', () => {
    const revoked: string[] = [];
    const next = releaseObjectUrl('blob:http://x/1', url => revoked.push(url));
    assert.deepEqual(revoked, ['blob:http://x/1']);
    assert.equal(next, '');
  });

  it('空 URL 不调用 revoke（避免无意义调用）', () => {
    const revoked: string[] = [];
    assert.equal(releaseObjectUrl('', url => revoked.push(url)), '');
    assert.deepEqual(revoked, []);
  });
});
