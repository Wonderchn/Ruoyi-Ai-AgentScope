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

package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.aiintegration.web.P04ErrorCode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G-11 / C9.3：两侧 P04 符号错误码枚举的**镜像相等**护栏。
 *
 * <p><b>为什么必须有这一条。</b>platform 与 AI 侧的符号错误码存在于两个物理上无法互相
 * 引用的枚举里（{@code ruoyi-ai-runtime} 的 {@code P04AiErrorCode}、
 * {@code ruoyi-ai-integration} 的 {@code P04ErrorCode}），两侧各自被不同的模块消费：
 * 内层 handler 抛的是 AI 侧那个，网关映射响应时用的是 integration 侧那个。
 * 两者之间**没有任何编译期约束** —— 只在一侧新增/改名/改码值，
 * 结果不是编译失败，而是运行期"内层抛 409 资源版本冲突，网关按未知码收敛成 503"，
 * 或者更糟：两侧同名不同码，客户端按 {@code errorCode} 分支时静默走错分支。
 *
 * <p><b>本判据此刻补的是 C9.3 遗留项。</b>T0 在 {@code file-leases.json} 的
 * {@code shared_contract_change_log} 里登记：两侧新增
 * {@code RESOURCE_VERSION_CONFLICT(409, "资源版本冲突")} 时"两侧枚举镜像相等护栏测试
 * 缺失（当前缺失）"。没有这条护栏时，"两侧同改"只能靠人记得。
 *
 * <p><b>为什么只放在 {@code ruoyi-ai-web}。</b>它是本仓唯一同时依赖
 * {@code ruoyi-ai-runtime} 与 {@code ruoyi-ai-integration} 的模块 —— 放别处连两个类型都
 * 没法同时出现在类路径上，判据根本无法表达。这是位置由依赖方向决定、而不是随意挑选的结果。
 */
@Tag("dev")
class PolicyErrorCodeMirrorTest {

    @Test
    @DisplayName("两侧符号错误码逐项镜像相等：常量名、声明顺序、HTTP 状态、文案")
    void bothSidesMirrorEachOtherExactly() {
        List<String> aiSide = describe(P04AiErrorCode.values(),
                P04AiErrorCode::name, P04AiErrorCode::httpStatus, P04AiErrorCode::message);
        List<String> integrationSide = describe(P04ErrorCode.values(),
                P04ErrorCode::name, P04ErrorCode::httpStatus, P04ErrorCode::message);

        assertThat(aiSide)
                .as("两侧 P04 错误码枚举必须逐项镜像（顺序也一致）：顺序差异会让『按 ordinal 传递』"
                        + "的隐式假设悄悄错位，而按 name 传递时顺序差异又是无害噪音 —— "
                        + "要求一致是为了让差异只可能来自真实变更，而不是排序口味")
                .containsExactlyElementsOf(integrationSide);
    }

    @Test
    @DisplayName("C9.3：RESOURCE_VERSION_CONFLICT 在两侧都是 409『资源版本冲突』")
    void resourceVersionConflictIsMirrored() {
        assertThat(P04AiErrorCode.RESOURCE_VERSION_CONFLICT.httpStatus()).isEqualTo(409);
        assertThat(P04AiErrorCode.RESOURCE_VERSION_CONFLICT.message()).isEqualTo("资源版本冲突");
        assertThat(P04ErrorCode.RESOURCE_VERSION_CONFLICT.httpStatus()).isEqualTo(409);
        assertThat(P04ErrorCode.RESOURCE_VERSION_CONFLICT.message()).isEqualTo("资源版本冲突");
    }

    @Test
    @DisplayName("C9.3：改名冲突码必须独立于 POLICY_VERSION_STALE（两者语义不同、不得合并）")
    void renameConflictIsNotCollapsedIntoPolicyVersionStale() {
        // POLICY_VERSION_STALE 被 LocalPlatformPermits 专门分支占用（许可/epoch 语义）。
        // 把改名冲突复用到它，会把"授权版本过期"误判成"改名冲突"，反之亦然。
        assertThat(P04ErrorCode.RESOURCE_VERSION_CONFLICT)
                .as("改名冲突码与授权版本过期码必须是两个不同的常量")
                .isNotEqualTo(P04ErrorCode.POLICY_VERSION_STALE);
        assertThat(P04AiErrorCode.RESOURCE_VERSION_CONFLICT)
                .isNotEqualTo(P04AiErrorCode.POLICY_VERSION_STALE);
        assertThat(P04ErrorCode.POLICY_VERSION_STALE.message())
                .as("复用会同时污染两侧文案；两句文案必须不同才说明它们真是两个语义")
                .isNotEqualTo(P04ErrorCode.RESOURCE_VERSION_CONFLICT.message());
    }

    @Test
    @DisplayName("锚点：两侧都必须真的读到非空常量集，否则本判据会退化成恒真")
    void anchorsProveBothSidesAreRead() {
        assertThat(P04AiErrorCode.values()).hasSizeGreaterThanOrEqualTo(13);
        assertThat(P04ErrorCode.values()).hasSizeGreaterThanOrEqualTo(13);
    }

    private static <E extends Enum<E>> List<String> describe(E[] values,
                                                              java.util.function.Function<E, String> name,
                                                              java.util.function.ToIntFunction<E> status,
                                                              java.util.function.Function<E, String> message) {
        List<String> out = new ArrayList<>(values.length);
        Arrays.stream(values).forEach(value -> out.add(
                name.apply(value) + "(" + status.applyAsInt(value) + "," + message.apply(value) + ")"));
        return out;
    }
}
