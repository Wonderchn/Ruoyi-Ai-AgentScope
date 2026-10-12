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

package com.nageoffer.ai.ragent.agent.identity;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 轮次身份作用域登记表（s2-tenant-cmp-a1，2026-10-12）。
 *
 * <p><b>为什么需要。</b>轮末状态保存由框架在共享池上回调 {@code AgentStateStore}
 * （{@code ReActAgent.saveStateToSession}），调用栈里没有任何受理期上下文，
 * 库回调只给 {@code (userId, sessionId)} 这一对键。请求线程在轮次受理时把捕获的
 * {@link AgentRunScope} 登记到同一对键上，存储实现在每次 DAO 访问前取出重绑；
 * 释放钩子在补存盘之后、放闸之前注销——放闸后同一 (user, 会话) 的新轮会重新登记
 * 同一键，注销若放到最后会把新轮的登记顶掉。
 *
 * <p><b>边界。</b>查不到＝本线程不属于任何已登记轮次，消费方按原形态直跑
 * （fail-closed 语义不变，不允许把池化残留当身份）。键含会话 ID（雪花全局唯一），
 * 同号用户跨租户也不会互相命中；值不可变，并发读写由 ConcurrentHashMap 保证。
 */
public class AgentRunScopeRegistry {

    private final ConcurrentMap<Key, AgentRunScope> scopes = new ConcurrentHashMap<>();

    /** 登记本轮捕获；scope 为 {@code null}（无捕获形态）或键不全时为空操作。 */
    public void register(String userId, String sessionId, AgentRunScope scope) {
        if (scope == null || userId == null || sessionId == null) {
            return;
        }
        scopes.put(new Key(userId, sessionId), scope);
    }

    /** 注销（幂等）；键不全时为空操作。 */
    public void unregister(String userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }
        scopes.remove(new Key(userId, sessionId));
    }

    /** 查登记；未命中返回 {@code null}。 */
    public AgentRunScope resolve(String userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return null;
        }
        return scopes.get(new Key(userId, sessionId));
    }

    private record Key(String userId, String sessionId) {
    }
}
