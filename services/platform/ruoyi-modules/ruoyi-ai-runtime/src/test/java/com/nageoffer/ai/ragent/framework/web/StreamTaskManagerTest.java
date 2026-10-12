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

package com.nageoffer.ai.ragent.framework.web;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class StreamTaskManagerTest {

    private static final String TASK_ID = "t-1001";
    private static final String OWNER_ID = "u-1";
    private static final String OTHER_ID = "u-2";
    private static final String CANCEL_TOPIC = "global:ragent:stream:cancel";
    private static final String CANCEL_KEY = "ragent:stream:cancel:" + TASK_ID;
    private static final String OWNER_KEY = "ragent:stream:owner:" + TASK_ID;

    private RBucket<String> cancelBucket;
    private RBucket<String> ownerBucket;
    private RTopic topic;
    private StreamTaskManager taskManager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cancelBucket = mock(RBucket.class);
        ownerBucket = mock(RBucket.class);
        topic = mock(RTopic.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.<String>getBucket(CANCEL_KEY)).thenReturn(cancelBucket);
        when(redissonClient.<String>getBucket(OWNER_KEY)).thenReturn(ownerBucket);
        when(redissonClient.getTopic(anyString())).thenReturn(topic);
        taskManager = new StreamTaskManager(redissonClient);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldRecordOwnerOnRegister() {
        taskManager.register(TASK_ID, OWNER_ID, () -> {
        });

        // 属主要跨节点可查：停止请求可能落在没跑这条流的节点上
        verify(ownerBucket).set(OWNER_ID, Duration.ofMinutes(30));
    }

    @Test
    void shouldRejectCancelFromNonOwner() {
        when(ownerBucket.get()).thenReturn(OWNER_ID);
        login(OTHER_ID);

        assertThatThrownBy(() -> taskManager.cancelByUser(TASK_ID))
                .isInstanceOf(ClientException.class);

        // taskId 是雪花 ID，时间有序可预测，越权者不该留下任何取消痕迹
        verify(cancelBucket, never()).set(anyString(), any(Duration.class));
        verify(topic, never()).publish(any());
    }

    @Test
    void shouldCancelWhenRequesterIsOwner() {
        when(ownerBucket.get()).thenReturn(OWNER_ID);
        login(OWNER_ID);

        taskManager.cancelByUser(TASK_ID);

        verify(cancelBucket).set(OWNER_ID, Duration.ofMinutes(30));
        // 载荷带上发起方，执行端才有得复核
        verify(topic).publish(TASK_ID + "|" + OWNER_ID);
    }

    @Test
    void shouldIgnoreCancelMarkerPlantedByOthers() {
        // 取消早于注册到达时属主还无从比对，只能在注册这一刻回头验标记
        when(cancelBucket.get()).thenReturn(OTHER_ID);
        AtomicInteger finalized = new AtomicInteger();

        taskManager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet);

        assertThat(finalized.get()).isZero();
    }

    @Test
    void shouldHonorOwnMarkerPlantedBeforeRegister() {
        when(cancelBucket.get()).thenReturn(OWNER_ID);
        AtomicInteger finalized = new AtomicInteger();

        taskManager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet);

        assertThat(finalized.get()).isOne();
    }

    @Test
    void shouldHonorSystemCancelMarker() {
        // SSE 超时与断连由容器回调触发，那条线程上没有登录用户可比对
        when(cancelBucket.get()).thenReturn("__system__");
        AtomicInteger finalized = new AtomicInteger();

        taskManager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet);

        assertThat(finalized.get()).isOne();
    }

    @Test
    void shouldMarkSystemCancelAsSystem() {
        taskManager.cancel(TASK_ID);

        verify(cancelBucket).set("__system__", Duration.ofMinutes(30));
        verify(topic).publish(TASK_ID + "|__system__");
    }

    @Test
    void shouldDropBroadcastCancelFromNonOwner() {
        AtomicInteger finalized = new AtomicInteger();
        taskManager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet);

        captureListener().onMessage(CANCEL_TOPIC, TASK_ID + "|" + OTHER_ID);

        // 发布端校验挡不住抢在属主落地前的喷射，执行端必须自己再比一次
        assertThat(finalized.get()).isZero();
        // 更要紧的是别置旗标：置了会让 register 的标记复核短路，等于把复核那道门绕开
        assertThat(taskManager.isCancelled(TASK_ID)).isFalse();
    }

    @Test
    void shouldRunBroadcastCancelFromOwner() {
        AtomicInteger finalized = new AtomicInteger();
        taskManager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet);

        captureListener().onMessage(CANCEL_TOPIC, TASK_ID + "|" + OWNER_ID);

        assertThat(finalized.get()).isOne();
        assertThat(taskManager.isCancelled(TASK_ID)).isTrue();
    }

    @Test
    void shouldTreatLegacyPayloadAsSystemCancel() {
        AtomicInteger finalized = new AtomicInteger();
        taskManager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet);

        // 滚动升级期老节点广播的是裸 taskId，它在发布前已做过同样的属主校验
        captureListener().onMessage(CANCEL_TOPIC, TASK_ID);

        assertThat(finalized.get()).isOne();
    }

    @Test
    void shouldNotResurrectTaskAfterUnregister() {
        AtomicInteger cancelled = new AtomicInteger();
        taskManager.register(TASK_ID, OWNER_ID, () -> {
        });
        taskManager.unregister(TASK_ID);

        // 流同步结束时 unregister 先于 bindHandle 执行，这里不该无中生有造一条幽灵任务
        taskManager.bindHandle(TASK_ID, cancelled::incrementAndGet);
        captureListener().onMessage(CANCEL_TOPIC, TASK_ID + "|__system__");

        assertThat(taskManager.isCancelled(TASK_ID)).isFalse();
        assertThat(cancelled.get()).isZero();
    }

    // ===== F10 op2：订阅名==发布名 调用点不变式（platform 专属；ai 独立运行无 NameMapper，按设计不复制） =====
    @Test
    void subscribeChannelMustEqualPublishChannelUnderTenantMapper() {
        // 平台 TenantKeyPrefixHandler 的等价镜像：含 global: 短路；无租户不加前缀；有租户加 "<tenantId>:"。
        // 钉调用点不变式：订阅线程（无租户）与发布线程（租户 000000）必须落到同一条频道名。
        AtomicReference<String> currentTenant = new AtomicReference<>();
        Function<String, String> tenantMapper = name -> {
            if (name == null || name.isBlank()) {
                return null;
            }
            if (name.contains("global:")) {
                return name;
            }
            String tenantId = currentTenant.get();
            if (tenantId == null || tenantId.isBlank() || name.startsWith(tenantId)) {
                return name;
            }
            return tenantId + ":" + name;
        };

        // 自检：镜像不是恒等映射，否则下面的等式没有鉴别力
        assertThat(tenantMapper.apply("ragent:stream:cancel")).isEqualTo("ragent:stream:cancel");
        currentTenant.set("000000");
        assertThat(tenantMapper.apply("ragent:stream:cancel")).isEqualTo("000000:ragent:stream:cancel");
        currentTenant.set(null);

        List<String> subscribeChannels = new ArrayList<>();
        List<String> publishChannels = new ArrayList<>();
        AtomicBoolean publishing = new AtomicBoolean(false);

        RTopic mappedTopic = mock(RTopic.class);
        RedissonClient mappedClient = mock(RedissonClient.class);
        when(mappedClient.<String>getBucket(anyString())).thenReturn(cancelBucket);
        when(mappedClient.getTopic(anyString())).thenAnswer(invocation -> {
            String mappedChannel = tenantMapper.apply(invocation.getArgument(0));
            (publishing.get() ? publishChannels : subscribeChannels).add(mappedChannel);
            return mappedTopic;
        });

        StreamTaskManager manager = new StreamTaskManager(mappedClient);
        // 订阅：@PostConstruct 容器启动线程，无租户
        manager.subscribe();
        // 发布：stop 请求线程，租户 000000
        publishing.set(true);
        currentTenant.set("000000");
        manager.cancel(TASK_ID);

        assertThat(subscribeChannels).as("订阅只应挂一条频道").hasSize(1);
        assertThat(publishChannels).as("发布只应广播一条频道").hasSize(1);
        assertThat(subscribeChannels.get(0))
                .as("F10 op2：发布名必须等于订阅名，否则 stop 到不了执行节点")
                .isEqualTo(publishChannels.get(0));
    }

    /**
     * 取回订阅时注册的监听器，用它模拟一条到达本节点的取消广播
     */
    @SuppressWarnings("unchecked")
    private MessageListener<String> captureListener() {
        taskManager.subscribe();
        ArgumentCaptor<MessageListener<String>> captor = ArgumentCaptor.forClass(MessageListener.class);
        verify(topic).addListener(eq(String.class), captor.capture());
        return captor.getValue();
    }

    private void login(String userId) {
        UserContext.set(LoginUser.builder().userId(userId).build());
    }
}
