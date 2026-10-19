/**
 * F13:4 应用市场 API 改指判据。
 *
 * 市场列表必须打**已集成**的公开面 `GET /api/ai/v1/flows/public/search`
 * （参数 `current/size/keyword`），详情必须打新端点
 * `GET /api/ai/v1/flows/public/{uuid}/inputs`。
 *
 * 本文件跑真实 URL 构造纯函数（无 `@/` 别名、无 hook-fetch），
 * 并用源码静态判据钉住 `api/chat/index.ts` 不再保留 legacy `/workflow/public` 路径。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { describe, it } from 'node:test';

import {
  buildPublicFlowSearchUrl,
  PUBLIC_FLOW_SEARCH_PATH,
  publicFlowInputsPath,
} from '../src/api/chat/flow-market.ts';

const INDEX_SOURCE = fs.readFileSync(new URL('../src/api/chat/index.ts', import.meta.url), 'utf8');

describe('公开工作流列表 URL', () => {
  it('固定路径逐字为 /api/ai/v1/flows/public/search', () => {
    assert.equal(PUBLIC_FLOW_SEARCH_PATH, '/api/ai/v1/flows/public/search');
  });

  it('参数是 current/size/keyword（不是 currentPage/pageSize）', () => {
    const url = buildPublicFlowSearchUrl({ current: 2, size: 12, keyword: '季度 报告' });
    assert.ok(url.startsWith(`${PUBLIC_FLOW_SEARCH_PATH}?`), url);
    const query = new URL(url, 'http://gateway.test').searchParams;
    assert.equal(query.get('current'), '2');
    assert.equal(query.get('size'), '12');
    assert.equal(query.get('keyword'), '季度 报告');
    assert.equal(query.has('currentPage'), false);
    assert.equal(query.has('pageSize'), false);
  });

  it('空参数不进入 query（列表首屏不带空关键字）', () => {
    const url = buildPublicFlowSearchUrl({ current: 1, size: 10, keyword: '' });
    const query = new URL(url, 'http://gateway.test').searchParams;
    assert.equal(query.get('current'), '1');
    assert.equal(query.get('size'), '10');
    assert.equal(query.has('keyword'), false);
  });
});

describe('公开工作流 inputs URL', () => {
  it('路径逐字为 /api/ai/v1/flows/public/{uuid}/inputs', () => {
    assert.equal(
      publicFlowInputsPath('0123456789abcdef0123456789abcdef'),
      '/api/ai/v1/flows/public/0123456789abcdef0123456789abcdef/inputs',
    );
  });

  it('uuid 做 URL 编码（不拼出未转义的查询/路径分隔）', () => {
    assert.equal(
      publicFlowInputsPath('a/b c'),
      '/api/ai/v1/flows/public/a%2Fb%20c/inputs',
    );
  });
});

describe('api/chat/index.ts 已删除 legacy 路径', () => {
  it('不再出现 /workflow/public（列表与详情都改指新公开面）', () => {
    assert.equal(INDEX_SOURCE.includes('/workflow/public'), false);
  });

  it('列表/详情分别接到 flow-market 的构造器', () => {
    assert.match(INDEX_SOURCE, /buildPublicFlowSearchUrl/);
    assert.match(INDEX_SOURCE, /publicFlowInputsPath/);
  });
});
