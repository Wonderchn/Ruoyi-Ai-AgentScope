# SSE 前置验证工具（P2 门槛）

P2 要交付"可恢复 RAG 流式：断线重连与事件回放"，前提是前端通道真的能处理**命名事件、游标、去重、终态与过期游标**。这个工具用来做这件事的**端到端实测**，而不是靠推断。

## 工具构成

| 文件 | 作用 |
|---|---|
| `fake-sse-server.mjs` | 零依赖（`node:http`）假 SSE 服务，按路径选择场景，并记录**服务端收到的全部请求头与 URL**（用于证明客户端是否发了游标、是否把 token 放进了 URL） |
| `probe.mjs` | 零依赖端到端探针：起一个一次性服务实例，对缺口补齐（含 **header-only `Last-Event-ID` 续传**）、`?run=` 会话隔离与日志脱敏逐项断言，失败即非零退出 |
| `README.md` | 场景说明、实测结论与用法 |

## 场景

| 场景 | 用途 |
|---|---|
| `S1` | 一次完整运行（`run.accepted` → `output_delta` → `usage` → `run.terminal`，**seq 连续**），**每个会话的首次连接故意漏掉一个 seq**；**按 `afterSeq` / `Last-Event-ID` 过滤**，因此可用于验证缺口补齐 |
| `S3` | 规范合法的**多行 `data:`**（必须按 `\n` 连接，不能只留最后一行） |
| `S4` | 只有心跳、连接保持打开的流 |
| `S5` | 流中途被强制断开（没有终态事件） |
| `S6` | HTTP 410 + `CURSOR_EXPIRED` 快照体 |

## 用法

```bash
node tools/sse-pregate/fake-sse-server.mjs          # 终端 1，默认 127.0.0.1:18131

# 每次测试用独立 run id：S1 的"首连漏一帧"按会话（?run=<id>）计数，互不干扰
curl -N 'http://127.0.0.1:18131/S1?run=t1'                  # 首连：id 1,3,4,5,6（漏 2）
curl -N 'http://127.0.0.1:18131/S1?run=t1&afterSeq=1'       # 续传：id 2,3,4,5,6（补齐 2）
curl -N -H 'Last-Event-ID: 3' 'http://127.0.0.1:18131/S1?run=t2'   # 用 SSE 标准头做游标
curl -i http://127.0.0.1:18131/S6                   # 410 + 快照
```

要点：

- **会话键与游标无关**。"首连漏帧"按 `路径#会话` 计数，会话只由 `?run=<id>` 决定；不带的请求共享该路径的同一个 `default` 会话。因此**仅用 `Last-Event-ID` 续传是同一会话的第二连**，会补齐首连缺的帧，而不会被误计成新的首连把缺口重新打开。
- **`?run=<id>` 必须每次测试唯一**。不同 run id 各自经历"首连漏帧"；若共用同一个 run id，第二个测试就看不到缺口，断言会变成假阳性。也可用 `OMIT_ONCE=<n>` 改漏掉的序号，或设 `OMIT_ONCE=0` 关闭漏帧。
- **回放必须覆盖区间内每一帧**：带游标的请求会返回该游标之后的**全部**帧，包括上一连接暂缓的那一帧。这是"终态必须等缺口补齐"能成立的前提（假服务不会永久吞掉某帧）。
- 路径同时接受 `/S1` 与 `/S1/runs/{runId}/events`，既能用 curl 手测，也能直接对接按契约构造路径的客户端。
- 服务端每个请求都会打印一行（`run=` / `connection=` / `cursor=` / `sent=` / `omitted=`），并写入 `server-requests.jsonl`：**凭据类头与敏感查询参数值都会被脱敏**（替换为长度标记），只保留"是否发送过"这一事实。启动时会清空该日志，避免跨次运行混证据。
- 回归断言用 `node tools/sse-pregate/probe.mjs` 一键复跑（自起一次性实例，不需要先手动开服务）。

## 已用此工具得到的结论（2026-09-29）

对 `services/ruoyi-web` 当时安装的 `hook-fetch@2.0.4-beta.1`（产物 SHA-256 与 `node_modules` 逐字节一致）实测，**3 PASS / 7 FAIL / 1 RETRACTED**：

| 能力 | 结果 |
|---|---|
| 命名事件透传到消费方 | PASS |
| 凭据只在头部、不进 URL | PASS |
| 心跳注释帧不污染内容 | PASS |
| 事件 `id` / 游标 / `Last-Event-ID` | FAIL（不读 `id:`，不重发游标，无自动重连） |
| 多行 `data:` 拼接 | FAIL（只留最后一行） |
| 回放/重复帧去重 | FAIL |
| 静默流看门狗 | FAIL |
| 突然断开 vs 正常终态 | FAIL（断开不报错，与正常完成不可区分） |
| `410 CURSOR_EXPIRED` 可辨别 | FAIL（状态码/errorCode/快照全丢） |
| 现有分发器渲染契约事件集 | FAIL（8 帧喂入 0 帧渲染） |

依据该结论，后续 PR 新增了 `services/ruoyi-web/src/utils/sse/`（契约合规的 run/SSE 客户端）并附单元测试；本工具用于对该客户端做 **HTTP 层**的补充验证。

**端到端已验**（2026-09-29，两轮修复后）：用真客户端对 `S1?run=<id>` 实测（两个不同 run id 各自独立）：

```text
MSG seq=1 type=run.accepted
RECONNECT reason=gap afterSeq=1        <- 缺口被发现并记录，终态被暂缓
MSG seq=2 type=run.step_started
MSG seq=3 type=run.output_delta
MSG seq=4 type=run.output_delta
MSG seq=5 type=run.usage
MSG seq=6 type=run.terminal
END NORMALLY; applied=6   delivered=[1,2,3,4,5,6]   hole_logged=true
```

服务端日志确认每轮两次请求（`afterSeq=0` 与 `afterSeq=1`），凭据为脱敏值。

## 何时用它

- P2 实现 run 事件接口后：用真实后端替换假服务，重跑 S1/S4/S5/S6，核对游标、心跳与过期行为。
- 回归排查：出现"断线后不恢复"或"重复渲染"时，先跑 S1/S3 判断是解析层还是状态层的问题。

## 边界

- 这是**本地开发/验证工具**，不参与任何产品构建，也不被 CI 执行（CI 不构建 `services/ruoyi-web`，见 `.github/workflows/ci.yml` 的 `frontend` 作业只针对 `services/ai/frontend`）。
- 不包含真实后端、真实模型或真实客户数据；`S6` 的 410 体是固定的合成快照。
