# SSE 前置验证工具（P2 门槛）

P2 要交付"可恢复 RAG 流式：断线重连与事件回放"，前提是前端通道真的能处理**命名事件、游标、去重、终态与过期游标**。这个工具用来做这件事的**端到端实测**，而不是靠推断。

## 工具构成

| 文件 | 作用 |
|---|---|
| `fake-sse-server.mjs` | 零依赖（`node:http`）假 SSE 服务，按路径选择场景，并记录**服务端收到的全部请求头与 URL**（用于证明客户端是否发了游标、是否把 token 放进了 URL） |
| `README.md` | 场景说明、实测结论与用法 |

## 场景

| 场景 | 用途 |
|---|---|
| `S1` | 一次完整运行（`run.accepted` → `output_delta` → `usage` → `run.terminal`），**首次连接故意漏掉一个 seq**；**按 `afterSeq` 过滤**，因此可用于验证缺口补齐 |
| `S3` | 规范合法的**多行 `data:`**（必须按 `\n` 连接，不能只留最后一行） |
| `S4` | 只有心跳、连接保持打开的流 |
| `S5` | 流中途被强制断开（没有终态事件） |
| `S6` | HTTP 410 + `CURSOR_EXPIRED` 快照体 |

## 用法

```bash
node tools/sse-pregate/fake-sse-server.mjs          # 终端 1，默认 127.0.0.1:18131
curl -N http://127.0.0.1:18131/S1                   # 首连：id 1,3,4,5,6（漏掉 2）
curl -N 'http://127.0.0.1:18131/S1?afterSeq=1'      # 续传：id 2,3,4,5,6（补齐 2）
curl -N -H 'Last-Event-ID: 3' http://127.0.0.1:18131/S1   # 用 SSE 标准头做游标
curl -i http://127.0.0.1:18131/S6                   # 410 + 快照
```

路径同时接受 `/S1` 与 `/S1/runs/{runId}/events`，因此既能用 curl 手测，也能直接对接按契约构造路径的客户端。

服务端把每个请求写入 `server-requests.jsonl`：**凭据类头（`authorization`/`cookie`/`proxy-authorization`/`x-api-key`）会被脱敏**为长度标记，只保留"是否发送过"这一事实，磁盘上不会出现明文 token。

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

**端到端已验**：用真客户端对 S1 实测，首连收到 1 后检测到缺口 → 以 `afterSeq=1` 续传 → 回补 2..6 → 以终态结束，游标停在 6；服务端日志确认两次请求（`/S1/runs/r-1/events` 与 `?afterSeq=1`）。

## 何时用它

- P2 实现 run 事件接口后：用真实后端替换假服务，重跑 S1/S4/S5/S6，核对游标、心跳与过期行为。
- 回归排查：出现"断线后不恢复"或"重复渲染"时，先跑 S1/S3 判断是解析层还是状态层的问题。

## 边界

- 这是**本地开发/验证工具**，不参与任何产品构建，也不被 CI 执行（CI 不构建 `services/ruoyi-web`，见 `.github/workflows/ci.yml` 的 `frontend` 作业只针对 `services/ai/frontend`）。
- 不包含真实后端、真实模型或真实客户数据；`S6` 的 410 体是固定的合成快照。
