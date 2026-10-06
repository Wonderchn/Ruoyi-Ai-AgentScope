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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C12.3：主题命名与**事件类型覆盖**的漂移护栏。
 *
 * <p><b>为什么覆盖要单独一条判据。</b>RocketMQ 的订阅是"一事件类型一主题"，
 * 所以传输层必须静态列举要为哪些 {@code eventType} 建订阅。这份清单与
 * {@code RunEventAppender} 的 {@code EVENT_*} 常量是**两份**信息，
 * 漂移的后果是最难被发现的一种：新事件类型照常写进 {@code ai_run_event} 与
 * {@code outbox_event}，relay 照常投递成功（主题是新建的，broker 不报错），
 * 只是**没有任何消费者订阅它** —— 消息静默堆积，跨实例永远收不到。
 * 所以这里用反射把 {@code RunEventAppender} 的常量读出来，断言两边**恰好相等**：
 * 少一个（订阅缺失）或多一个（订阅到不存在的类型）都会让测试变红。
 */
@Tag("dev")
class OutboxMqTopicsTest {

    @Test
    @DisplayName("订阅清单恰好等于 RunEventAppender 声明的事件类型（少一个/多一个都算漂移）")
    void subscribedEventTypesMatchTheAppenderConstants() throws Exception {
        Set<String> declared = new TreeSet<>();
        for (Field field : RunEventAppender.class.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isPublic(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods)
                    && field.getType() == String.class && field.getName().startsWith("EVENT_")) {
                declared.add((String) field.get(null));
            }
        }
        assertThat(declared)
                .as("反射读不到 RunEventAppender 的 EVENT_* 常量说明常量被改名/移走，本护栏必须跟着修")
                .isNotEmpty();
        assertThat(OutboxMqTopics.RUN_EVENT_TYPES)
                .as("outbox MQ 订阅清单与事件类型常量漂移（新增事件类型必须同时加订阅）")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    @DisplayName("主题名 = 前缀 + 净化后的 eventType；点是非法主题字符，必须被确定性替换")
    void topicNameIsDeterministicAndRocketMqLegal() {
        assertThat(OutboxMqTopics.topicFor("ai-outbox-", "run.terminal")).isEqualTo("ai-outbox-run-terminal");
        assertThat(OutboxMqTopics.topicFor("ai-outbox-", "run.output_delta")).isEqualTo("ai-outbox-run-output_delta");
        assertThat(OutboxMqTopics.topicFor(null, "run.status")).isEqualTo("ai-outbox-run-status");
        assertThat(OutboxMqTopics.topicFor("  ", "run.status")).isEqualTo("ai-outbox-run-status");

        // 幂等：净化结果再过一次净化不变（否则生产/消费两侧在多级拼接时可能分叉）
        for (String type : OutboxMqTopics.RUN_EVENT_TYPES) {
            String once = OutboxMqTopics.topicFor("ai-outbox-", type);
            assertThat(once).matches("[a-zA-Z0-9_-]+");
            assertThat(OutboxMqTopics.sanitize(once)).isEqualTo(once);
        }
    }

    @Test
    @DisplayName("生产端与消费端取到同一个主题名（配置类与工具类不得各写一份规则）")
    void producerAndConsumerComputeTheSameTopicName() {
        OutboxMqProperties properties = new OutboxMqProperties();
        properties.setTopicPrefix("custom-");
        for (String type : OutboxMqTopics.RUN_EVENT_TYPES) {
            assertThat(properties.topicFor(type)).isEqualTo(OutboxMqTopics.topicFor("custom-", type));
        }
    }

    @Test
    @DisplayName("负例：空 eventType 与超长主题名都拒绝，不产出半个主题名")
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> OutboxMqTopics.topicFor("ai-outbox-", " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> OutboxMqTopics.topicFor("ai-outbox-", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> OutboxMqTopics.topicFor("x".repeat(OutboxMqTopics.ROCKETMQ_TOPIC_MAX_LENGTH),
                "run.status"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("超长");
    }

    @Test
    @DisplayName("去重键：外部业务动作优先用 operationKey，其余用 eventId；两者皆空则拒绝")
    void dedupKeyPrefersOperationKey() {
        assertThat(OutboxMqTopics.dedupKey("e-1", null)).isEqualTo("evt:e-1");
        assertThat(OutboxMqTopics.dedupKey("e-1", "  ")).isEqualTo("evt:e-1");
        assertThat(OutboxMqTopics.dedupKey("e-1", "op-9")).isEqualTo("op:op-9");
        assertThatThrownBy(() -> OutboxMqTopics.dedupKey(null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("去重键不可得");
    }

    @Test
    @DisplayName("已知事件类型集合与订阅清单同源且顺序确定（注册顺序必须可复现）")
    void knownEventTypesAreOrderedAndDeduplicated() {
        Set<String> known = OutboxMqTopics.knownEventTypes();
        assertThat(known).isInstanceOf(LinkedHashSet.class);
        assertThat(known).containsExactlyElementsOf(OutboxMqTopics.RUN_EVENT_TYPES);
        assertThat(Arrays.stream(OutboxMqTopics.RUN_EVENT_TYPES.toArray()).distinct().count())
                .as("订阅清单本身不得有重复项").isEqualTo(OutboxMqTopics.RUN_EVENT_TYPES.size());
    }
}
