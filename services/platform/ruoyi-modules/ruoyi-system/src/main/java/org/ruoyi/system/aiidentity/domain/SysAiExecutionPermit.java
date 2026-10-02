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
 * 平台侧执行 permit 授予账对象 sys_ai_execution_permit（V4 迁移）。
 *
 * <p>与 AI 侧 ai_execution_permit 共同构成跨节点活跃集；撤权 drain 期间
 * 新 permit 必须被屏障（sys_ai_tenant_barrier）拒绝。本单元只声明实体与
 * 数据层，permit 的签发/释放流程属后续单元。
 *
 * @author AI-Integration
 */
@Data
@TableName("sys_ai_execution_permit")
public class SysAiExecutionPermit implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * permit 标识
     */
    @TableId(value = "permit_id")
    private String permitId;

    /**
     * 租户编号
     */
    private String tenantId;

    /**
     * 成员身份（platform:<tenantId>:<userId>）
     */
    private String memberId;

    /**
     * AI canonical 动作（如 kb.read）
     */
    private String action;

    /**
     * 签发时的策略版本
     */
    private Integer policyVersion;

    /**
     * 资源引用集合摘要
     */
    private String resourceRefsHash;

    /**
     * 幂等操作标识（唯一）
     */
    private String operationId;

    /**
     * 状态（ACTIVE/RELEASED/REVOKED）
     */
    private String status;

    /**
     * 获取时间
     */
    private Date acquiredAt;

    /**
     * 过期时间
     */
    private Date expiresAt;

    /**
     * 释放时间
     */
    private Date releasedAt;

}
