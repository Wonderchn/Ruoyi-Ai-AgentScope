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

import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.system.aiidentity.domain.SysAiPolicyRevision;
import org.ruoyi.system.aiidentity.mapper.SysAiPolicyRevisionMapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * AI 政策版本服务实现。
 *
 * <p>全部读写都显式携带 tenant_id 参数，并统一在 {@code TenantHelper.ignore} 中执行：
 * 版本行属于平台级权威事实，不允许被登录态的租户行级拦截器二次过滤（否则内部端点
 * 的无登录态调用与跨租户管理写都会失真）。
 *
 * <p>本实现不开事务：递增/初始化必须发生在使用方事务内（见接口事务契约）。
 *
 * @author AI-Integration
 */
@RequiredArgsConstructor
@Service
public class AiPolicyRevisionServiceImpl implements AiPolicyRevisionService {

    private final SysAiPolicyRevisionMapper baseMapper;

    @Override
    public Optional<Integer> currentVersion(String tenantId) {
        if (StringUtils.isBlank(tenantId)) {
            return Optional.empty();
        }
        Integer version = TenantHelper.ignore(() -> baseMapper.selectVersionByTenantId(tenantId));
        // 无行 → empty；version 列约束 >= 1，防御性过滤非法值，绝不默认为 1
        return Optional.ofNullable(version).filter(v -> v >= 1);
    }

    @Override
    public int requireCurrentVersion(String tenantId) {
        return currentVersion(tenantId).orElseThrow(() ->
            new ServiceException("租户[" + tenantId + "]无 AI 策略版本，拒绝读取（不默认为 1）"));
    }

    @Override
    public void bump(String tenantId) {
        if (StringUtils.isBlank(tenantId)) {
            throw new ServiceException("策略版本递增失败：租户编号为空");
        }
        int rows = TenantHelper.ignore(() -> baseMapper.incrementVersion(tenantId));
        if (rows != 1) {
            // 必须恰好命中租户的一行版本记录；无行（未初始化）或异常都回滚使用方事务
            throw new ServiceException("租户[" + tenantId + "]策略版本递增失败：受影响行数=" + rows
                + "（租户无版本行，不得默认初始化），事务回滚");
        }
    }

    @Override
    public void bumpAll(Collection<String> tenantIds) {
        if (CollUtil.isEmpty(tenantIds)) {
            return;
        }
        // 排序去重后逐租户递增：多租户批量写时按同一全序加锁，防交叉死锁
        List<String> sorted = tenantIds.stream()
            .filter(StringUtils::isNotBlank)
            .distinct()
            .sorted()
            .toList();
        for (String tenantId : sorted) {
            bump(tenantId);
        }
    }

    @Override
    public void initialize(String tenantId) {
        if (StringUtils.isBlank(tenantId)) {
            throw new ServiceException("策略版本初始化失败：租户编号为空");
        }
        if (currentVersion(tenantId).isPresent()) {
            throw new ServiceException("租户[" + tenantId + "]已存在策略版本行，拒绝重复初始化");
        }
        SysAiPolicyRevision revision = new SysAiPolicyRevision();
        revision.setTenantId(tenantId);
        revision.setVersion(1);
        int rows = TenantHelper.ignore(() -> baseMapper.insert(revision));
        if (rows != 1) {
            throw new ServiceException("租户[" + tenantId + "]策略版本初始化失败：受影响行数=" + rows);
        }
    }

}
