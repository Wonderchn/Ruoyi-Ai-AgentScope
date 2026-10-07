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

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.support.RocketMQMessageListenerContainerRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按 {@code eventType} 逐个注册 outbox 订阅（C12.3：主题按 eventType 分）。
 *
 * <p><b>复用 starter 的 public 注册入口，不复制它的容器装配。</b>
 * {@code RocketMQMessageListenerContainerRegistrar.registerContainer(String, Object, RocketMQMessageListener)}
 * 是 starter 自己的 public API（Spring 侧 pillar：容器要设 20 多个属性、还要解析
 * {@code ${...}} 占位符与 rocketmqProperties 默认值）。自己 new
 * {@code DefaultRocketMQListenerContainer} 就得把那 20 多个属性抄一遍，
 * 一旦 starter 升级就静默漂移。这里只负责"为哪个主题注册哪个监听器"。
 *
 * <p><b>为什么在 {@link SmartInitializingSingleton#afterSingletonsInstantiated()} 里注册。</b>
 * starter 的 {@code RocketMQMessageListenerBeanPostProcessor} 自己是个
 * {@code SmartLifecycle}，它在 {@code start()} 时才启动已注册的容器；
 * 而 {@code afterSingletonsInstantiated()} 发生在刷新完成、{@code LifecycleProcessor.onRefresh()}
 * **之前**。在这个时点注册，容器才能被同一个生命周期一起启动；放到更晚（例如
 * {@code ApplicationReadyEvent}）就会错过启动窗口。
 *
 * <p><b>启动期形状自检，不静默漏订阅。</b>注册完成后立刻断言
 * "已注册事件类型集合 == 配置要求的事件类型集合"，不等号则**启动失败**。
 * 漏一个订阅的症状是：消息照常投递成功、只是那个类型的主题**永远没人消费**——
 * 静默堆积，没有任何报错。宁可启动失败。
 *
 * <p><b>{@code RUNTIME_UNVERIFIED（无 broker）}。</b>本机与 VM 都没有 RocketMQ broker，
 * "订阅真的生效"无法验证。已核实的只有：编译通过 + 单测里按清单逐个注册成功
 * （用 mock 的 registrar 捕获调用）。**不要在报告里把它写成"已接通"。**
 */
public class OutboxMqListenerRegistrar implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(OutboxMqListenerRegistrar.class);

    private final ObjectProvider<RocketMQMessageListenerContainerRegistrar> registrarProvider;
    private final OutboxMqIngestService ingestService;
    private final OutboxMqProperties properties;

    private final List<String> registeredEventTypes = new ArrayList<>();

    public OutboxMqListenerRegistrar(ObjectProvider<RocketMQMessageListenerContainerRegistrar> registrarProvider,
                                     OutboxMqIngestService ingestService, OutboxMqProperties properties) {
        this.registrarProvider = registrarProvider;
        this.ingestService = ingestService;
        this.properties = properties;
    }

    /** 已注册的事件类型（顺序 = 注册顺序），供启动自检与测试断言。 */
    public List<String> registeredEventTypes() {
        return List.copyOf(registeredEventTypes);
    }

    @Override
    public void afterSingletonsInstantiated() {
        RocketMQMessageListenerContainerRegistrar registrar = registrarProvider.getIfAvailable();
        if (registrar == null) {
            throw new IllegalStateException("p2.outbox.mq.enabled=true，但容器内没有 "
                    + "RocketMQMessageListenerContainerRegistrar（RocketMQ starter 未装配）。"
                    + "拒绝启动成『开关开着但一个订阅都没有』——那会让跨实例消息静默堆积（C12.4）。");
        }

        // 先**整体校验**再注册：校验放在循环里会让"前几个已经注册、后面才发现清单有错"，
        // 也就是把半套订阅交给 starter（它可能已经把前几个容器启动起来）。
        // 启动失败本身能兜住，但"先产生副作用再失败"是不必要的；一次性校验干净。
        Set<String> configured = new LinkedHashSet<>(properties.getEventTypes());
        if (configured.isEmpty()) {
            throw new IllegalStateException("p2.outbox.mq.event-types 为空：开关开着却没有任何订阅，"
                    + "跨实例消息会静默堆积（C12.4）。要么给出清单，要么关掉 p2.outbox.mq.enabled。");
        }
        for (String eventType : configured) {
            if (eventType == null || eventType.isBlank()) {
                throw new IllegalStateException("p2.outbox.mq.event-types 含空白项，拒绝注册半个订阅："
                        + properties.getEventTypes());
            }
        }

        for (String eventType : configured) {
            String topic = properties.topicFor(eventType);
            String key = OutboxMqTopics.sanitize(eventType);
            String beanName = "outboxMqListener_" + key;
            registrar.registerContainer(beanName, new OutboxMqListener(ingestService, eventType),
                    annotationFor(topic, key));
            registeredEventTypes.add(eventType);
            log.info("outbox MQ 已注册订阅：eventType={} topic={} bean={}", eventType, topic, beanName);
        }

        // 形状自检：注册数必须等于配置数（漏一个就是静默堆积）
        if (registeredEventTypes.size() != configured.size()
                || !new LinkedHashSet<>(registeredEventTypes).equals(configured)) {
            throw new IllegalStateException("outbox MQ 订阅注册不完整：configured=" + configured
                    + " registered=" + registeredEventTypes);
        }
        log.info("outbox MQ 订阅注册完成：{} 个事件类型（RUNTIME_UNVERIFIED：无 broker，订阅是否真的生效未验证）",
                registeredEventTypes.size());
    }

    /**
     * 合成一份 {@link RocketMQMessageListener} 注解实例。
     *
     * <p>为什么不手写一张属性表：注解的成员会随 starter 版本增删（本版本有 26 个成员），
     * 手写就等于把它冻结在代码里，升级后新成员的默认值不会被带上。
     * 这里先用反射取**注解自带的默认值**铺底，再覆盖我们真正要控制的四项；
     * 因此 starter 加成员时不需要改本类。
     */
    static RocketMQMessageListener annotationFor(String topic, String groupSuffix) {
        Map<String, Object> attributes = new HashMap<>();
        for (Method attribute : RocketMQMessageListener.class.getDeclaredMethods()) {
            Object defaultValue = attribute.getDefaultValue();
            if (defaultValue != null) {
                attributes.put(attribute.getName(), defaultValue);
            }
        }
        attributes.put("topic", topic);
        attributes.put("consumerGroup", "outbox_" + groupSuffix + "_cg");
        return AnnotationUtils.synthesizeAnnotation(attributes, RocketMQMessageListener.class, null);
    }
}
