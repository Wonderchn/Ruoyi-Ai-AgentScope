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

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.rag.controller.request.MessageFeedbackRequest;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.MessageFeedbackMapper;
import com.nageoffer.ai.ragent.rag.mq.MessageFeedbackConsumer;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.ResponseEntity;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * W3-5-BE-1 / WP-043 F05⑥：消息反馈面内嵌装配的判据（T3，@Tag("dev") 门禁 15）。
 *
 * <p>五组判据，每组都带锚点或对照（BRIEF §4「判据会静默退化」同族防线）：
 * <ol>
 *   <li><b>装配正负例</b>：local 门控开 ⇒ 受理面 + 服务都在；关 ⇒ 都不是 bean
 *       （"开关关着 = 连 bean 都不是"，W3-T0-5 同族口径）；</li>
 *   <li><b>身份与委托</b>：主体来自 {@link PrincipalContext}，缺失拒绝，scope 复核
 *       （{@code conversation.rename}），委托只传业务参数；</li>
 *   <li><b>包络契约</b>：整数 {@code code=200}（旧 {@code Result} 字符串 {@code "0"} 会被
 *       网关 requireSingleJsonObject 收敛为 503 的回归锚）；</li>
 *   <li><b>D07 假成功红线</b>：消息链缺席时受理必须响亮拒绝，不允许"200 但消息发不出去"；
 *       正例锚点 = 生产者在位时同一调用成功，证明拒绝不是恒真；</li>
 *   <li><b>legacy 消费者门控</b>：默认不装配；开关打开时才注册（运行期关闭判定仍由
 *       {@code SaasCapabilityBoundary} 兜底，属既有实现，不在本判据重复建设）。</li>
 * </ol>
 *
 * <p><b>真实路由可达性（网关白名单 + 真实请求）不在本类</b>：白名单归 T0
 * （{@code AiGatewayController.ROUTES}），放行 ⇔ 承接的交集由
 * {@code LocalWhitelistHandlerCoverageTest} 的源码扫描判据负责；真机请求归实例窗口
 * （T6 W3-5 的 R10 判据）。本类只证明"装配面 + 受理语义 + 假成功红线"。
 */
@Tag("dev")
class AiEmbeddedFeedbackConfigurationTest {

    private static final String[] ENABLED = {
            "ai.integration.enabled=true", "ai.integration.transport=local"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedFeedbackConfiguration.class);

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    // ------------------------------------------------------------------ 1. 装配正负例

    @Test
    @DisplayName("local 门控开：受理面与反馈服务都是 bean（锚点：服务真是显式注册的实现）")
    void feedbackSurfaceAndServiceAreAssembledWhenLocalTransportIsEnabled() {
        runner.withPropertyValues(ENABLED)
                .withBean(MessageFeedbackMapper.class, () -> mock(MessageFeedbackMapper.class))
                .withBean(ConversationMessageMapper.class, () -> mock(ConversationMessageMapper.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            AiEmbeddedFeedbackConfiguration.LocalTransportAssembly.FeedbackSurface.class);
                    assertThat(context).hasSingleBean(MessageFeedbackService.class);
                    // 锚点：服务 bean 真的是显式注册的那个实现，而不是替身
                    assertThat(context.getBean(MessageFeedbackService.class))
                            .isInstanceOf(com.nageoffer.ai.ragent.rag.service.impl.MessageFeedbackServiceImpl.class);
                });
    }

    @Test
    @DisplayName("门控关（enabled 缺省）：受理面与服务都不是 bean")
    void feedbackSurfaceIsAbsentWithoutTheSwitch() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(
                    AiEmbeddedFeedbackConfiguration.LocalTransportAssembly.FeedbackSurface.class);
            assertThat(context).doesNotHaveBean(MessageFeedbackService.class);
            assertThat(context).doesNotHaveBean(MessageFeedbackConsumer.class);
        });
    }

    // ------------------------------------------------------------------ 2. 身份与委托

    @Test
    @DisplayName("受理身份来自 PrincipalContext 并委托服务；换主体委托跟着变")
    void surfaceReadsIdentityFromPrincipalContextAndDelegates() {
        MessageFeedbackService service = mock(MessageFeedbackService.class);
        var surface = new AiEmbeddedFeedbackConfiguration.LocalTransportAssembly.FeedbackSurface(service);

        PrincipalContext.set(principal("T1", "2101"));
        MessageFeedbackRequest request = new MessageFeedbackRequest();
        request.setVote(1);
        ResponseEntity<ApiEnvelope<Void>> response = surface.submit("msg-1", request);
        assertThat(response.getBody().code()).isEqualTo(200);
        verify(service).submitFeedbackAsync(eq("msg-1"), any(MessageFeedbackRequest.class));

        surface.cancel("msg-1");
        verify(service).cancelFeedbackAsync("msg-1");
    }

    @Test
    @DisplayName("无执行主体拒绝；scope 不含路由动作拒绝（403 语义）")
    void surfaceRefusesWithoutPrincipalOrScope() {
        MessageFeedbackService service = mock(MessageFeedbackService.class);
        var surface = new AiEmbeddedFeedbackConfiguration.LocalTransportAssembly.FeedbackSurface(service);

        PrincipalContext.clear();
        assertThatThrownBy(() -> surface.submit("msg-1", new MessageFeedbackRequest()))
                .as("无主体时不能把消息 ID 打进异步链（跨用户反馈的直接成因）")
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("缺少执行主体");
        assertThatThrownBy(() -> surface.cancel("msg-1")).isInstanceOf(ClientException.class);
        verify(service, never()).submitFeedbackAsync(anyString(), any(MessageFeedbackRequest.class));

        // 有主体但缺 scope：403 语义（P04AiException）
        PrincipalContext.set(new ExecutionPrincipal("T1", "2101", "platform:T1:2101", 1, 1,
                Set.of("kb.read"), "jti-feedback-1", "platform:local", 1, 9999999999L));
        assertThatThrownBy(() -> surface.submit("msg-1", new MessageFeedbackRequest()))
                .isInstanceOf(com.nageoffer.ai.ragent.framework.security.P04AiException.class);
        verify(service, never()).submitFeedbackAsync(anyString(), any(MessageFeedbackRequest.class));
    }

    // ------------------------------------------------------------------ 4. D07 假成功红线

    @Test
    @DisplayName("消息链缺席：受理响亮拒绝，不允许『200 但消息永远发不出去』")
    void acceptanceFailsLoudlyWhenTheMessageChainIsAbsent() {
        // 服务按内嵌装配的真实形态构造：producer = null（legacy 消息链未开启）
        MessageFeedbackServiceImplShim shim = new MessageFeedbackServiceImplShim(null);

        MessageFeedbackRequest request = new MessageFeedbackRequest();
        request.setVote(1);
        PrincipalContext.set(principal("T1", "2101"));
        assertThatThrownBy(() -> shim.submitFeedbackAsync("msg-1", request))
                .as("D07：受理 200 但事件永远离开不了本进程 = 假执行成功，必须拒绝")
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("未受理");
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("正例锚点：生产者在位时同一受理成功（证明上一条拒绝不是恒真）")
    void acceptanceSucceedsWhenTheProducerIsPresent() {
        MessageQueueProducer producer = mock(MessageQueueProducer.class);
        MessageFeedbackServiceImplShim shim = new MessageFeedbackServiceImplShim(producer);

        MessageFeedbackRequest request = new MessageFeedbackRequest();
        request.setVote(1);
        request.setReason("引用准确");
        PrincipalContext.set(principal("T1", "2101"));
        shim.submitFeedbackAsync("msg-1", request);
        PrincipalContext.clear();

        verify(producer).send(eq("message-feedback_topic"), eq("2101:msg-1"),
                eq("消息反馈"), any());
    }

    // ------------------------------------------------------------------ 5. legacy 消费者门控

    @Test
    @DisplayName("legacy 消费者：默认不装配；开关打开时才注册（连带生产者链）")
    void legacyConsumerFollowsTheSameGate() {
        // 关闭态：开关缺省 ⇒ 两个 legacy Assembly 的 @ConditionalOnProperty 都不成立。
        // 注意本 runner **不**带 LocalTransport 装配（mapper 缺席会让服务 bean 创建失败，
        // 而服务 bean 不是本判据的对象）——只验证 legacy 门控本身。
        ApplicationContextRunner legacyOnly = new ApplicationContextRunner()
                .withUserConfiguration(
                        AiEmbeddedFeedbackConfiguration.LegacyFeedbackConsumerAssembly.class,
                        AiEmbeddedFeedbackConfiguration.LegacyFeedbackProducerAssembly.class);
        legacyOnly.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).as("legacy 链关闭时消费者连 bean 都不是")
                    .doesNotHaveBean(MessageFeedbackConsumer.class);
            assertThat(context).as("legacy 链关闭时生产者链也连 bean 都不是")
                    .doesNotHaveBean(MessageQueueProducer.class);
        });

        // 打开态：同一 runner 只加开关（+ provider 侧替身），consumer/producer 链齐全。
        // 锚点：正例先证明装配真的会随开关成立，负例才不是"条件永远不成立"的恒真。
        // DelegatingTransactionListener 的 @Autowired(required=true) 依赖是生产者事务边界的
        // 必要前提（T0 第二/三轮实测定案）：PlatformTransactionManager 用替身；
        // ObjectMapper 用**真实实例**（回查路径真的要反序列化 MessageWrapper，mock 会把它藏掉；
        // 干净 ApplicationContextRunner 不导入 JacksonAutoConfiguration，必须显式提供）。
        legacyOnly.withPropertyValues("ai.integration.enabled=true", "ai.integration.legacy-listeners-enabled=true")
                .withBean(MessageFeedbackService.class, () -> mock(MessageFeedbackService.class))
                .withBean(org.apache.rocketmq.spring.core.RocketMQTemplate.class,
                        () -> mock(org.apache.rocketmq.spring.core.RocketMQTemplate.class))
                .withBean(org.springframework.transaction.PlatformTransactionManager.class,
                        () -> mock(org.springframework.transaction.PlatformTransactionManager.class))
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,
                        com.fasterxml.jackson.databind.ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MessageFeedbackConsumer.class);
                    assertThat(context).hasSingleBean(MessageQueueProducer.class);
                });
    }

    private static ExecutionPrincipal principal(String tenant, String user) {
        return new ExecutionPrincipal(tenant, user, "platform:" + tenant + ":" + user, 1, 1,
                Set.of("conversation.rename"), "jti-feedback-" + user, "platform:local",
                1, 9999999999L);
    }

    /**
     * 受理失败语义的直测壳：{@code MessageFeedbackServiceImpl} 按
     * {@code AiEmbeddedFeedbackConfiguration.messageFeedbackService} 的同一构造形态
     * （producer 可为 null）实例化，身份取自 {@code PrincipalContext} ——
     * 证明"消息链缺席 ⇒ 拒绝"落点在生产者复核而非身份判定。
     */
    private static final class MessageFeedbackServiceImplShim
            extends com.nageoffer.ai.ragent.rag.service.impl.MessageFeedbackServiceImpl {

        MessageFeedbackServiceImplShim(MessageQueueProducer producer) {
            super(mock(MessageFeedbackMapper.class), mock(ConversationMessageMapper.class), producer);
        }
    }
}
