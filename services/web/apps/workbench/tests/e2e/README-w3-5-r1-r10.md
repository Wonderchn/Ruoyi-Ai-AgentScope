# T6 W3-5 真机联调判据执行器（R1–R10）— 使用说明

> 本目录脚本在**联调窗口**由 T6 执行；判据清单与验收口径见
> `D:/AI-project/mydocs/platform-embedded/team/reports/t6/W3-5-workbench.md` §7。
> 前置：T0 实例（写路径需 high-risk ON 形态）+ 已知可用登录账号 + `DEV_PROXY_TARGET` 指向实例的 vite dev 已起。

## 执行形态

**PowerShell + CDP（Chrome DevTools Protocol）**，与 batch1 的浏览器验收同款（`http://localhost:<port>`，**不用** 127.0.0.1 —— Vite 6 可能只监听 `::1`）。

判据三条铁律（BRIEF §4 实测坑，逐条落在脚本里）：
1. **只认 testid**，不做文案匹配；
2. **轮询等待非 loading 状态**（上限 20s），不用固定延时；
3. **核对响应体来源**：HTTP 200 + 渲染 ≠ 来自后端 —— 每条判据记录响应的
   content-type / 与后端已知形状比对（HTML 特征 = SPA fallback = 代理未生效，判 FAIL）。

## R1–R10 逐条（与报告 §7 同一编号，不重复展开）

| 编号 | 页 | 判据 testid | 响应来源核对 |
| --- | --- | --- | --- |
| R1 | /rag/debug | `retrieval-rows` | POST `/api/ai/v1/knowledge-bases/retrievals` 响应为 JSON，含 `RetrievedChunk` 字段（id/text/score） |
| R2 | /rag/debug | `retrieval-empty` | 同上端点，`data: []` |
| R3 | /rag/debug | `retrieval-not-found` | HTTP 404 + `errorCode=RESOURCE_NOT_FOUND_OR_FORBIDDEN` |
| R4 | /rag/debug | `retrieval-forbidden` | HTTP 403 |
| R5 | /rag | `rag-uploaded` | HTTP 201 + body 含 sha256/sizeBytes（客户端 201 判据在 `@ruoyi/events/rag` uploadDocument） |
| R6 | /rag | `rag-ingest-run[data-ingest-status=SUCCEEDED]` 且 `rag-docs[data-doc-count>=1]` | run snapshot JSON |
| R7 | /rag | `rag-answer` + `rag-citations[data-citation-count>=1]` | SSE 帧 / run snapshot；citation 含 versionId/docId/chunkIndex |
| R8 | /rag | 私有 PDF 弹窗（`rag-view-source-0` 后 iframe/canvas 渲染） | GET `/documents/{id}/source` 响应 content-type=`application/pdf` 且非 HTML（SPA fallback 排除） |
| R9 | /history | `feedback-state-not-found` | POST feedback → HTTP 404（W3-5-BE-1 **前**现状取证） |
| R10 | /history | `feedback-state-submitted` → `feedback-cancel` → `feedback-state-cancelled`；幂等=连赞两次不产生重复行 | W3-5-BE-1 **后**：HTTP 200 受理 |

R9/R10 **不可混写**：分属 W3-5-BE-1 前后两个判据态；R10 执行前必须先核 T0 宣布的三件套已进**当前产物 SHA**（门禁 16）。

## 登录态

- 现有页面登录走 `/auth/login`（账号/密码由 T0 窗口时提供；**凭证不落盘**，运行时注入环境变量，转储先脱敏）。
- 非超管负例（R4）需要无 `ai:kb:retrieve` 的账号——由 T0 提供或现场建，不要复用超管冒充。

## 产物锚（每次执行前核对，门禁 16）

```
期望 ruoyi-admin.jar SHA256 = 05E7F856E7D33864B74DEA7F59F2F942A5FFAD94C691A581BB785268FA811898
（若 T10 登记簿出现更新锚，以登记簿为准并在执行记录里写明）
```

## 结果落盘

证据目录：`D:/AI-project/.scratch/platform-embedded-impl/T6/w3-5-e2e/<run-id>/`
每条判据一个 JSON（含：url、method、status、content-type、响应体特征摘要（脱敏）、testid、判定 PASS/FAIL/NOT_RUN），外加一个 `summary.md` 汇总表。
