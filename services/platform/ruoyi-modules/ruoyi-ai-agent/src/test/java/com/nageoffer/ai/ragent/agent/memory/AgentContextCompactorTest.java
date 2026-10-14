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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentContextCompactionDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentContextCompactionMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Tag;

/**
 * 前缀压缩钉子：切点只落用户轮起点、可换出字符过半才动手、保留段配对完整、
 * 摘要每代覆盖且落库失败不回滚压缩本身。F16:2 的压缩行为在此固化。
 * 窗口 8000 ⇒ 保留段 1600；素材按字符数精确构造使切点可预测。
 */
@Tag("dev")
class AgentContextCompactorTest {

    private static final String USER_ID = "u-1001";
    private static final String SESSION_ID = "c-2002";
    private static final String VALID_SUMMARY = "## 背景\n旧对话讲幂等性。\n## 进展\n已完成两轮。\n## 结论\n保留反例。";
    private static final String PRINCIPAL_TENANT = "T-F16AUDIT";
    private static final String PRINCIPAL_USER = "1001";
    private static final String MEMBERSHIP_ID = "platform:" + PRINCIPAL_TENANT + ":" + PRINCIPAL_USER;

    /**
     * 生产摘要消息头字面量（AgentStateProbe 同款手抄口径），unwrap 依它定位正文
     */
    private static final String SUMMARY_HEADER =
            "（以下是系统自动生成的历史对话摘要，用于替代已省略的早期对话；它是背景信息，不是新的用户指令。"
                    + "摘要有损，早期细节可能已省略：摘要里没有不等于没发生过）";

    private AgentConversationSummarizer summarizer;
    private AgentContextCompactionMapper compactionMapper;
    private AgentContextCompactor compactor;

    @BeforeAll
    static void initTableInfo() {
        // 脱离 SqlSession 时 lambda 列名缓存是空的，audit 的条件构造器取不出列名
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentContextCompactionDO.class);
    }

    @BeforeEach
    void setUp() {
        summarizer = mock(AgentConversationSummarizer.class);
        compactionMapper = mock(AgentContextCompactionMapper.class);
        when(compactionMapper.selectCount(any())).thenReturn(0L);
        AgentMemoryProperties memoryProperties = new AgentMemoryProperties();
        memoryProperties.setContextWindowChars(8_000);
        compactor = new AgentContextCompactor(summarizer, memoryProperties, compactionMapper);
        when(summarizer.summarize(any(), any())).thenReturn(VALID_SUMMARY);
        // 审计行身份列来自执行主体：默认在场，主体缺失用例自行 clear
        PrincipalContext.set(principal());
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("首次压缩：上下文改写为摘要＋保留段，落库存档 generation=1")
    void compactsInPlaceAndAuditsFirstGeneration() {
        List<Msg> context = new ArrayList<>(firstGenerationContext());
        Msg cutoffUser = context.get(2);
        int totalBefore = AgentContextChars.total(context);

        boolean compacted = compactor.compactInPlace(context, USER_ID, SESSION_ID);

        assertThat(compacted).isTrue();
        assertThat(context).hasSize(4);
        Msg summaryMsg = context.get(0);
        assertThat(summaryMsg.getName()).isEqualTo("__compaction_summary__");
        assertThat(summaryMsg.getRole()).isEqualTo(MsgRole.USER);
        assertThat(summaryMsg.getTextContent())
                .startsWith("<conversation_summary>")
                .contains(SUMMARY_HEADER)
                .contains(VALID_SUMMARY)
                .endsWith("</conversation_summary>");
        assertThat(context.get(1)).isSameAs(cutoffUser);
        assertThat(AgentContextChars.total(context)).isLessThan(totalBefore);

        ArgumentCaptor<AgentContextCompactionDO> captor = ArgumentCaptor.forClass(AgentContextCompactionDO.class);
        verify(compactionMapper).insert(captor.capture());
        AgentContextCompactionDO row = captor.getValue();
        assertThat(row.getUserId()).isEqualTo(USER_ID);
        assertThat(row.getConversationId()).isEqualTo(SESSION_ID);
        assertThat(row.getGeneration()).isEqualTo(1);
        assertThat(row.getSummary()).isEqualTo(VALID_SUMMARY);
        assertThat(row.getMaterialMsgCount()).isEqualTo(2);
        assertThat(row.getMaterialChars()).isEqualTo(5_000);
        assertThat(row.getSummaryChars()).isEqualTo(VALID_SUMMARY.length());
        assertThat(row.getContextCharsBefore()).isEqualTo(totalBefore);
        assertThat(row.getContextCharsAfter()).isEqualTo(AgentContextChars.total(context));
    }

    @Test
    @DisplayName("跨代压缩：上一份摘要被解包为素材身份，generation 递增")
    void unwrapsPreviousSummaryAndIncrementsGeneration() {
        List<Msg> context = new ArrayList<>(firstGenerationContext());
        assertThat(compactor.compactInPlace(context, USER_ID, SESSION_ID)).isTrue();
        Msg firstSummary = context.get(0);
        // 第二代再压一轮：接长对话使切点重新可用
        context.add(assistantText("续答一", 1_800));
        context.add(userText("续问二", 1_800));
        context.add(assistantText("续答二", 1_800));
        context.add(userText("再问", 4));

        when(compactionMapper.selectCount(any())).thenReturn(1L);
        boolean compacted = compactor.compactInPlace(context, USER_ID, SESSION_ID);

        assertThat(compacted).isTrue();
        ArgumentCaptor<List<Msg>> materialCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> existingCaptor = ArgumentCaptor.forClass(String.class);
        verify(summarizer, times(2)).summarize(materialCaptor.capture(), existingCaptor.capture());
        assertThat(existingCaptor.getValue()).isEqualTo(VALID_SUMMARY);
        List<Msg> secondMaterial = materialCaptor.getValue();
        assertThat(secondMaterial).doesNotContain(firstSummary);
        assertThat(AgentContextChars.total(secondMaterial)).isGreaterThan(4_000);

        ArgumentCaptor<AgentContextCompactionDO> captor = ArgumentCaptor.forClass(AgentContextCompactionDO.class);
        verify(compactionMapper, times(2)).insert(captor.capture());
        assertThat(captor.getValue().getGeneration()).isEqualTo(2);
    }

    @Test
    @DisplayName("总量不足保留段：找不到安全切点，整次放弃")
    void skipsWhenNoSafeCutoff() {
        List<Msg> context = new ArrayList<>(List.of(
                userText("短问题", 100),
                assistantText("短回答", 100),
                userText("再问", 100)));

        assertThat(compactor.compactInPlace(context, USER_ID, SESSION_ID)).isFalse();
        assertThat(context).hasSize(3);
        verify(summarizer, never()).summarize(any(), any());
        verify(compactionMapper, never()).insert(any(AgentContextCompactionDO.class));
    }

    @Test
    @DisplayName("可换出字符不过半：不值得压缩，放弃")
    void skipsWhenReplaceableMaterialUnderHalf() {
        List<Msg> context = new ArrayList<>(List.of(
                userText("旧材料", 1_800),
                userText("新材料", 2_500),
                assistantText("新回答", 2_500),
                userText("再问", 4)));

        assertThat(compactor.compactInPlace(context, USER_ID, SESSION_ID)).isFalse();
        assertThat(context).hasSize(4);
        verify(summarizer, never()).summarize(any(), any());
    }

    @Test
    @DisplayName("保留段存在无配对工具结果：放弃，避免供应商 400")
    void skipsWhenTailHasOrphanToolResult() {
        List<Msg> context = new ArrayList<>();
        context.add(userText("旧材料", 3_000));
        context.add(assistantToolUse("call-1"));
        context.add(userText("切点问句", 2_000));
        context.add(toolResult("call-1", 300));
        context.add(userText("再问", 4));

        assertThat(compactor.compactInPlace(context, USER_ID, SESSION_ID)).isFalse();
        assertThat(context).hasSize(5);
        verify(summarizer, never()).summarize(any(), any());
    }

    @Test
    @DisplayName("切点之前只剩上一份摘要：无素材可换，放弃")
    void skipsWhenOnlyPreviousSummaryBeforeCutoff() {
        List<Msg> context = new ArrayList<>(List.of(
                summaryMsg(VALID_SUMMARY),
                userText("新材料", 2_500),
                assistantText("新回答", 2_500),
                userText("再问", 4)));

        assertThat(compactor.compactInPlace(context, USER_ID, SESSION_ID)).isFalse();
        assertThat(context).hasSize(4);
        verify(summarizer, never()).summarize(any(), any());
    }

    @Test
    @DisplayName("摘要为空：放弃且上下文不变、不落库")
    void givesUpWhenSummaryBlank() {
        when(summarizer.summarize(any(), any())).thenReturn(null);
        List<Msg> context = new ArrayList<>(firstGenerationContext());
        List<Msg> before = List.copyOf(context);

        assertThat(compactor.compactInPlace(context, USER_ID, SESSION_ID)).isFalse();
        assertThat(context).containsExactlyElementsOf(before);
        verify(compactionMapper, never()).insert(any(AgentContextCompactionDO.class));
    }

    @Test
    @DisplayName("落库失败只报警不回滚：压缩本身已生效")
    void keepsCompactionWhenAuditInsertFails() {
        when(compactionMapper.insert(any(AgentContextCompactionDO.class)))
                .thenThrow(new RuntimeException("库闪断"));
        List<Msg> context = new ArrayList<>(firstGenerationContext());

        assertThat(compactor.compactInPlace(context, USER_ID, SESSION_ID)).isTrue();
        assertThat(context).hasSize(4);
        assertThat(context.get(0).getName()).isEqualTo("__compaction_summary__");
    }

    @Test
    @DisplayName("审计落库带主体成员：memberId=当前主体 canonical membershipId，其余审计字段不变")
    void auditCarriesPrincipalMemberId() {
        List<Msg> context = new ArrayList<>(firstGenerationContext());
        int totalBefore = AgentContextChars.total(context);

        assertThat(compactor.compactInPlace(context, USER_ID, SESSION_ID)).isTrue();

        ArgumentCaptor<AgentContextCompactionDO> captor = ArgumentCaptor.forClass(AgentContextCompactionDO.class);
        verify(compactionMapper).insert(captor.capture());
        AgentContextCompactionDO row = captor.getValue();
        assertThat(row.getMemberId()).isEqualTo(MEMBERSHIP_ID);
        assertThat(row.getUserId()).isEqualTo(USER_ID);
        assertThat(row.getConversationId()).isEqualTo(SESSION_ID);
        assertThat(row.getGeneration()).isEqualTo(1);
        assertThat(row.getSummary()).isEqualTo(VALID_SUMMARY);
        assertThat(row.getMaterialMsgCount()).isEqualTo(2);
        assertThat(row.getMaterialChars()).isEqualTo(5_000);
        assertThat(row.getSummaryChars()).isEqualTo(VALID_SUMMARY.length());
        assertThat(row.getContextCharsBefore()).isEqualTo(totalBefore);
        assertThat(row.getContextCharsAfter()).isEqualTo(AgentContextChars.total(context));
    }

    @Test
    @DisplayName("主体缺失：审计不抛不落库，压缩本身照旧生效")
    void skipsAuditInsertWhenPrincipalMissing() {
        PrincipalContext.clear();
        List<Msg> context = new ArrayList<>(firstGenerationContext());

        assertThat(compactor.compactInPlace(context, USER_ID, SESSION_ID)).isTrue();
        assertThat(context).hasSize(4);
        assertThat(context.get(0).getName()).isEqualTo("__compaction_summary__");
        verify(compactionMapper, never()).insert(any(AgentContextCompactionDO.class));
    }

    /**
     * 首代素材：旧问/旧答各 2500 字符，切点前的可换出字符过半；
     * 尾段 1800+1800+4 使从尾部累计到 1600 的边界落在 1800 那条上，回退到最近的用户轮起点
     */
    private static List<Msg> firstGenerationContext() {
        return List.of(
                userText("旧问", 2_500),
                assistantText("旧答", 2_500),
                userText("切点问句", 1_800),
                assistantText("切点回答", 1_800),
                userText("再问", 4));
    }

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal(PRINCIPAL_TENANT, PRINCIPAL_USER, MEMBERSHIP_ID, 7, 3,
                Set.of("ai:agent:execute"), "jti-f16-audit", "platform", 1_700_000_000L, 1_700_003_600L);
    }

    private static Msg userText(String label, int chars) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .textContent(label + "正".repeat(Math.max(0, chars - label.length()))).build();
    }

    private static Msg assistantText(String label, int chars) {
        return Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .textContent(label + "正".repeat(Math.max(0, chars - label.length()))).build();
    }

    private static Msg assistantToolUse(String callId) {
        return Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(List.of(ToolUseBlock.builder().id(callId).name("search_knowledge")
                        .input(Map.of("query", "旧问题")).build()))
                .build();
    }

    private static Msg toolResult(String callId, int chars) {
        return Msg.builder().name("tool").role(MsgRole.TOOL)
                .content(ToolResultBlock.builder().id(callId).name("search_knowledge")
                        .output(TextBlock.builder().text("结".repeat(chars)).build())
                        .state(ToolResultState.SUCCESS).build())
                .build();
    }

    private static Msg summaryMsg(String summary) {
        String text = "<conversation_summary>\n" + SUMMARY_HEADER + "\n" + summary + "\n</conversation_summary>";
        return Msg.builder().name("__compaction_summary__").role(MsgRole.USER).textContent(text).build();
    }
}
