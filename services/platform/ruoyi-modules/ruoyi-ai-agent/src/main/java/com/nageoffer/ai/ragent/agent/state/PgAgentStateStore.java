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

package com.nageoffer.ai.ragent.agent.state;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper;
import com.nageoffer.ai.ragent.agent.identity.AgentRunScope;
import com.nageoffer.ai.ragent.agent.identity.AgentRunScopeRegistry;
import com.nageoffer.ai.ragent.agent.memory.AsyncMemoryTaskScope;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * AgentStateStore 的 PostgreSQL 实现
 * 官方 2.0.2 仅有 in-memory / JSON 文件 / Redis / MySQL，本项目主存储为 PG，故自实现挂 ai_agent_state
 * payload 是 AgentScope 自有编解码的不透明 JSON，不与业务表建立结构约定
 *
 * <p><b>P1.3d：租户/成员进键，匿名回退移除。</b>ai_agent_state 的主键是
 * {@code (tenant_id, member_id, session_id, state_key)}，而 {@link AgentStateStore}
 * 接口的方法签名（外部库）固定为 userId 形态，因此租户与成员只能在本实现内部、
 * <b>每次访问 DAO 之前</b>从可信执行主体解析：没有主体即拒绝（fail-closed），
 * 不再把匿名请求落成 {@code __anon__} 行——那曾是一个所有匿名流量共享的全局命名空间，
 * 与隔离语义不相容。user_id 仅作为展示/legacy 引用写入，不参与键。
 */
@RequiredArgsConstructor
public class PgAgentStateStore implements AgentStateStore {

    private final AgentStateMapper agentStateMapper;
    private boolean provenanceRequired;
    private com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService sourceAuthorization;
    /**
     * 轮次身份作用域登记表与租户绑定端口（s2-tenant-cmp-a1，2026-10-12）：轮末状态保存由
     * 框架在共享池上回调本类（{@code ReActAgent.saveStateToSession}），受理期捕获经登记表
     * 按 {@code (userId, sessionId)} 取回重绑；登记表/端口缺席（独立应用、直构测试）按
     * 原形态直跑。装配形状同 {@link #configureSources}。
     */
    private AgentRunScopeRegistry runScopeRegistry;
    private TenantScopeBinder tenantScopeBinder;

    @org.springframework.beans.factory.annotation.Autowired
    public void configureSources(@org.springframework.beans.factory.annotation.Value("${ai.integration.enabled:false}") boolean required,
            org.springframework.beans.factory.ObjectProvider<com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService> sources) {
        this.provenanceRequired=required; this.sourceAuthorization=sources.getIfAvailable();
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void configureRunScope(
            org.springframework.beans.factory.ObjectProvider<AgentRunScopeRegistry> registryProvider,
            org.springframework.beans.factory.ObjectProvider<TenantScopeBinder> binderProvider) {
        this.runScopeRegistry = registryProvider == null ? null : registryProvider.getIfAvailable();
        this.tenantScopeBinder = binderProvider == null ? null : binderProvider.getIfAvailable();
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        inRunScope(userId, sessionId, () -> {
            Scope scope = requireScope();
            // 展示引用取可信主体上的 userId，不取 AgentScope 运行时入参（那是可伪造的展示值）
            agentStateMapper.upsert(scope.tenantId(), scope.memberId(), scope.displayUserId(),
                    sessionId, key, JsonUtils.getJsonCodec().toJson(value));
            return null;
        });
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        inRunScope(userId, sessionId, () -> {
            Scope scope = requireScope();
            agentStateMapper.upsert(scope.tenantId(), scope.memberId(), scope.displayUserId(),
                    sessionId, key, JsonUtils.getJsonCodec().toJson(values));
            return null;
        });
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        String payload = inRunScope(userId, sessionId, () -> queryPayload(userId, sessionId, key));
        if (StrUtil.isBlank(payload)) {
            return Optional.empty();
        }
        return Optional.ofNullable(JsonUtils.getJsonCodec().fromJson(payload, type));
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) {
        String payload = inRunScope(userId, sessionId, () -> queryPayload(userId, sessionId, key));
        if (StrUtil.isBlank(payload)) {
            return List.of();
        }
        List<?> rawItems = JsonUtils.getJsonCodec().fromJson(payload, List.class);
        List<T> result = new ArrayList<>(rawItems.size());
        for (Object item : rawItems) {
            result.add(JsonUtils.getJsonCodec().convertValue(item, itemType));
        }
        return result;
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return inRunScope(userId, sessionId, () -> {
            Scope scope = requireScope();
            return agentStateMapper.exists(scope.tenantId(), scope.memberId(), sessionId);
        });
    }

    @Override
    public void delete(String userId, String sessionId) {
        inRunScope(userId, sessionId, () -> {
            Scope scope = requireScope();
            agentStateMapper.deleteBySession(scope.tenantId(), scope.memberId(), sessionId);
            return null;
        });
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        inRunScope(userId, sessionId, () -> {
            Scope scope = requireScope();
            agentStateMapper.deleteByKey(scope.tenantId(), scope.memberId(), sessionId, key);
            return null;
        });
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return inRunScope(userId, null, () -> {
            Scope scope = requireScope();
            return new LinkedHashSet<>(agentStateMapper.selectSessionIds(scope.tenantId(), scope.memberId()));
        });
    }

    /**
     * 在受理期登记的本轮作用域内执行 DAO 访问（s2-tenant-cmp-a1）。
     *
     * <p>轮末状态保存由框架在 {@code subscribeOn(boundedElastic)} 上回调本类，池线程上
     * 主体是创建期继承的残留拷贝、动态租户为空；库回调只给 {@code (userId, sessionId)}，
     * 唯一可信来源是受理期登记表。命中即按 {@code AsyncMemoryTaskScope} 形状重绑：
     * 非配对 restore（只授 holder、不授同步起源）＋ {@link TenantScopeBinder} 内层绑租户
     * ＋finally 复原线程原主体；未命中＝原形态直跑（fail-closed 语义不变，残留不得当身份）。
     */
    private <T> T inRunScope(String userId, String sessionId, Supplier<T> body) {
        AgentRunScope scope = runScopeRegistry == null ? null : runScopeRegistry.resolve(userId, sessionId);
        if (scope == null) {
            return body.get();
        }
        return AsyncMemoryTaskScope.callWithResolvedCapture(scope.principal(), scope.tenantId(),
                scope.tenantScopeBinder(), body);
    }

    private String queryPayload(String userId, String sessionId, String key) {
        Scope scope = requireScope();
        if(provenanceRequired){
            var provenance=agentStateMapper.selectProvenance(scope.tenantId(),scope.memberId(),sessionId,key);
            if(sourceAuthorization==null || provenance==null || !sourceAuthorization.sourcesCurrent(PrincipalContext.require(),
                    provenance.sourceRefs(),provenance.policyVersion()==null?0:provenance.policyVersion(),
                    provenance.aclVersion()==null?0:provenance.aclVersion())){return null;}
            return provenance.payload();
        }
        return agentStateMapper.selectPayload(scope.tenantId(), scope.memberId(), sessionId, key);
    }

    /**
     * 访问 DAO 前解析租户作用域：无执行主体直接拒绝，绝不落库。
     */
    private Scope requireScope() {
        ExecutionPrincipal principal = PrincipalContext.require();
        return new Scope(principal.tenantId(), principal.membershipId(), principal.userId());
    }

    /**
     * @param tenantId       租户（键的一部分）
     * @param memberId       canonical membershipId（键的一部分，权威主体引用）
     * @param displayUserId  平台用户 ID（展示/legacy 引用，不参与键）
     */
    private record Scope(String tenantId, String memberId, String displayUserId) {
    }
}
