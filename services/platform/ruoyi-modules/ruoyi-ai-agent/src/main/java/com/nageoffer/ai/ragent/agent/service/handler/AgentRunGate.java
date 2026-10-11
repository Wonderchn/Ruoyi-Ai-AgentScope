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

package com.nageoffer.ai.ragent.agent.service.handler;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 用户维度的 Agent 并发闸门：一个用户同一时刻只跑一条流
 * 与 @IdempotentSubmit 的区别是覆盖整个流生命周期，而非控制器返回 emitter 前的同步窗口
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentRunGate {

    private static final String RUNNING_KEY_PREFIX = "ragent:agent:running:";

    /**
     * 运行位存 taskId|conversationId：删会话时要凭它认出在跑的是不是这一个，两段都是雪花数字串，不含竖线
     */
    private static final String SLOT_SEPARATOR = "|";

    private final RedissonClient redissonClient;
    private final AgentProperties agentProperties;
    /**
     * 租户作用域绑定端口（ENV-B5b 泄漏修复，2026-10-11）：运行位 key 经平台的
     * {@code TenantKeyPrefixHandler} 按**当前租户上下文**加前缀——acquire 在请求线程
     * （有租户）写 `&lt;tenant&gt;:ragent:agent:running:&lt;uid&gt;`；而释放钩子在 agent 回调
     * 线程（无租户）执行，若不再绑定租户会解析成**无前缀键**，CAS 必然打空、槽位泄漏
     * （实证：acquire/release 日志 expected 一致但 actual=null、键残留至 TTL）。
     * 端口缺席（独立应用/无租户拦截器）时按原形态直接执行。
     */
    private final ObjectProvider<TenantScopeBinder> tenantScopeBinder;

    /**
     * 抢运行位，抢不到直接拒绝；返回的释放动作由调用方挂到收尾路上
     */
    public Runnable acquire(String userId, String taskId, String conversationId) {
        String slotValue = taskId + SLOT_SEPARATOR + conversationId;
        // 捕获 acquire 侧租户（请求线程）——释放时必须重新绑定，否则 key 前缀解析不一致
        String tenantId = PrincipalContext.hasPrincipal() ? PrincipalContext.require().tenantId() : null;
        RBucket<String> slot = redissonClient.getBucket(runningKey(userId));
        if (!slot.setIfAbsent(slotValue, ttl())) {
            // 可观测化（ENV-B5b 泄漏调查）：被拒时给出占用者，便于定位挂起的流
            log.warn("agent-run gate busy: user={} holder={}", userId, slot.get());
            throw new ClientException("当前会话处理中，请稍后再发起新的对话");
        }
        log.info("agent-run gate acquired: user={} task={} conv={}", userId, taskId, conversationId);
        return () -> {
            Runnable doRelease = () -> release(userId, slotValue);
            TenantScopeBinder binder = tenantScopeBinder.getIfAvailable();
            if (binder != null && tenantId != null) {
                binder.runWithTenant(tenantId, doRelease);
            } else {
                doRelease.run();
            }
        };
    }

    /**
     * 该用户此刻正跑的流若属于这个会话，返回它的 taskId，否则返回 null
     * 运行位的取值格式只有闸门自己知道，外部拿到的始终是 taskId
     */
    public String runningTaskId(String userId, String conversationId) {
        RBucket<String> slot = redissonClient.getBucket(runningKey(userId));
        String slotValue = slot.get();
        if (StrUtil.isBlank(slotValue)) {
            return null;
        }
        int separator = slotValue.indexOf(SLOT_SEPARATOR);
        if (separator < 0 || !slotValue.substring(separator + 1).equals(conversationId)) {
            return null;
        }
        return slotValue.substring(0, separator);
    }

    /**
     * 只放自己占的位：运行位若被 TTL 挤掉又被下一轮抢走，无条件删会把别人的闸门放掉
     * 重复调用天然安全，值对不上就是空操作
     */
    private void release(String userId, String slotValue) {
        RBucket<String> slot = redissonClient.getBucket(runningKey(userId));
        // 可观测化（ENV-B5b 泄漏调查）：释放结果落到日志——此前的静默空操作
        // 使"流失败后运行位残留"只能靠 TTL 兜底而无可观测证据。
        boolean released = slot.compareAndSet(slotValue, null);
        int sep = slotValue.indexOf(SLOT_SEPARATOR);
        String taskId = sep > 0 ? slotValue.substring(0, sep) : slotValue;
        if (released) {
            log.info("agent-run gate released: user={} task={}", userId, taskId);
        } else {
            log.warn("agent-run gate release skipped (slot changed/expired): user={} expected={} actual={}",
                    userId, slotValue, slot.get());
        }
    }

    /**
     * 进程崩溃时没人来释放，TTL 是唯一出路
     * 取 SSE 超时的两倍：长过任何一条活着的流，又不至于把用户挡到下个小时
     */
    private Duration ttl() {
        return Duration.ofMillis(agentProperties.getSseTimeoutMs() * 2);
    }

    private String runningKey(String userId) {
        return RUNNING_KEY_PREFIX + userId;
    }
}
