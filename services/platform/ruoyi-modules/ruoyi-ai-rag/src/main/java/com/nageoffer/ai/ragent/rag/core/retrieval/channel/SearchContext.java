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

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索上下文
 * <p>
 * 携带检索所需的所有信息，在多个通道之间传递
 */
@Data
@Builder
public class SearchContext {

    /**
     * 原始问题
     */
    private String originalQuestion;

    /**
     * 重写后的问题
     */
    private String rewrittenQuestion;

    /**
     * 子问题列表
     */
    private List<String> subQuestions;

    /**
     * 意图识别结果
     */
    private List<SubQuestionIntent> intents;

    /**
     * 检索预算：召回扇出 / Rerank 候选池上限 / 最终条数，三段各自独立
     * 各阶段只读属于自己的那一段，避免用一个 topK 承载多重语义
     */
    private RetrievalBudget budget;

    /**
     * 检索作用域：定向命中库还是全库，请求入口算一次，各通道共读一份
     */
    private RetrievalScope retrievalScope;

    /**
     * P1.3b 已授权检索作用域（由检索入口的授权器解析一次，各通道共读）。
     *
     * <p>刻意<b>默认未设置</b>：未设置表示"这次检索没有经过授权解析"。
     * 通道必须用 {@link #getAuthorizedScope()} / {@link #requireAuthorizedScope()} 取用——
     * 缺作用域即拒绝或返回空集，绝不能把 null 理解成"没有限制，那就查全库"。
     */
    private AuthorizedRetrievalScope authorizedScope;

    /**
     * 扩展元数据
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 取已授权检索作用域；<b>未设置即返回"无授权"</b>（不是 null）。
     *
     * <p>这是检索链上"缺作用域 ⇒ 空集合"的唯一出处。返回 {@code denied()} 而不是抛错，
     * 是因为"没有授权"本身是一个正常且必须被正确处理的业务结果（list 空页 / 指定 404）。
     */
    public AuthorizedRetrievalScope getAuthorizedScope() {
        return authorizedScope == null ? AuthorizedRetrievalScope.denied() : authorizedScope;
    }

    /**
     * 取已授权检索作用域；未设置即抛错。
     *
     * <p>用在"必须已经过授权解析"的位置（如向量检索的直连入口）：这里的缺失属于接线错误，
     * 应当立刻失败而不是悄悄返回空结果——否则"忘了接线"会被伪装成"没有命中"。
     *
     * @throws ClientException 未设置作用域
     */
    public AuthorizedRetrievalScope requireAuthorizedScope() {
        if (authorizedScope == null) {
            throw new ClientException("search context carries no authorized retrieval scope");
        }
        return authorizedScope;
    }

    /**
     * 获取主问题（优先使用重写后的问题）
     */
    public String getMainQuestion() {
        return rewrittenQuestion != null ? rewrittenQuestion : originalQuestion;
    }
}
