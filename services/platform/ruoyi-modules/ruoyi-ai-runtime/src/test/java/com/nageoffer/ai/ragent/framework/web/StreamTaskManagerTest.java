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
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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
import org.junit.jupiter.api.Tag;

@Tag("dev")
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
        taskManager = new StreamTaskManager(redissonClient, nullBinderProvider());
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

        StreamTaskManager manager = new StreamTaskManager(mappedClient, nullBinderProvider());
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

    // ===== F10 op1：监听器侧租户重绑（platform 专属；ai 独立应用无 NameMapper/端口，按设计不复制） =====

    @Test
    void cancelLocalMustRunCancelActionsInsideCapturedTenantScope() {
        // 取消收尾跑在监听线程（无租户）上：落库被 MyBatis 租户行拦截器 fail-closed 拒绝
        // （tenant.context.missing、取消消息丢失），键删除也会落成无前缀键（键残留）。
        // cancelAction+finalizer 必须经端口重绑 register 侧捕获的租户后再执行。
        TenantMappedRedis redis = new TenantMappedRedis();
        List<String> tenantsSeenByActions = new ArrayList<>();
        AtomicInteger cancelActionRuns = new AtomicInteger();
        AtomicInteger finalizerRuns = new AtomicInteger();

        PrincipalContext.set(principal(TENANT_ID));
        redis.setCurrentTenant(TENANT_ID);
        StreamTaskManager manager = new StreamTaskManager(redis.client(), binderProvider(redis.binder()));
        manager.register(TASK_ID, OWNER_ID, () -> {
            finalizerRuns.incrementAndGet();
            tenantsSeenByActions.add(redis.currentTenant());
        });
        manager.bindHandle(TASK_ID, () -> {
            cancelActionRuns.incrementAndGet();
            tenantsSeenByActions.add(redis.currentTenant());
        });

        // 监听线程：无执行主体、无租户
        PrincipalContext.clear();
        redis.setCurrentTenant(null);
        deliverCancel(manager, redis.topic());

        assertThat(redis.boundTenants()).as("只应按 register 捕获的租户重绑一次").containsExactly(TENANT_ID);
        assertThat(cancelActionRuns.get()).isOne();
        assertThat(finalizerRuns.get()).isOne();
        assertThat(tenantsSeenByActions)
                .as("两个回调都要在重绑后的租户作用域内执行")
                .containsExactly(TENANT_ID, TENANT_ID);
    }

    @Test
    void cancelLocalFallsBackToDirectRunWithoutBinderPort() {
        // 端口缺席（独立应用没有租户拦截器）时保持原形态：收尾直接执行，不能因缺端口丢取消
        TenantMappedRedis redis = new TenantMappedRedis();
        AtomicInteger finalizerRuns = new AtomicInteger();

        PrincipalContext.set(principal(TENANT_ID));
        redis.setCurrentTenant(TENANT_ID);
        StreamTaskManager manager = new StreamTaskManager(redis.client(), nullBinderProvider());
        manager.register(TASK_ID, OWNER_ID, finalizerRuns::incrementAndGet);

        PrincipalContext.clear();
        redis.setCurrentTenant(null);
        deliverCancel(manager, redis.topic());

        assertThat(finalizerRuns.get()).isOne();
        assertThat(redis.boundTenants()).isEmpty();
    }

    @Test
    void cancelFinalizerMustPersistUnderReboundPrincipalAndTenant() {
        // 债务 i 的完整链（真机同形，A000001）：监听线程无主体、无租户 → 落库链先吃
        // PrincipalContext.require()（no execution principal in current context——此前 fake 门
        // 只查租户，单测才全绿真机红），再吃 MyBatis 租户行拦截器（tenant.context.missing）。
        // 只有身份与租户双重重绑到位才绿；失败形态与 AgentStreamEventBridge 一致：只留痕、不外抛。
        TenantMappedRedis redis = new TenantMappedRedis();
        AtomicReference<String> persistOutcome = new AtomicReference<>();
        AtomicBoolean originSeenInFinalizer = new AtomicBoolean(false);
        Runnable persistence = () -> {
            try {
                ExecutionPrincipal principal = PrincipalContext.require();
                originSeenInFinalizer.set(PrincipalContext.isSynchronousOrigin());
                String tenant = redis.currentTenant();
                if (tenant == null || tenant.isBlank()) {
                    throw new ClientException("tenant.context.missing");
                }
                persistOutcome.set("persisted:" + tenant + ":" + principal.tenantId() + ":" + principal.membershipId());
            } catch (Exception e) {
                persistOutcome.set("failed:" + e.getMessage());
            }
        };

        PrincipalContext.set(principal(TENANT_ID));
        redis.setCurrentTenant(TENANT_ID);
        StreamTaskManager manager = new StreamTaskManager(redis.client(), binderProvider(redis.binder()));
        manager.register(TASK_ID, OWNER_ID, persistence);

        // 监听线程：无执行主体、无租户
        PrincipalContext.clear();
        redis.setCurrentTenant(null);
        deliverCancel(manager, redis.topic());

        assertThat(persistOutcome.get())
                .as("取消收尾的落库要在捕获身份与租户里执行，否则 no execution principal / tenant.context.missing")
                .isEqualTo("persisted:" + TENANT_ID + ":" + TENANT_ID + ":platform:" + TENANT_ID + ":1");
        assertThat(originSeenInFinalizer.get())
                .as("恢复只授 holder、不授同步起源（(a′) 语义；直接杀 set↔restore 互换）")
                .isFalse();
    }

    @Test
    void cancelLocalMustRestoreListenerThreadPrincipal() {
        // 监听线程池共享：收尾包裹结束必须把线程原主体原样放回（restore(previous)，不是 clear）。
        // 预置「别的」主体模拟池化残留；若改成 clear，主体被抹掉同样是身份卫生事故。
        TenantMappedRedis redis = new TenantMappedRedis();
        AtomicInteger finalizerRuns = new AtomicInteger();
        ExecutionPrincipal listenerResidue = principal("T-B");

        PrincipalContext.set(principal(TENANT_ID));
        redis.setCurrentTenant(TENANT_ID);
        StreamTaskManager manager = new StreamTaskManager(redis.client(), binderProvider(redis.binder()));
        manager.register(TASK_ID, OWNER_ID, finalizerRuns::incrementAndGet);

        PrincipalContext.set(listenerResidue);
        redis.setCurrentTenant(null);
        deliverCancel(manager, redis.topic());

        assertThat(finalizerRuns.get()).isOne();
        assertThat(PrincipalContext.get())
                .as("收尾后监听线程主体必须原样放回，不能留捕获身份、也不能清掉原主体")
                .isSameAs(listenerResidue);
    }

    @Test
    void cancelLocalMustRestorePrincipalEvenWhenCancelActionThrows() {
        // 线程池共享：cancelAction（上游流中断动作）抛错向外传播时，finally 仍须把监听线程
        // 原主体放回——不是 finally 恢复的形态会把捕获身份留给下一个任务（跨租户污染）
        TenantMappedRedis redis = new TenantMappedRedis();
        ExecutionPrincipal listenerResidue = principal("T-B");

        PrincipalContext.set(principal(TENANT_ID));
        redis.setCurrentTenant(TENANT_ID);
        StreamTaskManager manager = new StreamTaskManager(redis.client(), binderProvider(redis.binder()));
        manager.register(TASK_ID, OWNER_ID, () -> {
        });
        manager.bindHandle(TASK_ID, () -> {
            throw new IllegalStateException("upstream interrupt failed");
        });

        PrincipalContext.set(listenerResidue);
        redis.setCurrentTenant(null);
        assertThatThrownBy(() -> deliverCancel(manager, redis.topic()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(PrincipalContext.get())
                .as("抛错路径也必须把线程原主体恢复原样")
                .isSameAs(listenerResidue);
    }

    @Test
    void interleavedCancelsKeepEachCapturedPrincipal() {
        // 两个任务、两个主体：捕获必须跟着任务记录走；会话级/静态单一捕获会让
        // 另一个任务的收尾带着别人的身份跑（跨租户污染）
        TenantMappedRedis redis = new TenantMappedRedis();
        Map<String, String> persisted = new HashMap<>();
        String taskA = TASK_ID + "-a";
        String taskB = TASK_ID + "-b";

        PrincipalContext.set(principal("T-A"));
        redis.setCurrentTenant("T-A");
        StreamTaskManager manager = new StreamTaskManager(redis.client(), binderProvider(redis.binder()));
        manager.register(taskA, OWNER_ID, () -> persisted.put(taskA, identityOrFailure()));
        PrincipalContext.set(principal("T-B"));
        redis.setCurrentTenant("T-B");
        manager.register(taskB, OWNER_ID, () -> persisted.put(taskB, identityOrFailure()));

        // 交错投递：B 先于 A
        PrincipalContext.clear();
        redis.setCurrentTenant(null);
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
        TenantMappedRedis redis = new TenantMappedRedis();
        AtomicBoolean finalizerSawNoPrincipal = new AtomicBoolean();
        AtomicInteger finalizerRuns = new AtomicInteger();
        StreamTaskManager manager = new StreamTaskManager(redis.client(), binderProvider(redis.binder()));

        PrincipalContext.clear();
        manager.register(TASK_ID, OWNER_ID, () -> {
            finalizerRuns.incrementAndGet();
            finalizerSawNoPrincipal.set(!PrincipalContext.hasPrincipal());
        });

        deliverCancel(manager, redis.topic());

        assertThat(finalizerRuns.get()).isOne();
        assertThat(finalizerSawNoPrincipal.get()).as("无捕获主体时收尾按原形态直跑").isTrue();
    }

    @Test
    void unregisterMustDeletePrefixedKeysUnderCapturedTenant() {
        // 键对称（VERDICT §5-6）：cancelKey/ownerKey 由请求线程（有租户）带前缀写入，
        // 收尾链在无租户线程直接删会解析成裸键、把带前缀键残留到 TTL(30m)
        TenantMappedRedis redis = new TenantMappedRedis();
        String prefixedCancelKey = TENANT_ID + ":" + CANCEL_KEY;
        String prefixedOwnerKey = TENANT_ID + ":" + OWNER_KEY;

        PrincipalContext.set(principal(TENANT_ID));
        redis.setCurrentTenant(TENANT_ID);
        StreamTaskManager manager = new StreamTaskManager(redis.client(), binderProvider(redis.binder()));
        manager.register(TASK_ID, OWNER_ID, () -> {
        });
        login(OWNER_ID);
        manager.cancelByUser(TASK_ID);

        assertThat(redis.containsKey(prefixedOwnerKey)).as("写入侧：属主键应带租户前缀").isTrue();
        assertThat(redis.containsKey(prefixedCancelKey)).as("写入侧：标记键应带租户前缀").isTrue();

        // 收尾线程：无主体、无租户。
        // 标记写入（cancelByUser）已按捕获租户绑定过一次，这里单独看删除侧那一次
        int boundBeforeDelete = redis.boundTenants().size();
        UserContext.clear();
        PrincipalContext.clear();
        redis.setCurrentTenant(null);
        manager.unregister(TASK_ID);

        assertThat(redis.containsKey(prefixedCancelKey)).as("带前缀的标记键必须被删掉").isFalse();
        assertThat(redis.containsKey(prefixedOwnerKey)).as("带前缀的属主键必须被删掉").isFalse();
        assertThat(redis.boundTenants().subList(boundBeforeDelete, redis.boundTenants().size()))
                .as("删除侧要按捕获租户重绑")
                .containsExactly(TENANT_ID);
    }

    @Test
    void systemCancelMarkerMustFollowCapturedTenantNamespace() {
        // 键对称（写入侧）：系统断连路径没有租户，若标记落到裸键上，
        // register 在租户线程读带前缀键读不到，收尾侧也删不掉——按捕获租户重绑后再写
        TenantMappedRedis redis = new TenantMappedRedis();
        PrincipalContext.set(principal(TENANT_ID));
        redis.setCurrentTenant(TENANT_ID);
        StreamTaskManager manager = new StreamTaskManager(redis.client(), binderProvider(redis.binder()));
        manager.register(TASK_ID, OWNER_ID, () -> {
        });

        // 断连兜底：容器回调线程，无租户
        PrincipalContext.clear();
        redis.setCurrentTenant(null);
        manager.cancel(TASK_ID);

        assertThat(redis.containsKey(TENANT_ID + ":" + CANCEL_KEY))
                .as("标记必须落在 register 读取侧的同一键形态上")
                .isTrue();
        assertThat(redis.containsKey(CANCEL_KEY)).as("不得在裸键上留下标记").isFalse();
    }

    @Test
    void registerMustSeeSystemCancelThatArrivedBeforeRegister() {
        // 竞态 iii：系统断连先于 register 到达时，Redis 标记写在无租户线程的裸键上，
        // 而 register 在租户线程读带前缀键——本地留痕这一形态必须在 register 复核时被认到
        TenantMappedRedis redis = new TenantMappedRedis();
        StreamTaskManager manager = new StreamTaskManager(redis.client(), binderProvider(redis.binder()));
        AtomicInteger finalized = new AtomicInteger();

        // 断连：容器回调线程，无租户、无主体
        manager.cancel(TASK_ID);
        assertThat(redis.containsKey(CANCEL_KEY)).as("系统路径此刻只能在裸键上留标记").isTrue();

        // register：请求线程，租户 T-A
        redis.setCurrentTenant(TENANT_ID);
        PrincipalContext.set(principal(TENANT_ID));
        try {
            manager.register(TASK_ID, OWNER_ID, finalized::incrementAndGet);
        } finally {
            PrincipalContext.clear();
        }

        assertThat(finalized.get()).as("预埋的系统取消必须在 register 复核时被认到").isOne();
        assertThat(manager.isCancelled(TASK_ID)).isTrue();
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

    /**
     * 让指定管理器订阅并取回监听器（监听线程模拟：无登录用户、无租户）
     */
    @SuppressWarnings("unchecked")
    private static MessageListener<String> listen(StreamTaskManager manager, RTopic topicMock) {
        manager.subscribe();
        ArgumentCaptor<MessageListener<String>> captor = ArgumentCaptor.forClass(MessageListener.class);
        verify(topicMock).addListener(eq(String.class), captor.capture());
        return captor.getValue();
    }

    /**
     * 让指定管理器订阅并投递一条系统取消广播（监听线程：无登录用户、无租户）
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

    private void login(String userId) {
        UserContext.set(LoginUser.builder().userId(userId).build());
    }

    private static ExecutionPrincipal principal(String tenantId) {
        return new ExecutionPrincipal(tenantId, "1", "platform:" + tenantId + ":1", 1, 1,
                Set.of(), "stream-task-test", "test", 1, Long.MAX_VALUE);
    }

    /**
     * 租户感知的假 Redis：getBucket(name) 按当前租户映射键名（TenantKeyPrefixHandler 的等价镜像：
     * 无租户不加前缀，有租户且名字未带前缀时加 {@code <tenantId>:}），值放共享 Map；
     * 用来核对写入侧与删除侧的键形态是否对称，以及收尾回调在什么租户作用域里执行。
     */
    private static final class TenantMappedRedis {

        private final Map<String, String> store = new HashMap<>();
        private final Map<String, RBucket<String>> buckets = new HashMap<>();
        private final AtomicReference<String> currentTenant = new AtomicReference<>();
        private final List<String> boundTenants = new ArrayList<>();
        private final RTopic topic;
        private final RedissonClient client;

        private TenantMappedRedis() {
            topic = mock(RTopic.class);
            client = mock(RedissonClient.class);
            when(client.getTopic(anyString())).thenReturn(topic);
            when(client.<String>getBucket(anyString())).thenAnswer(invocation ->
                    bucketFor(mapped(invocation.getArgument(0))));
        }

        private RedissonClient client() {
            return client;
        }

        private RTopic topic() {
            return topic;
        }

        private void setCurrentTenant(String tenantId) {
            currentTenant.set(tenantId);
        }

        private String currentTenant() {
            return currentTenant.get();
        }

        private List<String> boundTenants() {
            return boundTenants;
        }

        private boolean containsKey(String mappedKey) {
            return store.containsKey(mappedKey);
        }

        private String mapped(String name) {
            String tenantId = currentTenant.get();
            if (tenantId == null || tenantId.isBlank() || name.startsWith(tenantId)) {
                return name;
            }
            return tenantId + ":" + name;
        }

        /**
         * 端口实现（RuoYiTenantScopeBinder 的等价镜像）：进入时设当前租户、退出时还原，
         * 与 TenantHelper.dynamic 的进入/清理语义一致。
         */
        private void runWithTenant(String tenantId, Runnable handle) {
            boundTenants.add(tenantId);
            String previous = currentTenant.get();
            currentTenant.set(tenantId);
            try {
                handle.run();
            } finally {
                currentTenant.set(previous);
            }
        }

        private TenantScopeBinder binder() {
            return this::runWithTenant;
        }

        @SuppressWarnings("unchecked")
        private RBucket<String> bucketFor(String mappedKey) {
            return buckets.computeIfAbsent(mappedKey, key -> {
                RBucket<String> bucket = mock(RBucket.class);
                doAnswer(invocation -> {
                    store.put(key, invocation.getArgument(0));
                    return null;
                }).when(bucket).set(anyString(), any(Duration.class));
                doAnswer(invocation -> store.get(key)).when(bucket).get();
                doAnswer(invocation -> {
                    store.remove(key);
                    return null;
                }).when(bucket).deleteAsync();
                return bucket;
            });
        }
    }

    /** 端口缺席形态（独立应用没有租户拦截器，原行为直执） */
    private static ObjectProvider<TenantScopeBinder> nullBinderProvider() {
        return binderProvider(null);
    }

    private static ObjectProvider<TenantScopeBinder> binderProvider(TenantScopeBinder binder) {
        return new ObjectProvider<>() {
            @Override
            public TenantScopeBinder getObject() {
                if (binder == null) {
                    throw new IllegalStateException("no binder");
                }
                return binder;
            }

            @Override
            public TenantScopeBinder getObject(Object... args) {
                return getObject();
            }

            @Override
            public TenantScopeBinder getIfAvailable() {
                return binder;
            }

            @Override
            public TenantScopeBinder getIfUnique() {
                return binder;
            }
        };
    }
}
