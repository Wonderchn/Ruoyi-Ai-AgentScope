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

package com.nageoffer.ai.ragent.framework.mq.transport;

import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.support.RocketMQMessageListenerContainerRegistrar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C12.3/C12.5：按 {@code eventType} 逐个注册订阅的**形状与自检**。
 *
 * <p><b>本测试能证明什么、不能证明什么（刻意写清楚）。</b>
 * 无 broker，所以"订阅真的生效、消息真的往返"**证明不了**（那部分记
 * {@code RUNTIME_UNVERIFIED}）。能证明的是：注册**逐类型发生且只发生一次**、
 * 每个主题名都等于 {@code OutboxMqTopics} 的规范形式、合成注解带上了正确的
 * topic/consumerGroup、**漏订阅或清单漂移会让启动失败**。
 * 静默漏订阅是这一段最危险的失效模式（消息投递成功、只是没人消费），
 * 而这些判据正好覆盖它。
 */
@Tag("dev")
class OutboxMqListenerRegistrarTest {

    private static ObjectProvider<RocketMQMessageListenerContainerRegistrar> provider(
            RocketMQMessageListenerContainerRegistrar registrar) {
        @SuppressWarnings("unchecked")
        ObjectProvider<RocketMQMessageListenerContainerRegistrar> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registrar);
        return provider;
    }

    private static OutboxMqIngestService ingestStub() {
        return mock(OutboxMqIngestService.class);
    }

    @Test
    @DisplayName("按 eventType 逐个注册：数量 = 清单长度，主题名 = 规范形式，且无重复注册")
    void registersEveryConfiguredEventTypeExactlyOnce() {
        RocketMQMessageListenerContainerRegistrar registrar = mock(RocketMQMessageListenerContainerRegistrar.class);
        OutboxMqProperties properties = new OutboxMqProperties();
        OutboxMqListenerRegistrar underTest = new OutboxMqListenerRegistrar(provider(registrar), ingestStub(), properties);

        underTest.afterSingletonsInstantiated();

        ArgumentCaptor<String> beanNames = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> listeners = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<RocketMQMessageListener> annotations =
                ArgumentCaptor.forClass(RocketMQMessageListener.class);
        verify(registrar, times(OutboxMqTopics.RUN_EVENT_TYPES.size()))
                .registerContainer(beanNames.capture(), listeners.capture(), annotations.capture());

        assertThat(underTest.registeredEventTypes())
                .containsExactlyElementsOf(OutboxMqTopics.RUN_EVENT_TYPES);
        assertThat(new TreeSet<>(beanNames.getAllValues()))
                .as("bean 名不得重复（重复会让先注册的容器被覆盖，那个类型就没人消费）")
                .hasSize(OutboxMqTopics.RUN_EVENT_TYPES.size());

        List<String> topics = new ArrayList<>();
        for (RocketMQMessageListener ann : annotations.getAllValues()) {
            topics.add(ann.topic());
            assertThat(ann.consumerGroup()).isNotBlank();
        }
        assertThat(topics).containsExactlyElementsOf(
                OutboxMqTopics.RUN_EVENT_TYPES.stream().map(properties::topicFor).toList());
        assertThat(topics).allSatisfy(topic -> assertThat(topic).matches("[a-zA-Z0-9_-]+"));
        assertThat(listeners.getAllValues()).allSatisfy(l -> assertThat(l).isInstanceOf(OutboxMqListener.class));
    }

    @Test
    @DisplayName("扩展漂移护栏：注册出来的清单恰好等于 RunEventAppender 的事件类型常量")
    void registeredTypesMatchTheAppenderConstants() throws Exception {
        RocketMQMessageListenerContainerRegistrar registrar = mock(RocketMQMessageListenerContainerRegistrar.class);
        OutboxMqListenerRegistrar underTest = new OutboxMqListenerRegistrar(provider(registrar), ingestStub(),
                new OutboxMqProperties());

        underTest.afterSingletonsInstantiated();

        TreeSet<String> declared = new TreeSet<>();
        for (Field field : RunEventAppender.class.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isPublic(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods)
                    && field.getType() == String.class && field.getName().startsWith("EVENT_")) {
                declared.add((String) field.get(null));
            }
        }
        assertThat(declared).isNotEmpty();
        assertThat(underTest.registeredEventTypes())
                .as("注册器实际注册的类型与产品事件类型常量漂移（新增事件类型必须同时加订阅）")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    @DisplayName("负例：没有 starter 注册器 ⇒ 启动失败（不允许『开关开着但一个订阅都没有』）")
    void missingRegistrarFailsLoudly() {
        OutboxMqListenerRegistrar underTest = new OutboxMqListenerRegistrar(provider(null), ingestStub(),
                new OutboxMqProperties());

        assertThatThrownBy(underTest::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RocketMQMessageListenerContainerRegistrar");
    }

    @Test
    @DisplayName("负例：清单里出现空白项 ⇒ 启动失败，不注册半个订阅")
    void blankEventTypeFailsLoudly() {
        RocketMQMessageListenerContainerRegistrar registrar = mock(RocketMQMessageListenerContainerRegistrar.class);
        OutboxMqProperties properties = new OutboxMqProperties();
        properties.setEventTypes(List.of("run.status", "  "));
        OutboxMqListenerRegistrar underTest = new OutboxMqListenerRegistrar(provider(registrar), ingestStub(), properties);

        assertThatThrownBy(underTest::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("空白项");
        verify(registrar, never()).registerContainer(anyString(), any(), any());
    }

    @Test
    @DisplayName("合成的注解带上正确的 topic / consumerGroup，且其余成员取注解默认值（不手写冻结）")
    void synthesizedAnnotationCarriesTopicAndGroup() {
        RocketMQMessageListener ann = OutboxMqListenerRegistrar.annotationFor("ai-outbox-run-status", "run_status");

        assertThat(ann.topic()).isEqualTo("ai-outbox-run-status");
        assertThat(ann.consumerGroup()).isEqualTo("outbox_run_status_cg");
        // 未覆盖的成员必须来自注解默认值（starter 升级新增成员时不需要改本类）
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (var attribute : RocketMQMessageListener.class.getDeclaredMethods()) {
            if (attribute.getDefaultValue() != null) {
                defaults.put(attribute.getName(), attribute.getDefaultValue());
            }
        }
        assertThat(ann.selectorExpression()).isNotNull();
        assertThat(defaults).containsKey("nameServer");
        assertThat(ann.nameServer()).as("未覆盖的成员应保留注解默认值").isEqualTo(defaults.get("nameServer"));
    }

    @Test
    @DisplayName("监听器把消息交给 ingest，且**不吞异常**（吞掉就等于 ack 一条没处理的消息）")
    void listenerDelegatesAndDoesNotSwallow() {
        OutboxMqIngestService ingest = ingestStub();
        when(ingest.ingest(any())).thenReturn(OutboxMqIngestService.IngestOutcome.CONSUMED);
        OutboxMqListener listener = new OutboxMqListener(ingest, "run.status");
        OutboxMqEnvelope envelope = OutboxMqEnvelope.builder()
                .tenantId("000000").eventId("e-1").eventType("run.status").build();

        listener.onMessage(envelope);

        verify(ingest).ingest(envelope);
        assertThat(listener.subscribedEventType()).isEqualTo("run.status");

        when(ingest.ingest(any())).thenThrow(new OutboxMqReauthorizationUnavailableException("db down"));
        assertThatThrownBy(() -> listener.onMessage(envelope))
                .as("授权事实不可得必须抛出让 broker 重投；吞掉会让这条事件被 ack 后永久消失")
                .isInstanceOf(OutboxMqReauthorizationUnavailableException.class);
    }
}
