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

package org.ruoyi.common.mybatis.core.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.exception.ServiceException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * canonical 成员标识：<b>平台侧</b>实现（E5/WP-026）的期望值锁定。
 *
 * <p>与 AI 侧的 {@code MembershipIdParityTest}（ruoyi-ai-web）断言<b>同一组字面量</b>。
 * 两侧无法互相依赖，所以用共享的字面量把两份实现钉在一起：改规则必须同时改两处，
 * 否则其中一侧的测试会立刻失败。这不是"文档约定"，是可执行的约束。
 */
@Tag("dev") // 必须：本模块 surefire 用 <groups>${profiles.active}</groups>，没有 dev 标签的测试会被静默跳过
class PlatformMembershipIdTest {

    /** 与 {@code MembershipIdParityTest.SAMPLES} 完全相同的输入/期望对。 */
    private static final List<String[]> SAMPLES = List.of(
            new String[]{"000000", "1", "platform:000000:1"},
            new String[]{"0", "11", "platform:0:11"},
            new String[]{"12345", "11", "platform:12345:11"},
            new String[]{"T1", "2101", "platform:T1:2101"},
            new String[]{"a".repeat(64), "9".repeat(20), "platform:" + "a".repeat(64) + ":" + "9".repeat(20)});

    private static final List<String[]> INVALID = List.of(
            new String[]{null, "1"},
            new String[]{"", "1"},
            new String[]{" ", "1"},
            new String[]{"t:1", "1"},
            new String[]{"t1", null},
            new String[]{"t1", ""},
            new String[]{"t1", "abc"},
            new String[]{"t1", "1a"},
            new String[]{"t1", "-1"},
            new String[]{"t1", " 1"},
            new String[]{"a".repeat(65), "1"},
            new String[]{"t1", "9".repeat(21)});

    @Test
    @DisplayName("平台侧 canonical 成员标识与约定字面量一致")
    void platformSideMatchesTheAgreedLiterals() {
        for (String[] sample : SAMPLES) {
            assertThat(PlatformMembershipId.canonical(sample[0], sample[1]))
                    .as("tenant=%s user=%s", sample[0], sample[1])
                    .isEqualTo(sample[2]);
        }
    }

    @Test
    @DisplayName("数值租户按文本参与：0 保持 '0'，不并入平台默认租户 '000000'")
    void numericTenantsStayLiteralText() {
        assertThat(PlatformMembershipId.canonical("0", "11")).isEqualTo("platform:0:11");
        assertThat(PlatformMembershipId.canonical("000000", "11")).isEqualTo("platform:000000:11");
        assertThat(PlatformMembershipId.canonical("0", "11"))
                .as("0 与 000000 是不同的租户，不能合并")
                .isNotEqualTo(PlatformMembershipId.canonical("000000", "11"));
    }

    @Test
    @DisplayName("非法输入拒绝，不做尽力拼接")
    void refusesInvalidInput() {
        for (String[] pair : INVALID) {
            assertThatThrownBy(() -> PlatformMembershipId.canonical(pair[0], pair[1]))
                    .as("tenant=%s user=%s", pair[0], pair[1])
                    .isInstanceOf(ServiceException.class);
        }
    }
}
