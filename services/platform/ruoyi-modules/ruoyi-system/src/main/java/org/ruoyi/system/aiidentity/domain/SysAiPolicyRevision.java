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

package org.ruoyi.system.aiidentity.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 政策版本对象 sys_ai_policy_revision（V4 迁移）。
 *
 * <p>租户一行；政策写事务内锁定并同事务递增。<b>不预置行</b>：无行即「无版本」，
 * 读取方不得默认为 1；新租户在创建事务内同建初始行（见
 * {@code AiPolicyRevisionService#initialize}）。刻意不继承 BaseEntity——
 * 本表没有 create_dept/create_by 等公共列，继承会导致 MP 生成不存在的列名。
 *
 * @author AI-Integration
 */
@Data
@TableName("sys_ai_policy_revision")
public class SysAiPolicyRevision implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 租户编号（主键）
     */
    @TableId(value = "tenant_id")
    private String tenantId;

    /**
     * 当前策略版本（>= 1，只增不减）
     */
    private Integer version;

    /**
     * 更新时间（由数据库默认值维护）
     */
    private Date updateTime;

}
