/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ruoyi.aiintegration.web;

import jakarta.servlet.http.HttpServletRequest;
import org.ruoyi.aiintegration.authorization.AiActionRegistry;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.aiintegration.delegation.ProductionSigningKeySource;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 生产 AI 网关（U06/P1.2c）：{@code /api/ai/v1/**} 白名单代理，真实委托链的 platform 入口。
 *
 * <p>流程（每步失败即终止，失败响应 {@code HTTP status == body.code}）：
 * <ol>
 *   <li>白名单：method + 路径形状必须精确命中固定路由表；白名单外一律 404
 *       （不存在的路径与无权访问的资源同形，不泄露网关能力面）；</li>
 *   <li>登录证据：请求未携带 sa-token 凭证头（{@code Authorization}，与
 *       {@code sa-token.token-name} 一致）→ 401；</li>
 *   <li>成员身份：{@link CurrentPrincipalResolver#resolveCurrentMember()} 为 empty
 *       （已登录但不归属当前租户/无租户上下文）→ 403；</li>
 *   <li>策略版本：经 {@link ObjectProvider}&lt;{@link PlatformIdentitySource}&gt; 查
 *       真实身份事实；无身份源 → 503；成员不存在/停用/无策略版本行 → 403
 *       （版本缺失绝不默认 1）；</li>
 *   <li>最小 scope：路由 → canonical 动作 → 平台权限（{@link AiActionRegistry} 固定映射），
 *       身份 scopes 不含该权限 → 403；委托只携带<b>本路由所需的那一个 scope</b>；</li>
 *   <li>签发委托并转发：剥内部身份头与逐跳头、剥浏览器凭证，附
 *       {@code Authorization: Bearer <委托>}；不跟随重定向；超时/体长受限；
 *       恶意响应 → 503；AI 状态码透传。</li>
 * </ol>
 *
 * <p>网关不解析、不信任 body/header 里的身份字段：身份只来自 SaToken 会话经
 * resolver 与身份源查询的真实事实。
 */
@RestController
@RequestMapping("/api/ai/v1")
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiGatewayController {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayController.class);

    /** 登录证据头：与 platform {@code sa-token.token-name: Authorization} 一致。 */
    static final String LOGIN_EVIDENCE_HEADER = "Authorization";

    /** 网关前缀（与 @RequestMapping 一致），用于从 requestURI 提取内部子路径。 */
    static final String GATEWAY_PREFIX = "/api/ai/v1";

    /** AI 侧内部 API 前缀（被 {@code DelegatedPrincipalFilter} 保护的同一前缀）。 */
    static final String AI_INTERNAL_PREFIX = "/internal/ai/v1";

    /**
     * 内部身份头黑名单：一律不转发（网关身份只经委托凭证传递，
     * 伪造的 X-Tenant/X-User 到了 AI 侧也不被任何组件采信）。
     */
    private static final Set<String> INTERNAL_IDENTITY_HEADERS = Set.of(
            "x-tenant", "x-tenant-id", "x-user", "x-user-id", "x-member-id",
            "x-membership-id", "x-policy-version", "x-acl-version", "x-principal");

    /** 逐跳头/由转发栈自行决定的头：一律不转发。 */
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "authorization", "host", "content-length", "connection", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "cookie", "expect");

    /** 白名单路由：method + 路径形状 → canonical 动作。运行期固定，不可扩展。 */
    private record Route(String method, String pattern, String action) {
    }

    private static final List<Route> ROUTES = List.of(
            new Route("GET", "/runtime-config/revisions/{revisionId}", "config.read"),
            new Route("POST", "/runtime-config/revisions", "config.publish"),
            new Route("POST", "/runtime-config/revisions/{revisionId}/revoke", "config.revoke"),
            new Route("POST", "/runtime-config/revisions/{revisionId}/rollback", "config.publish"),
            // F08:0–3（2026-10-18）：知识图谱管理面 4 条。内层 handler = GraphController
            // （类级 /internal/ai/v1/admin/kg）。三个 GET 走网关字节分支，必须整数 ApiEnvelope
            // + 交付回执（AiEmbeddedAdminDeliveryConfiguration 已登记该控制器）；POST 重建走 JSON 分支。
            // 动作复用既有 kb.read / config.read / kb.write，零新增 canonical 动作/权限/菜单行。
            new Route("GET", "/admin/kg/status", "config.read"),
            new Route("GET", "/admin/kg/graph", "kb.read"),
            new Route("GET", "/admin/kg/labels", "kb.read"),
            new Route("POST", "/admin/kg/projection/rebuild", "kb.write"),
            new Route("GET", "/knowledge-bases", "kb.list"),
            new Route("POST", "/knowledge-bases", "kb.write"),
            new Route("GET", "/knowledge-bases/{id}", "kb.read"),
            new Route("DELETE", "/knowledge-bases/{id}", "kb.delete"),
            new Route("PUT", "/knowledge-bases/{id}/acl", "kb.acl.manage"),
            new Route("DELETE", "/knowledge-bases/{id}/acl", "kb.acl.manage"),
            new Route("POST", "/knowledge-bases/retrievals", "kb.retrieve"),
            new Route("GET", "/documents/{id}", "document.read"),
            new Route("GET", "/documents/{id}/content", "document.download"),
            // F04 private chat attachments: authenticated gateway only (no public object URLs).
            // Internal handlers (scan-visible, LocalWhitelistHandlerCoverageTest):
            //  POST /internal/ai/v1/chat/attachments          <- ChatAttachmentController.upload
            //  GET  /internal/ai/v1/chat/attachments/{id}/content <- ChatAttachmentController.content
            new Route("POST", "/chat/attachments", "document.upload"),
            new Route("GET", "/chat/attachments/{attachmentId}/content", "document.read"),
            new Route("GET", "/conversations", "conversation.read"),
            new Route("GET", "/conversations/{id}", "conversation.read"),
            new Route("GET", "/conversations/{id}/messages", "conversation.read"),
            new Route("GET", "/conversations/{id}/export", "conversation.export"),
            // WP-034B：F03 会话写入。服务端由**已装配**的 AiResourceController
            // （/internal/ai/v1/conversations/{id}）承接：先功能级 requireFunction 再资源级 requireGrant，
            // 写服务内部再取一次 PrincipalContext 并做 tenant+member 限定，0 行按"不存在"拒绝。
            // G-52：F03「新建会话」原本**没有任何可达端点** —— 会话行只能在聊天时由 touchConversation
            // 副作用创建，而聊天需引擎（门控关）⇒ 一条会话行都造不出来；旧路径 POST /system/session
            // 在未打包的 ruoyi-chat（G-22）⇒ 404。此处放行显式创建。
            // **动作复用 `conversation.rename`（→ 已播种的 `ai:conversation:write`）**，不新增动作/权限行，
            // 与 C13.4 处置引擎四条同形：不新增权限行、不动「26 个规范动作」护栏、零迁移。
            // 创建会话本质就是"会话写"，与改名同属对该聚合的写。
            new Route("POST", "/conversations", "conversation.rename"),
            new Route("PUT", "/conversations/{id}", "conversation.rename"),
            new Route("DELETE", "/conversations/{id}", "conversation.delete"),
            // RW-01（T0 集成；t2r-runtime 交付 ConversationBatchDeleteService/Controller）：
            // F03 普通会话批量删除。内层 handler = /internal/ai/v1/conversations/batch-delete
            // （与 GET/POST/PUT/DELETE /conversations 同一前缀形状，网关转送目标恒为
            //  base + "/internal/ai/v1" + subPath，故内层逐字可命中）。
            // 动作复用 conversation.delete（→ V12 已播种的 ai:conversation:delete），
            // **不新增 canonical 动作、不新增权限行、不新增迁移**（P1CurrentAuthorizationTest
            //  的动作条数护栏不变）。D05 的服务端契约与负例已交付（见 RW-01 报告 §5），
            // 故原"批量多资源授权是计划 §13 待决定项"的前置条件已由 D05 满足。
            // 段级匹配无冲突：本行 2 段，POST /conversations 1 段，
            // POST /conversations/messages/{messageId}/feedback 4 段。
            new Route("POST", "/conversations/batch-delete", "conversation.delete"),
            // W3-5-BE-1（T0 登记，2026-10-06；t3-ingest 交付 AiEmbeddedFeedbackConfiguration 后）：
            // 消息反馈面（T6 workbench 历史页赞/踩）。此前 MessageFeedbackController 内层存在
            // 但不在白名单 ⇒ 网关必然 404（T6 判据钉死的缺口）。
            //
            // 🔴 维护者裁决 §6（2026-10-09 回填）：**提交与取消是两个不同能力，不得共用一个动作。**
            //   POST   /…/feedback → `conversation.rename`（= ai:conversation:write，既有写权限）
            //   DELETE /…/feedback → **`conversation.delete`**（= ai:conversation:delete）
            // 原实现两行都用 `conversation.rename`。裁决原文：「仓内没有『write 天然强于 delete』的
            // 授权继承规则，因此『不是授权绕过，因为要求更强权限』不能作为当前实现的安全结论。」
            // ⇒ 持 write-only 的身份不得能删。不新增 canonical 动作、不新增权限行、不新增迁移
            //（`conversation.delete` 是既有 34 条之一，故 P1CurrentAuthorizationTest 的动作条数护栏不变）。
            //
            // ⚠️ **不能只改本表**：内层 `AiEmbeddedFeedbackConfiguration.FeedbackSurface` 的 scope
            // 复核是各自独立持有的字面量（另一 reactor 的类，不能互相 import）。两侧必须同时改，
            // 且由 `AiEmbeddedFeedbackConfigurationTest` 的**行为**判据钉住
            //（write-only 不能删 / delete-only 能删 / delete-only 不能提交）。
            new Route("POST", "/conversations/messages/{messageId}/feedback", "conversation.rename"),
            new Route("DELETE", "/conversations/messages/{messageId}/feedback", "conversation.delete"),
            // RW-22-R1-R7（T0 登记，2026-10-08）：F17 推荐追问面。内层 handler =
            // /internal/ai/v1/conversations/messages/{messageId}/recommended-questions，
            // 由 RecommendedQuestionController（类级 @RequestMapping("/internal/ai/v1")）承接，
            // 装配由 AiEmbeddedRecommendationConfiguration 显式登记（三开关：
            // ai.integration.enabled + transport=local + p2.enabled + ai.model.enabled）。
            //
            // 此前该面三件套缺两件：控制器不是 bean（com.nageoffer.** 不在 platform 扫描根下）、
            // 白名单零命中 ⇒ 客户端必然 404；而它的类级前缀在 RW-22-R1 已归位、信封已换成整数
            // ApiEnvelope、身份已改用 PrincipalContext.require()（原 UserContext.getUserId()
            // 在内嵌态恒为 null，会让 user_id 谓词静默失效）。本行是第三件。
            //
            // 动作 = `conversation.rename`（→ 已播种的 `ai:conversation:write`），**不是 `conversation.read`**。
            // R6 局部规格原写"复用 conversation.read"，其产品理由（派生自可读消息、落库只是缓存）
            // 回答的是"为什么可以复用"，**没有回答"读权限是否应允许写"**。
            // R（RW-29-R7）判定为 **REVIEW_FAIL**：该 POST 在缓存未命中时
            // **①调用真实 LLM generator（计费外呼）②UPDATE 落库**；若只凭 `ai:conversation:read`
            // 即可触发，等于**用读权限做写、并用读权限触发外呼**。
            // T0 据此重裁为既有写动作，与本文件上方
            // `POST /conversations/messages/{messageId}/feedback`（同样是"对会话消息的写"）**同一口径**：
            // **不新增 canonical 动作、不新增权限行、不新增迁移**（权威值以
            // P1CurrentAuthorizationTest:245/:252 为准：旧 34 条逐项保留 +
            // F13-SLICE-1 批准的 flow.* 4 条 + F13-A1 批准的 flow.node.write 1 条 = 39；
            // 本条路由不涉及任何动作的增改——再新增动作须走维护者立项批注 + 三断言同步）。
            // 判据两侧都钉住：持 `ai:conversation:write` 才 200；**只持 `ai:conversation:read` 必须 403**。
            // 未采用"读权限时只读缓存、不落库"的折中：那会让同一端点出现两种成功形状，
            // 与 RW-22-R1"同一控制器不分裂成两种成功形状"的既定纪律冲突。
            // 写面：**不铸** GET 交付回执（网关只在字节分支释放许可，JSON 分支铸造 = 许可泄漏）。
            new Route("POST", "/conversations/messages/{messageId}/recommended-questions", "conversation.rename"),
            // WP-034：F10 Agent 会话面。此前 `/agent/v1/**` 完全没有白名单路由，
            // 所以客户端**无法**经 `/api/ai/v1` 到达 WP-033B 交付的会话面——
            // 那是"服务端可装配"与"客户端可访问"之间的缺口。
            //
            // 逐条登记（**不用通配 `/agent/v1/**`**，也不做根 Controller 扫描）：
            // 通配会把同一前缀下任何将来新增的控制器一起放行，等于取消白名单。
            //
            // 动作复用会话读/写/删除，不新增权限行：Agent 会话与普通会话是同一
            // "用户自己的会话"语义（`ai:conversation:read/write/delete`），
            // 且 AgentConversationServiceImpl 自身按 tenant+user 限定作用域。
            //
            // F10-A1（2026-10-10）补齐两条（原"有意不放行 batch-delete"的口径已由 D05 消灭）：
            //   create：镜像 G-52 general 路径 `POST /conversations` 的映射
            //     （→ conversation.rename；内层 ConversationSurface.create 已在，
            //     归属只取 PrincipalContext，请求体只有 title）；
            //   batch-delete：镜像 RW-01/F03 general 路径 `POST /conversations/batch-delete`
            //     的映射（→ conversation.delete）。"批量多资源授权是计划 §13 待决定项"
            //     的前置条件已由 D05（批量授权决定 + 服务端契约与负例族）消灭；agent 路径
            //     复用同一受控服务契约 ConversationBatchDeleteService（集合 ≤100 /
            //     空集合与重复 ID 拒绝 / 逐资源 permit / 单事务），故按同一先例放行，
            //     零迁移、零新权限行。
            new Route("GET", "/agent/v1/conversations", "conversation.read"),
            new Route("GET", "/agent/v1/conversations/{id}/messages", "conversation.read"),
            new Route("POST", "/agent/v1/conversations", "conversation.rename"),
            new Route("PUT", "/agent/v1/conversations/{id}/title", "conversation.rename"),
            new Route("DELETE", "/agent/v1/conversations/{id}", "conversation.delete"),
            new Route("POST", "/agent/v1/conversations/batch-delete", "conversation.delete"),
            // WP-033 / C13.4（T0 登记，2026-10-06）：Agent **引擎**面。经源码核实四条真实端点
            // 早已存在（AgentChatController:94/112/129、AgentMetaController:85），但此前
            // 一条都不在白名单里 —— 与本文件上一段记录的会话面缺口同类：
            // "服务端可装配"不等于"客户端可访问"。C13 已把它们接到内层可达前缀之下
            // （类级 @RequestMapping("/internal/ai/v1")，与 RunController/AiResourceController/
            // UploadController/AgentActionController 同形），故内层 handler 现在真实存在。
            //
            // 只有**三条 JSON** 在此登记：
            new Route("POST", "/agent/v1/stop", "run.cancel"),
            new Route("GET", "/agent/v1/meta", "agent.execute"),
            new Route("GET", "/agent/v1/agents", "agent.execute"),
            // 另两条是 text/event-stream（GET /agent/v1/chat、POST /agent/v1/chat/confirm），
            // **刻意不在此登记**：本通用转发有 2s/2MiB 上限且不做 SSE，登记了只会超时或缓冲失败。
            // 它们由 AiGatewayStreamController 逐条精确映射（专用流式受限传输，先于 catch-all 生效）。
            //
            // 动作与权限全部**复用既有已播种行**，不新增 canonical 动作、不新增迁移：
            //   run.cancel    -> ai:run:cancel     (V5)
            //   agent.execute -> ai:agent:execute  (V6)
            // 权威值以 P1CurrentAuthorizationTest 为准：旧 34 条逐项集合相等 +
            // F13-SLICE-1 批准的 flow.* 4 条 + F13-A1 批准的 flow.node.write 1 条 +
            // F11-A1 批准的 skill.* 4 条 + F06:1/2-A 批准的 ingestion.run 1 条 = 44。
            // （本轮由 F06:1/2-A 显式批准新增**第 44 个动作** `ingestion.run`；
            //   再往上新增（第 45 个）仍需具名批准。）
            // （注：本条注释曾长期写作"26"、后写作"34"并引用过期行号 209，均为过期信息；
            //   此处按实测权威更正并保留说明。）
            new Route("GET", "/memories", "memory.read"),
            new Route("GET", "/runs/{id}", "run.get"),
            new Route("GET", "/runs/{id}/event-records", "run.events"),
            // P2：正式受理/生命周期/专用流与上传/文档
            new Route("POST", "/runs", "run.submit"),
            new Route("POST", "/runs/{id}/cancel", "run.cancel"),
            new Route("POST", "/runs/{id}/resume", "run.resume"),
            new Route("GET", "/runs/{id}/actions", "run.get"),
            new Route("POST", "/runs/{id}/approvals", "run.approve"),
            new Route("GET", "/runs/{id}/reconciliations/{actionId}", "run.reconcile"),
            new Route("POST", "/runs/{id}/reconciliations/{actionId}/query", "run.reconcile"),
            new Route("GET", "/runs/{id}/events", "run.stream"),
            new Route("POST", "/documents/uploads", "document.upload"),
            new Route("POST", "/documents/{id}/ingestions", "document.ingest"),
            new Route("POST", "/documents/{id}/tombstone", "kb.delete"),
            new Route("GET", "/documents/{id}/meta", "document.read"),
            new Route("GET", "/documents/{id}/source", "document.download"),
            new Route("GET", "/knowledge-bases/{id}/documents", "document.list"),
            // W4-9：F09 Agent 目录（8 handler）。内层 handler 落在
            // /internal/ai/v1/agent-catalog/**（由 AiEmbeddedAgentCatalogConfiguration 装配）。
            // 逐条登记、不用通配，与本文件上文"不登记 /agent/v1/**"的纪律一致；全部为 JSON。
            // 每个 action 的权限串见 AiCanonicalAction，权限行由 V27__agent_catalog_permissions.sql 播种。
            // F11（Skills）曾因闭包经 IntentNodeRegistry → DefaultIntentClassifier →
            // LLMService/PromptTemplateLoader/IntentTreeCacheManager 未装配（task-13 / W4-T0-46）
            // 而**不登记** /agent-catalog/agent-skills 的任何路径；
            // 该缺口已由 S2-F11-A1 接管——7 条见本表末尾的 agent-skills 段（同一前缀家族）。
            new Route("GET", "/agent-catalog/agents", "agent.list"),
            new Route("GET", "/agent-catalog/agents/export", "agent.read"),
            new Route("GET", "/agent-catalog/agents/{id}", "agent.read"),
            new Route("POST", "/agent-catalog/agents", "agent.write"),
            new Route("PUT", "/agent-catalog/agents/{id}", "agent.write"),
            new Route("DELETE", "/agent-catalog/agents/{id}", "agent.delete"),
            new Route("POST", "/agent-catalog/agents/{id}/activate", "agent.activate"),
            new Route("GET", "/agent-catalog/agents/{id}/prompts", "agent.read"),
            new Route("PUT", "/agent-catalog/agents/{id}/prompts/{slotKey}", "agent.write"),
            new Route("GET", "/agent-catalog/agents/prompt-slots/{slotKey}/default", "agent.read"),
            // RW-06（T2m，T0 集成；t2m-models 交付 RuntimeCatalogController）：
            // 运行配置目录（模型 / 提供方 / 档位 / 参数 / embedding 维度 / 限额）。
            // 内层 handler = RuntimeCatalogController（类级 /internal/ai/v1/runtime-config）。
            // 动作复用既有三条（config.read / config.publish / config.revoke）：
            // **不新增 canonical 动作、不新增权限行、不新增迁移**；既有四条
            // /runtime-config/revisions* 路由保持原样不动。
            // 段数不冲突：GET /runtime-config/revisions（列表，2 段）与既有
            // GET /runtime-config/revisions/{revisionId}（3 段）形状不同；
            // POST .../{revisionId}/catalog 是"给已发布不可变版本附加档位事实"（write-once），
            // 与 POST .../{revisionId}/rollback（追加新版本）是两件事，语义见 RW-06 报告。
            new Route("GET", "/runtime-config/catalog", "config.read"),
            new Route("GET", "/runtime-config/settings", "config.read"),
            new Route("GET", "/runtime-config/revisions", "config.read"),
            new Route("POST", "/runtime-config/revisions/{revisionId}/catalog", "config.publish"),
            // RW-04-R1（T3 交付，T0 集成）：知识管理**分块面**的内层 handler
            // = KnowledgeChunkController（类级 /internal/ai/v1，公开前缀 /api/ai/v1）。
            // KB 面与文档面当时闭包未闭合（FileStorageService→ObjectStorageClient/凭据；
            // 文档面还要 ParserRegistry/IngestionEngine 等），由
            // AiEmbeddedKnowledgeAdminConfiguration 默认关闭（ai.embedded.knowledge-admin.full
            // =false），故 RW-04 期此处只登记分块面 6 条。
            // **S2-F05-A1 起该闭包已闭合、开关在 shipped 内嵌档案打开**，KB 面与文档面
            // 的 17 条随本段一并放行（见下方登记），登记即"放行＋有人接"成对成立。
            // 动作复用既有集合（document.read / kb.write），**不新增 canonical 动作、
            // 不新增权限行、不新增迁移**。段数互不遮蔽：{chunk-id}/enable 为 6 段、
            // batch-enable 为 5 段，与 5 段的 {chunk-id} 形状不同。
            new Route("GET", "/knowledge-base/docs/{docId}/chunks", "document.read"),
            new Route("POST", "/knowledge-base/docs/{docId}/chunks", "kb.write"),
            new Route("PUT", "/knowledge-base/docs/{docId}/chunks/{chunkId}", "kb.write"),
            new Route("DELETE", "/knowledge-base/docs/{docId}/chunks/{chunkId}", "kb.write"),
            new Route("PATCH", "/knowledge-base/docs/{docId}/chunks/{chunkId}/enable", "kb.write"),
            new Route("PATCH", "/knowledge-base/docs/{docId}/chunks/batch-enable", "kb.write"),
            // S2-F05-A1（2026-10-10）：知识管理**知识库面 5 条 + 文档面 12 条 = 17 条**。
            // 内层 handler = KnowledgeBaseController / KnowledgeDocumentController
            // （类级 /internal/ai/v1；由 AiEmbeddedKnowledgeAdminConfiguration.FullAdmin 装配，
            //  门控 ai.embedded.knowledge-admin.full=true，shipped 内嵌档案已显式打开）。
            //
            // **逐条清点（本表以控制器映射方法实测为准）**：KB 面 5 = GET 列表/GET 详情/
            // POST 创建/PUT 重命名/DELETE 删除；文档面 12 = ingestion-spec-schema/upload/
            // chunk(重解析)/delete/详情/update/分页/search/enable/chunk-logs/preview/file。
            // 树内旧注释的"15 条路由"是 RW-04-R8 期口径（当时文档面 10/11 条），
            // 与实测 17 条不符，**以本表逐条登记为准**。
            //
            // 动作映射（全部为既有 canonical 动作，零新增；权限行 V4 7101-7108 + V5 7118/7119
            // 已全部播种 ⇒ 零迁移）：
            //   kb.list / kb.write / kb.read / kb.delete ← 与复数资源面 /knowledge-bases/** 同口径；
            //   document.list → ai:document:read（复用读，AiCanonicalAction:36 既有映射）；
            //   document.upload / document.ingest ← V5 已播种；
            //   文档删除复用 kb.delete（与 POST /documents/{id}/tombstone 同口径——
            //   仓内没有 document.delete 这个动作，且不新增第 44 个动作）；
            //   摄取 schema 复用 config.read（控制器自铸许可用的也是它）。
            //
            // 段数与顺序（matchRoute 为**声明序首个命中**）：三条 3 段字面量
            // （docs/ingestion-spec-schema、docs/search、{kbId}/docs）必须排在同为 3 段的
            // 变量形 docs/{docId} 之前，否则 search/schema 会被 {docId} 吃掉；
            // 4 段的 chunk-logs/preview/file、6 段的 enable 与既有 5/6 段分块面互不遮蔽。
            //
            // GET 的交付回执由**控制器自铸**（KnowledgeBaseController.queryKnowledgeBase/
            // pageQuery、KnowledgeDocumentController.permitJson/file），**不并入**
            // AiEmbeddedAdminDeliveryConfiguration 的 advice —— 两处都铸就是双铸（契约回执泄漏）。
            //
            // 未达面（B / 显式 NOT_RUN，不在此处冒充）：
            // （multipart 上传 POST /knowledge-base/{kbId}/docs/upload 自 F05:1 起已由专用流
            //   通道承接，见下方该 route 的注记——不再是未达面）
            // 真实 S3/OSS（rag.storage.type=s3|oss，F22 op3）、
            // MQ 承载的两条事务消息操作（KB 删除 / 文档重解析：内嵌无 broker 时显式 503）。
            new Route("GET", "/knowledge-base/docs/ingestion-spec-schema", "config.read"),
            new Route("GET", "/knowledge-base/docs/search", "document.list"),
            new Route("GET", "/knowledge-base/docs/{docId}", "document.read"),
            new Route("GET", "/knowledge-base/docs/{docId}/chunk-logs", "document.read"),
            new Route("GET", "/knowledge-base/docs/{docId}/preview", "document.read"),
            new Route("GET", "/knowledge-base/docs/{docId}/file", "document.download"),
            // F05:1 薄适配（2026-10-16）：该 admin 上传已于运行期由专用流通道承接——
            // AiGatewayStreamController 的精确 @PostMapping("/knowledge-base/{kbId}/docs/upload")
            // 先于本 catch-all 生效，经 multipart 旁路排除（GatewayMultipartConfiguration /
            // GatewayRawUploadFilter **成对**登记该路径）+ local MultipartTargetRequest 直调内层，
            // 原始 multipart（文件 + 表单字段 sourceType/sourceLocation/processMode/pipelineId/
            // ingestionSpec/scheduleCron/scheduleEnabled）整包到达内层 @ModelAttribute，
            // 内层零形状改动（200 + 整数 code + KnowledgeDocumentVO）。
            // ⚠️ 本行 route 登记**保留不动**：它是该 path/动作在路由表中的权威登记
            //    （动作 document.upload 与专用流同一把钥匙，零新增动作/权限行/迁移）；
            //    运行期该路径由专用流精确映射命中，不再落到下面 catch-all 的转发分支。
            // **不要**用"再包一层 StandardMultipartHttpServletRequest"去修 catch-all 分支：
            //    实测表单字段会变成 ApplicationPart 形态导致绑定 400（red/a1-fixfield-web/，复核 F1）。
            new Route("POST", "/knowledge-base/{kbId}/docs/upload", "document.upload"),
            new Route("POST", "/knowledge-base/docs/{docId}/chunk", "document.ingest"),
            new Route("PUT", "/knowledge-base/docs/{docId}", "kb.write"),
            new Route("DELETE", "/knowledge-base/docs/{docId}", "kb.delete"),
            new Route("PATCH", "/knowledge-base/docs/{docId}/enable", "kb.write"),
            new Route("GET", "/knowledge-base/{kbId}/docs", "document.list"),
            new Route("GET", "/knowledge-base", "kb.list"),
            new Route("POST", "/knowledge-base", "kb.write"),
            new Route("GET", "/knowledge-base/{kbId}", "kb.read"),
            new Route("PUT", "/knowledge-base/{kbId}", "kb.write"),
            new Route("DELETE", "/knowledge-base/{kbId}", "kb.delete"),
            // R6: explicit administrative surfaces; GET replies carry P2 delivery receipts.
            // KB/document full admin 已由 S2-F05-A1 闭合放行（上方 17 条）。
            new Route("GET", "/intent-tree/trees", "config.read"),
            new Route("POST", "/intent-tree", "config.publish"),
            new Route("PUT", "/intent-tree/{id}", "config.publish"),
            new Route("DELETE", "/intent-tree/{id}", "config.publish"),
            new Route("POST", "/intent-tree/batch/enable", "config.publish"),
            new Route("POST", "/intent-tree/batch/disable", "config.publish"),
            new Route("POST", "/intent-tree/batch/delete", "config.publish"),
            new Route("GET", "/mappings", "config.read"),
            new Route("GET", "/mappings/{id}", "config.read"),
            new Route("POST", "/mappings", "config.publish"),
            new Route("PUT", "/mappings/{id}", "config.publish"),
            new Route("DELETE", "/mappings/{id}", "config.publish"),
            new Route("GET", "/sample-questions/random", "kb.read"),
            new Route("GET", "/sample-questions", "kb.read"),
            new Route("GET", "/sample-questions/{id}", "kb.read"),
            new Route("POST", "/sample-questions", "config.publish"),
            new Route("PUT", "/sample-questions/{id}", "config.publish"),
            new Route("DELETE", "/sample-questions/{id}", "config.publish"),
            // S2-F06-A1（2026-10-10）：F06 摄取**管线 CRUD** 5 条。内层 handler =
            // IngestionPipelineController（类级 /internal/ai/v1，由
            // AiEmbeddedIngestionConfiguration 显式装配、in AutoConfiguration.imports）。
            // 逐条登记（不用通配 `/ingestion/**`）：通配会把同一前缀下任何将来新增的端点
            // （尤其**未装配**的任务面 /ingestion/tasks**）一起放行，等于取消白名单——
            // 登记了却没有 handler 会让 LocalWhitelistHandlerCoverageTest 的
            // "放行了但没人接"护栏变红。
            // 动作复用既有两条（config.read / config.publish）：**不新增 canonical 动作、
            // 不新增权限行、不新增迁移**（P1CurrentAuthorizationTest 的动作条数护栏不变）。
            // 两条 GET 在网关走字节分支，故内层同时给整数 ApiEnvelope 与两个回执头
            // （控制器已并入 AiEmbeddedAdminDeliveryConfiguration assignableTypes，config.read）。
            // 段数不冲突：GET/POST /ingestion/pipelines 为 2 段，
            // GET/PUT/DELETE /ingestion/pipelines/{id} 为 3 段。
            new Route("GET", "/ingestion/pipelines", "config.read"),
            new Route("GET", "/ingestion/pipelines/{id}", "config.read"),
            new Route("POST", "/ingestion/pipelines", "config.publish"),
            new Route("PUT", "/ingestion/pipelines/{id}", "config.publish"),
            new Route("DELETE", "/ingestion/pipelines/{id}", "config.publish"),
            // F06:1/2（canonical 摄取任务面，2026-10-15）。六行逐条登记；内层 handler =
            // IngestionTaskRuntimeController（类级 /internal/ai/v1，由 AiEmbeddedIngestionConfiguration
            // 显式装配）。三条 GET 在网关走字节分支，内层同时给整数 ApiEnvelope 与两个回执头
            // （回执由控制器自铸 DeliveryPermits）。段数不冲突：/ingestion/tasks 为 2 段，
            // /ingestion/tasks/{id}[/nodes|/cancel|/retry] 为 3..4 段。
            new Route("GET", "/ingestion/tasks", "run.get"),
            new Route("GET", "/ingestion/tasks/{id}", "run.get"),
            new Route("GET", "/ingestion/tasks/{id}/nodes", "run.get"),
            new Route("POST", "/ingestion/tasks", "run.submit"),
            new Route("POST", "/ingestion/tasks/{id}/cancel", "run.cancel"),
            new Route("POST", "/ingestion/tasks/{id}/retry", "run.submit"),
            new Route("GET", "/dashboard/overview", "run.get"),
            new Route("GET", "/dashboard/performance", "run.get"),
            new Route("GET", "/dashboard/trends", "run.get"),
            new Route("GET", "/rag/traces/runs", "run.get"),
            new Route("GET", "/rag/traces/runs/{traceId}", "run.get"),
            new Route("GET", "/rag/traces/runs/{traceId}/nodes", "run.events"),
            new Route("GET", "/biz-change-logs", "run.get"),
            // F18:2（2026-10-18）：成员端读面。动作复用 `run.get`（成员读自己的变更日志，
            // **不新增 canonical 动作、不新增权限行、不新增迁移**）。
            // ⚠️ 声明序：两段字面量 `/biz-change-logs/mine` 必须排在
            // `GET /biz-change-logs/{id}` **之前**——matchRoute 取声明序首个命中，
            // 否则 "mine" 会被 {id} 当成管理端详情 id 吃掉（admin 语义被静默改写）。
            // 3 段 `/biz-change-logs/mine/{id}` 与 2 段 `{id}` 不同形，但仍紧随字面量登记，
            // 使成员面在表中成对可读。
            new Route("GET", "/biz-change-logs/mine", "run.get"),
            new Route("GET", "/biz-change-logs/mine/{id}", "run.get"),
            new Route("GET", "/biz-change-logs/{id}", "run.get"),
            new Route("GET", "/rag/eval", "kb.retrieve"),
            // F13-SLICE-1（AIFlow 工作流定义面）。逐条登记，不用通配 `/flows/**`
            // —— 通配会把同一前缀下任何将来新增的端点一起放行，等于取消白名单。
            // 内层 handler = FlowWorkflowController（类级 /internal/ai/v1），
            // 由 AiEmbeddedFlowConfiguration 显式装配、in AutoConfiguration.imports。
            // 四条动作 flow.list/read/write/delete 默认授予全为"否"。
            // 两条 GET 在网关走字节分支，故内层必须同时给整数 ApiEnvelope 与两个回执头。
            // F13:2（2026-10-18）：AIFlow 运行面 runtime 视图 5 条（action flow.run.read / flow.delete）。
            // 内层 handler = FlowRuntimeController（类级 /internal/ai/v1；由
            // AiEmbeddedFlowRunConfiguration 显式装配，随 AutoConfiguration.imports 生效）。
            // ⚠️ 声明序（matchRoute 取声明序首个命中）：
            //   GET /flows/runs（2 段字面量）必须在 GET /flows/{uuid} **之前**；
            //   GET /flows/runs/{runtimeUuid}（3 段）必须在 GET /flows/{uuid}/nodes 之前
            //   （否则 /flows/runs/nodes 会被变量形吃成"读图"）。
            // 三条 GET 在网关走字节分支，内层控制器先取数后自铸整数 ApiEnvelope + 两个回执头；
            // 两条写面走 JSON 分支、内层不铸回执。
            // SSE（POST /flows/{uuid}/run、GET /flows/runs/{runtimeUuid}/events）**不在此登记**：
            // 它们由 AiGatewayStreamController 精确映射，先于 catch-all 生效。
            new Route("GET", "/flows/runs", "flow.run.read"),
            new Route("GET", "/flows/runs/{runtimeUuid}", "flow.run.read"),
            new Route("GET", "/flows/runs/{runtimeUuid}/nodes", "flow.run.read"),
            new Route("POST", "/flows/runs/clear", "flow.delete"),
            new Route("DELETE", "/flows/runs/{runtimeUuid}", "flow.delete"),
            new Route("GET", "/flows", "flow.list"),
            // F13-M1（2026-10-18，维护者 B 批项 5）：发布/公开面 6 条。
            // 公开语义写死：同租户、跨用户、只读；跨租户永不因 is_public 可见
            // （服务层谓词 = is_public=1 AND is_enable=1 AND is_deleted=0 AND tenant_id=当前）。
            //
            // ⚠️ 声明序：四条 /flows/public/** 必须排在既有 GET /flows/{uuid} 与
            // GET /flows/{uuid}/nodes **之前**——matchRoute 取声明序首个命中，
            // 否则 "public" 会被 {uuid} 当成工作流 uuid 吃掉，公开面被静默改写成
            // 「我的工作流详情 / 图读」语义。公开详情 404 由控制器直接返回
            // （同文案「工作流不存在」，私有/停用/软删/跨租户不泄露存在性）。
            //
            // 动作映射：搜索 → flow.list；操作符 → flow.list（静态目录，无 DB）；
            // 公开组件库 → flow.read；公开详情 → flow.read；
            // 启停/公开开关 → **flow.publish**（→ V34 播种的 ai:flow:publish，
            // 租户级、不进 PLATFORM_ADMIN_ACTIONS，与 flow.node.write 同口径）。
            // 三条 GET（搜索/操作符/组件库）与公开详情都在网关走字节分支，
            // 控制器必须自铸整数 ApiEnvelope + 两个交付回执头；两条写面走 JSON 分支、
            // 内层**不铸**回执（JSON 分支铸造 = 永久 ACTIVE 许可泄漏）。
            new Route("GET", "/flows/public/search", "flow.list"),
            new Route("GET", "/flows/public/operators", "flow.list"),
            new Route("GET", "/flows/public/component/list", "flow.read"),
            new Route("GET", "/flows/public/{uuid}", "flow.read"),
            new Route("POST", "/flows/{uuid}/enabled", "flow.publish"),
            new Route("POST", "/flows/{uuid}/public", "flow.publish"),
            new Route("GET", "/flows/{uuid}", "flow.read"),
            new Route("POST", "/flows", "flow.write"),
            new Route("PUT", "/flows/{uuid}", "flow.write"),
            new Route("DELETE", "/flows/{uuid}", "flow.delete"),
            // F13-A1（2026-10-11，维护者立项批注「spec开始立项」）：op0 图 CRUD 12 条。
            // 内层 handler = FlowGraphController（类级 /internal/ai/v1；由
            // AiEmbeddedFlowConfiguration 显式装配、随 AutoConfiguration.imports 生效），
            // 复用 V9 既有四表（节点/边/组件+工作流；**零表迁移**）。
            // 动作映射（本批**恰好一条新动作**，获批增量见
            // P1CurrentAuthorizationTest 的 F13-A1 增量集）：
            //   读图（三条 GET）→ 既有 `flow.read`（读图即读工作流定义）；
            //   写图（POST/PUT/DELETE）→ 新动作 `flow.node.write`（→ `ai:flow:design`，
            //   权限行由 V32 注册，menu_id 7203，默认不分配）。
            // 逐条登记不用通配；三条 GET 在网关走字节分支，故内层必须同时给整数
            // ApiEnvelope 与两个回执头（控制器自铸，动作=flow.read）。
            // 段数互不遮蔽：/flows/{uuid}/nodes 为 4 段、{nodeUuid} 为 5 段，与既有
            // 3 段 /flows/{uuid} 不同形；/flow-components 两条为 1/2 段。
            new Route("GET", "/flows/{uuid}/nodes", "flow.read"),
            new Route("POST", "/flows/{uuid}/nodes", "flow.node.write"),
            new Route("PUT", "/flows/{uuid}/nodes/{nodeUuid}", "flow.node.write"),
            new Route("DELETE", "/flows/{uuid}/nodes/{nodeUuid}", "flow.node.write"),
            new Route("GET", "/flows/{uuid}/edges", "flow.read"),
            new Route("POST", "/flows/{uuid}/edges", "flow.node.write"),
            new Route("PUT", "/flows/{uuid}/edges/{edgeUuid}", "flow.node.write"),
            new Route("DELETE", "/flows/{uuid}/edges/{edgeUuid}", "flow.node.write"),
            new Route("GET", "/flow-components", "flow.read"),
            new Route("POST", "/flow-components", "flow.node.write"),
            new Route("PUT", "/flow-components/{uuid}", "flow.node.write"),
            new Route("DELETE", "/flow-components/{uuid}", "flow.node.write"),
            // S2-F11-A1（2026-10-11）：Skills 管理面（7 handler → 4 动作）。
            // 内层 handler = AgentSkillController（类级 /internal/ai/v1/agent-catalog，
            // 三件套已归位：前缀＋整数 ApiEnvelope＋GET 交付回执），由
            // AiEmbeddedAgentSkillConfiguration 显式装配（随 S1 引擎闭包门——admin 服务依赖
            // IntentNodeRegistry，闭包未闭合不装、fail-closed，与 Flow 面同纪律）。
            // 逐条登记、不用通配 `/agent-catalog/agent-skills/**`——通配会把同一前缀下
            // 任何将来新增的端点一起放行，等于取消白名单。
            // 动作映射（Spec §6-P3）：list/tool-options → skill.list；detail → skill.read；
            // create/update/toggle → skill.write；delete → skill.delete。
            // 四条动作**全部**进 PLATFORM_ADMIN_ACTIONS 第二道门（Spec §6-P4 默认"进"，
            // T0 合并终裁维持"进"、从严方向），
            // 权限行由 V33 注册（沿用 V27 预留 7137-7140 动作行 + 7142 页面 C 行，不占新号）。
            // 三条 GET 在网关走字节分支，故内层同时给整数 ApiEnvelope 与两个回执头；
            // 写面走 JSON 分支、内层**不铸**回执（JSON 分支铸造 = 永久 ACTIVE 许可泄漏）。
            // 段级匹配为声明序首个命中：GET tool-options（3 段字面量）必须排在
            // GET {id}（3 段变量）之前，否则 tool-options 会被 {id} 吃掉。
            new Route("GET", "/agent-catalog/agent-skills", "skill.list"),
            new Route("GET", "/agent-catalog/agent-skills/tool-options", "skill.list"),
            new Route("GET", "/agent-catalog/agent-skills/{id}", "skill.read"),
            new Route("POST", "/agent-catalog/agent-skills", "skill.write"),
            new Route("PUT", "/agent-catalog/agent-skills/{id}", "skill.write"),
            new Route("DELETE", "/agent-catalog/agent-skills/{id}", "skill.delete"),
            new Route("POST", "/agent-catalog/agent-skills/{id}/enabled", "skill.write"),
            // F11:4 技能包导出（GET 字节分支 + 交付回执）；import 走 AiGatewayStreamController
            // 专用上传通道（精确映射先于 catch-all），此处刻意不登记 import（避免死路由）。
            new Route("GET", "/agent-catalog/agent-skills/{id}/export", "skill.read"),
            // F12-M1（2026-10-18，维护者批 B 批 G-22）：MCP 管理面 18 条，逐条登记、禁止通配。
            // 内层 handler = McpAdminController（类级 /internal/ai/v1/mcp），由
            // AiEmbeddedMcpAdminConfiguration 显式装配；三件套已归位（前缀＋整数 ApiEnvelope＋
            // GET 交付回执）。动作映射见 brief §2.3：列表/全部/选项/市场工具列表 → mcp.list；
            // 详情/导出 → mcp.read；启停/刷新/加载/批量加载 → mcp.write；删除 → mcp.delete；
            // 连接测试 → mcp.test。五条动作全部进 PLATFORM_ADMIN_ACTIONS 第二道门，
            // 权限行由 V39 注册（7144-7148，默认不分配）。
            // 声明序（matchRoute 取声明序首个命中）：同为 3 段的字面量 all/export/options
            // 必须排在 {id} 之前，否则会被当成 id 吃掉；4 段 {id}/tools 与 3 段 {id} 不同形。
            // 六条 GET 在网关走字节分支，故内层必须同时给整数 ApiEnvelope 与两个回执头；
            // 写面走 JSON 分支、内层不铸回执。
            new Route("GET", "/mcp/markets", "mcp.list"),
            new Route("GET", "/mcp/markets/all", "mcp.list"),
            new Route("GET", "/mcp/markets/export", "mcp.read"),
            new Route("GET", "/mcp/markets/{id}", "mcp.read"),
            new Route("GET", "/mcp/markets/{id}/tools", "mcp.list"),
            new Route("POST", "/mcp/markets/{id}/refresh", "mcp.write"),
            new Route("POST", "/mcp/markets/{id}/status", "mcp.write"),
            new Route("DELETE", "/mcp/markets/{ids}", "mcp.delete"),
            new Route("GET", "/mcp/tools", "mcp.list"),
            new Route("GET", "/mcp/tools/all", "mcp.list"),
            new Route("GET", "/mcp/tools/options", "mcp.list"),
            new Route("GET", "/mcp/tools/export", "mcp.read"),
            new Route("GET", "/mcp/tools/{id}", "mcp.read"),
            new Route("POST", "/mcp/tools/{id}/status", "mcp.write"),
            new Route("POST", "/mcp/tools/{id}/test", "mcp.test"),
            new Route("DELETE", "/mcp/tools/{ids}", "mcp.delete"),
            // 3 段字面量 batch-load 排在 4 段变量形 {id}/load 之前（literal-before-variable）
            new Route("POST", "/mcp/market-tools/batch-load", "mcp.write"),
            new Route("POST", "/mcp/market-tools/{id}/load", "mcp.write"),
            // F20-A（2026-10-18，维护者「F20快做」＋ D1 批）：编程助手 Harness 面 22 条 JSON 路由。
            // 内层 handler = CodingHarnessController（类级 /internal/ai/v1/coding），由
            // AiEmbeddedCodingConfiguration 显式装配。动作映射（brief §2）：
            //   读（会话/消息/运行/事件/模型/工作区/文件读）→ coding.use
            //   写（建会话/置顶/删除/恢复/建运行/输入/取消/恢复运行/文件写）→ coding.write
            //   审批与计划（工具审批 resolve / plan approve / plan revision）→ coding.approve
            //   命令（execute_process）→ coding.command
            // 权限行由 V41__coding_harness_permissions.sql 注册（7204-7207，默认不分配；
            // 租户级 ⇒ 不进 PLATFORM_ADMIN_ACTIONS）。
            // **两条 SSE 不在此登记**：POST /coding/chat 与 GET .../events/stream 由
            // AiGatewayStreamController 精确映射（先于 catch-all 生效）；登记了也不会被
            // 本通用转发承接，只会变成不可达死路由。
            // 段数互不遮蔽：/coding/sessions 2 段；{sessionId} 3 段；
            // {sessionId}/runs 与 {sessionId}/messages 4 段；runs/{runId} 5 段；
            // .../events 6 段；.../inputs|cancel|resume 与 plan/approve|revision 7 段；
            // approvals/{approvalId}/resolve 8 段。
            // 八条 GET 在网关走字节分支 ⇒ 控制器自铸 DeliveryPermits 回执头；
            // 写面走 JSON 分支，内层不铸回执（铸造 = 永久 ACTIVE 许可泄漏）。
            new Route("POST", "/coding/sessions", "coding.write"),
            new Route("GET", "/coding/sessions", "coding.use"),
            new Route("GET", "/coding/sessions/{sessionId}", "coding.use"),
            new Route("PUT", "/coding/sessions/{sessionId}/pin", "coding.write"),
            new Route("DELETE", "/coding/sessions/{sessionId}", "coding.write"),
            new Route("POST", "/coding/sessions/{sessionId}/restore", "coding.write"),
            new Route("GET", "/coding/sessions/{sessionId}/messages", "coding.use"),
            new Route("POST", "/coding/sessions/{sessionId}/runs", "coding.write"),
            new Route("GET", "/coding/sessions/{sessionId}/runs", "coding.use"),
            new Route("GET", "/coding/sessions/{sessionId}/runs/{runId}", "coding.use"),
            new Route("GET", "/coding/sessions/{sessionId}/runs/{runId}/events", "coding.use"),
            new Route("POST", "/coding/sessions/{sessionId}/runs/{runId}/inputs", "coding.write"),
            new Route("POST", "/coding/sessions/{sessionId}/runs/{runId}/cancel", "coding.write"),
            new Route("POST", "/coding/sessions/{sessionId}/runs/{runId}/resume", "coding.write"),
            new Route("POST",
                    "/coding/sessions/{sessionId}/runs/{runId}/approvals/{approvalId}/resolve",
                    "coding.approve"),
            new Route("POST", "/coding/sessions/{sessionId}/runs/{runId}/plan/approve",
                    "coding.approve"),
            new Route("POST", "/coding/sessions/{sessionId}/runs/{runId}/plan/revision",
                    "coding.approve"),
            new Route("GET", "/coding/models", "coding.use"),
            new Route("GET", "/coding/workspace", "coding.use"),
            new Route("GET", "/coding/file", "coding.use"),
            new Route("PUT", "/coding/file", "coding.write"),
            new Route("POST", "/coding/command", "coding.command"));

    private final CurrentPrincipalResolver principalResolver;
    private final ObjectProvider<PlatformIdentitySource> identitySource;
    /**
     * 委托签名密钥。仅 {@code transport=http} 需要存在（生产装配 fail-fast 保证）；
     * {@code transport=local}（E3/C3 内嵌同进程转送）下不铸造委托凭证，本依赖可为空。
     */
    private final ObjectProvider<ProductionSigningKeySource> signingKeys;
    private final AiGatewayClient client;
    private final AiIntegrationProperties properties;

    public AiGatewayController(CurrentPrincipalResolver principalResolver,
                               ObjectProvider<PlatformIdentitySource> identitySource,
                               ObjectProvider<ProductionSigningKeySource> signingKeys,
                               AiGatewayClient client,
                               AiIntegrationProperties properties) {
        this.principalResolver = principalResolver;
        this.identitySource = identitySource;
        this.signingKeys = signingKeys;
        this.client = client;
        this.properties = properties;
    }

    /** 网关唯一入口：白名单匹配 → 委托 → 转发；白名单外 404。 */
    @RequestMapping("/**")
    public ResponseEntity<?> gateway(HttpServletRequest request,
                                     jakarta.servlet.http.HttpServletResponse response,
                                     @RequestBody(required = false) byte[] body) {
        try {
            return doGateway(request, response, body);
        } catch (P04Exception ex) {
            if(response!=null && response.isCommitted()){return null;}
            return fail(ex.errorCode());
        } catch (AiGatewayClient.UpstreamUnavailableException ex) {
            if(response!=null && response.isCommitted()){
                // 响应已提交（典型：GET 字节分支响应已写出，其后的交付回执/许可释放失败）：
                // 不能再改状态码，但**绝不允许静默**——否则许可泄漏类故障在服务端零痕迹
                // （R12 卡2 F-W6-P1 可观测化；此前此分支直接 return null，ACK 失败被完全吞掉）。
                log.warn("ai-gateway upstream-unavailable after commit: method={} path={} reason={}",
                        request.getMethod(), request.getRequestURI(), ex.getMessage(), ex);
                return null;
            }
            // 上游不可用/恶意响应：不放行也不泄露原因。
            // 但**服务端必须留下原因** —— 客户端只拿泛化文案，诊断信息只进日志。
            // 没有这一行时，三种截然不同的内部失败（执行事实不可用 / 内层路由未命中 /
            // 内层派发抛异常）在外部完全同形（都是 503 + "授权服务不可用"），
            // 导致对同一现象做出三次互相矛盾的归因（G-34）。
            log.warn("ai-gateway upstream-unavailable: method={} path={} reason={}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage(), ex);
            return fail(P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }

    public ResponseEntity<?> gateway(HttpServletRequest request,byte[] body) {
        return gateway(request,null,body);
    }

    private ResponseEntity<?> doGateway(HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response, byte[] body) {
        String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
        String subPath = subPath(request);
        if (subPath == null) {
            return fail(P04ErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        Optional<Route> matched = matchRoute(method, subPath);
        if (matched.isEmpty()) {
            // 白名单外与不存在同形：404，不区分"路径不存在"与"方法不允许"
            return fail(P04ErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        Route route = matched.get();

        // 1. 登录证据：无任何凭证 → 401（未登录）
        String loginEvidence = request.getHeader(LOGIN_EVIDENCE_HEADER);
        if (loginEvidence == null || loginEvidence.isBlank()) {
            return fail(P04ErrorCode.AUTH_REQUIRED);
        }

        // 2. 成员身份：resolver empty（已登录但不归属当前租户）→ 403
        CurrentPrincipalResolver.CurrentMember member = principalResolver.resolveCurrentMember()
                .orElseThrow(() -> new P04Exception(P04ErrorCode.TENANT_CONTEXT_MISSING));

        // 3. 策略版本：真实身份事实，缺身份源 503，缺成员/版本 403（绝不默认 1）
        PlatformIdentitySource source = identitySource.getIfAvailable();
        if (source == null) {
            return fail(P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        PlatformIdentitySource.PlatformIdentity identity =
                source.membership(member.tenantId(), member.userId(), member.membershipId());
        if (identity == null || !identity.enabled()) {
            throw new P04Exception(P04ErrorCode.MEMBERSHIP_INVALID);
        }

        // 4. 最小 scope：路由动作 → 平台权限，身份必须显式持有
        String requiredPermission = AiActionRegistry.requirePermission(route.action());
        if (!identity.scopes().contains(requiredPermission)) {
            throw new P04Exception(P04ErrorCode.FORBIDDEN);
        }

        // 4b. 平台管理身份（维护者裁决 A2-ter）：Agent 目录是<b>平台级</b>资源
        //     （ai_agent_* 三表没有 tenant_id、uk_agent_name 全局唯一），因此这些动作
        //     要求"scope + 平台管理身份"两个<b>独立</b>条件同时成立。
        //     只持有 scope 的租户管理员/成员在这里被拒 —— 即使它被误授了该 scope。
        //     resolver 的默认实现返回 false（fail-closed）：漏实现只会更严。
        if (AiActionRegistry.requiresPlatformAdmin(route.action())
                && !principalResolver.isPlatformAdmin()) {
            throw new P04Exception(P04ErrorCode.FORBIDDEN);
        }

        // 5. 签发委托（只带本路由所需 scope）并转发。
        //    transport=local（E3/C3）：同进程转送，不铸造委托凭证；本地执行事实
        //    由 LocalAiGatewayClient 经 AiIdentityPort 桥接进 AI 侧上下文。
        String delegationToken = null;
        if (!properties.isLocalTransport()) {
            ProductionSigningKeySource keys = signingKeys.getIfAvailable();
            if (keys == null) {
                throw new P04Exception(P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
            }
            delegationToken = keys.issue(
                    member.tenantId(), member.userId(), member.membershipId(),
                    List.of(route.action()), identity.policyVersion(), null).token();
        }

        return forward(request,response,member,route.action(), method, subPath, delegationToken, body);
    }

    /** 组装转发请求并透传 AI 状态码。 */
    private ResponseEntity<?> forward(HttpServletRequest request,jakarta.servlet.http.HttpServletResponse servletResponse,
                                      CurrentPrincipalResolver.CurrentMember member,String action,String method,
                                      String subPath, String delegationToken,
                                      byte[] body) {
        if (body != null && body.length > properties.getMaxForwardBodyBytes()) {
            throw new P04Exception(P04ErrorCode.BAD_REQUEST);
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > properties.getMaxForwardBodyBytes()) {
            throw new P04Exception(P04ErrorCode.BAD_REQUEST);
        }

        String query = request.getQueryString();
        // transport=local（E3/C3）：基地址是本进程，转送只用路径与 query，
        // 用占位主机保持 URI 绝对形状；跨进程 HTTP 传输仍取真实 ai-base-url。
        String base = properties.isLocalTransport() ? "http://local" : properties.getAiBaseUrl();
        String target = base + AI_INTERNAL_PREFIX + subPath
                + (query == null || query.isBlank() ? "" : "?" + query);
        URI uri;
        try {
            uri = new URI(target);
        } catch (URISyntaxException e) {
            throw new P04Exception(P04ErrorCode.BAD_REQUEST);
        }

        Map<String, String> headers = sanitizedHeaders(request);
        if (!properties.isLocalTransport()
                && (properties.getServiceCredential() == null || properties.getServiceCredential().isBlank())) {
            throw new P04Exception(P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        // 委托凭证是唯一身份载体（http 传输）；浏览器凭证已在黑名单中剥除。
        // transport=local 时身份不经头传递：LocalAiGatewayClient 从 AiIdentityPort 桥接。
        if (delegationToken != null) {
            headers.put("Authorization", "Bearer " + delegationToken);
        }
        if (!properties.isLocalTransport()) {
            headers.put("X-P04-Service-Credential", properties.getServiceCredential());
        }
        headers.put(RequestId.HEADER, RequestId.currentOrEmpty());

        if(action.equals("document.download") || action.equals("conversation.export")
                || servletResponse!=null && (method.equals("GET") || action.equals("kb.retrieve") || action.equals("run.approve") || action.equals("run.reconcile"))){
            var transfer=client.forwardBytes(new AiGatewayClient.ForwardRequest(method,uri,Map.copyOf(headers),body));
            if(transfer.status()!=200 && transfer.status()!=206){
                return ResponseEntity.status(transfer.status()).contentType(MediaType.APPLICATION_JSON)
                        .body(new String(transfer.bytes(),java.nio.charset.StandardCharsets.UTF_8));
            }
            if(servletResponse==null){throw new AiGatewayClient.UpstreamUnavailableException("delivery output absent");}
            try {
                servletResponse.setStatus(transfer.status());
                servletResponse.setContentType(transfer.contentType());
                servletResponse.setHeader("Cache-Control","no-store");
                servletResponse.setHeader("X-Content-Type-Options","nosniff");
                servletResponse.setHeader("Accept-Ranges","bytes");
                servletResponse.setHeader(RequestId.HEADER,RequestId.currentOrEmpty());
                if(!transfer.contentRange().isBlank()){servletResponse.setHeader("Content-Range",transfer.contentRange());}
                    servletResponse.setContentLength(transfer.bytes().length);
                var out=servletResponse.getOutputStream();
                for(int offset=0;offset<transfer.bytes().length;offset+=8192){
                    out.write(transfer.bytes(),offset,Math.min(8192,transfer.bytes().length-offset));
                }
                out.flush();
                servletResponse.flushBuffer();
                return null;
            }catch(java.io.IOException e){
                if(!servletResponse.isCommitted()){servletResponse.resetBuffer();servletResponse.setStatus(503);servletResponse.setContentLength(0);}
                return null;
            }finally{
                // No application write occurs after this point, including client-abort paths.
                byte[] acknowledgement;
                try{acknowledgement=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(Map.of(
                        "tenantId",member.tenantId(),"memberId",member.membershipId(),"permitId",transfer.permitId(),"operationId",transfer.operationId()));
                }catch(Exception e){throw new AiGatewayClient.UpstreamUnavailableException("delivery acknowledgement invalid");}
                var ackHeaders = new java.util.LinkedHashMap<String,String>();
                ackHeaders.put("Content-Type","application/json");
                if (!properties.isLocalTransport()) {
                    ackHeaders.put("X-P04-Service-Credential", properties.getServiceCredential());
                }
                var released=client.forward(new AiGatewayClient.ForwardRequest("POST",
                        URI.create(base+AI_INTERNAL_PREFIX+"/authorization/deliveries/release"),
                        ackHeaders,acknowledgement));
                if(released.status()!=204 || !released.body().isBlank()){throw new AiGatewayClient.UpstreamUnavailableException("delivery release unconfirmed");}
            }
        }

        AiGatewayClient.ForwardResponse response = client.forward(
                new AiGatewayClient.ForwardRequest(method, uri, Map.copyOf(headers), body));

        // 透传 AI 响应状态码与体；AI 内部协议固定 JSON 包络
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status())
                .header(RequestId.HEADER, RequestId.currentOrEmpty());
        if (!response.body().isBlank()) {
            builder.contentType(MediaType.APPLICATION_JSON);
        }
        return builder.body(response.body());
    }

    /**
     * 净化转发头：剥内部身份头、逐跳头、cookie 与浏览器凭证；
     * 其余头（如 Content-Type、Accept、Idempotency-Key）原样透传。
     */
    private Map<String, String> sanitizedHeaders(HttpServletRequest request) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        var headerNames = request.getHeaderNames();
        if (headerNames == null) {
            return sanitized;
        }
        List<String> names = new ArrayList<>();
        headerNames.asIterator().forEachRemaining(names::add);
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            if(lower.startsWith("x-ai-delivery-")){continue;}
            if (lower.equals("x-p04-service-credential") || lower.equals("x-service-credential")) {
                continue;
            }
            if (INTERNAL_IDENTITY_HEADERS.contains(lower) || HOP_BY_HOP_HEADERS.contains(lower)) {
                continue;
            }
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                sanitized.put(name, value);
            }
        }
        if (request.getContentType() != null && !request.getContentType().isBlank()) {
            sanitized.put("Content-Type", request.getContentType());
        }
        return sanitized;
    }

    /** 从 requestURI 提取网关内子路径；形状可疑（残留编码/前缀不符）返回 null → 404。 */
    private static String subPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (!uri.equals(GATEWAY_PREFIX) && !uri.startsWith(GATEWAY_PREFIX + "/")) {
            return null;
        }
        String subPath = uri.substring(GATEWAY_PREFIX.length());
        if (subPath.isEmpty()) {
            subPath = "/";
        }
        // 残留编码/穿越形状不在白名单判定内，直接按不存在处理（fail-closed）
        if (subPath.indexOf('%') >= 0 || subPath.indexOf('\\') >= 0) {
            return null;
        }
        return subPath;
    }

    /**
     * 白名单匹配：段级精确比较，{@code {x}} 通配任意单段（非空且不含 {@code /}）。
     * 包络外路径（含根路径）一律不匹配。
     */
    static Optional<Route> matchRoute(String method, String subPath) {
        if (method == null || method.isBlank() || subPath == null
                || subPath.isBlank() || !subPath.startsWith("/")) {
            return Optional.empty();
        }
        String[] actual = subPath.substring(1).split("/", -1);
        for (Route route : ROUTES) {
            if (!route.method().equals(method)) {
                continue;
            }
            String[] pattern = route.pattern().substring(1).split("/", -1);
            if (matches(pattern, actual)) {
                return Optional.of(route);
            }
        }
        return Optional.empty();
    }

    private static boolean matches(String[] pattern, String[] actual) {
        if (pattern.length != actual.length) {
            return false;
        }
        for (int i = 0; i < pattern.length; i++) {
            if (pattern[i].startsWith("{") && pattern[i].endsWith("}")) {
                if (actual[i].isBlank()) {
                    return false;
                }
            } else if (!pattern[i].equals(actual[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 失败响应遵循本协议口径：HTTP status == body.code，符号码放 {@code data.errorCode}
     * （与 {@code ProductionAuthorizationController} 相同的自包含映射）。
     */
    private static ResponseEntity<ApiResponse<Map<String, Object>>> fail(P04ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.httpStatus())
                .header(RequestId.HEADER, RequestId.currentOrEmpty())
                .body(ApiResponse.error(errorCode.httpStatus(), errorCode.message(), errorCode.name()));
    }
}
