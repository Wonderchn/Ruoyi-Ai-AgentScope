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
            new Route("GET", "/knowledge-bases", "kb.list"),
            new Route("POST", "/knowledge-bases", "kb.write"),
            new Route("GET", "/knowledge-bases/{id}", "kb.read"),
            new Route("DELETE", "/knowledge-bases/{id}", "kb.delete"),
            new Route("PUT", "/knowledge-bases/{id}/acl", "kb.acl.manage"),
            new Route("DELETE", "/knowledge-bases/{id}/acl", "kb.acl.manage"),
            new Route("POST", "/knowledge-bases/retrievals", "kb.retrieve"),
            new Route("GET", "/documents/{id}", "document.read"),
            new Route("GET", "/documents/{id}/content", "document.download"),
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
            // 但不在白名单 ⇒ 网关必然 404（T6 判据钉死的缺口）。动作复用 conversation.rename
            //（= ai:conversation:write，会话消息是同一聚合的写面），不新增动作/权限行/迁移。
            new Route("POST", "/conversations/messages/{messageId}/feedback", "conversation.rename"),
            new Route("DELETE", "/conversations/messages/{messageId}/feedback", "conversation.rename"),
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
            // 动作复用 conversation.read：推荐追问是**用户本来就能读的**那条 assistant 消息的
            // 派生内容，LLM 调用无外部副作用，落库只是该派生结果的缓存；本卡由此**不新增
            // canonical 动作、不新增权限行、不新增迁移**（动作总数护栏不变，权威值以
            // P1CurrentAuthorizationTest:209 的 assertEquals(34, ...) 为准）。
            // 写面：**不铸** GET 交付回执（网关只在字节分支释放许可，JSON 分支铸造 = 许可泄漏）。
            new Route("POST", "/conversations/messages/{messageId}/recommended-questions", "conversation.read"),
            // WP-034：F10 Agent 会话面。此前 `/agent/v1/**` 完全没有白名单路由，
            // 所以客户端**无法**经 `/api/ai/v1` 到达 WP-033B 交付的会话面——
            // 那是"服务端可装配"与"客户端可访问"之间的缺口。
            //
            // 逐条登记（**不用通配 `/agent/v1/**`**，也不做根 Controller 扫描）：
            // 通配会把同一前缀下任何将来新增的控制器一起放行，等于取消白名单。
            //
            // 动作复用会话读/写，不新增权限行：Agent 会话与普通会话是同一
            // "用户自己的会话"语义（`ai:conversation:read/write/delete`），
            // 且 AgentConversationServiceImpl 自身按 tenant+user 限定作用域。
            // 有意**不**放行 `POST /agent/v1/conversations/batch-delete`：
            // 批量多资源授权是计划 §13 的待决定项。
            new Route("GET", "/agent/v1/conversations", "conversation.read"),
            new Route("GET", "/agent/v1/conversations/{id}/messages", "conversation.read"),
            new Route("PUT", "/agent/v1/conversations/{id}/title", "conversation.rename"),
            new Route("DELETE", "/agent/v1/conversations/{id}", "conversation.delete"),
            // WP-033 / C13.4（T0 登记，2026-10-06）：Agent **引擎**面。经源码核实四条真实端点
            // 早已存在（AgentChatController:94/112/129、AgentMetaController:85），但此前
            // 一条都不在白名单里 —— 与本文件上一段记录的会话面缺口同类：
            // "服务端可装配"不等于"客户端可访问"。C13 已把它们接到内层可达前缀之下
            // （类级 @RequestMapping("/internal/ai/v1")，与 RunController/AiResourceController/
            // UploadController/AgentActionController 同形），故内层 handler 现在真实存在。
            //
            // 只有**两条 JSON** 在此登记：
            new Route("POST", "/agent/v1/stop", "run.cancel"),
            new Route("GET", "/agent/v1/meta", "agent.execute"),
            // 另两条是 text/event-stream（GET /agent/v1/chat、POST /agent/v1/chat/confirm），
            // **刻意不在此登记**：本通用转发有 2s/2MiB 上限且不做 SSE，登记了只会超时或缓冲失败。
            // 它们由 AiGatewayStreamController 逐条精确映射（专用流式受限传输，先于 catch-all 生效）。
            //
            // 动作与权限全部**复用既有已播种行**，不新增 canonical 动作、不新增迁移：
            //   run.cancel    -> ai:run:cancel     (V5)
            //   agent.execute -> ai:agent:execute  (V6)
            // `P1CurrentAuthorizationTest` 断言 knownActions().size()==34，故不得新增动作。
            // （注：本条注释曾长期写作"26"，是过期数字；权威值以 P1CurrentAuthorizationTest:209
            //   的 assertEquals(34, AiActionRegistry.knownActions().size()) 为准。R/RW-29 已两次
            //   因该过期数字产生误导，故此处更正并保留说明。）
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
            // F11（Skills）本批**未装**：其闭包经 IntentNodeRegistry → DefaultIntentClassifier →
            // LLMService/PromptTemplateLoader/IntentTreeCacheManager 均未装配（task-13 / W4-T0-46），
            // 故此处不登记 /agent-catalog/agent-skills 的任何路径。
            new Route("GET", "/agent-catalog/agents", "agent.list"),
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
            // 只登记**闭包已闭合**的分块面 6 条；KB 面与文档面的装配闭包未闭合
            // （FileStorageService→ObjectStorageClient/凭据；文档面还要 ParserRegistry/
            //  IngestionEngine 等），由 AiEmbeddedKnowledgeAdminConfiguration 默认关闭
            // （ai.embedded.knowledge-admin.full=false），故此处**不登记**它们的路由 ——
            // 登记了却没有 handler 会让 LocalWhitelistHandlerCoverageTest 的
            // "放行了但没人接"护栏变红，且等于开放不可用面。
            // 动作复用既有集合（document.read / kb.write），**不新增 canonical 动作、
            // 不新增权限行、不新增迁移**。段数互不遮蔽：{chunk-id}/enable 为 6 段、
            // batch-enable 为 5 段，与 5 段的 {chunk-id} 形状不同。
            new Route("GET", "/knowledge-base/docs/{docId}/chunks", "document.read"),
            new Route("POST", "/knowledge-base/docs/{docId}/chunks", "kb.write"),
            new Route("PUT", "/knowledge-base/docs/{docId}/chunks/{chunkId}", "kb.write"),
            new Route("DELETE", "/knowledge-base/docs/{docId}/chunks/{chunkId}", "kb.write"),
            new Route("PATCH", "/knowledge-base/docs/{docId}/chunks/{chunkId}/enable", "kb.write"),
            new Route("PATCH", "/knowledge-base/docs/{docId}/chunks/batch-enable", "kb.write"),
            // R6: explicit administrative surfaces; GET replies carry P2 delivery receipts.
            // KB/document full admin and Dashboard remain closed until their prerequisites are verified.
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
            new Route("GET", "/rag/traces/runs", "run.get"),
            new Route("GET", "/rag/traces/runs/{traceId}", "run.get"),
            new Route("GET", "/rag/traces/runs/{traceId}/nodes", "run.events"),
            new Route("GET", "/biz-change-logs", "run.get"),
            new Route("GET", "/biz-change-logs/{id}", "run.get"),
            new Route("GET", "/rag/eval", "kb.retrieve"));

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
            if(response!=null && response.isCommitted()){return null;}
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
