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
| `SseSyntax.ts` | 纯 SSE 语法层（无 `fetch`、无定时器）：按 WHATWG SSE 规则解析 `data:` / `event:` / `id:` / `retry:` / `:` 注释帧，支持增量分片 |
| `RunStreamClient.ts` | 传输与状态层：请求构造、游标、看门狗、去重、重连、错误映射 |

## 用法

```ts
import { openRunStream } from '@/utils/sse/RunStreamClient';

const stream = openRunStream(
  { baseURL: import.meta.env.VITE_API_URL, runId, token: userStore.token },
  { stallTimeoutMs: 45_000, onDebug: import.meta.env.DEV ? console.debug : undefined },
);

for await (const message of stream.messages) {
  if (message.isComment) continue;            // 心跳，不渲染
  if (message.type === 'run.output_delta') append((message.parsed?.payload as any)?.text ?? '');
  if (message.type === 'run.terminal') break; // 只有终态才算结束
}
```

契约要点（与设计文档一致）：

- **游标**：`openRunStream` 记录已应用的最高 `seq`，重连时以 `afterSeq` 续传；重复帧按 `seq` 丢弃。
- **终态**：只有 `run.terminal`（或 envelope 的 `type`）结束运行。连接断开是重连信号，**不是**成功。
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

当前：35 个用例通过（SSE 语法 15 + 客户端 20）。

## 尚未接线

本模块只提供客户端能力，**尚未**有页面使用它：把聊天页从现有协议切到统一 run/SSE 契约属于后续单元（P2/P4）。切换前不要删除 `hook-fetch` 通道。
