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

package org.ruoyi.aiweb.local;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.PlatformPermitPort;
import com.nageoffer.ai.ragent.framework.security.StaleVersionException;
import org.ruoyi.aiintegration.identity.ProductionAuthorizationProvider;
import org.ruoyi.aiintegration.web.P04ErrorCode;
import org.ruoyi.aiintegration.web.P04Exception;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link PlatformPermitPort} 的内嵌本地实现（E3）：同进程调用 platform 许可提供者
 * （{@code sys_ai_execution_permit} 的权威写入方），语义与 HTTP 端点
 * {@code /internal/platform/v1/authorization/permits/acquire|release} 逐条对齐：
 *
 * <ul>
 *   <li>policyVersion 陈旧 → {@link StaleVersionException}（AI 侧 409 收敛）；</li>
 *   <li>成员/权限/屏障/租户不可用 → {@link ServiceException}（AI 侧 UNKNOWN 收敛，不放行）；</li>
 *   <li>回执 permitId/policyVersion/operationId 与请求不一致由调用方（DefaultRevocationGuard）
 *       继续按"回执非法"拒绝。</li>
 * </ul>
 */
public class LocalPlatformPermits implements PlatformPermitPort {

    private final ObjectProvider<ProductionAuthorizationProvider> provider;

    public LocalPlatformPermits(ObjectProvider<ProductionAuthorizationProvider> provider) {
        this.provider = provider;
    }

    @Override
    public AcquireResult acquire(AcquireRequest request) {
        ProductionAuthorizationProvider permits = provider.getIfAvailable();
        if (permits == null) {
            throw new ServiceException("platform permit provider unavailable");
        }
        try {
            ProductionAuthorizationProvider.PermitGrant grant = permits.acquire(
                    new ProductionAuthorizationProvider.PermitRequest(request.tenantId(), request.subject(),
                            request.membershipId(), request.policyVersion(), request.aclVersion(), request.action(),
                            request.resourceRef(), request.resourceRefsHash(), request.operationId()));
            if (grant == null) {
                throw new ServiceException("platform permit receipt invalid");
            }
            return new AcquireResult(grant.permitId(), grant.policyVersion(), grant.operationId());
        } catch (StaleVersionException stale) {
            throw stale;
        } catch (P04Exception rejected) {
            if (rejected.errorCode() == P04ErrorCode.POLICY_VERSION_STALE) {
                throw new StaleVersionException("policyVersion changed");
            }
            throw new ServiceException("platform permit rejected");
        } catch (ServiceException unavailable) {
            throw unavailable;
        } catch (RuntimeException unavailable) {
            throw new ServiceException("platform permit unavailable");
        }
    }

    @Override
    public void release(ReleaseRequest request) {
        ProductionAuthorizationProvider permits = provider.getIfAvailable();
        if (permits == null) {
            throw new ServiceException("platform permit provider unavailable");
        }
        try {
            permits.release(new ProductionAuthorizationProvider.PermitRelease(request.tenantId(),
                    request.membershipId(), request.permitId(), request.operationId()));
        } catch (ServiceException unavailable) {
            throw unavailable;
        } catch (RuntimeException unconfirmed) {
            throw new ServiceException("platform permit release unconfirmed");
        }
    }
}
