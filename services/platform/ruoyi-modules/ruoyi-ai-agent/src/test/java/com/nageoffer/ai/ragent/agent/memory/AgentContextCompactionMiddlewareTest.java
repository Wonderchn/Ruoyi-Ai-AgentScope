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

import com.nageoffer.ai.ragent.agent.memory.AgentContextTrimmer.TrimResult;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Tag;

/**
 * 压缩接线点钉子：两道水位按门分工、压缩前先验上行列表与 context 的引用关系、
 * 压缩改写后整段重建上行列表、任何异常退回裁剪不阻塞推理。F16:2 的接线行为在此固化。
 * 注意 AgentState.builder().context(...) 恒拷贝成内部列表：断言与打桩一律用 contextMutable() 实例。
 */
@Tag("dev")
class AgentContextCompactionMiddlewareTest {

    private static final String USER_ID = "u-1001";
    private static final String SESSION_ID = "c-2002";
    private static final String SUMMARY_MSG_NAME = "__compaction_summary__";

    private AgentContextTrimmer trimmer;
    private AgentContextCompactor compactor;
    private AgentMemoryProperties memoryProperties;
    private AgentContextCompactionMiddleware middleware;

    @BeforeEach
    void setUp() {
        trimmer = mock(AgentContextTrimmer.class);
        compactor = mock(AgentContextCompactor.class);
        memoryProperties = new AgentMemoryProperties();
        memoryProperties.setContextWindowChars(8_000);
        middleware = new AgentContextCompactionMiddleware(trimmer, compactor, memoryProperties);
        when(trimmer.trimInPlace(any())).thenReturn(TrimResult.UNCHANGED);
    }

    @Test
    @DisplayName("过压缩门且末条为用户消息：调压缩并按 前缀＋改写后上下文 重建上行列表")
    void compactsOverGateAndRebuildsUpstreamMessages() {
        List<Msg> source = overGateContext();
        RuntimeContext ctx = runtimeContextWith(source);
        List<Msg> context = ctx.getAgentState().contextMutable();
        Msg prefixMsg = userText("长期记忆块", 100);
        ReasoningInput input = alignedInput(prefixMsg, context);
        Msg summaryMsg = summaryPlaceholder();
        when(compactor.compactInPlace(any(), any(), any())).thenAnswer(inv -> {
            List<Msg> ctxList = inv.getArgument(0);
            ctxList.clear();
            ctxList.add(summaryMsg);
            return true;
        });

        ReasoningInput observed = apply(ctx, input);

        verify(compactor).compactInPlace(context, USER_ID, SESSION_ID);
        assertThat(observed.messages()).hasSize(2);
        assertThat(observed.messages().get(0)).isSameAs(prefixMsg);
        assertThat(observed.messages().get(1)).isSameAs(summaryMsg);
        verify(trimmer, never()).trimInPlace(any());
    }

    @Test
    @DisplayName("压缩放弃（返回 false）：本轮退回裁剪层")
    void fallsBackToTrimWhenCompactionGivesUp() {
        RuntimeContext ctx = runtimeContextWith(overGateContext());
        List<Msg> context = ctx.getAgentState().contextMutable();
        ReasoningInput input = alignedInput(null, context);
        when(compactor.compactInPlace(any(), any(), any())).thenReturn(false);

        ReasoningInput observed = apply(ctx, input);

        verify(compactor).compactInPlace(any(), any(), any());
        verify(trimmer).trimInPlace(context);
        assertThat(observed).isSameAs(input);
    }

    @Test
    @DisplayName("压缩抛异常：退回裁剪，不阻塞本轮推理")
    void fallsBackToTrimWhenCompactionThrows() {
        RuntimeContext ctx = runtimeContextWith(overGateContext());
        List<Msg> context = ctx.getAgentState().contextMutable();
        ReasoningInput input = alignedInput(null, context);
        when(compactor.compactInPlace(any(), any(), any())).thenThrow(new RuntimeException("摘要链路故障"));

        ReasoningInput observed = apply(ctx, input);

        verify(trimmer).trimInPlace(context);
        assertThat(observed).isSameAs(input);
    }

    @Test
    @DisplayName("摘要开关关闭：到不了压缩层，只裁剪")
    void trimsOnlyWhenSummaryDisabled() {
        memoryProperties.setSummaryEnabled(false);
        RuntimeContext ctx = runtimeContextWith(overGateContext());
        List<Msg> context = ctx.getAgentState().contextMutable();
        ReasoningInput input = alignedInput(null, context);

        ReasoningInput observed = apply(ctx, input);

        verify(trimmer).trimInPlace(context);
        verify(compactor, never()).compactInPlace(any(), any(), any());
        assertThat(observed).isSameAs(input);
    }

    @Test
    @DisplayName("末条不是用户消息（工具循环未闭合）：不压缩")
    void skipsCompactionWhenLastMessageIsNotUser() {
        RuntimeContext ctx = runtimeContextWith(overGateContext());
        List<Msg> context = ctx.getAgentState().contextMutable();
        context.add(assistantText("工具循环中的回答", 1_000));
        ReasoningInput input = alignedInput(null, context);

        apply(ctx, input);

        verify(compactor, never()).compactInPlace(any(), any(), any());
        verify(trimmer).trimInPlace(context);
    }

    @Test
    @DisplayName("低于压缩门：走裁剪层，不压缩")
    void trimsOnlyBelowCompactGate() {
        RuntimeContext ctx = runtimeContextWith(new ArrayList<>(List.of(
                userText("历史", 2_500),
                userText("本轮问句", 1_500))));
        List<Msg> context = ctx.getAgentState().contextMutable();
        ReasoningInput input = alignedInput(null, context);
        assertThat(AgentContextChars.total(context))
                .isBetween(memoryProperties.resolveTrimTriggerChars(), memoryProperties.resolveCompactTriggerChars());

        apply(ctx, input);

        verify(trimmer).trimInPlace(context);
        verify(compactor, never()).compactInPlace(any(), any(), any());
    }

    @Test
    @DisplayName("上行列表与上下文引用对不上：本轮不压缩，只裁剪")
    void skipsCompactionWhenUpstreamMismatch() {
        RuntimeContext ctx = runtimeContextWith(overGateContext());
        List<Msg> context = ctx.getAgentState().contextMutable();
        // 上行列表用同内容的独立副本，引用比对必然失配
        List<Msg> copies = context.stream()
                .map(m -> userText("副本", 200))
                .toList();
        ReasoningInput input = new ReasoningInput(new ArrayList<>(copies), List.of(), null);

        apply(ctx, input);

        verify(compactor, never()).compactInPlace(any(), any(), any());
        verify(trimmer).trimInPlace(context);
    }

    @Test
    @DisplayName("会话状态取不到：本轮按原列表推理，两层都不动")
    void passesThroughWhenStateUnavailable() {
        ReasoningInput input = new ReasoningInput(new ArrayList<>(List.of(userText("本轮问句", 10))), List.of(), null);
        RuntimeContext ctx = RuntimeContext.builder().userId(USER_ID).sessionId(SESSION_ID).build();

        ReasoningInput observed = apply(ctx, input);

        verify(trimmer, never()).trimInPlace(any());
        verify(compactor, never()).compactInPlace(any(), any(), any());
        assertThat(observed).isSameAs(input);
    }

    @Test
    @DisplayName("裁剪层替换上行列表里的工具结果消息")
    void appliesTrimReplacementsToUpstreamMessages() {
        RuntimeContext ctx = runtimeContextWith(new ArrayList<>(List.of(
                userText("历史", 2_500),
                userText("本轮问句", 1_500))));
        List<Msg> context = ctx.getAgentState().contextMutable();
        Msg placeholder = userText("占位", 10);
        ReasoningInput input = alignedInput(null, context);
        when(trimmer.trimInPlace(any())).thenReturn(new TrimResult(2_490, Map.of(context.get(0), placeholder)));

        ReasoningInput observed = apply(ctx, input);

        assertThat(observed.messages()).hasSize(2);
        assertThat(observed.messages().get(0)).isSameAs(placeholder);
        assertThat(observed.messages().get(1)).isSameAs(context.get(1));
    }

    private ReasoningInput apply(RuntimeContext ctx, ReasoningInput input) {
        AtomicReference<ReasoningInput> observed = new AtomicReference<>();
        Flux<AgentEvent> out = middleware.onReasoning(mock(io.agentscope.core.agent.Agent.class), ctx, input, next -> {
            observed.set(next);
            return Flux.empty();
        });
        out.blockLast();
        return observed.get();
    }

    private RuntimeContext runtimeContextWith(List<Msg> context) {
        RuntimeContext ctx = RuntimeContext.builder().userId(USER_ID).sessionId(SESSION_ID).build();
        ctx.setAgentState(AgentState.builder()
                .userId(USER_ID).sessionId(SESSION_ID).context(context).build());
        return ctx;
    }

    /**
     * 过压缩门（>6400）的最小上下文；末条保持用户消息
     */
    private static List<Msg> overGateContext() {
        return new ArrayList<>(List.of(
                userText("旧问", 3_500),
                assistantText("旧答", 3_000),
                userText("本轮问句", 10)));
    }

    /**
     * 上行列表与 context 逐条同引用对齐；prefixMsg 模拟外层中间件插入的记忆块
     */
    private static ReasoningInput alignedInput(Msg prefixMsg, List<Msg> context) {
        List<Msg> messages = new ArrayList<>();
        if (prefixMsg != null) {
            messages.add(prefixMsg);
        }
        messages.addAll(context);
        return new ReasoningInput(messages, List.of(), null);
    }

    private static Msg userText(String label, int chars) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .textContent(label + "正".repeat(Math.max(0, chars - label.length()))).build();
    }

    private static Msg assistantText(String label, int chars) {
        return Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .textContent(label + "正".repeat(Math.max(0, chars - label.length()))).build();
    }

    private static Msg summaryPlaceholder() {
        return Msg.builder().name(SUMMARY_MSG_NAME).role(MsgRole.USER).textContent("摘要占位").build();
    }
}
