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
| R9 | /history | `feedback-state-not-found` | POST feedback → HTTP 404（W3-5-BE-1 **前**现状取证；该态在 a987ed6 后不再出现，仅当产物早于 a987ed6 时执行） |
| R10 | /history | **主判据（默认形态，shipped 纪律：legacy-listeners-enabled 产品不打开）**：`feedback-state-not_found`…见下 —— 实际预期 `feedback-state-forbidden`（403 `TENANT_CONTEXT_MISSING`）且文案含"队列未装配"原文（`requireProducer()` D07 响亮拒绝，`AiInternalExceptionResolver` 映射 403） | POST feedback → HTTP 403 + 原文核对 |

**R10 分支裁定（T0 验收 team-message-863e5fc4）**：
- **主判据 = 403 + "队列未装配"原文**（默认形态下 `requireProducer()` 响亮拒绝是 D07 正确 fail-closed，**不是缺陷**，不得记 FAIL）；
- **submitted 分支（`feedback-state-submitted` → cancel → `feedback-state-cancelled`）本波 `NOT_RUN`**：结构性未接通 —— `MessageFeedbackConsumer` 装配需 `ai.integration.legacy-listeners-enabled=true` 专用实验窗 + RocketMQ 基础设施（T3 的 C12.5-5 消费链容器接线尚未打通），成本高收益低，**不伪造**；
- 两分支**不可混写**；submitted 分支解锁条件 = 专用实验窗 + broker 链就位，由 T0/T3 后续宣布。
- 幂等判据（连赞两次不产生重复行）随 submitted 分支一并顺延。

R9/R10 **不可混写**：R9 = W3-5-BE-1 前判据态（产物含 feade4f、不含 a987ed6 时才有意义）；R10 = W3-5-BE-1 后判据态（产物含 a987ed6，当前锚 05E7F856 即满足门禁 16 的"已进当前产物"核对）。执行前先核产物提交清单（门禁 16）。

## 登录态

- 现有页面登录走 `/auth/login`（账号/密码由 T0 窗口时提供；**凭证不落盘**，运行时注入环境变量，转储先脱敏）。
- 非超管负例（R4）需要无 `ai:kb:retrieve` 的账号——由 T0 提供或现场建，不要复用超管冒充。

## 产物锚（每次执行前核对，门禁 16）

```
期望 ruoyi-admin.jar SHA256 = B96DC1B91B831C0BC802C9BE647A0EF654D774C11FA2AD204305CAC5833142FD
（commit cd053b9，W6 绿产物，测试 1579/0/0/32；取代 05E7F856——若 T10 登记簿出现更新锚，以登记簿为准并写明）
```

**双锚执行口径（T10 已登记进 ARTIFACTS.md v2 锚节；R10 文案判定依赖）**：
R10 的 403 两源**文案**分支（`feedbackFailureMessage` 的"反馈链未开启：队列未装配"）在**待集成**前端增量里，不在产物内（产物内是 a987ed6 时点版本）。双锚形态执行时，前端 3 文件 SHA256 与后端锚**并列写出**，两锚独立可查（64 位值执行时 `Get-FileHash` 现采并全文落盘）：

```
后端锚：ruoyi-admin.jar SHA256 = B96DC1B91B831C0BC802C9BE647A0EF654D774C11FA2AD204305CAC5833142FD (cd053b9)
前端锚：src/api/ai/message-feedback.ts        = F6C1EE7501C56875…（执行时现采 64 位）
        tests/message-feedback.test.ts         = 2143ECED5FDA47E5…（同上）
        tests/e2e/README-w3-5-r1-r10.md       = 464F5E082679A9DE…（同上）
（若窗口排在 T0 集成批次之后 ⇒ 前端已进产物 ⇒ 自动单锚，前端锚行写"已集成，见产物 commit"）
```

## 结果落盘

证据目录：`D:/AI-project/.scratch/platform-embedded-impl/T6/w3-5-e2e/<run-id>/`
每条判据一个 JSON（含：url、method、status、content-type、响应体特征摘要（脱敏）、testid、判定 PASS/FAIL/NOT_RUN），外加一个 `summary.md` 汇总表。
