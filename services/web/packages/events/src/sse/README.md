# Run/SSE 事件客户端（`src/utils/sse`）

面向统一 run/SSE 契约的最小前端客户端。它替代 `hook-fetch` 的 SSE 通道来承载运行事件，因为该通道经过实测无法满足契约要求（见下）。

## 为什么需要它

`docs` 之外的验证结论记录在项目外的 P0 证据目录中，摘要如下（用真实安装的 `hook-fetch@2.0.4-beta.1` 对本地假 SSE 服务实测）：

| 能力 | 现状 |
|---|---|
| 命名事件透传 | ✅ 可用 |
| 事件 `id` / 断线游标 | ❌ 不读取 `id:`，不发 `Last-Event-ID`，无自动重连 |
| 多行 `data:` 拼接 | ❌ 只保留最后一行 |
| 重复/回放去重 | ❌ 无 |
| 静默流看门狗 | ❌ 无 |
| 断开与终态区分 | ❌ 断开不报错，与正常结束不可区分 |
| 非 2xx（如 `410 CURSOR_EXPIRED`） | ❌ 状态码与错误码全部丢失 |
| 凭据不进 URL | ✅ 可用 |

因此本模块按契约实现这些语义，并用 `node:test` 覆盖。

## 结构

| 文件 | 职责 |
|---|---|
| `SseSyntax.ts` | 纯 SSE 语法层（无 `fetch`、无定时器）：按 WHATWG 规则解析 `data:` / `event:` / `id:` / `retry:` / `:` 注释帧，支持增量分片 |
| `RunStreamClient.ts` | 传输与状态层：请求构造、游标、缺口补齐、看门狗、去重、重连、错误映射 |

`SseSyntax.ts` 严格遵循规范的几条要点（都有对应测试）：

- 多行 `data:` 以 `\n` 连接，去掉末尾一个 `\n`；
- **数据缓冲为空的帧不派发**：只有 `event:` 没有 `data:` 的帧不会变成事件；
- **流结束时未以空行结束的帧被丢弃**：被截断的 `event: run.terminal` 不会被当成已完成；
- CRLF 是一个行尾；**跨网络块被拆开的 CRLF 不会拆成两个行尾**；块尾单独的 CR 会等到下一块再判定。

## 用法

```ts
import { openRunStream } from '@/utils/sse/RunStreamClient';

const stream = openRunStream(
  { baseURL: import.meta.env.VITE_API_URL, runId, token: userStore.token },
  { stallTimeoutMs: 45_000, onDebug: import.meta.env.DEV ? console.debug : undefined },
);

try {
  for await (const message of stream.messages) {
    if (message.isComment)
      continue; // 心跳，不渲染
    if (message.type === 'run.output_delta')
      append((message.parsed?.payload as any)?.text ?? '');
    if (message.type === 'run.terminal')
      break; // 只有终态才算结束
  }
}
catch (error) {
  if (error instanceof RunEventStreamIncompleteError) {
    // 从未收到终态且重连已耗尽：任务的真实结果未知，必须提示"状态未知/待核对"，
    // 不能当成正常完成。
  }
  if (error instanceof RunEventStreamProtocolError) {
    // 信封违反契约（schemaVersion 未知 / runId 不是本 run / seq 非法 / SSE id 与 seq 不符 /
    // SSE event: 与信封 type 不一致或不全 / 非 JSON 信封）：这是服务端契约破坏，
    // 重放不会好转，不要重试，直接上报。
  }
  if (error instanceof CursorExpiredError) {
    // 游标超出保留期：展示 error.snapshot，不要盲目重连。
  }
}
```

契约要点（与设计文档一致）：

- **方法**：默认 `GET /runs/{runId}/events`（契约定义该端点为只读订阅）；需要请求体时才显式传 `method: 'POST'`。
- **缺省游标**：`afterSeq` 缺省 = `0`（契约：从可见事件起点回放）。因此**首个可见事件不是 `seq=1` 时视为空洞并回补**，不接受稀疏起点。
- **可见序号连续**：契约保证同一 run 的可见 `seq` 从 1 起连续（合并小批、丢弃增量都不得跳号）。客户端据此把"缺号"判定为丢帧。
- **缺口补齐**：发现空洞时**暂缓该帧（包括终态）、不推进游标**，按最后连续 `seq` 回放补齐；重叠帧由 `seen` 去重。暂缓的帧不在 `seen` 里，因此回放会重新送达并通过连续性检查——**不会静默丢弃**。缺口补齐不消耗错误重试预算（另有 `maxResumeCycles` 上限）。
- **信封门禁**：任何业务帧（**尤其终态**）必须先通过信封校验才会被接受：`schemaVersion` 必须是本客户端支持的 `1`、`runId` 必须等于订阅的 run、`seq` 必须是安全正整数、SSE `id:` 存在时必须等于 `seq`、SSE `event:` 与信封 `type` 必须同时存在且一致（终态判定只用验证过的 `type`）。任一违反抛 `RunEventStreamProtocolError`（带 `reason`），**不重试、不伪装订阅完成**——没有合法游标、属于别的 run、或类型标记互相矛盾的终态绝不构成完整性证明。信封合法但没带 `id:` 的帧可以接受（游标来自信封 `seq`）。
- **终态 = 订阅完整**：`run.terminal` 只有在它之前的可见事件全部交付后才交给页面。若在限定次数内补不齐，抛 `RunEventStreamIncompleteError`，页面应显示"运行可能已结束，但本条订阅不完整"，并可改用 `GET /runs/{runId}` 的快照核对。**绝不以"收到终态"替代"事件已补齐"。**
- **游标语义**：`appliedCursor()` 是**已连续交付**的游标，空洞不会抬高它，可直接用于恢复。
- **过期游标**：HTTP 410 抛 `CursorExpiredError`，携带 `lastSeq` 与已持久化快照，**不重连**（需重新鉴权/展示快照）。
- **凭据**：只通过 `authorization` 头传递，永不进 URL。
- **看门狗**：静默超过 `stallTimeoutMs` 抛 `RunStreamStalledError`；心跳会重置预算。
- **清理**：取消/释放有时间上限，传输层 `cancel()` 挂起不会拖住错误或最后一条消息。

## 测试

本包没有测试框架依赖，因此用 Node 22 自带的 `node:test`，并用 `tests/ts-loader.mjs` 复用已安装的 `typescript` 做类型剥离（不新增依赖、不改 lockfile）：

```bash
node --import ./tests/ts-loader.mjs --test tests/SseSyntax.test.ts tests/RunStreamClient.test.ts
npx tsc -p tsconfig.tests.json --noEmit   # 类型检查（含 tests）
```

当前：**54 个用例通过**（SSE 语法 21 + 客户端 33）。`tsconfig.tests.json` 是自洽配置（自带 `lib` 与 `types: ["node"]`），只覆盖 `src/utils/sse/**` 与 `tests/**`，因此不依赖 DOM 库推断，也不需要它去检查整个 `src`。

## 尚未接线

本模块只提供客户端能力，**尚未**有页面使用它：把聊天页从现有协议切到统一 run/SSE 契约属于后续单元（P2/P4）。切换前不要删除 `hook-fetch` 通道。
