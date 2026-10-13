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

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamTaskManagerTest {

    private static final String TASK_ID = "t-1001";
    private static final String OWNER_ID = "u-1";
    private static final String OTHER_ID = "u-2";
    private static final String TENANT_ID = "T-A";
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
        PrincipalContext.clear();
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

    // ===== F10 op2 债务 i：收尾链执行主体重绑（ai 树语义镜像：平台树同形 + 租户 binder 块；本树无 binder 端口） =====

    @Test
    void cancelFinalizerMustPersistUnderReboundPrincipal() {
        // 债务 i（真机同形，A000001）：监听线程无主体 → 落库链先吃 PrincipalContext.require()
        // （no execution principal in current context）→ 重绑 register 侧捕获的身份引用后落库成功。
        // 失败形态与 AgentStreamEventBridge.settleAndPersistMessage 一致：只留痕、不外抛。
        RecordingRedis redis = new RecordingRedis();
        AtomicReference<String> persistOutcome = new AtomicReference<>();
        AtomicBoolean originSeenInFinalizer = new AtomicBoolean(false);
        Runnable persistence = () -> {
            try {
                ExecutionPrincipal principal = PrincipalContext.require();
                originSeenInFinalizer.set(PrincipalContext.isSynchronousOrigin());
                persistOutcome.set("persisted:" + principal.tenantId() + ":" + principal.membershipId());
            } catch (Exception e) {
                persistOutcome.set("failed:" + e.getMessage());
            }
        };

        PrincipalContext.set(principal(TENANT_ID));
        StreamTaskManager manager = new StreamTaskManager(redis.client());
        manager.register(TASK_ID, OWNER_ID, persistence);

        // 监听线程：无执行主体
        PrincipalContext.clear();
        deliverCancel(manager, redis.topic());

        assertThat(persistOutcome.get())
                .as("取消收尾的落库要在捕获身份里执行，否则 no execution principal")
                .isEqualTo("persisted:" + TENANT_ID + ":platform:" + TENANT_ID + ":1");
        assertThat(originSeenInFinalizer.get())
                .as("恢复只授 holder、不授同步起源（(a′) 语义；直接杀 set↔restore 互换）")
                .isFalse();
    }

    @Test
    void cancelLocalMustRestoreListenerThreadPrincipal() {
        // 监听线程池共享：收尾包裹结束必须把线程原主体原样放回（restore(previous)，不是 clear）。
        // 预置「别的」主体模拟池化残留；若改成 clear，主体被抹掉同样是身份卫生事故。
        RecordingRedis redis = new RecordingRedis();
        AtomicInteger finalizerRuns = new AtomicInteger();
        ExecutionPrincipal listenerResidue = principal("T-B");

        PrincipalContext.set(principal(TENANT_ID));
        StreamTaskManager manager = new StreamTaskManager(redis.client());
        manager.register(TASK_ID, OWNER_ID, finalizerRuns::incrementAndGet);

        PrincipalContext.set(listenerResidue);
        deliverCancel(manager, redis.topic());

        assertThat(finalizerRuns.get()).isOne();
        assertThat(PrincipalContext.get())
                .as("收尾后监听线程主体必须原样放回，不能留捕获身份、也不能清掉原主体")
                .isSameAs(listenerResidue);
    }

    @Test
    void cancelLocalMustRestorePrincipalEvenWhenCancelActionThrows() {
        // 线程池共享：cancelAction（上游流中断动作）抛错向外传播时，finally 仍须把监听线程
        // 原主体放回——不是 finally 恢复的形态会把捕获身份留给下一个任务（跨主体污染）
        RecordingRedis redis = new RecordingRedis();
        ExecutionPrincipal listenerResidue = principal("T-B");

        PrincipalContext.set(principal(TENANT_ID));
        StreamTaskManager manager = new StreamTaskManager(redis.client());
        manager.register(TASK_ID, OWNER_ID, () -> {
        });
        manager.bindHandle(TASK_ID, () -> {
            throw new IllegalStateException("upstream interrupt failed");
        });

        PrincipalContext.set(listenerResidue);
        assertThatThrownBy(() -> deliverCancel(manager, redis.topic()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(PrincipalContext.get())
                .as("抛错路径也必须把线程原主体恢复原样")
                .isSameAs(listenerResidue);
    }

    @Test
    void interleavedCancelsKeepEachCapturedPrincipal() {
        // 两个任务、两个主体：捕获必须跟着任务记录走；会话级/静态单一捕获会让
        // 另一个任务的收尾带着别人的身份跑（跨主体污染）
        RecordingRedis redis = new RecordingRedis();
        Map<String, String> persisted = new HashMap<>();
        String taskA = TASK_ID + "-a";
        String taskB = TASK_ID + "-b";

        PrincipalContext.set(principal("T-A"));
        StreamTaskManager manager = new StreamTaskManager(redis.client());
        manager.register(taskA, OWNER_ID, () -> persisted.put(taskA, identityOrFailure()));
        PrincipalContext.set(principal("T-B"));
        manager.register(taskB, OWNER_ID, () -> persisted.put(taskB, identityOrFailure()));

        // 交错投递：B 先于 A
        PrincipalContext.clear();
        MessageListener<String> listener = listen(manager, redis.topic());
        listener.onMessage(CANCEL_TOPIC, taskB + "|__system__");
        listener.onMessage(CANCEL_TOPIC, taskA + "|__system__");

        assertThat(persisted.get(taskB)).as("B 的收尾只能带 B 的身份").isEqualTo("T-B:platform:T-B:1");
        assertThat(persisted.get(taskA)).as("A 的收尾只能带 A 的身份").isEqualTo("T-A:platform:T-A:1");
    }

    @Test
    void registerWithoutPrincipalRunsFinalizerDirectly() {
        // 无主体场景（内嵌直调）：register 时取不到主体必须留空，不能因此抛
        // "no execution principal" 把取消收尾整个打掉；没有身份门要满足时按原形态直跑
        RecordingRedis redis = new RecordingRedis();
        AtomicBoolean finalizerSawNoPrincipal = new AtomicBoolean();
        AtomicInteger finalizerRuns = new AtomicInteger();
        StreamTaskManager manager = new StreamTaskManager(redis.client());

        PrincipalContext.clear();
        manager.register(TASK_ID, OWNER_ID, () -> {
            finalizerRuns.incrementAndGet();
            finalizerSawNoPrincipal.set(!PrincipalContext.hasPrincipal());
        });

        deliverCancel(manager, redis.topic());

        assertThat(finalizerRuns.get()).isOne();
        assertThat(finalizerSawNoPrincipal.get()).as("无捕获主体时收尾按原形态直跑").isTrue();
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

    /**
     * 让指定管理器订阅并取回监听器（监听线程模拟：无登录用户、无主体）
     */
    @SuppressWarnings("unchecked")
    private static MessageListener<String> listen(StreamTaskManager manager, RTopic topicMock) {
        manager.subscribe();
        ArgumentCaptor<MessageListener<String>> captor = ArgumentCaptor.forClass(MessageListener.class);
        verify(topicMock).addListener(eq(String.class), captor.capture());
        return captor.getValue();
    }

    /**
     * 让指定管理器订阅并投递一条系统取消广播（监听线程：无登录用户、无主体）
     */
    private static void deliverCancel(StreamTaskManager manager, RTopic topicMock) {
        listen(manager, topicMock).onMessage(CANCEL_TOPIC, TASK_ID + "|__system__");
    }

    /**
     * 收尾链落库要的身份：tenantId 与 membershipId 必须与 register 侧捕获完全一致
     */
    private static String identityOrFailure() {
        try {
            ExecutionPrincipal principal = PrincipalContext.require();
            return principal.tenantId() + ":" + principal.membershipId();
        } catch (Exception e) {
            return "failed:" + e.getMessage();
        }
    }

    private static ExecutionPrincipal principal(String tenantId) {
        return new ExecutionPrincipal(tenantId, "1", "platform:" + tenantId + ":1", 1, 1,
                Set.of(), "stream-task-test", "test", 1, Long.MAX_VALUE);
    }

    /**
     * 可核对读写形态的假 Redis：主键+属主键共享内存 store（ai 树无 TenantKeyPrefixHandler，
     * 不需要平台测试那种租户映射）。
     */
    private static final class RecordingRedis {

        private final Map<String, String> store = new HashMap<>();
        private final Map<String, RBucket<String>> buckets = new HashMap<>();
        private final RTopic topic;
        private final RedissonClient client;

        private RecordingRedis() {
            topic = mock(RTopic.class);
            client = mock(RedissonClient.class);
            when(client.getTopic(anyString())).thenReturn(topic);
            when(client.<String>getBucket(anyString())).thenAnswer(invocation ->
                    bucketFor(invocation.getArgument(0)));
        }

        private RedissonClient client() {
            return client;
        }

        private RTopic topic() {
            return topic;
        }

        @SuppressWarnings("unchecked")
        private RBucket<String> bucketFor(String key) {
            return buckets.computeIfAbsent(key, name -> {
                RBucket<String> bucket = mock(RBucket.class);
                doAnswer(invocation -> {
                    store.put(name, invocation.getArgument(0));
                    return null;
                }).when(bucket).set(anyString(), any(Duration.class));
                doAnswer(invocation -> store.get(name)).when(bucket).get();
                doAnswer(invocation -> {
                    store.remove(name);
                    return null;
                }).when(bucket).deleteAsync();
                return bucket;
            });
        }
    }
}
