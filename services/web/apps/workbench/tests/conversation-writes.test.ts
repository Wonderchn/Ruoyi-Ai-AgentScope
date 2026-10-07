/**
 * C9 / D10 会话写入面判据（WP-035 前端）。
 *
 * ## 这些断言为什么值得存在
 *
 * 1. **判据要能证明请求真的发出去了**：本文件用**记录型 fake fetch** 抓取
 *    `{url, init}`，逐字断言 method / 路径 / 请求头 / 请求体。空页面也成立的
 *    "元素存在"式断言在这里一条都没有。
 * 2. **"按 409 判冲突"必须被证伪**：后端四个不同的码共享 HTTP 409，
 *    所以本文件对另外三个码逐条断言"不得判成 version-conflict"——
 *    任何人把判据改回 `status === 409`，这三条立刻变红。
 * 3. **兼容模式不是可有可无的遗留路径**：读路径不返回 `version`，
 *    客户端只能从一次改名响应里拿到版本。本文件钉住"首个版本来自响应、
 *    且不可信响应不得被当成 0 缓存"。
 * 4. **迟到响应隔离**：`identityJson` 在请求前后比对身份快照，
 *    身份变了就抛 `AbortError`。本文件断言"切租户/退出后，旧响应不会被当成成功"。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  bootstrapVersion,
  checkConversationTitle,
  classifyWriteFailure,
  CONVERSATION_TITLE_MAX,
  ConversationInputError,
  createConversationWriteApi,
  OTHER_409_CODES,
  parseConversationVersion,
  RESOURCE_VERSION_CONFLICT,
  withVersion,
  writeFailureMessage,
} from '../src/api/ai/conversation-writes.ts';

interface Recorded {
  url: string;
  init: RequestInit;
}

interface Harness {
  calls: Recorded[];
  fetcher: typeof fetch;
  bumpEpoch: () => void;
}

/** 记录每一次真实发出的请求；响应由调用方逐条指定。 */
function harness(responses: Array<{ body: unknown; status?: number }>): Harness {
  const calls: Recorded[] = [];
  let index = 0;
  const state = { epoch: 1 };
  const fetcher = (async (url: string | URL, init: RequestInit = {}) => {
    calls.push({ url: String(url), init });
    const next = responses[index++] ?? { body: { code: 200, data: null } };
    const status = next.status ?? 200;
    return {
      ok: status >= 200 && status < 300,
      status,
      json: async () => next.body,
    } as unknown as Response;
  }) as unknown as typeof fetch;
  const bumpEpoch = () => {
    state.epoch += 1;
  };
  return { calls, fetcher, bumpEpoch };
}

function depsFor(h: Harness, epochRef: { epoch: number }, expiredCalls: number[] = []) {
  return {
    baseUrl: '',
    clientId: 'e5cd7e4891bf95d1d19206ce24a7b32e',
    identity: () => ({ token: 'jwt-abc', epoch: epochRef.epoch }),
    onAuthExpired: () => expiredCalls.push(1),
    fetcher: h.fetcher,
  };
}

/**
 * 改名成功的响应夹具 —— **默认按【真机形状】：`version` 是字符串**。
 *
 * ## 为什么夹具默认是字符串（这条是被真机纠正出来的）
 *
 * 本夹具最初写成 `version: <number>`，那是**我假设的形状**。真机（产物 `A2577E0E…`，C=6042）实测：
 * ```
 * PUT /api/ai/v1/conversations/2107269542295109632  {"title":"…"}
 * → 200 {"code":200,"msg":"success","data":{"renamed":true,"conversationId":"…","version":"2"}}
 * ```
 * 原因（读源码核实）：`AiResourceController:211` 是 **`long version`**（原始类型）⇒ 放进
 * `Map.of(…,"version",version)` 时**自动装箱成 `Long`**；而 `PlatformObjectMapperConfig:43`
 * 注册了 `platformModule.addSerializer(Long.class, ToStringSerializer.instance)`，
 * 注释逐字写着「**Long 一律输出为字符串，避免前端 JS 精度丢失**」。
 *
 * ⇒ **"判据的输入形状必须取自真机响应，不能取自自己的假设"**：夹具按假设写时，
 * 单测全绿只证明"假设自洽"。所以**默认路径就是字符串**；数字形状作为**兼容用例**单列。
 */
function okRename(conversationId: string, version: number | string) {
  return { code: 200, msg: 'ok', data: { conversationId, renamed: true, version: String(version) } };
}

/** 兼容用例：若后端某天改回数字（或网关换了序列化器），客户端也必须能读。 */
function okRenameNumeric(conversationId: string, version: number) {
  return { code: 200, msg: 'ok', data: { conversationId, renamed: true, version } };
}

function fail(status: number, errorCode: string, msg = '失败') {
  return { code: status, msg, data: { errorCode } };
}

/** 19 位雪花 id：任何 `Number(id)` 都会静默丢精度，所以必须逐字往返。 */
const SNOWFLAKE = '2076944338398593026';

describe('checkConversationTitle（与后端三条规则逐字对齐）', () => {
  it('正常标题通过，并返回 trim 后的值', () => {
    assert.deepEqual(checkConversationTitle('  季度复盘  '), { kind: 'ok', value: '季度复盘' });
  });

  it('空/空白 → empty（后端 400 title required）', () => {
    assert.deepEqual(checkConversationTitle(''), { kind: 'empty' });
    assert.deepEqual(checkConversationTitle('   '), { kind: 'empty' });
    assert.deepEqual(checkConversationTitle('\n\t'), { kind: 'empty' });
  });

  it('非字符串 → empty（不把 undefined 送成 "undefined"）', () => {
    assert.deepEqual(checkConversationTitle(undefined), { kind: 'empty' });
    assert.deepEqual(checkConversationTitle(null), { kind: 'empty' });
    assert.deepEqual(checkConversationTitle(42), { kind: 'empty' });
  });

  it('恰好 128 个字符通过（边界是 <= 而不是 <）', () => {
    const title = 'a'.repeat(CONVERSATION_TITLE_MAX);
    assert.deepEqual(checkConversationTitle(title), { kind: 'ok', value: title });
  });

  it('129 个字符拒绝，且**不截断**（后端明写不截断，会 400 title too long）', () => {
    const title = 'a'.repeat(CONVERSATION_TITLE_MAX + 1);
    assert.deepEqual(checkConversationTitle(title), { kind: 'too-long', length: 129 });
  });

  it('长度按原串判定：前后空格也算长度（不制造"前端放行、后端拒绝"的口径差）', () => {
    const title = ` ${'a'.repeat(CONVERSATION_TITLE_MAX)} `;
    assert.equal(title.length, CONVERSATION_TITLE_MAX + 2);
    assert.deepEqual(checkConversationTitle(title), { kind: 'too-long', length: CONVERSATION_TITLE_MAX + 2 });
  });
});

describe('classifyWriteFailure：只认符号码，不按 409', () => {
  it('RESOURCE_VERSION_CONFLICT → version-conflict（这是唯一的正例）', () => {
    const failure = classifyWriteFailure({ status: 409, errorCode: RESOURCE_VERSION_CONFLICT, message: '资源版本冲突' });
    assert.equal(failure.kind, 'version-conflict');
    assert.equal(failure.status, 409);
    assert.equal(failure.errorCode, RESOURCE_VERSION_CONFLICT);
  });

  it('另外三个后端码也走 409，但**不得**被判成改名冲突', () => {
    assert.equal(OTHER_409_CODES.length, 3, '锚点：负例必须有三条（后端共享 409 的码数）');
    for (const code of OTHER_409_CODES) {
      const failure = classifyWriteFailure({ status: 409, errorCode: code, message: 'x' });
      assert.notEqual(failure.kind, 'version-conflict', `${code} 不是改名冲突，不得误报`);
      assert.equal(failure.errorCode, code, '原始符号码必须保留（不编造、不吞掉）');
      assert.equal(failure.kind, 'other');
    }
  });

  it('同一状态码不同来源可区分（H-20：分类判据必须能区分同码不同源）', () => {
    const asConflict = classifyWriteFailure({ status: 409, errorCode: RESOURCE_VERSION_CONFLICT });
    const asStale = classifyWriteFailure({ status: 409, errorCode: 'POLICY_VERSION_STALE' });
    assert.notEqual(asConflict.kind, asStale.kind);
  });

  it('即使状态码不是 409，符号码是冲突码也判冲突（符号码优先于状态码）', () => {
    assert.equal(classifyWriteFailure({ status: 500, errorCode: RESOURCE_VERSION_CONFLICT }).kind, 'version-conflict');
    assert.equal(classifyWriteFailure({ status: -1, errorCode: RESOURCE_VERSION_CONFLICT }).kind, 'version-conflict');
  });

  it('401/403/404/400/503 各自分类（403 不得被当成登出）', () => {
    assert.equal(classifyWriteFailure({ status: 401, errorCode: 'AUTH_REQUIRED' }).kind, 'auth-expired');
    assert.equal(classifyWriteFailure({ status: 403, errorCode: 'FORBIDDEN' }).kind, 'forbidden');
    assert.equal(classifyWriteFailure({ status: 404, errorCode: 'RESOURCE_NOT_FOUND_OR_FORBIDDEN' }).kind, 'not-found');
    assert.equal(classifyWriteFailure({ status: 400, errorCode: 'BAD_REQUEST' }).kind, 'bad-request');
    assert.equal(classifyWriteFailure({ status: 503, errorCode: 'AUTHORIZATION_UNAVAILABLE' }).kind, 'unavailable');
  });

  it('取消（AbortError）不是业务失败', () => {
    const failure = classifyWriteFailure(Object.assign(new Error('Request identity changed'), { name: 'AbortError' }));
    assert.equal(failure.kind, 'other');
    assert.match(failure.message, /取消/);
  });

  it('无信息对象不抛错（不把"取不到"变成崩溃）', () => {
    assert.equal(classifyWriteFailure(undefined).kind, 'other');
    assert.equal(classifyWriteFailure(null).status, -1);
    assert.equal(classifyWriteFailure('boom').errorCode, '', '取不到符号码时是空串，不编造');
  });

  it('每种分类都有非空文案，且冲突文案明说"未写入"', () => {
    for (const kind of ['version-conflict', 'auth-expired', 'forbidden', 'not-found', 'bad-request', 'unavailable', 'other'] as const) {
      const message = writeFailureMessage({ kind, status: 0, errorCode: '', message: '' });
      assert.ok(message.length > 0, `${kind} 必须有文案`);
    }
    assert.match(writeFailureMessage({ kind: 'version-conflict', status: 409, errorCode: '', message: '' }), /未写入/);
  });
});

describe('renameConversation：真实请求 + 真实持久结果', () => {
  it('不带 expectedVersion → 兼容模式：请求体里**没有**该字段，并带回新版本', async () => {
    const h = harness([{ body: okRename(SNOWFLAKE, 7) }]);
    const api = createConversationWriteApi(depsFor(h, { epoch: 1 }));

    const result = await api.renameConversation(SNOWFLAKE, '新标题');

    assert.equal(h.calls.length, 1, '锚点：必须真的发出了一次请求');
    assert.equal(h.calls[0].url, `/api/ai/v1/conversations/${SNOWFLAKE}`);
    assert.equal(h.calls[0].init.method, 'PUT');
    assert.deepEqual(JSON.parse(String(h.calls[0].init.body)), { title: '新标题' });
    const headers = h.calls[0].init.headers as Record<string, string>;
    assert.equal(headers.Authorization, 'Bearer jwt-abc');
    assert.equal(headers.ClientID, 'e5cd7e4891bf95d1d19206ce24a7b32e');
    assert.equal(headers['Content-Type'], 'application/json');
    assert.equal(result.version, 7);
    assert.equal(result.renamed, true);
  });

  it('带 expectedVersion → 请求体逐字含该字段（这是 C9 的 CAS 请求形状）', async () => {
    const h = harness([{ body: okRename(SNOWFLAKE, 8) }]);
    const api = createConversationWriteApi(depsFor(h, { epoch: 1 }));

    await api.renameConversation(SNOWFLAKE, '再改一次', 7);

    assert.deepEqual(JSON.parse(String(h.calls[0].init.body)), { title: '再改一次', expectedVersion: 7 });
  });

  it('19 位雪花 id 逐字进 URL（Long 当字符串，不做 Number 转换）', async () => {
    const h = harness([{ body: okRename(SNOWFLAKE, 1) }]);
    const api = createConversationWriteApi(depsFor(h, { epoch: 1 }));
    await api.renameConversation(SNOWFLAKE, 't');
    assert.ok(h.calls[0].url.includes(SNOWFLAKE), 'id 必须逐字出现');
    assert.equal(h.calls[0].url, `/api/ai/v1/conversations/2076944338398593026`);
  });

  it('服务端 409 + 符号码 → 调用方拿到 version-conflict，且**没有任何本地缓存被更新**', async () => {
    const h = harness([{ body: fail(409, RESOURCE_VERSION_CONFLICT, '资源版本冲突'), status: 409 }]);
    const api = createConversationWriteApi(depsFor(h, { epoch: 1 }));

    let caught: unknown;
    try {
      await api.renameConversation(SNOWFLAKE, '并发失败方', 3);
    }
    catch (error) {
      caught = error;
    }

    assert.ok(caught, '409 必须抛错，不能静默成功');
    const failure = classifyWriteFailure(caught);
    assert.equal(failure.kind, 'version-conflict');
    assert.equal(failure.status, 409);
    assert.equal(failure.errorCode, RESOURCE_VERSION_CONFLICT, '符号码必须可见（这是 C9.3 的落地）');
  });

  it('409 但符号码是 POLICY_VERSION_STALE → 不得报成"改名冲突"（端到端走同一判据）', async () => {
    const h = harness([{ body: fail(409, 'POLICY_VERSION_STALE', '策略版本过期'), status: 409 }]);
    const api = createConversationWriteApi(depsFor(h, { epoch: 1 }));

    let caught: unknown;
    try {
      await api.renameConversation(SNOWFLAKE, 'x', 3);
    }
    catch (error) {
      caught = error;
    }

    const failure = classifyWriteFailure(caught);
    assert.equal(failure.kind, 'other');
    assert.equal(failure.errorCode, 'POLICY_VERSION_STALE');
    assert.notEqual(failure.kind, 'version-conflict');
  });

  it('401 → 触发 onAuthExpired 且抛错（不静默返回）', async () => {
    const h = harness([{ body: { code: 401, msg: '登录状态已失效' }, status: 401 }]);
    const expired: number[] = [];
    const api = createConversationWriteApi(depsFor(h, { epoch: 1 }, expired));

    await assert.rejects(() => api.renameConversation(SNOWFLAKE, 'x'));
    assert.equal(expired.length, 1, '锚点：401 必须真的触发一次过期处理');
  });

  it('切租户/退出（身份 epoch 变化）→ 迟到的成功响应被丢弃', async () => {
    const epochRef = { epoch: 1 };
    const h = harness([{ body: okRename(SNOWFLAKE, 9) }]);
    const api = createConversationWriteApi(depsFor(h, epochRef));

    const pending = api.renameConversation(SNOWFLAKE, '并发改名');
    epochRef.epoch += 1; // 请求在途时退出/切租户
    await assert.rejects(pending, (error: unknown) => (error as Error).name === 'AbortError');
  });

  it('输入预检失败时**一个请求都不发**（锚点：calls 长度为 0）', async () => {
    const h = harness([]);
    const api = createConversationWriteApi(depsFor(h, { epoch: 1 }));

    await assert.rejects(() => api.renameConversation(SNOWFLAKE, '   '), (error: unknown) => {
      assert.ok(error instanceof ConversationInputError);
      assert.equal((error as ConversationInputError).kind, 'empty');
      return true;
    });
    await assert.rejects(() => api.renameConversation(SNOWFLAKE, 'a'.repeat(129)), (error: unknown) => {
      assert.equal((error as ConversationInputError).kind, 'too-long');
      return true;
    });
    await assert.rejects(() => api.renameConversation('', 't'), (error: unknown) => {
      assert.ok(error instanceof ConversationInputError);
      return true;
    });

    assert.equal(h.calls.length, 0, '预检失败不得产生网络请求');
  });
});

describe('deleteConversation（C9.6：软删不带 expectedVersion）', () => {
  it('发 DELETE，且请求体为空（不带 version —— D10 只约束改名）', async () => {
    const h = harness([{ body: { code: 200, data: { conversationId: SNOWFLAKE, deleted: true } } }]);
    const api = createConversationWriteApi(depsFor(h, { epoch: 1 }));

    const result = await api.deleteConversation(SNOWFLAKE);

    assert.equal(h.calls.length, 1);
    assert.equal(h.calls[0].url, `/api/ai/v1/conversations/${SNOWFLAKE}`);
    assert.equal(h.calls[0].init.method, 'DELETE');
    assert.equal(h.calls[0].init.body, undefined);
    assert.equal(result.deleted, true);
  });

  it('缺失 id → 预检拒绝且不发请求', async () => {
    const h = harness([]);
    const api = createConversationWriteApi(depsFor(h, { epoch: 1 }));
    await assert.rejects(() => api.deleteConversation(''));
    assert.equal(h.calls.length, 0);
  });
});

describe('版本引导（兼容模式为什么是必需的）', () => {
  it('首个版本来自改名响应，之后可作为 expectedVersion 使用', async () => {
    const h = harness([
      { body: okRename(SNOWFLAKE, 4) },
      { body: okRename(SNOWFLAKE, 5) },
    ]);
    const api = createConversationWriteApi(depsFor(h, { epoch: 1 }));

    let versions = new Map<string, number>();
    const first = await api.renameConversation(SNOWFLAKE, '第一次');
    versions = withVersion(versions, SNOWFLAKE, bootstrapVersion(first));
    assert.equal(versions.get(SNOWFLAKE), 4);

    const second = await api.renameConversation(SNOWFLAKE, '第二次', versions.get(SNOWFLAKE));
    versions = withVersion(versions, SNOWFLAKE, bootstrapVersion(second));

    assert.deepEqual(JSON.parse(String(h.calls[0].init.body)), { title: '第一次' });
    assert.deepEqual(JSON.parse(String(h.calls[1].init.body)), { title: '第二次', expectedVersion: 4 });
    assert.equal(versions.get(SNOWFLAKE), 5, '版本必须随成功改名前进');
  });

  it('响应缺 version / 不可信 → bootstrapVersion 返回 null，**不缓存 0**', () => {
    assert.equal(bootstrapVersion(undefined), null);
    assert.equal(bootstrapVersion({}), null);
    assert.equal(bootstrapVersion({ version: null }), null);
    assert.equal(bootstrapVersion({ version: 'abc' }), null);
    assert.equal(bootstrapVersion({ version: -1 }), null);
    assert.equal(bootstrapVersion({ version: Number.NaN }), null);
    assert.equal(bootstrapVersion({ version: 0 }), 0, '0 是合法版本（V13 旧行就是 0），必须与"缺失"区分');
  });

  it('`Number()` 的静默强制转换不得把"缺失"变成"版本 0"（两个陷阱逐条钉住）', () => {
    // Number(null) === 0、Number('') === 0、Number(true) === 1、Number([]) === 0。
    // 少了这些断言，上面的实现只要退化成单个 `Number(raw.version)` 分支就会静默错，
    // 而后果是把每一次"未引导版本"的改名都当成 expectedVersion: 0 → 必然 409。
    assert.equal(bootstrapVersion({ version: null }), null, 'Number(null)===0 是陷阱');
    assert.equal(bootstrapVersion({ version: '' }), null, 'Number(\'\')===0 是陷阱');
    assert.equal(bootstrapVersion({ version: '   ' }), null);
    assert.equal(bootstrapVersion({ version: true }), null, 'Number(true)===1 是陷阱');
    assert.equal(bootstrapVersion({ version: [] }), null, 'Number([])===0 是陷阱');
    assert.equal(bootstrapVersion({ version: { valueOf: () => 3 } }), null, '不调用自定义 valueOf');
  });

  it('字符串数字仍是合法版本 —— 且这是【真机形状】，不是兼容边角', () => {
    // 真机（产物 A2577E0E…，C=6042）逐字：{"renamed":true,"conversationId":"…","version":"2"}
    // 成因：AiResourceController:211 的 `long` 装箱成 Long，被 PlatformObjectMapperConfig:43
    // （Long → ToStringSerializer，注释「Long 一律输出为字符串，避免前端 JS 精度丢失」）序列化。
    assert.equal(bootstrapVersion({ version: '12' }), 12);
    assert.equal(bootstrapVersion({ version: '0' }), 0);
    assert.equal(bootstrapVersion({ version: '2' }), 2);
  });

  it('严格解析：非规范十进制一律 null（**不给猜的机会**）', () => {
    // 裸 Number() 会把这些"猜"成有效版本：'1e3'→1000、'0x10'→16、'1.5'→1.5。
    // 一个版本号只能是规范十进制非负整数；其余形状都是"不可信"，必须 null。
    assert.equal(parseConversationVersion('1e3'), null, 'Number(\'1e3\')===1000 是陷阱');
    assert.equal(parseConversationVersion('0x10'), null, 'Number(\'0x10\')===16 是陷阱');
    assert.equal(parseConversationVersion('1.5'), null, '非整数版本必须拒绝');
    assert.equal(parseConversationVersion('+1'), null);
    assert.equal(parseConversationVersion('1_000'), null);
    assert.equal(parseConversationVersion('-1'), null, '版本非负');
    assert.equal(parseConversationVersion('9007199254740993'), null, '超 Number.MAX_SAFE_INTEGER');
    assert.equal(parseConversationVersion(1.5), null);
    assert.equal(parseConversationVersion(Number.MAX_SAFE_INTEGER + 1), null);
    // 前后空白是被容忍的唯一"非规范"形状（服务端 JSON 里出现空白的可能性极低，且无歧义）
    assert.equal(parseConversationVersion(' 1 '), 1);
    assert.equal(parseConversationVersion(Number.MAX_SAFE_INTEGER), Number.MAX_SAFE_INTEGER);
  });

  it('renameConversation 返回前规范化 version ⇒ `RenameResult.version` 的类型是真话', async () => {
    // 真机响应是字符串；若把原始 JSON 直接当 RenameResult 返回，声明是 number、运行时是 string
    // ⇒ 消费者 `result.version + 1` 会得到 "21"。本用例钉住"返回的必须是 number"。
    const h = harness([{ body: okRename(SNOWFLAKE, 7) }]);
    const api = createConversationWriteApi(depsFor(h, { epoch: 1 }));
    const result = await api.renameConversation(SNOWFLAKE, '规范化');
    assert.equal(typeof result.version, 'number', '线上是字符串，返回给调用方的必须是数字');
    assert.equal(result.version, 7);
    assert.equal(result.version! + 1, 8, '加法必须成立（字符串会变成拼接）');
  });

  it('数字形状仍可读（网关换序列化器时的兼容用例）', async () => {
    const h = harness([{ body: okRenameNumeric(SNOWFLAKE, 9) }]);
    const api = createConversationWriteApi(depsFor(h, { epoch: 1 }));
    const result = await api.renameConversation(SNOWFLAKE, '数字形状');
    assert.equal(result.version, 9);
  });

  it('null 版本会清掉缓存条目（而不是留下过期版本继续 CAS）', () => {
    const before = new Map([['c-1', 3]]);
    assert.deepEqual([...withVersion(before, 'c-1', null).keys()], []);
    assert.equal(withVersion(before, 'c-1', null).has('c-1'), false);
    assert.deepEqual([...before.keys()], ['c-1'], '输入映射不被就地修改');
  });
});
