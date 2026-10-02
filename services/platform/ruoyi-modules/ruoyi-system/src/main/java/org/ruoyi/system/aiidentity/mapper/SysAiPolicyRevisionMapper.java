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

package org.ruoyi.system.aiidentity.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.system.aiidentity.domain.SysAiPolicyRevision;

/**
 * AI 政策版本 数据层
 *
 * <p>读/递增使用固定 SQL（注解声明）：版本递增必须保持
 * {@code SET version = version + 1} 的单语句原子语义，受影响行数即「该租户
 * 是否存在版本行」。调用方统一经 {@code TenantHelper.ignore} 绕开租户行级
 * 拦截器（tenant_id 由参数显式指定，见 {@code AiPolicyRevisionServiceImpl}）。
 *
 * @author AI-Integration
 */
public interface SysAiPolicyRevisionMapper extends BaseMapperPlus<SysAiPolicyRevision, SysAiPolicyRevision> {

    /**
     * 读取租户当前策略版本
     *
     * @param tenantId 租户编号
     * @return 当前版本；无行返回 {@code null}（调用方不得默认为 1）
     */
    @Select("SELECT version FROM sys_ai_policy_revision WHERE tenant_id = #{tenantId}")
    Integer selectVersionByTenantId(@Param("tenantId") String tenantId);

    /**
     * 同事务递增策略版本（单语句原子：version = version + 1）
     *
     * @param tenantId 租户编号
     * @return 受影响行数；必须为 1，否则说明租户无版本行（调用方抛错回滚）
     */
    @Update("UPDATE sys_ai_policy_revision SET version = version + 1, update_time = now() "
        + "WHERE tenant_id = #{tenantId}")
    int incrementVersion(@Param("tenantId") String tenantId);

}
