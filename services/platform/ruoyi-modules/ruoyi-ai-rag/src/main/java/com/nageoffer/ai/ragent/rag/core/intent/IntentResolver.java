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

package com.nageoffer.ai.ragent.rag.core.intent;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.rag.dto.IntentCandidate;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.runtime.scope.AsyncRetrievalTaskScope;
import com.nageoffer.ai.ragent.runtime.scope.RetrievalEgressSubjectFacts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.concurrent.Executor;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INTENT_MIN_SCORE;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MAX_INTENT_COUNT;
import static com.nageoffer.ai.ragent.rag.enums.IntentKind.SYSTEM;

@Slf4j
@Service
public class IntentResolver {

    private final IntentClassifier intentClassifier;
    private final Executor intentClassifyExecutor;

    /**
     * 租户作用域绑定端口（BD-1）：任务体重绑时在身份作用域内侧绑定受理捕获的租户。
     * 端口缺席（独立应用无租户拦截器）时按原形态直跑。
     */
    private final ObjectProvider<TenantScopeBinder> tenantScopeBinder;

    /**
     * 出站身份重建事实端口（BD-1）：意图分类链任务体含 LLM 出站，池线程无同步起源，
     * 按在线事实重建出站主体（flush 同形）。端口缺席＝拒绝重建、退回捕获形态（出站照旧拒）。
     */
    private final ObjectProvider<RetrievalEgressSubjectFacts> egressFacts;

    /**
     * 意图分类链的出站声明权限集（BD-1，声明集最小化＝恰一项）。
     *
     * <p>意图分类是 KB 检索管线的前半程（其输出只路由/收窄检索作用域），整条管线的授权动作
     * 就是检索（{@code RetrievalScopeAuthorizer.ACTION_KB_RETRIEVE} = "kb.retrieve"）。
     * 动作名→权限串映射在 ruoyi-ai-api 的 {@code AiCanonicalAction}
     * （"kb.retrieve" → "ai:kb:retrieve"）；rag 模块依赖面不可见 api，故此处落权限串字面量
     * 并以该映射锚定——两处漂移时本重建会被成员在线 scopes 检查拒绝（fail-closed，不放大）。
     */
    public static final Set<String> INTENT_EGRESS_PERMISSIONS = Set.of("ai:kb:retrieve");

    /**
     * 显式构造（替代 {@code @RequiredArgsConstructor}，S1 装配批 R1）：两处限定符都写在
     * <b>构造参数</b>上——platform 树没有 {@code services/ai/lombok.config}，字段上的
     * {@code @Qualifier} 不会进入 Lombok 生成的构造参数（原形态只是"接口恰有一个实现"侥幸可用，
     * RW-08 已登记该潜伏地雷）。
     *
     * <ul>
     *   <li>{@code defaultIntentClassifier}：按名钉住唯一实现（闭包以该名注册
     *       {@code DefaultIntentClassifier}）；</li>
     *   <li>{@code intentClassifyExecutor}：platform 侧两个 {@code @Primary} 的 {@code Executor}
     *       （{@code ThreadPoolConfig}）会让按类型注入以"多个 primary"启动期失败，必须按名注入。</li>
     * </ul>
     *
     * <p>BD-1（2026-10-14）追加了 {@code tenantScopeBinder}/{@code egressFacts} 两个端口：
     * 分类任务体从受理线程跳到 {@code intent_classify_executor_*} 池线程后动态租户丢失，
     * 真机对 {@code ai_intent_node} fail-closed（{@code tenant.context.missing}）——任务体以
     * 受理捕获重绑；体内 LLM 出站段按在线事实重建出站主体（flush 同形）。
     */
    public IntentResolver(@Qualifier("defaultIntentClassifier") IntentClassifier intentClassifier,
                          @Qualifier("intentClassifyExecutor") Executor intentClassifyExecutor,
                          ObjectProvider<TenantScopeBinder> tenantScopeBinder,
                          ObjectProvider<RetrievalEgressSubjectFacts> egressFacts) {
        this.intentClassifier = intentClassifier;
        this.intentClassifyExecutor = intentClassifyExecutor;
        this.tenantScopeBinder = tenantScopeBinder;
        this.egressFacts = egressFacts;
    }

    @RagTraceNode(name = "intent-resolve", type = "INTENT")
    public List<SubQuestionIntent> resolve(RewriteResult rewriteResult) {
        List<String> subQuestions = CollUtil.isNotEmpty(rewriteResult.subQuestions())
                ? rewriteResult.subQuestions()
                : List.of(rewriteResult.rewrittenQuestion());
        // BD-1：受理线程（同步起源）捕获身份引用；无捕获形态按原样直跑。捕获在线程跳变前完成，
        // 任务体内经重建式（含 LLM 出站段）或其退化捕获形态重绑（拒绝重建＝非配对 restore，出站照旧拒）。
        AsyncRetrievalTaskScope.Capture capture = AsyncRetrievalTaskScope.Capture.capture();
        List<CompletableFuture<SubQuestionIntent>> tasks = subQuestions.stream()
                .map(q -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return AsyncRetrievalTaskScope.callWithRebuiltEgress(capture, tenantScopeBinder,
                                        egressFacts, INTENT_EGRESS_PERMISSIONS,
                                        () -> new SubQuestionIntent(q, classifyIntents(q)));
                            } catch (Exception e) {
                                log.error("子问题意图分类失败，降级为空意图，question：{}", q, e);
                                return new SubQuestionIntent(q, List.of());
                            }
                        },
                        intentClassifyExecutor
                ))
                .toList();
        List<SubQuestionIntent> subIntents = tasks.stream()
                .map(CompletableFuture::join)
                .toList();
        return capTotalIntents(subIntents);
    }

    public List<NodeScore> mergeKbIntents(List<SubQuestionIntent> subIntents) {
        List<NodeScore> kbIntents = new ArrayList<>();
        for (SubQuestionIntent si : subIntents) {
            kbIntents.addAll(NodeScoreFilters.kb(si.nodeScores()));
        }
        return kbIntents;
    }

    public boolean isSystemOnly(List<NodeScore> nodeScores) {
        return nodeScores.size() == 1
                && nodeScores.get(0).getNode() != null
                && nodeScores.get(0).getNode().getKind() == SYSTEM;
    }

    private List<NodeScore> classifyIntents(String question) {
        List<NodeScore> scores = intentClassifier.classifyTargets(question);
        return scores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .limit(MAX_INTENT_COUNT)
                .toList();
    }

    /**
     * 限制总意图数量不超过 MAX_INTENT_COUNT
     * <p>
     * 策略：
     * 1. 如果总数未超限，直接返回
     * 2. 如果超限，每个子问题至少保留 1 个最高分意图
     * 3. 剩余配额按分数从高到低分配给其他意图
     */
    private List<SubQuestionIntent> capTotalIntents(List<SubQuestionIntent> subIntents) {
        int totalIntents = subIntents.stream()
                .mapToInt(si -> si.nodeScores().size())
                .sum();

        // 未超限，直接返回
        if (totalIntents <= MAX_INTENT_COUNT) {
            return subIntents;
        }

        // 步骤1：收集所有意图，按子问题索引分组
        List<IntentCandidate> allCandidates = collectAllCandidates(subIntents);

        // 步骤2：每个子问题保留最高分意图
        List<IntentCandidate> guaranteedIntents = selectTopIntentPerSubQuestion(allCandidates, subIntents.size());

        // 步骤3：计算剩余配额
        int remaining = MAX_INTENT_COUNT - guaranteedIntents.size();

        // 步骤4：从剩余候选中按分数选择
        List<IntentCandidate> additionalIntents = selectAdditionalIntents(allCandidates, guaranteedIntents, remaining);

        // 步骤5：合并并重建结果
        return rebuildSubIntents(subIntents, guaranteedIntents, additionalIntents);
    }

    /**
     * 收集所有意图候选，标记所属子问题索引
     */
    private List<IntentCandidate> collectAllCandidates(List<SubQuestionIntent> subIntents) {
        List<IntentCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < subIntents.size(); i++) {
            List<NodeScore> nodeScores = subIntents.get(i).nodeScores();
            if (CollUtil.isEmpty(nodeScores)) {
                continue;
            }
            for (NodeScore ns : nodeScores) {
                candidates.add(new IntentCandidate(i, ns));
            }
        }
        // 按分数降序排序
        candidates.sort((a, b) -> Double.compare(b.nodeScore().getScore(), a.nodeScore().getScore()));
        return candidates;
    }

    /**
     * 每个子问题选择最高分意图（保底策略）
     */
    private List<IntentCandidate> selectTopIntentPerSubQuestion(List<IntentCandidate> allCandidates, int subQuestionCount) {
        List<IntentCandidate> topIntents = new ArrayList<>();
        boolean[] selected = new boolean[subQuestionCount];

        for (IntentCandidate candidate : allCandidates) {
            int index = candidate.subQuestionIndex();
            if (!selected[index]) {
                topIntents.add(candidate);
                selected[index] = true;
            }
            // 所有子问题都有了保底意图，提前退出
            if (topIntents.size() == subQuestionCount) {
                break;
            }
        }
        return topIntents;
    }

    /**
     * 从剩余候选中选择额外意图
     */
    private List<IntentCandidate> selectAdditionalIntents(List<IntentCandidate> allCandidates,
                                                          List<IntentCandidate> guaranteedIntents,
                                                          int remaining) {
        if (remaining <= 0) {
            return List.of();
        }

        List<IntentCandidate> additional = new ArrayList<>();
        for (IntentCandidate candidate : allCandidates) {
            // 跳过已经被选为保底的意图
            if (guaranteedIntents.contains(candidate)) {
                continue;
            }
            additional.add(candidate);
            if (additional.size() >= remaining) {
                break;
            }
        }
        return additional;
    }

    /**
     * 根据选中的意图重建 SubQuestionIntent 列表
     */
    private List<SubQuestionIntent> rebuildSubIntents(List<SubQuestionIntent> originalSubIntents,
                                                      List<IntentCandidate> guaranteedIntents,
                                                      List<IntentCandidate> additionalIntents) {
        // 合并所有选中的意图
        List<IntentCandidate> allSelected = new ArrayList<>(guaranteedIntents);
        allSelected.addAll(additionalIntents);

        // 按子问题索引分组
        Map<Integer, List<NodeScore>> groupedByIndex = new HashMap<>();
        for (IntentCandidate candidate : allSelected) {
            groupedByIndex.computeIfAbsent(candidate.subQuestionIndex(), k -> new ArrayList<>())
                    .add(candidate.nodeScore());
        }

        // 重建结果
        List<SubQuestionIntent> result = new ArrayList<>();
        for (int i = 0; i < originalSubIntents.size(); i++) {
            SubQuestionIntent original = originalSubIntents.get(i);
            List<NodeScore> retained = groupedByIndex.getOrDefault(i, List.of());
            result.add(new SubQuestionIntent(original.subQuestion(), retained));
        }
        return result;
    }
}
