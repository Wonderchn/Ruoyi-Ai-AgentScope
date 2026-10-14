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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.SearchResultPostProcessor;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 多通道检索引擎
 * <p>
 * 负责协调多个检索通道和后置处理器：
 * 1. 并行执行所有启用的检索通道
 * 2. 依次执行后置处理器链
 * 3. 返回最终的检索结果
 */
@Slf4j
@Service
public class MultiChannelRetrievalEngine {

    private final List<SearchChannel> searchChannels;
    private final List<SearchResultPostProcessor> postProcessors;
    private final RetrievalScopeResolver retrievalScopeResolver;
    private final org.springframework.beans.factory.ObjectProvider<RetrievalScopeAuthorizer> retrievalScopeAuthorizer;
    private final Executor ragRetrievalExecutor;
    private final SearchChannelProperties searchProperties;

    /**
     * 显式构造（替代 {@code @RequiredArgsConstructor}，S1 装配批 R1）：{@code Executor} 参数
     * 带 {@code @Qualifier} <b>按名注入</b>——platform 侧两个 {@code @Primary} 的 {@code Executor}
     * （{@code ThreadPoolConfig}）会让按类型注入以"多个 primary"启动期失败。
     * 用显式构造参数而非字段注解：platform 树无 {@code lombok.config}，
     * Lombok 生成的构造参数不会带上 final 字段的 {@code @Qualifier}；
     * 参数顺序与生成形态逐位一致，既有测试的手工构造（{@code MultiChannelRetrievalEngineTest}）不受影响。
     *
     * <p>BD-2（2026-10-14，维护者裁决批准）追加了 {@code retrievalScopeAuthorizer}：检索入口在此
     * 解析已授权检索作用域（P1.3b 装配点接线——{@code SearchContext.authorizedScope} 此前在 main
     * 全史无生产者，向量通道对空作用域交空卷、结构性不可达）。授权器经 {@code ObjectProvider} 注入：
     * <b>装配缺席是合法状态</b>（同授权器自身对内层依赖的口径——结果是"没有授权"（denied），
     * 不是应用起不来，最小装配上下文照常启动）；动作名 {@code kb.retrieve} 由授权器内部映射为
     * 权限串 {@code ai:kb:retrieve}（AiCanonicalAction），本类不触碰权限串字面量。
     */
    public MultiChannelRetrievalEngine(List<SearchChannel> searchChannels,
                                       List<SearchResultPostProcessor> postProcessors,
                                       RetrievalScopeResolver retrievalScopeResolver,
                                       org.springframework.beans.factory.ObjectProvider<RetrievalScopeAuthorizer> retrievalScopeAuthorizer,
                                       @Qualifier("ragRetrievalExecutor") Executor ragRetrievalExecutor,
                                       SearchChannelProperties searchProperties) {
        this.searchChannels = searchChannels;
        this.postProcessors = postProcessors;
        this.retrievalScopeResolver = retrievalScopeResolver;
        this.retrievalScopeAuthorizer = retrievalScopeAuthorizer;
        this.ragRetrievalExecutor = ragRetrievalExecutor;
        this.searchProperties = searchProperties;
    }

    /**
     * 执行多通道检索（仅 KB 场景）
     * <p>
     * 按子问题逐个调用：检索问题与作用域都取自同一个子问题，二者同源
     *
     * @param subIntent 子问题及其意图
     * @param budget    检索预算（召回扇出 / Rerank 候选池上限 / 最终条数）
     * @return 后处理后的 Chunk 及其意图归属
     */
    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public KnowledgeRetrievalResult retrieveKnowledgeChannels(SubQuestionIntent subIntent,
                                                               RetrievalBudget budget) {
        SearchContext context = buildSearchContext(subIntent, budget);

        List<SearchChannelResult> channelResults = executeSearchChannels(context);
        if (CollUtil.isEmpty(channelResults)) {
            return KnowledgeRetrievalResult.empty();
        }

        List<RetrievedChunk> chunks = executePostProcessors(channelResults, context);
        // 异常或超时导致定向证据为空时，保留的定向范围会使其按未命中处理
        return new KnowledgeRetrievalResult(
                chunks,
                deriveAttribution(chunks, context.getRetrievalScope()),
                context.getRetrievalScope().directedIntentIds());
    }

    /**
     * 按库推导意图归属：定向作用域下，最终存活 chunk 的 collection 属于某命中意图的绑定库即归属该意图
     * <p>
     * 归属与证据经由哪条通道到达无关——所有检索共用同一个问题，「哪条查询捞到它」只携带库信息与排名运气；
     * 同一库被多个意图绑定时全部归属（确定性多归属）。补充路证据的库不在任何命中意图绑定里，天然无归属；
     * 全局作用域没有命中意图，整体无归属
     */
    private Map<String, Set<String>> deriveAttribution(List<RetrievedChunk> chunks, RetrievalScope scope) {
        if (scope == null || !scope.directed() || chunks.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> intentIdsByCollection = new LinkedHashMap<>();
        for (NodeScore intent : scope.intents()) {
            String intentId = intent.getNode().getId();
            if (intentId == null || intentId.isBlank()) {
                continue;
            }
            for (String collection : intent.getNode().getEffectiveCollectionNames()) {
                intentIdsByCollection
                        .computeIfAbsent(collection, ignored -> new LinkedHashSet<>())
                        .add(intentId);
            }
        }
        Map<String, Set<String>> intentIdsByChunkKey = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            Set<String> intentIds = chunk.getCollectionName() == null
                    ? null
                    : intentIdsByCollection.get(chunk.getCollectionName());
            if (intentIds != null && !intentIds.isEmpty()) {
                intentIdsByChunkKey.putIfAbsent(RetrievedChunkKey.of(chunk), Set.copyOf(intentIds));
            }
        }
        return intentIdsByChunkKey;
    }

    private List<SearchChannelResult> executeSearchChannels(SearchContext context) {
        // 按通道类型枚举序做稳定排序：通道并行执行、下游融合（RRF）与归因均与顺序无关，
        // 这里排序仅为日志/派发顺序稳定可复现，不承载任何检索优先级语义
        List<SearchChannel> enabledChannels = searchChannels.stream()
                .filter(channel -> channel.isEnabled(context))
                .sorted(Comparator.comparingInt(channel -> channel.getType().ordinal()))
                .toList();

        if (enabledChannels.isEmpty()) {
            // 全站无任何知识召回、退化为裸 LLM，属配置事故而非正常降级，不能静默
            log.warn("没有任何启用的检索通道，本次不做知识召回；请检查 rag.search.channels.*.enabled 与对应后端开关");
            return List.of();
        }

        log.info("启用的检索通道：{}",
                enabledChannels.stream().map(SearchChannel::getName).toList());

        long channelTimeoutMs = searchProperties.getChannels().getTimeoutMs();
        List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
                .map(channel -> withTimeout(CompletableFuture.supplyAsync(
                        () -> {
                            long startTime = System.currentTimeMillis();
                            try {
                                log.info("执行检索通道：{}", channel.getName());
                                return channel.search(context);
                            } catch (Exception e) {
                                log.error("检索通道 {} 执行失败", channel.getName(), e);
                                return channel.emptyResult(System.currentTimeMillis() - startTime);
                            }
                        },
                        ragRetrievalExecutor
                ), channel, channelTimeoutMs))
                .toList();

        int successCount = 0;
        int failureCount = 0;
        int totalChunks = 0;

        List<SearchChannelResult> results = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();

        for (SearchChannelResult result : results) {
            int chunkCount = result.getChunks().size();
            totalChunks += chunkCount;

            if (chunkCount > 0) {
                successCount++;
                log.info("通道 {} 完成 ✓ - 检索到 {} 个 Chunk，耗时：{}ms",
                        result.getChannelName(),
                        chunkCount,
                        result.getLatencyMs()
                );
            } else {
                failureCount++;
                log.warn("通道 {} 完成但无结果 - 耗时：{}ms",
                        result.getChannelName(),
                        result.getLatencyMs()
                );
            }
        }

        log.info("多通道检索统计 - 总通道数: {}, 有结果: {}, 无结果: {}, Chunk 总数: {}",
                enabledChannels.size(), successCount, failureCount, totalChunks);

        return results;
    }

    private List<RetrievedChunk> executePostProcessors(List<SearchChannelResult> results,
                                                       SearchContext context) {
        List<SearchResultPostProcessor> enabledProcessors = postProcessors.stream()
                .filter(processor -> processor.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
                .toList();

        if (enabledProcessors.isEmpty()) {
            log.warn("没有启用的后置处理器，直接返回原始结果");
            return results.stream()
                    .flatMap(r -> r.getChunks().stream())
                    .collect(Collectors.toList());
        }

        List<RetrievedChunk> chunks = results.stream()
                .flatMap(r -> r.getChunks().stream())
                .collect(Collectors.toList());

        int initialSize = chunks.size();

        for (SearchResultPostProcessor processor : enabledProcessors) {
            try {
                int beforeSize = chunks.size();
                chunks = processor.process(chunks, results, context);
                int afterSize = chunks.size();

                log.info("后置处理器 {} 完成 - 输入: {} 个 Chunk, 输出: {} 个 Chunk, 变化: {}",
                        processor.getName(),
                        beforeSize,
                        afterSize,
                        (afterSize - beforeSize > 0 ? "+" : "") + (afterSize - beforeSize)
                );
            } catch (Exception e) {
                log.error("后置处理器 {} 执行失败，跳过该处理器", processor.getName(), e);
            }
        }

        log.info("后置处理器链执行完成 - 初始: {} 个 Chunk, 最终: {} 个 Chunk",
                initialSize, chunks.size());

        return chunks;
    }

    /**
     * 通道级超时：超过预算的通道按空结果降级，不让最慢一条钳制同一子问题里其余通道的融合
     * 只放弃结果、不中断执行，任务仍在池内跑完，超时值过小等于整路白算
     * <p>
     * 降级结果记引擎等到放弃为止的真实耗时：记 0 会让下游统计把「等满预算才放弃」读成
     * 「秒回、库里没料」，而这两者的处置方向正相反——前者调超时、后者补语料
     */
    private CompletableFuture<SearchChannelResult> withTimeout(CompletableFuture<SearchChannelResult> future,
                                                               SearchChannel channel, long timeoutMs) {
        if (timeoutMs <= 0) {
            return future;
        }
        long startTime = System.currentTimeMillis();
        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    long latencyMs = System.currentTimeMillis() - startTime;
                    Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof TimeoutException) {
                        log.warn("检索通道 {} 超过通道级超时 {}ms，放弃其结果，其余通道照常融合", channel.getName(), timeoutMs);
                    } else {
                        log.error("检索通道 {} 异步执行失败", channel.getName(), cause);
                    }
                    return channel.emptyResult(latencyMs);
                });
    }

    /**
     * 构建检索上下文
     * 作用域在此处算一次挂进上下文，各通道只读不判，保证同一子问题内三条通道的检索范围一致
     */
    private SearchContext buildSearchContext(SubQuestionIntent subIntent, RetrievalBudget budget) {
        List<SubQuestionIntent> subIntents = List.of(subIntent);
        String question = subIntent.subQuestion();

        // BD-2：检索入口解析已授权作用域（P1.3b 装配点接线）。候选资源对本链为空——全局/定向收窄
        // 是检索作用域（retrievalScopeResolver）的事；授权作用域回答"当前主体在线能查哪些库"，
        // 由授权器按动作 kb.retrieve 解析（空请求=全部已授权集合），通道按其剔除越权库。
        // 授权装配缺席（最小上下文/独立应用）= 合法状态：denied——空授权拒绝，不回落全库；
        // 无主体（载体缺席直跑形态）同理。
        return SearchContext.builder()
                .originalQuestion(question)
                .rewrittenQuestion(question)
                .intents(subIntents)
                .budget(budget)
                .retrievalScope(retrievalScopeResolver.resolve(subIntents))
                .authorizedScope(resolveAuthorizedScope())
                .build();
    }

    /** 授权器缺席＝没有授权（denied），不是错误——与授权器自身对内层依赖的口径一致。 */
    private AuthorizedRetrievalScope resolveAuthorizedScope() {
        RetrievalScopeAuthorizer authorizer =
                retrievalScopeAuthorizer == null ? null : retrievalScopeAuthorizer.getIfAvailable();
        return authorizer == null ? AuthorizedRetrievalScope.denied() : authorizer.authorize(List.of());
    }
}
