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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.keyword.KeywordRetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 关键词检索通道
 * <p>
 * 基于全文检索引擎（ES）的 BM25 关键词召回，与向量通道互补：擅长精确词、编号、专有名词等
 * 仅当开启 ES 关键词检索（rag.keyword.type=es）时才注册，
 * 否则整个通道不存在，引擎自动退化为纯向量检索
 * <p>
 * 与其他通道并行执行，结果统一进 RRF 融合，通道间无先后与优先级之分；检索范围读引擎解析好的作用域，与向量通道同源
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.keyword", name = "type", havingValue = "es")
public class KeywordSearchChannel implements SearchChannel {

    private final KeywordRetrieverService keywordRetriever;
    private final SearchChannelProperties properties;
    private final Executor innerRetrievalExecutor;

    @Override
    public String getName() {
        return "KeywordSearch";
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.KEYWORD;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getKeyword().isEnabled();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            // F05:4-R1：空授权 / 无授权 collection / 无已发布 chunk 引用时，
            // 必须在任何 ES 调用之前交空卷（与向量通道同口径，绝不回落全库）。
            AuthorizedRetrievalScope authorizedScope = context.getAuthorizedScope();
            if (!AuthorizedChannelSupport.hasUsableScope(authorizedScope)) {
                log.info("关键词检索授权作用域为空或缺少已发布块引用，跳过");
                return emptyResult(System.currentTimeMillis() - startTime);
            }

            // 作用域由引擎统一解析：定向为命中库，全局为全部有效库。
            // 请求库集只是选择条件，必须与授权集合求交后才允许下发给 ES。
            RetrievalScope scope = context.getRetrievalScope();
            List<String> collections = AuthorizedChannelSupport.intersect(
                    authorizedScope, scope.targetCollections());
            if (CollUtil.isEmpty(collections)) {
                log.info("关键词检索未解析到目标知识库，跳过");
                return emptyResult(System.currentTimeMillis() - startTime);
            }
            List<String> supplementCollections = AuthorizedChannelSupport.intersect(
                    authorizedScope, scope.supplementCollections());

            String question = context.getMainQuestion();
            ScopeQuota quota = ScopeQuota.split(scope, context.getBudget().recallBudget(), supplementRatio());
            // 补充路失败必须只损失自己：它拿到的是兜底名额，而 join() 抛出会让已经取回的命中库证据
            // 一起被通道级 catch 丢掉。当前 ES 实现恰好自己吞了异常，但那是实现的偶然、不是通道的保证。
            // 求交与结果过滤都在同一个 executor 任务内完成，授权集合不依赖异步完成后的再读取。
            CompletableFuture<List<RetrievedChunk>> supplementTask = quota.supplement() > 0
                    && !supplementCollections.isEmpty()
                    ? CompletableFuture.supplyAsync(
                    () -> AuthorizedChannelSupport.retainAuthorized(authorizedScope,
                            keywordRetriever.search(question, supplementCollections, quota.supplement())),
                    innerRetrievalExecutor)
                    .exceptionally(e -> {
                        log.warn("关键词补充路检索失败，仅丢弃补充证据: {}", e.getMessage());
                        return List.of();
                    })
                    : CompletableFuture.completedFuture(List.of());

            List<RetrievedChunk> primary = AuthorizedChannelSupport.retainAuthorized(
                    authorizedScope, keywordRetriever.search(question, collections, quota.primary()));
            List<RetrievedChunk> supplement = supplementTask.join();

            long latency = System.currentTimeMillis() - startTime;
            log.info("关键词检索完成，命中 {} 库 {} 条，补充 {} 库 {} 条，耗时 {}ms",
                    collections.size(), primary.size(), supplementCollections.size(), supplement.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD)
                    .channelName(getName())
                    .chunks(ChunkRanking.mergeByScore(primary, supplement))
                    .latencyMs(latency)
                    .build();
        } catch (Exception e) {
            log.error("关键词检索失败", e);
            return emptyResult(System.currentTimeMillis() - startTime);
        }
    }

    private double supplementRatio() {
        return properties.getScope().getSupplementRatio();
    }
}
