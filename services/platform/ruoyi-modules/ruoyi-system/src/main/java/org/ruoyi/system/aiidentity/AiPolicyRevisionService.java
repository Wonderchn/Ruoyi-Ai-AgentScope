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

package org.ruoyi.system.aiidentity;

import org.ruoyi.common.core.exception.ServiceException;

import java.util.Collection;
import java.util.Optional;

/**
 * AI 政策版本（policyVersion 权威）读取与递增。
 *
 * <p>口径（V4 迁移注释 / 05 §4.3）：platform 持 policyVersion 权威。
 * <b>不预置行</b>：无行即「无版本」——读取方一律拒绝，<b>绝不默认为 1</b>；
 * 新租户在创建事务内同建初始行（{@link #initialize}），旧租户初始化来源经 C6 确认。
 *
 * <p>事务契约：{@link #bump}、{@link #bumpAll}、{@link #initialize} 必须在
 * 使用方的同一事务内调用（本接口不自行开启事务，版本递增与身份事实变更
 * 必须同生共死）；对未开启事务的调用属于编程错误。
 *
 * @author AI-Integration
 */
public interface AiPolicyRevisionService {

    /**
     * 读取租户当前策略版本。
     *
     * @param tenantId 租户编号
     * @return 当前版本；租户无版本行时返回 empty（绝不默认为 1）
     */
    Optional<Integer> currentVersion(String tenantId);

    /**
     * 读取租户当前策略版本，无版本即拒绝。
     *
     * @param tenantId 租户编号
     * @return 当前版本
     * @throws ServiceException 租户无版本行（不默认为 1）
     */
    int requireCurrentVersion(String tenantId);

    /**
     * 同事务递增租户策略版本。
     *
     * @param tenantId 租户编号
     * @throws ServiceException 受影响行数不为 1
     *         （租户无版本行或更新异常），由调用方事务回滚
     */
    void bump(String tenantId);

    /**
     * 对多个租户逐个递增版本。<b>先排序去重再递增</b>（防多租户批量写时的交叉死锁），
     * 空集合为无操作。每个租户都必须显式可枚举，不允许「全部租户」。
     *
     * @param tenantIds 受影响租户集合
     * @throws ServiceException 任一租户递增失败
     */
    void bumpAll(Collection<String> tenantIds);

    /**
     * 新租户创建事务内同建版本行（version = 1）。
     *
     * @param tenantId 租户编号
     * @throws ServiceException 租户已存在版本行
     */
    void initialize(String tenantId);

}
