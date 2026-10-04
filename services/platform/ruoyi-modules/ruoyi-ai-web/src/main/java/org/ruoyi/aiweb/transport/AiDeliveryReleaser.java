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

package org.ruoyi.aiweb.transport;

/**
 * 交付回执的本地出口（E3/C3）：POST {@code /internal/ai/v1/authorization/deliveries/release}
 * 在跨进程传输下是独立 HTTP 调用；内嵌后它发生在响应<b>已提交</b>之后，
 * servlet forward 已不可用，因此收敛为一次本地方法调用。
 *
 * <p>实现方（AI 运行时侧）必须保持原语义一条不减：身份三元组（tenant/member/operation）
 * 与 permit 绑定校验、幂等释放、失败抛异常（由网关收敛为 503，不降级放行）。
 * 典型实现直接委托 {@code RevocationGuard.releaseDelivery(tenantId, memberId, permitId, operationId)}。
 */
public interface AiDeliveryReleaser {

    /**
     * 释放交付许可。
     *
     * @param tenantId    主体租户
     * @param memberId    canonical 成员标识
     * @param permitId    交付许可标识
     * @param operationId 交付操作标识
     * @throws RuntimeException 校验失败/释放未确认（网关按 503 fail-closed 处理）
     */
    void releaseDelivery(String tenantId, String memberId, String permitId, String operationId);
}
