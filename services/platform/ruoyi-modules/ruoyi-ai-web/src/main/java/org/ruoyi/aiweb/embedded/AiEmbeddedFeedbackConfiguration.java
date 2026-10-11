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

import com.nageoffer.ai.ragent.framework.integration.SaasBoundaryProperties;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.rag.controller.request.MessageFeedbackRequest;
import com.nageoffer.ai.ragent.rag.mq.MessageFeedbackConsumer;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息反馈面的内嵌装配（W3-5-BE-1 / WP-043 F05⑥ 反馈组；T3 落地，路由白名单归 T0）。
 *
 * <p><b>为什么内嵌态需要单独的受理面。</b>旧 {@code MessageFeedbackController} 是独立
 * ragent 应用的组件：{@code ruoyi-ai-web} 的 {@code AutoConfiguration.imports} 没有它
 * （内嵌形态下从来不是 bean），而且即使装配，它映射在应用根
 * {@code /conversations/messages/{messageId}/feedback}——网关转送目标恒为
 * {@code /internal/ai/v1 + subPath}，内层也必然 miss（与 WP-033B 的引擎四条同形缺陷）。
 * 本面把它按 {@code ConversationSurface} 的同一形状接到内部前缀之下，客户端路径
 * {@code /conversations/messages/{messageId}/feedback} 经网关白名单（T0 登记）到达。
 *
 * <p><b>包络：整数 {@code code}。</b>旧控制器返回 {@code Result}（字符串 {@code code="0"}）；
 * 网关 {@code AiGatewayClient.requireSingleJsonObject} 只接受"单 JSON 对象 + 整数 code
 * 等于 HTTP 状态"，字符串 code 会以 "missing envelope code" 收敛为 503（实测定案）。
 * 故本面与 {@code AgentMetaController.meta} 同形返回 {@link ApiEnvelope}。
 *
 * <p><b>身份只来自 {@link com.nageoffer.ai.ragent.framework.context.PrincipalContext}。</b>
 * 服务层的受理身份判定（{@code MessageFeedbackServiceImpl.acceptanceUserId}）在内嵌形态
 * 下已经只认执行主体；本面在进入服务前先复核主体存在 + 路由动作 scope，缺任一即拒绝，
 * 不给"无主体验证就把消息 ID 打进异步链"留口子。
 *
 * <p><b>写路径的最终身份仍是"被反馈的已持久化消息行"。</b>受理主体只决定"谁能发起"，
 * 反馈行落谁的 tenant/member 由 {@code AiDomainWriteIdentity.applyFromPersistedFact}
 * 以消息行为准回填（T3 复核既有实现，未改动该语义）。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedFeedbackConfiguration {

    /** 本地传输下的装配（http 传输不注册任何 AI 侧 bean，与 {@code AiEmbeddedRagConfiguration} 同边界）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    public static class LocalTransportAssembly {

        /**
         * 反馈服务（内嵌形态的显式登记；独立应用的 {@code @Service} 组件扫描在内嵌进程不生效）。
         *
         * <p><b>消息链缺席可创建、受理时响亮拒绝。</b>生产者经 {@code ObjectProvider}
         * 解析：legacy 消息链未开启时为 {@code null}，服务仍可创建（受理面的身份/参数校验
         * 正常工作），但异步发送会以明确失败拒绝 —— "受理 200 但消息永远发不出去"的
         * 假成功被堵死（D07；判据见 {@code AiEmbeddedFeedbackConfigurationTest}）。
         * 主题字段在显式 {@code new} 时取默认值 {@code message-feedback_topic}（与服务层
         * 字段缺省一致），容器装配时由 {@code @Value} 覆盖。
         */
        @Bean
        @ConditionalOnMissingBean(MessageFeedbackService.class)
        public MessageFeedbackService messageFeedbackService(
                com.nageoffer.ai.ragent.rag.dao.mapper.MessageFeedbackMapper feedbackMapper,
                com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper conversationMessageMapper,
                ObjectProvider<MessageQueueProducer> producerProvider) {
            return new com.nageoffer.ai.ragent.rag.service.impl.MessageFeedbackServiceImpl(
                    feedbackMapper, conversationMessageMapper, producerProvider.getIfAvailable());
        }

        /**
         * 反馈受理面（内嵌公开面的服务端承接）。
         *
         * <p>路径 = 内部前缀 + 客户端所见子路径；外部直接请求 {@code /internal/ai/v1/**}
         * 由 {@code AiInternalAccessBoundaryFilter} 关闭为 404（既有边界，不在本组重复建设）。
         */
        @Bean
        @ConditionalOnMissingBean
        public FeedbackSurface feedbackSurface(MessageFeedbackService feedbackService) {
            return new FeedbackSurface(feedbackService);
        }

        // ---------------------------------------------------------------- 会话历史写面（RW-01-CHATHIST，T0 集成）
        //
        // 背景：聊天受理 DTO 带 conversationId，但 `RagChatExecutor` 原先只写 `ai_chat_message`，
        // 而历史读路径（`GET /conversations/{id}/messages`）读 `ai_message` ⇒ 发送后刷新看不到该轮。
        // 修法是"让写路径写入读路径所读的表"：执行器经 `ConversationHistoryPort` 调
        // `ConversationMessageService.addMessage` 落 `platform.ai_message`；**读路径一行未改、无 union**。
        //
        // 为什么端口在执行器侧声明、实现留在 ai-rag：`ruoyi-ai-rag` 依赖 `ruoyi-ai-runtime`（单向），
        // 在 `RagChatExecutor` 里直接 import `rag.service.*` 会编译失败 ⇒ 按本仓既有约定
        // （`RuntimeModelGatewayPort` / `RunConfigBindingPort` / `PlatformFactsPort`）反向声明端口。
        //
        // 为什么必须在此显式登记：`com.nageoffer.**` 不在 platform 组件扫描根之下，`@Component` 不生效。
        // **不登记则生产在使用点 fail-closed**（带 conversationId 的 run 明确失败，而不是静默不落历史）。
        // 四个 Mapper 由 `AiEmbeddedMapperConfiguration` 的 `@MapperScan`（`rag.dao.mapper`）覆盖；
        // `MessageFeedbackService` 由本类上方 `@Bean` 提供；`TenantConversationReadRepository` 由
        // `AiEmbeddedRagConfiguration` 登记（`AiResourceController` 同批）。无需改 `AutoConfiguration.imports`。
        @Bean
        @ConditionalOnMissingBean
        public com.nageoffer.ai.ragent.rag.service.ConversationMessageService conversationMessageService(
                com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper conversationMessageMapper,
                com.nageoffer.ai.ragent.rag.dao.mapper.ConversationSummaryMapper conversationSummaryMapper,
                com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper conversationMapper,
                MessageFeedbackService messageFeedbackService) {
            return new com.nageoffer.ai.ragent.rag.service.impl.ConversationMessageServiceImpl(
                    conversationMessageMapper, conversationSummaryMapper, conversationMapper, messageFeedbackService);
        }

        @Bean
        @ConditionalOnMissingBean
        public com.nageoffer.ai.ragent.runtime.port.ConversationHistoryPort conversationHistoryPort(
                com.nageoffer.ai.ragent.rag.service.ConversationMessageService conversationMessageService,
                com.nageoffer.ai.ragent.authorization.TenantConversationReadRepository conversations) {
            return new com.nageoffer.ai.ragent.rag.service.impl.ConversationHistoryAdapter(
                    conversationMessageService, conversations);
        }

        /**
         * 反馈受理面：内部前缀下的两个 handler（提交/取消），客户端路径由网关白名单放行。
         *
         * <p><b>两条路由的委托动作不同（维护者裁决 §6）</b>：
         * <ul>
         *   <li>{@code POST   /conversations/messages/{messageId}/feedback} → {@code conversation.rename}
         *       （= {@code ai:conversation:write}，既有写权限）；</li>
         *   <li>{@code DELETE /conversations/messages/{messageId}/feedback} → {@code conversation.delete}
         *       （= {@code ai:conversation:delete}）。</li>
         * </ul>
         * 原实现两条都用 {@code conversation.rename}。裁决明确否掉了
         * "因为 delete 要求更强权限，所以不是授权绕过"这一辩护：仓内**没有**
         * "write 天然强于 delete" 的授权继承规则。持 write-only 不得能删。
         * 不新增规范动作/权限行/迁移（两个动作都是既有 34 条）。
         *
         * <p>静态嵌套控制器仍会被平台组件扫描独立发现；本类自身必须声明
         * 集成开启与 local 传输条件，不能依靠外层配置继承门控。
         */
        @RestController
        @ConditionalOnEmbeddedLocal
        public static class FeedbackSurface {

            /**
             * 提交反馈的路由动作。必须与 {@code AiGatewayController.ROUTES} 中
             * {@code POST /conversations/messages/{messageId}/feedback} 登记的动作逐字一致。
             */
            public static final String SUBMIT_SCOPE = "conversation.rename";

            /**
             * 取消反馈的路由动作。必须与 {@code AiGatewayController.ROUTES} 中
             * {@code DELETE /conversations/messages/{messageId}/feedback} 登记的动作逐字一致。
             */
            public static final String CANCEL_SCOPE = "conversation.delete";

            /**
             * 内层可达前缀：与网关 {@code AI_INTERNAL_PREFIX} 同值。两侧各自持有常量
             * （不能互相 import，反向依赖成环）；一致性由 {@code LocalWhitelistHandlerCoverageTest}
             * 的源码扫描与真实请求判据钉住。
             */
            public static final String INTERNAL_PREFIX = "/internal/ai/v1";

            private final MessageFeedbackService feedbackService;

            public FeedbackSurface(MessageFeedbackService feedbackService) {
                this.feedbackService = feedbackService;
            }

            /**
             * 提交赞/踩反馈（异步受理：200 = 已受理进反馈链，不代表持久化已完成——
             * 契约客户端 {@code message-feedback.ts} 注释同口径）。
             *
             * <p><b>写路径不登记交付 permit。</b>与 {@code ConversationSurface} 写路径同形：
             * 网关对 POST JSON 只要求"单 JSON 对象 + 整数 code 等于状态"，permit 是
             * 字节输出路径（GET {@code forwardBytes}）的交付证明，写路径登记它只会制造
             * 一个没人交付的 permit（泄漏复活的形态）。
             */
            @PostMapping(INTERNAL_PREFIX + "/conversations/messages/{messageId}/feedback")
            public ResponseEntity<ApiEnvelope<Void>> submit(@PathVariable String messageId,
                                                            @RequestBody MessageFeedbackRequest request) {
                requirePrincipal(SUBMIT_SCOPE);
                feedbackService.submitFeedbackAsync(messageId, request);
                return ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        .body(ApiEnvelope.ok(null));
            }

            /**
             * 取消赞/踩反馈（无 body；同上，异步受理）。
             *
             * <p><b>本方法要求 {@code conversation.delete}，不是 {@code conversation.rename}。</b>
             * 维护者裁决 §6：提交与取消是两个不同能力，仓内没有"write 天然强于 delete"的继承规则。
             * 详见 {@link #requirePrincipal(String)}。
             */
            @DeleteMapping(INTERNAL_PREFIX + "/conversations/messages/{messageId}/feedback")
            public ResponseEntity<ApiEnvelope<Void>> cancel(@PathVariable String messageId) {
                requirePrincipal(CANCEL_SCOPE);
                feedbackService.cancelFeedbackAsync(messageId);
                return ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        .body(ApiEnvelope.ok(null));
            }

            /**
             * 主体复核：缺主体拒绝（403 语义，与 {@code AiInternalExceptionResolver} 对
             * {@code ClientException} 的既有映射一致），有主体再复核路由动作 scope ——
             * "网关已校验"不构成内层免检的理由（{@code ConversationSurface.requirePrincipal} 的
             * fail-closed 口径）。
             *
             * <p><b>scope 由调用方按各自的路由动作传入</b>（提交 {@link #SUBMIT_SCOPE}、
             * 取消 {@link #CANCEL_SCOPE}）。这两个字面量必须与
             * {@code AiGatewayController.ROUTES} 中同一路径上登记的动作**逐字一致**：
             * 两个 reactor 各自持有常量、不能互相 import，所以一致性靠
             * {@code AiEmbeddedFeedbackConfigurationTest} 的行为判据钉住，不能靠"看着一样"。
             */
            private static void requirePrincipal(String requiredScope) {
                ExecutionPrincipal principal = PrincipalContext.get();
                if (principal == null) {
                    throw new ClientException("缺少执行主体，无法受理消息反馈");
                }
                if (!principal.hasScope(requiredScope)) {
                    throw new P04AiException(P04AiErrorCode.FORBIDDEN);
                }
            }
        }
    }

    /**
     * 消息反馈 MQ 消费者的内嵌装配（W3-5-BE-1 第三件；"异步持久化"这一半）。
     *
     * <p><b>门控与既有形态逐字一致。</b>独立应用里该消费者由
     * {@code @ConditionalOnProperty(ai.integration.legacy-listeners-enabled=true)} 注册——
     * 独立应用的产品配置出现该属性会让启动失败（{@code SaasBoundaryConfiguration} 启动自检；
     * 该配置类不在内嵌装配清单里，故内嵌形态不受此自检约束），消息入口第一行再由
     * {@code SaasCapabilityBoundary.requireOpenOrClosed} 做一次关闭判定。
     * 内嵌形态沿用**同一组门控**：装配开关同值 + 运行期同一关闭判定 + {@code SaasCapabilityBoundary}
     * bean 缺席时按关闭处理（{@code requireOpenOrClosed} 的既有语义）。
     * 边界本体由同门控的 {@link LegacyCapabilityBoundaryAssembly} 提供（见该类注释）：
     * shipped 默认两组都不在（零变化）；显式打开 legacy 时两组同时成立，
     * 而"只开监听开关、缺边界/边界关闭"的拒绝语义在任何装配下都不弱化。
     *
     * <p><b>为什么仍要登记而不是只等 legacy 开关。</b>登记让"内嵌侧有一个现成的、
     * 带完整去重/授权消费链的反馈持久化组件"成为事实（W3-5-BE-1 的判据对象），
     * 同时把"开关关着 = 连 bean 都不是"（W3-T0-5 同族口径）作为运行期行为钉进测试：
     * 默认交付下它不装配，不产生任何公开面或副作用。
     *
     * <p><b>消费依赖诚实声明（F17-MQ 接线口径更正）。</b>旧消费者
     * {@code @RequiredArgsConstructor} 注入 {@code MessageFeedbackService}。旧注释曾称
     * "starter 的注解处理器只在独立应用为 {@code @RocketMQMessageListener} 建容器"——与
     * rocketmq-spring-boot 2.3.6 的事实不符：{@code RocketMQMessageListenerBeanPostProcessor}
     * 对**任何 bean**（不区分组件扫描还是 {@code @Bean} 登记）按类注解注册容器，只要
     * starter 在 classpath 且 {@code RocketMQAutoConfiguration} 激活（{@code rocketmq.name-server}
     * 缺省也激活，producer group 只约束生产者侧）。因此内嵌形态下容器接线不是缺口；真跑需要
     * 本开关（消费者成为 bean）+ broker 配置（{@code rocketmq.name-server} 与
     * {@code rocketmq.producer.group}，后者缺失时生产者组以启动期 NoSuchBean 响亮失败）
     * 同时成立——那是显式的部署决策（C12.5-5：验证通过后由 T0 决定开启），
     * 不在本装配里静默补齐。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "ai.integration.legacy-listeners-enabled", havingValue = "true")
    public static class LegacyFeedbackConsumerAssembly {

        @Bean
        @ConditionalOnMissingBean
        public MessageFeedbackConsumer messageFeedbackConsumer(MessageFeedbackService feedbackService,
                                                               ObjectProvider<SaasCapabilityBoundary> capabilityBoundary,
                                                               ObjectProvider<com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder> tenantScopeBinder) {
            // 租户作用域端口（ENV-B5b 修）：平台侧（ruoyi-admin 的 RuoYiTenantScopeBinder，
            // ai.integration.enabled=true 装配）提供实现；缺席时消费者按无租户拦截器形态直执。
            return new MessageFeedbackConsumer(feedbackService, capabilityBoundary, tenantScopeBinder);
        }
    }

    /** 生产者链的内嵌登记门（缺省不装配，见类注释）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "ai.integration.legacy-listeners-enabled", havingValue = "true")
    public static class LegacyFeedbackProducerAssembly {

        /**
         * 与 {@code RocketMQAutoConfiguration}（独立应用装配，注释已由 C12.6 更正）同形的
         * 生产者组装。这里**显式引用**该自动配置类，保证内嵌 legacy 打开时三件套齐全：
         * {@code RocketMQTemplate}（starter 按 {@code rocketmq.name-server} 装配）+ 事务监听器
         * + 适配器。缺 template 时容器在装配本 bean 处响亮失败（NoSuchBean），而不是
         * 把"受理 200 但消息永远发不出去"做成静默假成功（C12.4 / D07 红线）。
         */
        @Bean
        @ConditionalOnMissingBean(MessageQueueProducer.class)
        public MessageQueueProducer embeddedMessageQueueProducer(
                org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate,
                com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener transactionListener) {
            return new com.nageoffer.ai.ragent.framework.mq.producer.RocketMQProducerAdapter(
                    rocketMQTemplate, transactionListener);
        }

        @Bean
        @ConditionalOnMissingBean
        public com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener
        delegatingTransactionListener() {
            return new com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener();
        }
    }

    /**
     * 内嵌形态的旧能力边界（受控实现；F17-MQ 接线第一件，2026-10-11）。
     *
     * <p><b>为什么必须有这一组。</b>消息消费者第一行调用
     * {@code SaasCapabilityBoundary.requireOpenOrClosed(...)}；
     * 该方法对"取不到边界 bean"按<b>关闭</b>处理（P1 成文语义，
     * {@code SaasCapabilityBoundaryTest} 钉住，不得弱化）。独立 ragent 应用由
     * {@code SaasBoundaryConfiguration} 提供边界 bean；该类不在 platform 扫描根、也不在
     * 任何 {@code AutoConfiguration.imports}，因此<b>此前内嵌形态即使把
     * {@code ai.integration.legacy-listeners-enabled=true} 打开，也只是"消费者是 bean、
     * 每条消息首行拒绝"（R4：ClosedCapabilityException + broker 重投循环，且无启动自检兜底）</b>
     * ——这正是文档告诫的"不能只开监听开关"。本组是那条链在内嵌形态的受控实现。
     *
     * <p><b>门控与生产者/消费者装配逐字同值。</b>三组 legacy Assembly 都由
     * {@code ai.integration.enabled=true} × {@code ai.integration.legacy-listeners-enabled=true}
     * 门控 ⇒ "消费者是 bean"与"边界存在于容器"同时成立、或同时不成立；不存在"消费者在、
     * 边界不在"的中间装配（shipped 默认：都不在，零变化）。边界本体以
     * {@link SaasBoundaryProperties} 为唯一真值来源（与独立应用同一个类、同一个属性），
     * 不新增放行开关，也不改动运行期判定逻辑。
     *
     * <p><b>打开仍然不是"只开监听开关"。</b>消费者第一行的关闭判定依旧执行：边界缺席
     * （本组被摘掉）或以关闭态边界注入时，消息在任何业务写之前被拒（判据见
     * {@code AiEmbeddedFeedbackConfigurationTest} 的 F17-MQ 组）。且生产者组在缺
     * {@code RocketMQTemplate} 时启动期响亮失败（NoSuchBean），"开关开着但没有 broker 配置"
     * 不可能静默成立。旧能力族是<b>整族</b>共享一组开关与一个边界（{@code SaasBoundaryProperties}
     * 单布尔真值）：本组生效时，同部署里其它已装配的 legacy 链（如 F06 文档计划任务，
     * 另有自己的 knowledge-admin.full 门）也会通过各自的关闭判定——这是族语义的既有形态，
     * 不在此处按能力拆分。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "ai.integration.legacy-listeners-enabled", havingValue = "true")
    @EnableConfigurationProperties(SaasBoundaryProperties.class)
    public static class LegacyCapabilityBoundaryAssembly {

        @Bean
        @ConditionalOnMissingBean
        public SaasCapabilityBoundary saasCapabilityBoundary(SaasBoundaryProperties properties) {
            return new SaasCapabilityBoundary(properties);
        }
    }
}
