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

import cn.hutool.core.util.StrUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.nageoffer.ai.ragent.framework.context.AsyncPrincipalReference;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式任务跨节点取消管理器
 * <p>
 * 机制层与引擎无关：注册表 + Redis 标记 + 广播主题构成统一的取消协议，
 * 各引擎的个性化收尾动作（补发事件、中断上游流等）以回调形式注入
 *
 * <p><b>租户重绑定（ENV-B5b 同型，F10 op1）。</b>register 在请求线程捕获租户存进任务记录；
 * 取消收尾跑在监听线程（无租户）上，必须经 {@link TenantScopeBinder} 重绑定同一租户——
 * 否则收尾落库被 MyBatis 租户行拦截器 fail-closed 拒绝（{@code tenant.context.missing}、
 * 取消消息丢失），且 unregister 的键删除会解析成无前缀键、把写入侧的带前缀键残留到 TTL。
 * 端口缺席（独立应用）时按原形态直接执行。
 *
 * <p><b>执行主体重绑定（F10 op2 债务 i）。</b>register 在请求线程同时捕获身份引用
 * （{@link AsyncPrincipalReference}，冻结口径「异步只传身份引用」，绝不传 bearer/完整主体）；
 * 取消收尾链的落库门是 {@code PrincipalContext.require()}——缺即 {@code no execution principal}
 * （真机 A000001 根因），监听线程上先用<b>非配对</b> {@code restore()} 恢复引用（只授 holder、
 * 不授同步起源，勿用 {@code set()}，见 P2-SCOUT §3 (a′)），外层 {@code finally} 无条件恢复
 * 线程原主体；租户重绑在内层，两段收尾包在同一个 Runnable 里。用户 stop 与系统断连
 * 都汇入本方法，同一修复一并覆盖。
 */
@Slf4j
@Component
public class StreamTaskManager {

    // 跨节点取消频道必须带 global: 前缀：订阅发生在容器启动线程（无租户），发布发生在 stop 请求线程（有租户），
    // 只有 global: 能让 TenantKeyPrefixHandler 短路租户前缀，两端才映射到同一条频道；与 global:sse / global:websocket 同款
    private static final String CANCEL_TOPIC = "global:ragent:stream:cancel";
    private static final String CANCEL_KEY_PREFIX = "ragent:stream:cancel:";
    private static final String OWNER_KEY_PREFIX = "ragent:stream:owner:";
    private static final Duration CANCEL_TTL = Duration.ofMinutes(30);

    /**
     * 系统侧回收的发起方占位，与任何用户 ID 都不会撞（用户 ID 是雪花数字串）
     */
    private static final String SYSTEM_REQUESTER = "__system__";

    /**
     * 广播载荷 taskId|requester 的分隔符：taskId 与用户 ID 都是雪花数字串，不含竖线
     */
    private static final String PAYLOAD_SEPARATOR = "|";

    private final Cache<String, StreamTaskInfo> tasks = CacheBuilder.newBuilder()
            .expireAfterWrite(CANCEL_TTL)
            .maximumSize(10000)  // 限制最大数量，基本上不可能超出这个数量。如果觉得不稳妥，可以把值调大并在配置文件声明
            .build();

    private final RedissonClient redissonClient;

    /**
     * 租户作用域绑定端口（ENV-B5b 同型，F10 op1）：监听线程无租户，取消收尾里的
     * 落库与键删除都要重绑 register 侧捕获的租户。端口缺席（独立应用）时按原形态直接执行。
     */
    private final ObjectProvider<TenantScopeBinder> tenantScopeBinder;

    private int listenerId = -1;

    public StreamTaskManager(RedissonClient redissonClient, ObjectProvider<TenantScopeBinder> tenantScopeBinder) {
        this.redissonClient = redissonClient;
        this.tenantScopeBinder = tenantScopeBinder;
    }

    @PostConstruct
    public void subscribe() {
        RTopic topic = redissonClient.getTopic(CANCEL_TOPIC);
        listenerId = topic.addListener(String.class, (channel, payload) -> {
            if (StrUtil.isBlank(payload)) {
                return;
            }
            int separator = payload.indexOf(PAYLOAD_SEPARATOR);
            // 滚动升级期老节点仍广播裸 taskId，它在发布前已做过同样的属主校验，按系统侧收
            String taskId = separator < 0 ? payload : payload.substring(0, separator);
            String requester = separator < 0 ? SYSTEM_REQUESTER : payload.substring(separator + 1);
            cancelLocal(taskId, requester);
        });
    }

    @PreDestroy
    public void unsubscribe() {
        if (listenerId == -1) {
            return;
        }
        redissonClient.getTopic(CANCEL_TOPIC).removeListener(listenerId);
    }

    /**
     * 注册取消收尾回调并记下属主，回调负责补发终止事件并结束响应流
     * 若任务已被属主或系统标记取消，回调立即执行
     */
    public void register(String taskId, String ownerUserId, Runnable onCancelFinalizer) {
        StreamTaskInfo taskInfo = getOrCreate(taskId);
        // 属主必须先于收尾回调落地：反过来的话，两条赋值之间到达的广播能拿本地回调杀掉一条无主的流
        taskInfo.ownerUserId = ownerUserId;
        taskInfo.finalizer = onCancelFinalizer;
        // 捕获注册侧身份引用与租户（请求线程）：取消收尾在监听线程上执行，落库链先吃
        // PrincipalContext.require()（缺即 no execution principal，fail-closed）、再吃 MyBatis
        // 租户行拦截器——身份按冻结口径只传引用（无 scopes/无原 jti/无签发方材料，不可当 bearer
        // 重放），租户从同一捕获派生防两处来源漂移；无主体（独立应用）则留空直跑
        taskInfo.principalRef = PrincipalContext.hasPrincipal()
                ? AsyncPrincipalReference.from(PrincipalContext.require())
                : null;
        taskInfo.tenantId = taskInfo.principalRef == null ? null : taskInfo.principalRef.tenantId();
        // 属主进 Redis 而非只留本地：停止请求可能落在没跑这条流的节点上
        if (StrUtil.isNotBlank(ownerUserId)) {
            RBucket<String> owner = redissonClient.getBucket(ownerKey(taskId));
            owner.set(ownerUserId, CANCEL_TTL);
        }
        if (isTaskCancelledInRedis(taskId, taskInfo)) {
            onCancelFinalizer.run();
        }
    }

    /**
     * 绑定上游流的中断动作，取消时先于收尾回调执行
     * 任务已结算注销时不再创建记录，否则会留下一条无人触发的幽灵任务
     */
    public void bindHandle(String taskId, Runnable cancelAction) {
        StreamTaskInfo taskInfo = tasks.getIfPresent(taskId);
        if (taskInfo == null) {
            return;
        }
        taskInfo.cancelAction = cancelAction;
        if (taskInfo.cancelled.get() && cancelAction != null) {
            cancelAction.run();
        }
    }

    public boolean isCancelled(String taskId) {
        StreamTaskInfo info = tasks.getIfPresent(taskId);
        return info != null && info.cancelled.get();
    }

    /**
     * 系统侧回收（SSE 超时、客户端断连），容器回调线程上没有登录用户可比对
     */
    public void cancel(String taskId) {
        // 断连可能早于 register：那一刻本方法跑在无租户的容器回调线程上，Redis 标记会落在
        // 无前缀键上，而 register 在租户线程只能读到带前缀键——预埋标记读不到（窄竞态）。
        // emitter 与 register 同节点同实例，故未登记时先在本地记下这一形态，register 复核时补收。
        if (tasks.getIfPresent(taskId) == null) {
            getOrCreate(taskId).pendingSystemCancel = true;
        }
        publishCancel(taskId, SYSTEM_REQUESTER);
    }

    /**
     * 用户主动停止：taskId 是雪花 ID，时间有序可预测，不是访问凭证，必须比对属主
     */
    public void cancelByUser(String taskId) {
        String requester = UserContext.requireUser().getUserId();
        RBucket<String> owner = redissonClient.getBucket(ownerKey(taskId));
        String ownerUserId = owner.get();
        if (StrUtil.isNotBlank(ownerUserId) && !ownerUserId.equals(requester)) {
            log.warn("拒绝越权停止流式任务，taskId：{}，属主：{}，发起方：{}", taskId, ownerUserId, requester);
            // 不区分「不存在」与「非属主」，免得停止接口变成他人任务的探测器
            throw new ClientException("任务不存在或已结束");
        }
        // 属主查不到多半是任务已结束，也可能是注册还没落地，故标记带上发起方交给注册那一刻复核
        publishCancel(taskId, requester);
    }

    private void publishCancel(String taskId, String requester) {
        // 先设置 Redis 标记，再发布消息
        // 标记要和 register 的读取侧保持同一键形态：已登记的任务按捕获租户重绑后再写，
        // 免得无租户的系统路径把标记落到裸键上（read 侧在租户线程读带前缀键）且收尾侧删不掉
        Runnable writeMarker = () -> redissonClient.getBucket(cancelKey(taskId)).set(requester, CANCEL_TTL);
        StreamTaskInfo taskInfo = tasks.getIfPresent(taskId);
        String tenantId = taskInfo == null ? null : taskInfo.tenantId;
        TenantScopeBinder tenantBinder = tenantScopeBinder.getIfAvailable();
        if (tenantBinder != null && tenantId != null) {
            tenantBinder.runWithTenant(tenantId, writeMarker);
        } else {
            writeMarker.run();
        }

        // 发布消息通知所有节点（包括本地）
        // 本地节点也通过监听器统一处理，避免重复调用 cancelLocal
        // 载荷带上发起方：执行端要靠它复核，只有发布端校验挡不住属主落地前的抢跑
        redissonClient.getTopic(CANCEL_TOPIC).publish(taskId + PAYLOAD_SEPARATOR + requester);
    }

    /**
     * 检查任务是否在 Redis 中被标记为已取消，是则同步状态到本地缓存
     * 标记可能先于注册到达，那一刻还没有属主可比对，只能推到这里复核
     */
    private boolean isTaskCancelledInRedis(String taskId, StreamTaskInfo taskInfo) {
        if (taskInfo.cancelled.get()) {
            return true;
        }

        // 预埋取消的两种形态都要复核。形态一：系统断连在 register 之前到达，已记在本地——
        // 它写在无租户线程上，Redis 标记落的是无前缀键，本方法在租户线程读的是带前缀键，只能内存里认。
        if (taskInfo.pendingSystemCancel) {
            taskInfo.cancelled.set(true);
            return true;
        }

        // 形态二：Redis 标记（属主/系统在租户线程写入，键形态与上方读取侧一致）
        RBucket<String> bucket = redissonClient.getBucket(cancelKey(taskId));
        String requester = bucket.get();
        if (requester == null) {
            return false;
        }
        if (!isRequesterAllowed(taskInfo, requester)) {
            log.warn("忽略非属主埋下的取消标记，taskId：{}，属主：{}，发起方：{}", taskId, taskInfo.ownerUserId, requester);
            return false;
        }
        taskInfo.cancelled.set(true);
        return true;
    }

    /**
     * 系统侧回收无条件放行；用户侧只认精确属主，属主还没落地（注册未发生）时一律不认
     * 这正是预埋标记要在 register 那一刻复核的窗口，本地放过去反而绕开了复核
     */
    private boolean isRequesterAllowed(StreamTaskInfo taskInfo, String requester) {
        if (SYSTEM_REQUESTER.equals(requester)) {
            return true;
        }
        return StrUtil.isNotBlank(taskInfo.ownerUserId) && taskInfo.ownerUserId.equals(requester);
    }

    private void cancelLocal(String taskId, String requester) {
        StreamTaskInfo taskInfo = tasks.getIfPresent(taskId);
        if (taskInfo == null) {
            return;
        }

        // 执行端复核发起方：taskId 时间有序可预测，越权取消喷得中就成
        // 不匹配时连 cancelled 都不置——置了会让 register 的复核短路，等于把标记复核那道门绕开
        if (!isRequesterAllowed(taskInfo, requester)) {
            log.warn("拒绝越权取消流式任务，taskId：{}，属主：{}，发起方：{}", taskId, taskInfo.ownerUserId, requester);
            return;
        }

        // 使用 CAS 确保只执行一次
        if (!taskInfo.cancelled.compareAndSet(false, true)) {
            return;
        }

        Runnable cancelActions = () -> {
            if (taskInfo.cancelAction != null) {
                taskInfo.cancelAction.run();
            }

            // 在取消时执行收尾回调，保存已累积的内容
            if (taskInfo.finalizer != null) {
                taskInfo.finalizer.run();
            }
        };
        // 监听线程没有执行主体：收尾链的落库门是 PrincipalContext.require()（真机 A000001
        // 根因），先恢复注册侧捕获的身份引用——用非配对 restore（只授 holder、不授同步起源；
        // 勿用 set，它会在共享监听线程上临时打开 chat egress 闸）；租户重绑在内层，两段收尾
        // 同包在一个 Runnable 里。外层 finally 无条件恢复线程原主体（线程池共享，泄漏=跨租户污染）。
        Runnable tenantBound = () -> {
            TenantScopeBinder tenantBinder = tenantScopeBinder.getIfAvailable();
            if (tenantBinder != null && taskInfo.tenantId != null) {
                tenantBinder.runWithTenant(taskInfo.tenantId, cancelActions);
            } else {
                cancelActions.run();
            }
        };
        AsyncPrincipalReference principalRef = taskInfo.principalRef;
        if (principalRef == null) {
            tenantBound.run();
            return;
        }
        ExecutionPrincipal previous = PrincipalContext.get();
        PrincipalContext.restore(principalRef.toExecutionPrincipal());
        try {
            tenantBound.run();
        } finally {
            PrincipalContext.restore(previous);
        }
    }

    public void unregister(String taskId) {
        // 捕获租户必须在失效本地缓存之前取出
        StreamTaskInfo taskInfo = tasks.getIfPresent(taskId);
        String tenantId = taskInfo == null ? null : taskInfo.tenantId;

        // 清理本地缓存
        tasks.invalidate(taskId);

        // 清理Redis：写入侧（register / stop 请求线程）的键带租户前缀，而收尾链（监听/回调线程）
        // 没有租户，直接删会解析成无前缀键，把带前缀键残留到 TTL（30m）——按捕获租户重绑后删。
        // 无捕获租户（启动即失败、独立应用）时按原形态直接删。
        Runnable deleteKeys = () -> {
            redissonClient.getBucket(cancelKey(taskId)).deleteAsync();
            redissonClient.getBucket(ownerKey(taskId)).deleteAsync();
        };
        TenantScopeBinder tenantBinder = tenantScopeBinder.getIfAvailable();
        if (tenantBinder != null && tenantId != null) {
            tenantBinder.runWithTenant(tenantId, deleteKeys);
        } else {
            deleteKeys.run();
        }
    }

    private String cancelKey(String taskId) {
        return CANCEL_KEY_PREFIX + taskId;
    }

    private String ownerKey(String taskId) {
        return OWNER_KEY_PREFIX + taskId;
    }

    @SneakyThrows
    private StreamTaskInfo getOrCreate(String taskId) {
        return tasks.get(taskId, StreamTaskInfo::new);
    }

    private static final class StreamTaskInfo {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile String ownerUserId;
        /**
         * register 侧捕获的身份引用（F10 op2 债务 i）：收尾链在监听线程上恢复它，
         * 只用于租户定位与归属记录，不是授权证据（恢复体无 scopes、无同步起源）。
         */
        private volatile AsyncPrincipalReference principalRef;
        private volatile String tenantId;
        private volatile boolean pendingSystemCancel;
        private volatile Runnable cancelAction;
        private volatile Runnable finalizer;
    }
}
