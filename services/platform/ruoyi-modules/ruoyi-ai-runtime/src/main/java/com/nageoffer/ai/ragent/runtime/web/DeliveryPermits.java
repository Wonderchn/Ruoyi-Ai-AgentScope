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

package com.nageoffer.ai.ragent.runtime.web;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 最终交付 permit：GET/字节输出的应用边界登记（P1 强撤权模型复用）。
 *
 * <p>成功路径由平台网关在输出结束后 ACK release；错误路径由本类关闭。
 * 无法确认输出停止时 permit 保持 ACTIVE，撤权屏障保持 PENDING（不虚假排空）。
 */
@Component
public class DeliveryPermits {

    private final ObjectProvider<RevocationGuard> guard;

    public DeliveryPermits(ObjectProvider<RevocationGuard> guard) {
        this.guard = guard;
    }

    public record Permit(String permitId, String operationId, RevocationGuard.Operation operation)
            implements AutoCloseable {

        @Override
        public void close() {
            operation.close();
        }
    }

    public Permit enter(ExecutionPrincipal principal, String action, String resourceRef) {
        RevocationGuard revocationGuard = guard.getIfAvailable();
        if (revocationGuard == null) {
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE, "delivery permit unavailable");
        }
        RevocationGuard.Operation operation = revocationGuard.enter(principal, action, resourceRef);
        return new Permit(operation.permitId(), operation.operationId(), operation);
    }
}
