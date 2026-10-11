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

package org.ruoyi.aiidentity;

import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link TenantScopeBinder} 的平台侧实现（ENV-BATCH-5b 修，2026-10-11）：
 * 把 AI 侧异步线程携带的租户绑定到当前线程（{@code TenantHelper.dynamic}），
 * 使 MyBatis 租户行拦截器对业务表放行（其设计文档明确"有动态租户（MQ/异步等）放行"）。
 *
 * <p>装配口径同 {@link RuoYiPlatformIdentitySource}：{@code ai.integration.enabled=true}
 * （生产接线开关，默认关）。独立/旧形态没有本 bean，AI 侧调用方按"无租户拦截器"直接执行。
 */
@Component
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class RuoYiTenantScopeBinder implements TenantScopeBinder {

    @Override
    public void runWithTenant(String tenantId, Runnable handle) {
        TenantHelper.dynamic(tenantId, handle);
    }
}
