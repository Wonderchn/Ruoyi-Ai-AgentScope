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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.aiintegration.web.AiGatewayClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F23 op7 收口（P1-1A 第三包）：内嵌本地传输的**交付回执未确认语义**。
 *
 * <p><b>背景。</b>op7 判据「SSE 发送失败/未知 ACK 时许可保留」「不能用 HTTP 200 推定
 * ACK 已成功」在真机面已被逐帧许可铸/释守恒（ENV-BATCH-4 P5、dual V6a-c）与
 * post-commit 不静默（{@code P1GatewayIdentityTest.ackFailureAfterCommitIsLoggedNotEmpty}）
 * 部分覆盖；但**释放未确认这一负分支此前没有任何定向测试**（GAP-ASSESSMENT T-9；
 * WAVE-REVIEW §2.9/F-04 具名"未取证"）。本类在本地传输层把该分支钉死：
 * 释放未确认 ⇒ 抛 {@code UpstreamUnavailableException}（网关收敛为 503 /
 * 失败留痕，绝不 204 假成功、绝不降级放行），且正控证明判据非恒抛。
 *
 * <p>真实 HTTP 网关面的同类负分支（真 HTTP 503 + permit 保持 ACTIVE）仍在
 * 真机窗口清单（见切片报告 {@code r17/f23res/}）。
 */
@Tag("dev")
class LocalDeliveryAcknowledgementTest {

    private static final String RELEASE_PATH = "http://local/internal/ai/v1/authorization/deliveries/release";

    private static AiGatewayClient.ForwardRequest release(byte[] body) {
        return new AiGatewayClient.ForwardRequest("POST", URI.create(RELEASE_PATH),
                Map.of("Content-Type", "application/json"), body);
    }

    private static byte[] releaseBody(String permitId, String operationId) {
        return ("{\"tenantId\":\"T1\",\"memberId\":\"platform:T1:2101\",\"permitId\":\"" + permitId
                + "\",\"operationId\":\"" + operationId + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("正控：确认的释放原样传递身份四元组并返回 204（负分支非恒抛）")
    void confirmedReleasePassesIdentityAndReturns204() {
        AtomicReference<String> seen = new AtomicReference<>();
        LocalAiGatewayClient client = new LocalAiGatewayClient(1000, null,
                (tenantId, memberId, permitId, operationId) ->
                        seen.set(tenantId + "|" + memberId + "|" + permitId + "|" + operationId));

        AiGatewayClient.ForwardResponse response =
                client.forward(release(releaseBody("permit-1", "operation-1")));

        assertThat(response.status()).isEqualTo(204);
        assertThat(seen.get()).isEqualTo("T1|platform:T1:2101|permit-1|operation-1");
    }

    @Test
    @DisplayName("释放未确认 → UpstreamUnavailableException（不许 204 假成功，网关按 503 fail-closed）")
    void unconfirmedReleaseNeverReportsSilentSuccess() {
        LocalAiGatewayClient client = new LocalAiGatewayClient(1000, null,
                (tenantId, memberId, permitId, operationId) -> {
                    throw new IllegalStateException("permit release unconfirmed");
                });

        assertThatThrownBy(() -> client.forward(release(releaseBody("permit-1", "operation-1"))))
                .isInstanceOf(AiGatewayClient.UpstreamUnavailableException.class)
                .hasMessageContaining("delivery acknowledgement unavailable");
    }

    @Test
    @DisplayName("畸形 ACK 体同样不得静默成功（解析失败=未确认）")
    void malformedAcknowledgementIsNeverSilentSuccess() {
        LocalAiGatewayClient client = new LocalAiGatewayClient(1000, null,
                (tenantId, memberId, permitId, operationId) -> { });

        assertThatThrownBy(() -> client.forward(release("not-json".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(AiGatewayClient.UpstreamUnavailableException.class)
                .hasMessageContaining("delivery acknowledgement unavailable");
    }
}
