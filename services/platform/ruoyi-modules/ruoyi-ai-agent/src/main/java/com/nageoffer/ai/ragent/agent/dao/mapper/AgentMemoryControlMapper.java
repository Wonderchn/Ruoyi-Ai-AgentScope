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

import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryControlDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * 长期记忆控制面 Mapper，提交期的串行点就在这张表的行锁上
 */
@SuppressWarnings({"SqlDialectInspection", "SqlNoDataSourceInspection", "SqlResolve"})
public interface AgentMemoryControlMapper {

    /**
     * 懒创建，并发下靠 ON CONFLICT 收敛；now 必须来自应用时钟，与消息 create_time 同源避免钟差
     */
    @Insert("""
            INSERT INTO t_agent_memory_control (tenant_id, member_id, user_id, revision, create_time, update_time)
            VALUES (#{tenantId}, #{memberId}, #{userId}, 0, #{now}, #{now})
            ON CONFLICT (tenant_id, member_id) DO NOTHING
            """)
    void ensureExists(@Param("tenantId") String tenantId,
                      @Param("memberId") String memberId,
                      @Param("userId") String userId,
                      @Param("now") Date now);

    @Select("""
            SELECT user_id, tenant_id, member_id, revision, create_time, update_time
            FROM t_agent_memory_control
            WHERE tenant_id = #{tenantId} AND member_id = #{memberId}
            """)
    AgentMemoryControlDO selectByUserId(@Param("tenantId") String tenantId,
                                        @Param("memberId") String memberId);

    /**
     * 提交事务的第一句：拿到行锁，同用户的提交从这里开始排队
     */
    @Select("""
            SELECT user_id, tenant_id, member_id, revision, create_time, update_time
            FROM t_agent_memory_control
            WHERE tenant_id = #{tenantId} AND member_id = #{memberId}
            FOR UPDATE
            """)
    AgentMemoryControlDO selectForUpdate(@Param("tenantId") String tenantId,
                                         @Param("memberId") String memberId);

    /**
     * 记忆集变更后推版本号；NOOP 不走这里，所以单靠版本号挡不住重复写入
     */
    @Update("""
            UPDATE t_agent_memory_control
            SET revision = revision + 1, update_time = CURRENT_TIMESTAMP
            WHERE tenant_id = #{tenantId} AND member_id = #{memberId}
            """)
    void bumpRevision(@Param("tenantId") String tenantId,
                      @Param("memberId") String memberId);
}
