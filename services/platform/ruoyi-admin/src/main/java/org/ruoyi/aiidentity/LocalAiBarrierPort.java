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

import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * AI 节点屏障端点的内嵌本地实现（E3，{@code transport=local}）：
 * 同进程直接调用 AI 运行时 {@link RevocationGuard}，替换
 * {@link HttpAiBarrierClient} 的 {@code POST /internal/ai/v1/authorization/barriers} HTTP 通道。
 *
 * <p>与 HTTP 形态逐条对齐（包括失败语义）：
 * <ul>
 *   <li>CLOSE：置 PENDING（拒绝新 permit）→ 复核状态确为 PENDING → 返回活跃 permit 数；</li>
 *   <li>STATUS：只读返回活跃数（屏障事实不可读即 empty）；</li>
 *   <li>OPEN：置 OPEN 并复核活跃数为 0，否则 {@code IllegalStateException}（"解除未确认"）；</li>
 *   <li>不可达/拒绝/形状不可信一律 empty：调用方读作"无法证明已停"，保持 PENDING/UNKNOWN，
 *       <b>不</b>凭租约过期宣告撤权成功。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnExpression("'${ai.integration.enabled:false}' == 'true'"
        + " and '${ai.integration.transport:http}' == 'local'")
public class LocalAiBarrierPort implements RevocationBarrierCoordinator.AiBarrierPort {

    private final ObjectProvider<RevocationGuard> guard;

    public LocalAiBarrierPort(ObjectProvider<RevocationGuard> guard) {
        this.guard = guard;
    }

    @Override
    public Optional<Long> close(String tenantId, String barrierId, String reason) {
        return readQuietly(() -> {
            RevocationGuard revocations = requireGuard();
            revocations.setBarrierState(tenantId, RevocationGuard.BarrierState.PENDING, barrierId, null, reason);
            if (revocations.barrierState(tenantId) != RevocationGuard.BarrierState.PENDING) {
                // 与 HTTP 回执形状校验等价：状态不符即不可信，按"无法证明已停"处理
                return null;
            }
            return revocations.activePermitCount(tenantId);
        });
    }

    @Override
    public Optional<Long> activePermitCount(String tenantId) {
        return readQuietly(() -> {
            RevocationGuard revocations = requireGuard();
            revocations.barrierState(tenantId);
            return revocations.activePermitCount(tenantId);
        });
    }

    @Override
    public void open(String tenantId, String barrierId) {
        try {
            RevocationGuard revocations = requireGuard();
            revocations.setBarrierState(tenantId, RevocationGuard.BarrierState.OPEN, barrierId, null, "released");
            Long active = revocations.activePermitCount(tenantId);
            if (active == null || active != 0) {
                throw new IllegalStateException("barrier open unacknowledged");
            }
        } catch (IllegalStateException unacknowledged) {
            throw unacknowledged;
        } catch (RuntimeException unavailable) {
            log.error("屏障解除失败：{}（按解除未确认处理）", unavailable.getClass().getSimpleName());
            throw new IllegalStateException("barrier open unacknowledged");
        }
    }

    private RevocationGuard requireGuard() {
        RevocationGuard revocations = guard.getIfAvailable();
        if (revocations == null) {
            throw new IllegalStateException("revocation guard unavailable");
        }
        return revocations;
    }

    /** 失败一律 empty（与 HTTP 客户端的"不可达=无法证明已停"同一口径）。 */
    private Optional<Long> readQuietly(Supplier<Long> work) {
        try {
            Long value = work.get();
            if (value == null || value < 0) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (RuntimeException unavailable) {
            log.error("屏障调用失败：{}（按无法证明已停处理）", unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
