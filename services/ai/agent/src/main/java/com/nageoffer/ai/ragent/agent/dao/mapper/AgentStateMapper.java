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

package com.nageoffer.ai.ragent.agent.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AgentScope 状态持久化 Mapper
 *
 * <p><b>P1.3d：主键含租户与成员。</b>t_agent_state 的主键是
 * {@code (tenant_id, member_id, session_id, state_key)}（V5 建立）——
 * 键在租户内可重复的表，跨租户的隔离只能靠键本身保证：两个租户的
 * 同名 user、同 session 必须落在不同的行上，删除也只命中本租户的行。
 * {@code user_id} 列保留为展示/legacy 引用（权威主体引用是 member_id），
 * 不再参与任何键与条件。
 */
@SuppressWarnings({"SqlDialectInspection", "SqlNoDataSourceInspection", "SqlResolve"})
public interface AgentStateMapper {

    @Insert("""
            INSERT INTO t_agent_state (tenant_id, member_id, user_id, session_id, state_key, payload, create_time, update_time)
            VALUES (#{tenantId}, #{memberId}, #{userId}, #{sessionId}, #{stateKey}, #{payload}::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (tenant_id, member_id, session_id, state_key)
            DO UPDATE SET payload = EXCLUDED.payload, update_time = CURRENT_TIMESTAMP
            """)
    void upsert(@Param("tenantId") String tenantId,
                @Param("memberId") String memberId,
                @Param("userId") String userId,
                @Param("sessionId") String sessionId,
                @Param("stateKey") String stateKey,
                @Param("payload") String payload);

    @Select("""
            SELECT payload
            FROM t_agent_state
            WHERE tenant_id = #{tenantId} AND member_id = #{memberId}
              AND session_id = #{sessionId} AND state_key = #{stateKey}
            """)
    String selectPayload(@Param("tenantId") String tenantId,
                         @Param("memberId") String memberId,
                         @Param("sessionId") String sessionId,
                         @Param("stateKey") String stateKey);

    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM t_agent_state
                WHERE tenant_id = #{tenantId} AND member_id = #{memberId}
                  AND session_id = #{sessionId}
            )
            """)
    boolean exists(@Param("tenantId") String tenantId,
                   @Param("memberId") String memberId,
                   @Param("sessionId") String sessionId);

    @Delete("""
            DELETE FROM t_agent_state
            WHERE tenant_id = #{tenantId} AND member_id = #{memberId}
              AND session_id = #{sessionId}
            """)
    void deleteBySession(@Param("tenantId") String tenantId,
                         @Param("memberId") String memberId,
                         @Param("sessionId") String sessionId);

    @Delete("""
            DELETE FROM t_agent_state
            WHERE tenant_id = #{tenantId} AND member_id = #{memberId}
              AND session_id = #{sessionId} AND state_key = #{stateKey}
            """)
    void deleteByKey(@Param("tenantId") String tenantId,
                     @Param("memberId") String memberId,
                     @Param("sessionId") String sessionId,
                     @Param("stateKey") String stateKey);

    @Select("""
            SELECT DISTINCT session_id
            FROM t_agent_state
            WHERE tenant_id = #{tenantId} AND member_id = #{memberId}
            ORDER BY session_id
            """)
    List<String> selectSessionIds(@Param("tenantId") String tenantId,
                                  @Param("memberId") String memberId);
}
