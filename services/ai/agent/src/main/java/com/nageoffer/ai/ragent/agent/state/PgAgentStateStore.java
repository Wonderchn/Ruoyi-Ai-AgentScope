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
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * AgentStateStore 的 PostgreSQL 实现
 * 官方 2.0.2 仅有 in-memory / JSON 文件 / Redis / MySQL，本项目主存储为 PG，故自实现挂 t_agent_state
 * payload 是 AgentScope 自有编解码的不透明 JSON，不与业务表建立结构约定
 *
 * <p><b>P1.3d：租户/成员进键，匿名回退移除。</b>t_agent_state 的主键是
 * {@code (tenant_id, member_id, session_id, state_key)}，而 {@link AgentStateStore}
 * 接口的方法签名（外部库）固定为 userId 形态，因此租户与成员只能在本实现内部、
 * <b>每次访问 DAO 之前</b>从可信执行主体解析：没有主体即拒绝（fail-closed），
 * 不再把匿名请求落成 {@code __anon__} 行——那曾是一个所有匿名流量共享的全局命名空间，
 * 与隔离语义不相容。user_id 仅作为展示/legacy 引用写入，不参与键。
 */
@RequiredArgsConstructor
public class PgAgentStateStore implements AgentStateStore {

    private final AgentStateMapper agentStateMapper;

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        Scope scope = requireScope();
        agentStateMapper.upsert(scope.tenantId(), scope.memberId(), scope.displayUserId(userId),
                sessionId, key, JsonUtils.getJsonCodec().toJson(value));
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        Scope scope = requireScope();
        agentStateMapper.upsert(scope.tenantId(), scope.memberId(), scope.displayUserId(userId),
                sessionId, key, JsonUtils.getJsonCodec().toJson(values));
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        String payload = queryPayload(userId, sessionId, key);
        if (StrUtil.isBlank(payload)) {
            return Optional.empty();
        }
        return Optional.ofNullable(JsonUtils.getJsonCodec().fromJson(payload, type));
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) {
        String payload = queryPayload(userId, sessionId, key);
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
        Scope scope = requireScope();
        return agentStateMapper.exists(scope.tenantId(), scope.memberId(), sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        Scope scope = requireScope();
        agentStateMapper.deleteBySession(scope.tenantId(), scope.memberId(), sessionId);
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        Scope scope = requireScope();
        agentStateMapper.deleteByKey(scope.tenantId(), scope.memberId(), sessionId, key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        Scope scope = requireScope();
        return new LinkedHashSet<>(agentStateMapper.selectSessionIds(scope.tenantId(), scope.memberId()));
    }

    private String queryPayload(String userId, String sessionId, String key) {
        Scope scope = requireScope();
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
