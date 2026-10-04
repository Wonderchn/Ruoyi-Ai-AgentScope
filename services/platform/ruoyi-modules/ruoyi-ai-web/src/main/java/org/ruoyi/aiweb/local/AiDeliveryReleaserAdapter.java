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
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import org.ruoyi.aiweb.transport.AiDeliveryReleaser;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link AiDeliveryReleaser} 的内嵌实现（E3/C3）：交付回执在响应已提交之后发生，
 * 收敛为一次本地方法调用，直接委托 AI 运行时的
 * {@link RevocationGuard#releaseDelivery(String, String, String, String)}
 * ——身份三元组绑定校验、幂等释放、失败抛异常（网关按 503 fail-closed）语义一条不减。
 */
public class AiDeliveryReleaserAdapter implements AiDeliveryReleaser {

    private final ObjectProvider<RevocationGuard> revocations;

    public AiDeliveryReleaserAdapter(ObjectProvider<RevocationGuard> revocations) {
        this.revocations = revocations;
    }

    @Override
    public void releaseDelivery(String tenantId, String memberId, String permitId, String operationId) {
        RevocationGuard guard = revocations.getIfAvailable();
        if (guard == null) {
            // 无撤权实现不得静默吞掉交付回执：网关按 503 处理
            throw new ServiceException("delivery revocation guard unavailable");
        }
        guard.releaseDelivery(tenantId, memberId, permitId, operationId);
    }
}
