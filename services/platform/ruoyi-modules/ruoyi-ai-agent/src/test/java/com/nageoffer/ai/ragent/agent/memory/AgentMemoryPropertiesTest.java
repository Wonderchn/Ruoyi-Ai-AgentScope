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

package com.nageoffer.ai.ragent.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Tag;

/**
 * 记忆配置派生面钉子：两道门与各保留/上限全部按固定比例从 contextWindowChars 派生
 * F16:0 的 agent 侧配置审计以此为准，改比例或改夹取必须显式改这里
 */
@Tag("dev")
class AgentMemoryPropertiesTest {

    @Test
    @DisplayName("默认值：窗口 1.2M、摘要与长期记忆双开、白名单只含 search_knowledge")
    void defaultsExposeOpsExitAndSafeWhitelist() {
        AgentMemoryProperties props = new AgentMemoryProperties();

        assertThat(props.getContextWindowChars()).isEqualTo(1_200_000);
        assertThat(props.isSummaryEnabled()).isTrue();
        assertThat(props.isLongTermEnabled()).isTrue();
        assertThat(props.getEvictableTools()).containsExactly("search_knowledge");
        assertThat(props.resolveExtractMinTurns()).isEqualTo(3);
        assertThat(props.resolveKeepRecentCycles()).isEqualTo(2);
    }

    @Test
    @DisplayName("门比例：裁剪 0.5、压缩 0.8、保留段 0.2")
    void gatesDeriveFromWindowByFixedRatios() {
        AgentMemoryProperties props = new AgentMemoryProperties();
        props.setContextWindowChars(8_000);

        assertThat(props.resolveTrimTriggerChars()).isEqualTo(4_000);
        assertThat(props.resolveCompactTriggerChars()).isEqualTo(6_400);
        assertThat(props.resolveKeepRecentChars()).isEqualTo(1_600);
    }

    @Test
    @DisplayName("摘要上限：0.1×预算夹进 [1500,6000]")
    void summaryMaxCharsClampedIntoBand() {
        AgentMemoryProperties props = new AgentMemoryProperties();

        props.setContextWindowChars(8_000);
        assertThat(props.resolveSummaryMaxChars()).isEqualTo(1_500);

        props.setContextWindowChars(20_000);
        assertThat(props.resolveSummaryMaxChars()).isEqualTo(2_000);

        props.setContextWindowChars(1_200_000);
        assertThat(props.resolveSummaryMaxChars()).isEqualTo(6_000);
    }

    @Test
    @DisplayName("长期记忆注入块上限：0.005×预算夹进 [1500,6000]；合并停手水位取其 0.75")
    void memoryMaxCharsClampedAndConsolidationDerived() {
        AgentMemoryProperties props = new AgentMemoryProperties();

        props.setContextWindowChars(8_000);
        assertThat(props.resolveMemoryMaxChars()).isEqualTo(1_500);
        assertThat(props.resolveConsolidationStopChars()).isEqualTo(1_125);

        props.setContextWindowChars(400_000);
        assertThat(props.resolveMemoryMaxChars()).isEqualTo(2_000);

        props.setContextWindowChars(1_200_000);
        assertThat(props.resolveMemoryMaxChars()).isEqualTo(6_000);
        assertThat(props.resolveConsolidationStopChars()).isEqualTo(4_500);
    }
}
