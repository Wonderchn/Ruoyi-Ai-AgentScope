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

package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.agent.controller.vo.AgentConversationVO;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentMessageVO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentConversationMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.ConversationBatchDeleteService;
import com.nageoffer.ai.ragent.agent.service.impl.AgentConversationServiceImpl;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * WP-033B 建立、WP-034 修可达性的 Agent 会话面内嵌装配（F10 的会话 CRUD 与身份桥）。
 *
 * <p><b>为什么需要单独一个控制器。</b>AI 侧既有的 {@code AgentConversationController}
 * 用 {@code UserContext.getUserId()} 取身份，而 {@code UserContext} 只由
 * {@code com.nageoffer.ai.ragent.user.config.UserContextInterceptor} 填充，
 * 该拦截器又只挂在独立 AI 应用的 {@code SaTokenConfig} 上。
 * 内嵌进程里<b>没有任何组件设置 UserContext</b>（实测：全仓 {@code UserContext.set(} 的调用点
 * 只有独立 AI 的拦截器、MQ 消费器、调度器与测试），因此内嵌态直接注册既有控制器会让
 * 每个请求都拿到 {@code userId == null}——要么查不到数据，要么把作用域退化成"没有用户限定"。
 *
 * <p>平台的规范身份源是 {@link PrincipalContext}：{@code LocalAiGatewayClient} 在 servlet
 * forward 前用平台登录事实构造 {@link ExecutionPrincipal} 并放进该上下文。
 * 底层的 {@link AgentConversationServiceImpl} <b>本来就用 {@code PrincipalContext.require()}
 * 解析租户作用域</b>，只是 {@code userId} 参数由调用方传入。所以正确的内嵌适配是：
 * <b>身份从 PrincipalContext 取，业务完全委托既有服务</b>——不复制任何查询/删除逻辑。
 *
 * <p><b>WP-034 修掉的三个可达性缺口。</b>WP-033B 交付的公开面在当时<b>既不可经网关到达、
 * 也不是"外部不可达"</b>，有三层原因（都由
 * {@code LocalAgentConversationRouteDispatchTest} 的真实请求钉住）：
 * <ol>
 *   <li><b>路径不在内部前缀下</b>：网关转送目标恒为 {@code /internal/ai/v1 + subPath}，
 *       而原控制器注册在 {@code /agent/v1/**} → 内层 handler 必然 miss（404）；</li>
 *   <li><b>包络形状不符</b>：网关强制"单 JSON 对象 + 整数 {@code code} 等于 HTTP 状态"，
 *       而 AI 侧旧 {@code Result} 的 {@code code} 是<b>字符串</b> {@code "0"} → 会被判为
 *       "缺少包络 code" 并收敛为 503。现在改用 {@link ApiEnvelope}（{@code int code}）；</li>
 *   <li><b>读路径缺交付证明</b>：网关对 {@code GET} 走字节输出路径，2xx 必须携带
 *       {@code X-AI-Delivery-Permit}/{@code X-AI-Delivery-Operation} 两个 UUID 头，
 *       缺失即 503。现在受保护读路径先登记 ACTIVE permit 并把标识放进响应头。</li>
 * </ol>
 *
 * <p><b>门控刻意独立于 {@code ragent.engine.type}。</b>
 * 引擎面（{@code AgentChatController} 的 SSE 对话、{@code /agent/v1/confirm}、{@code /stop}）
 * 依赖 {@code ReActAgentProvider} 的整条链，其中 {@code AgentRunGate} 需要 Redisson、
 * {@code ReActAgentProvider} 需要 AgentScope SDK 与真实模型——这些属 WP-033 的引擎部分，
 * 本轮不在本机可验证范围内。会话 CRUD（列表/历史/改名/删除）<b>不依赖</b>那条链，
 * 因此用独立开关 {@code agent.conversation.enabled} 交付，不把它绑在引擎开关上。
 *
 * <p><b>{@code batch-delete} 已交付并已放行（原"有意不交付"的说明已过期，保留沿革）。</b>
 * 批量删除曾等待"一个 permit 能否覆盖 N 个资源"的授权决定（计划 §13 待决定项之一）；
 * D05 已作出决定（F03 先例 RW-01 据此放行 general 路径），本包据此交付受控服务端契约
 * {@link ConversationBatchDeleteService}，F10-A1（2026-10-10）再镜像放行 agent 路径
 * {@code POST /agent/v1/conversations/batch-delete}（复用 {@code conversation.delete}，
 * 零迁移、零新权限行）。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedAgentConversationConfiguration {

    /** 本地传输下的 Agent 会话面装配。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    public static class LocalTransport {

        /**
         * Agent 会话面门控：{@code agent.conversation.enabled}。
         *
         * <p>与 {@code ragent.engine.type=agent} 分开：会话 CRUD 不需要 ReAct 引擎，
         * 而引擎需要 Redisson/SDK/真实模型。关闭时不注册任何会话 bean。
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnEmbeddedLocal
        @ConditionalOnProperty(name = "agent.conversation.enabled", havingValue = "true")
        @EnableConfigurationProperties(com.nageoffer.ai.ragent.agent.config.AgentProperties.class)
        public static class ConversationEnabled {

            /**
             * Agent 持久化状态仓（检查点/step 状态的库内副本）。
             *
             * <p>存储内部自解析主体（键含 tenant/member），所以这里只需要 Mapper。
             */
            @Bean
            @ConditionalOnMissingBean
            public PgAgentStateStore pgAgentStateStore(com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper agentStateMapper) {
                return new PgAgentStateStore(agentStateMapper);
            }

            /**
             * 会话删除前的"在途流"闸门（Redisson 支撑）。
             *
             * <p>该闸门记录"某个用户正在哪个会话上跑"，删除前必须拦住——否则在途流的收尾
             * 会把状态与消息<b>写回到已删除的会话</b>上。它只在删除路径被调用一次。
             *
             * <p><b>为什么不做"没有 Redis 时的降级实现"</b>：闸门类有 124 个方法，
             * 手写一个替身既不现实、也会在 SDK 升级时静默失效；而"查不到在途流就放行"
             * 正是这个闸门要防的缺陷。所以这里<b>不做降级</b>：
             * {@code agent.conversation.enabled=true} 而没有 {@code RedissonClient} 时，
             * 容器以缺依赖<b>响亮失败</b>，而不是让删除悄悄绕过在途流保护。
             *
             * <p>代价已登记（见规格「明确未做」）：会话面在无 Redis 的部署里不可用。
             * 不想要删除能力时不要打开这个开关。
             */
            @Bean
            @ConditionalOnMissingBean
            public com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate agentRunGate(
                    org.redisson.api.RedissonClient redissonClient,
                    com.nageoffer.ai.ragent.agent.config.AgentProperties agentProperties) {
                return new com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate(redissonClient, agentProperties);
            }

            @Bean
            @ConditionalOnMissingBean
            public AgentConversationService agentConversationService(
                    AgentConversationMapper conversationMapper, AgentMessageMapper messageMapper,
                    PgAgentStateStore agentStateStore,
                    com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate runGate,
                    ObjectProvider<com.nageoffer.ai.ragent.agent.config.ReActAgentProvider> agentProviderRef,
                    ObjectProvider<com.nageoffer.ai.ragent.authorization.AiResourceWriteService> resourceWriteServiceRef) {
                return new AgentConversationServiceImpl(conversationMapper, messageMapper, agentStateStore,
                        runGate, agentProviderRef, resourceWriteServiceRef);
            }

            /**
             * Agent 会话公开面：身份取自 {@link PrincipalContext}，业务委托既有服务。
             *
             * <p>只覆盖会话 CRUD；消息写入（user/assistant）由引擎路径驱动，不在公开面暴露。
             */
            @Bean
            @ConditionalOnMissingBean
            public ConversationSurface conversationSurface(AgentConversationService conversationService,
                                                           ObjectProvider<RevocationGuard> revocations,
                                                           ConversationBatchDeleteService batchDeleteService) {
                return new ConversationSurface(conversationService, revocations, batchDeleteService);
            }

            /**
             * 受控批量删除的服务端契约（C4 / D05）。
             *
             * <p><b>为什么单独一个 bean 而不是把逻辑写进控制器。</b>授权与事务必须与请求路径在
             * 同一边界内闭合：逻辑写在控制器里，任何新调用方（后台任务、重试器、内部脚本）
             * 都能绕过"集合形状校验 + 逐资源 permit + epoch 复核"。写成服务后，
             * "能不能删"与"删什么"由同一个方法给出，无法只满足一半。
             */
            @Bean
            @ConditionalOnMissingBean
            public ConversationBatchDeleteService conversationBatchDeleteService(
                    AgentConversationService conversationService,
                    ObjectProvider<RevocationGuard> revocations) {
                return new ConversationBatchDeleteService(conversationService, revocations);
            }

            /**
             * Agent 会话公开面控制器。
             *
             * <p><b>路径必须落在内部前缀下（WP-034 修正）。</b>网关
             * {@code AiGatewayController} 的转送目标恒为
             * {@code base + AI_INTERNAL_PREFIX + subPath}（{@code AI_INTERNAL_PREFIX}
             * 是 {@code /internal/ai/v1}）。也就是说客户端请求
             * {@code /api/ai/v1/agent/v1/conversations} 时，内层 handler 会在
             * {@code /internal/ai/v1/agent/v1/conversations} 上被查找。
             *
             * <p>WP-033B 把本控制器注册在 {@code /agent/v1/conversations}——那既<b>不在</b>
             * 内部前缀下（网关必然 miss → 404），又落在 platform 的公开路径空间里
             * （被 {@code AllUrlHandler} 收进登录拦截表，但内嵌进程只有在
             * {@code LocalAiGatewayClient} 转送期间才会设置 {@link PrincipalContext}，
             * 直接访问恒拒绝），而且不在 {@code AiInternalAccessBoundaryFilter} 的
             * {@code /internal/ai/v1/*} 关闭范围内。结果是这个公开面
             * <b>既不可经网关到达、也不是"外部不可达"</b>。
             *
             * <p>现在与既有一切网关可达面（{@code AiResourceController}、
             * {@code RunController}、{@code AgentActionController}、{@code UploadController}）
             * 同一形状：路径 = 内部前缀 + 客户端所见子路径。外部直接请求
             * {@code /internal/ai/v1/**} 由边界过滤器关闭为 404。
             *
             * <p>身份：从 {@link PrincipalContext} 取执行主体。无主体时
             * <b>拒绝而不是退化成"无用户限定"</b>——那是跨用户读写的直接成因。
             *
             * <p>静态嵌套控制器仍会被平台组件扫描独立发现；本类自身必须声明
             * 集成、local 传输和会话开关三个条件。外层配置条件不会自动
             * 应用于被独立扫描的组件，词法嵌套不构成装配门控。
             */
            @RestController
            @ConditionalOnEmbeddedLocal
            @ConditionalOnProperty(name = "agent.conversation.enabled", havingValue = "true")
            public static class ConversationSurface {

                /**
                 * 内层可达前缀：与网关 {@code AI_INTERNAL_PREFIX} 同值。
                 *
                 * <p>两侧各自持有常量（不能互相 import：{@code ruoyi-ai-web} 依赖
                 * {@code ruoyi-ai-integration}，反向 import 会成环）。一致性由
                 * {@code LocalAgentConversationRouteDispatchTest} 的真实请求钉住——
                 * 网关真把请求送到这个前缀上，两侧一旦漂移该判据立刻失败。
                 */
                public static final String INTERNAL_PREFIX = "/internal/ai/v1";

                /** 交付回执头：网关对 GET（字节输出路径）要求这两个头都是 UUID 形状。 */
                static final String DELIVERY_PERMIT_HEADER = "X-AI-Delivery-Permit";
                static final String DELIVERY_OPERATION_HEADER = "X-AI-Delivery-Operation";

                private final AgentConversationService conversationService;
                private final ObjectProvider<RevocationGuard> revocations;
                private final ConversationBatchDeleteService batchDeleteService;

                public ConversationSurface(AgentConversationService conversationService,
                                           ObjectProvider<RevocationGuard> revocations,
                                           ConversationBatchDeleteService batchDeleteService) {
                    this.conversationService = conversationService;
                    this.revocations = revocations;
                    this.batchDeleteService = batchDeleteService;
                }

                /**
                 * 会话列表（F10）。
                 *
                 * <p><b>为什么带交付回执头。</b>网关对 {@code GET} 走字节输出路径
                 * （{@code forwardBytes}），该路径对 2xx 响应强制要求
                 * {@code X-AI-Delivery-Permit}/{@code X-AI-Delivery-Operation} 两个 UUID 形状的头，
                 * 缺失即按"交付证明缺失"收敛为 503。所以受保护的读路径必须先登记一个
                 * ACTIVE permit，把两个标识放进响应头；网关在把字节交给客户端之后再执行
                 * 交付回执（本地形态经 {@code AiDeliveryReleaser}）。
                 *
                 * <p><b>成功路径刻意不 close。</b>permit 必须在"字节真的交付给客户端"之前保持
                 * ACTIVE——那正是撤权屏障要等的窗口。提前 close 会让并发撤权越过仍在写出的响应，
                 * 等于取消屏障语义。所以沿用 {@code AiResourceController.reply(...)} 的既有口径：
                 * <b>失败路径 close，成功路径交给网关的交付回执释放</b>。
                 */
                @GetMapping(INTERNAL_PREFIX + "/agent/v1/conversations")
                public ResponseEntity<ApiEnvelope<List<AgentConversationVO>>> listConversations() {
                    ExecutionPrincipal principal = requirePrincipal("conversation.read");
                    RevocationGuard.Operation operation = permit(principal, "conversation.read", TENANT_REF);
                    try {
                        return read(operation, conversationService.listByUserId(principal.userId()));
                    } catch (RuntimeException failure) {
                        operation.close();
                        throw failure;
                    }
                }

                @GetMapping(INTERNAL_PREFIX + "/agent/v1/conversations/{conversationId}/messages")
                public ResponseEntity<ApiEnvelope<List<AgentMessageVO>>> listMessages(
                        @PathVariable String conversationId) {
                    ExecutionPrincipal principal = requirePrincipal("conversation.read");
                    RevocationGuard.Operation operation =
                            permit(principal, "conversation.read", conversationRef(conversationId));
                    try {
                        return read(operation,
                                conversationService.listMessages(conversationId, principal.userId()));
                    } catch (RuntimeException failure) {
                        operation.close();
                        throw failure;
                    }
                }

                /**
                 * 改名（F03/F10）。
                 *
                 * <p>网关对 {@code PUT} 走 JSON 转发路径，只要求"单 JSON 对象 + 整数 code 等于状态"，
                 * 不要求交付回执头；因此写路径只做主体与 scope 复核后委托既有服务，
                 * 不登记读交付 permit。
                 */
                @PutMapping(INTERNAL_PREFIX + "/agent/v1/conversations/{conversationId}/title")
                public ResponseEntity<ApiEnvelope<Map<String, Object>>> rename(
                        @PathVariable String conversationId, @RequestBody TitleRequest request) {
                    ExecutionPrincipal principal = requirePrincipal("conversation.rename");
                    conversationService.rename(conversationId, principal.userId(),
                            request == null ? null : request.title());
                    return ResponseEntity.ok()
                            .header("Cache-Control", "no-store")
                            .body(ApiEnvelope.ok(Map.of("conversationId", conversationId, "renamed", true)));
                }

                /**
                 * 新建会话（F03 / G-52）。
                 *
                 * <p><b>与 {@link #rename} 同形，与读路径刻意不同形。</b>网关对 {@code POST}
                 * 走 JSON 转发路径，只要求"单 JSON 对象 + 整数 code 等于状态"，**不要求交付回执头**；
                 * 因此写路径只做主体与 scope 复核后委托既有服务，**不登记读交付 permit** ——
                 * permit 是给字节输出路径的读响应（{@code forwardBytes}）用的，写路径登记它
                 * 只会制造一个没人交付的 permit。
                 *
                 * <p><b>归属只来自主体。</b>请求体只有标题；tenant / member / user 由服务与运行时
                 * 写服务从执行主体取，控制器不转发、也无法覆盖（{@code TitleRequest} 只有 title）。
                 * 因此委托时**只传标题** —— 传一个 userId 进去会让人以为"换个 userId 就能建到别人名下"，
                 * 而实际上归属只认 {@code PrincipalContext}。
                 *
                 * <p><b>唯一写路径仍是运行时的 {@code write(...)}。</b>本方法**不**写 registry / ACL /
                 * epoch，也**不**自己拼授权事实 —— 那些在 {@code AiResourceWriteService#createConversation}
                 * 里落盘，且那是 G-40 {@code ai.integration.high-risk.enabled} 守卫的唯一经过点。
                 * 因此未开启 high-risk 的实例上本路径 fail-closed（503）是正确行为。
                 */
                @PostMapping(INTERNAL_PREFIX + "/agent/v1/conversations")
                public ResponseEntity<ApiEnvelope<Map<String, Object>>> create(
                        @RequestBody(required = false) TitleRequest request) {
                    requirePrincipal("conversation.rename");
                    String conversationId = conversationService.create(request == null ? null : request.title());
                    return ResponseEntity.ok()
                            .header("Cache-Control", "no-store")
                            .body(ApiEnvelope.ok(Map.of("conversationId", conversationId, "created", true)));
                }

                @DeleteMapping(INTERNAL_PREFIX + "/agent/v1/conversations/{conversationId}")
                public ResponseEntity<ApiEnvelope<Map<String, Object>>> delete(
                        @PathVariable String conversationId) {
                    ExecutionPrincipal principal = requirePrincipal("conversation.delete");
                    conversationService.delete(conversationId, principal.userId());
                    return ResponseEntity.ok()
                            .header("Cache-Control", "no-store")
                            .body(ApiEnvelope.ok(Map.of("conversationId", conversationId, "deleted", true)));
                }

                /**
                 * 受控批量删除（C4 / D05）。
                 *
                 * <p><b>契约由 {@link ConversationBatchDeleteService} 承担，控制器只做主体校验与形状转换。</b>
                 * 集合校验（非空 / ≤100 / 无重复）、全部资源可见性预检、逐资源 permit（确定顺序）、
                 * epoch 复核、单事务删除都在服务里；控制器不复制任何一条 —— 复制就会出现
                 * "两条路径各写一遍、其中一条漏了一层"，这正是本包历史上出过的缺陷形态。
                 *
                 * <p><b>路径映射到客户端 {@code POST /agent/v1/conversations/batch-delete}。</b>
                 * F10-A1（2026-10-10）已在网关白名单逐条登记该客户端路径（镜像 F03 general 路径的
                 * {@code conversation.delete} 映射）；D05 的批量授权决定与负例族由 RW-01 先行交付，
                 * 故不再维持"公开路径 404"的边界。
                 *
                 * <p>返回整数 code 包络（网关对 POST JSON 的硬要求）。
                 */
                @PostMapping(INTERNAL_PREFIX + "/agent/v1/conversations/batch-delete")
                public ResponseEntity<ApiEnvelope<Map<String, Object>>> batchDelete(
                        @RequestBody(required = false) BatchDeleteRequest request) {
                    requirePrincipal("conversation.delete");
                    ConversationBatchDeleteService.Outcome outcome =
                            batchDeleteService.deleteAll(request == null ? null : request.conversationIds());
                    return ResponseEntity.ok()
                            .header("Cache-Control", "no-store")
                            .body(ApiEnvelope.ok(Map.of(
                                    "deletedCount", outcome.deletedCount(),
                                    "permitCount", outcome.permitCount())));
                }

                /**
                 * 主体 + scope 复核。
                 *
                 * <p><b>缺主体必须拒绝</b>：{@link PrincipalContext} 为空时 userId 会是 null，
                 * 查询会退化成"没有用户限定"——那正是跨用户读写的直接成因。
                 *
                 * <p><b>scope 也必须复核</b>：网关在放行前已按权限校验过路由动作，但内嵌形态下
                 * 主体是本地桥接构造的，"网关已校验"不构成内层免检的理由——授权判定必须
                 * 与请求路径在同一边界内闭合（fail-closed）。
                 */
                private ExecutionPrincipal requirePrincipal(String action) {
                    ExecutionPrincipal principal = PrincipalContext.get();
                    if (principal == null) {
                        throw new ClientException("缺少执行主体，无法确定 Agent 会话的用户归属");
                    }
                    if (!principal.hasScope(action)) {
                        throw new P04AiException(P04AiErrorCode.FORBIDDEN);
                    }
                    return principal;
                }

                /** 登记 ACTIVE permit（登记先于 I/O；成功路径由网关交付回执释放）。 */
                private RevocationGuard.Operation permit(ExecutionPrincipal principal, String action, String ref) {
                    RevocationGuard guard = revocations.getIfAvailable();
                    if (guard == null) {
                        throw new ServiceException("delivery permit unavailable");
                    }
                    return guard.enter(principal, action, ref);
                }

                /** 读路径响应：整数 code 包络 + 交付标识头（网关对 GET 的硬要求）。 */
                private static <T> ResponseEntity<ApiEnvelope<T>> read(RevocationGuard.Operation operation, T data) {
                    return ResponseEntity.ok()
                            .header("Cache-Control", "no-store")
                            .header(DELIVERY_PERMIT_HEADER, operation.permitId())
                            .header(DELIVERY_OPERATION_HEADER, operation.operationId())
                            .body(ApiEnvelope.ok(data));
                }

                private static String conversationRef(String conversationId) {
                    return "conv:" + conversationId;
                }

                /** 会话列表的屏障资源引用（与 {@code AiResourceController} 的读路径同形）。 */
                static final String TENANT_REF = "tenant:conversations";

                /** 改名请求体（与既有 AI 侧契约同形）。 */
                public record TitleRequest(String title) {
                }

                /**
                 * 批量删除请求体（C4）。
                 *
                 * <p>刻意只有"一组 ID"：**不接收任何授权/租户/成员字段**。主体来自
                 * {@code PrincipalContext}（C6：客户端提交的 tenant/user/member 不参与主体选择），
                 * 上限与去重规则由服务端强制，客户端传什么都不改变这些判定。
                 */
                public record BatchDeleteRequest(java.util.List<String> conversationIds) {
                }
            }
        }
    }
}

