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

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * canonical 成员标识：<b>AI 侧</b>实现（E5/WP-025）的期望值锁定。
 *
 * <p>同一个登录用户在平台写路径与 AI 写路径上必须落到同一个
 * {@code ai_conversation.member_id}，否则平台会话与 AI 会话会变成两个"成员"，而 V7 的复合外键
 * 还要求它们一致。两侧无法互相依赖（平台公共模块不依赖 AI runtime），规则只能各写一份，
 * 因此用<b>同一组字面量</b>把两份实现钉在一起：
 * <ul>
 *   <li>本测试钉 AI 侧的 {@link ExecutionPrincipal#canonicalMembershipId}；</li>
 *   <li>{@code PlatformMembershipIdTest}（ruoyi-common-mybatis）钉平台侧的
 *       {@code PlatformMembershipId.canonical}；</li>
 *   <li>两边断言的是同一个 {@link #SAMPLES} 列表——改规则必须同时改两处，否则两侧各自的
 *       测试都会失败。</li>
 * </ul>
 */
@Tag("dev")
class MembershipIdParityTest {

    /** 与 {@code PlatformMembershipIdTest} 完全相同的输入/期望对。 */
    static final List<String[]> SAMPLES = List.of(
            new String[]{"000000", "1", "platform:000000:1"},
            new String[]{"0", "11", "platform:0:11"},
            new String[]{"12345", "11", "platform:12345:11"},
            new String[]{"T1", "2101", "platform:T1:2101"},
            new String[]{"a".repeat(64), "9".repeat(20), "platform:" + "a".repeat(64) + ":" + "9".repeat(20)});

    /** 与 {@code PlatformMembershipIdTest#refusesInvalidInput} 相同的非法输入。 */
    static final List<String[]> INVALID = List.of(
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
    @DisplayName("AI 侧 canonical 成员标识与约定字面量一致")
    void aiSideMatchesTheAgreedLiterals() {
        for (String[] sample : SAMPLES) {
            assertThat(ExecutionPrincipal.canonicalMembershipId(sample[0], sample[1]))
                    .as("tenant=%s user=%s", sample[0], sample[1])
                    .isEqualTo(sample[2]);
        }
    }

    @Test
    @DisplayName("数值租户按文本参与：0 保持 '0'，不并入平台默认租户 '000000'")
    void numericTenantsStayLiteralText() {
        assertThat(ExecutionPrincipal.canonicalMembershipId("0", "11")).isEqualTo("platform:0:11");
        assertThat(ExecutionPrincipal.canonicalMembershipId("000000", "11"))
                .isEqualTo("platform:000000:11");
        assertThat(ExecutionPrincipal.canonicalMembershipId("0", "11"))
                .as("0 与 000000 是不同的租户，不能合并")
                .isNotEqualTo(ExecutionPrincipal.canonicalMembershipId("000000", "11"));
    }

    @Test
    @DisplayName("非法输入拒绝，不做尽力拼接")
    void refusesInvalidInput() {
        for (String[] pair : INVALID) {
            assertThatThrownBy(() -> ExecutionPrincipal.canonicalMembershipId(pair[0], pair[1]))
                    .as("tenant=%s user=%s", pair[0], pair[1])
                    .isInstanceOf(ClientException.class);
        }
    }
}
