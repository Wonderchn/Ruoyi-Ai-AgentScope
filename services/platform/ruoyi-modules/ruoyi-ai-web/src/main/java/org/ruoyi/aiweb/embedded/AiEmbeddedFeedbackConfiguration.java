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

        /**
         * 反馈受理面：内部前缀下的两个 handler（提交/取消），客户端路径由网关白名单放行
         * （{@code POST|DELETE /conversations/messages/{messageId}/feedback} → 动作
         * {@code conversation.rename}，复用已播种的 {@code ai:conversation:write}，
         * 不新增规范动作/权限行/迁移 —— 与 C13.4 引擎两条同口径）。
         *
         * <p>静态嵌套控制器仍会被平台组件扫描独立发现；本类自身必须声明
         * 集成开启与 local 传输条件，不能依靠外层配置继承门控。
         */
        @RestController
        @ConditionalOnEmbeddedLocal
        public static class FeedbackSurface {

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
                requirePrincipal();
                feedbackService.submitFeedbackAsync(messageId, request);
                return ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        .body(ApiEnvelope.ok(null));
            }

            /** 取消赞/踩反馈（无 body；同上，异步受理）。 */
            @DeleteMapping(INTERNAL_PREFIX + "/conversations/messages/{messageId}/feedback")
            public ResponseEntity<ApiEnvelope<Void>> cancel(@PathVariable String messageId) {
                requirePrincipal();
                feedbackService.cancelFeedbackAsync(messageId);
                return ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        .body(ApiEnvelope.ok(null));
            }

            /**
             * 主体复核：缺主体拒绝（403 语义，与 {@code AiInternalExceptionResolver} 对
             * {@code ClientException} 的既有映射一致），有主体再复核路由动作 scope ——
             * "网关已校验"不构成内层免检的理由（{@code ConversationSurface.requirePrincipal} 的
             * fail-closed 口径）。本路由动作 = {@code conversation.rename}。
             */
            private static void requirePrincipal() {
                ExecutionPrincipal principal = PrincipalContext.get();
                if (principal == null) {
                    throw new ClientException("缺少执行主体，无法受理消息反馈");
                }
                if (!principal.hasScope("conversation.rename")) {
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
     * 而产品配置出现该属性会让启动失败（{@code SaasBoundaryConfiguration} 启动自检），
     * 消息入口第一行再由 {@code SaasCapabilityBoundary.requireOpenOrClosed} 做一次关闭判定。
     * 内嵌形态沿用**同一组门控**：装配开关同值 + 运行期同一关闭判定 + {@code SaasCapabilityBoundary}
     * bean 缺席时按关闭处理（{@code requireOpenOrClosed} 的既有语义）。
     *
     * <p><b>为什么仍要登记而不是只等 legacy 开关。</b>登记让"内嵌侧有一个现成的、
     * 带完整去重/授权消费链的反馈持久化组件"成为事实（W3-5-BE-1 的判据对象），
     * 同时把"开关关着 = 连 bean 都不是"（W3-T0-5 同族口径）作为运行期行为钉进测试：
     * 默认交付下它不装配，不产生任何公开面或副作用。
     *
     * <p><b>消费依赖诚实声明。</b>旧消费者 {@code @RequiredArgsConstructor} 注入
     * {@code MessageFeedbackService}；它只服务独立应用（RocketMQ starter 的注解处理器在那边
     * 才会为 {@code @RocketMQMessageListener} 建容器）。内嵌登记的是同一个类：真要开启
     * legacy 能力时，仍需消息总线/传输装配在位（broker 链）——那是显式的部署决策
     * （C12.5-5：验证通过后由 T0 决定开启），不在本装配里静默补齐。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "ai.integration.legacy-listeners-enabled", havingValue = "true")
    public static class LegacyFeedbackConsumerAssembly {

        @Bean
        @ConditionalOnMissingBean
        public MessageFeedbackConsumer messageFeedbackConsumer(MessageFeedbackService feedbackService,
                                                               ObjectProvider<SaasCapabilityBoundary> capabilityBoundary) {
            return new MessageFeedbackConsumer(feedbackService, capabilityBoundary);
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
}
