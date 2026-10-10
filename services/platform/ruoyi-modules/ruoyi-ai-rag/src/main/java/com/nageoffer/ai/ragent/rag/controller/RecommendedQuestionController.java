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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.rag.dto.RecommendedQuestionsPayload;
import com.nageoffer.ai.ragent.rag.service.RecommendedQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推荐追问问题控制器
 * <p>
 * 答案完成后按需触发，POST 幂等生成推荐追问并落库，不占用 chat 流式关键路径
 *
 * <p><b>路径面（RW-22-R1）</b>：类级前缀 {@code /internal/ai/v1}；公开面经网关白名单为
 * {@code /api/ai/v1/conversations/messages/{messageId}/recommended-questions}
 * （与既有 {@code /conversations/messages/{messageId}/feedback} 同一形状）。
 *
 * <p><b>身份来源（RW-22-R1，本类第二处必修）。</b>此前取 {@code UserContext.getUserId()}：
 * 内嵌形态下 {@code UserContext} <b>从来不被填充</b>（只有独立 AI 应用的
 * {@code UserContextInterceptor} 会填），因此这里恒为 {@code null}。而该值一路传到
 * {@code RecommendedQuestionServiceImpl.loadAssistantMessage(messageId, userId)} 的
 * {@code eq(ConversationMessageDO::getUserId, userId)} —— 它是<b>作用域谓词</b>，
 * 传 null 只会得到"查不到"，不会得到正确结果。
 * 故改为从 {@link PrincipalContext} 取<b>规范主体</b>：缺主体即拒绝（{@code require()} 语义），
 * 不做匿名回退。这与 {@code ConversationSurface}/{@code FeedbackSurface} 的内嵌桥同一口径。
 *
 * <p><b>信封（RW-22-R1）</b>：返回 {@link ApiEnvelope}（整数 {@code code}）。
 *
 * <p>授权不在本层：网关白名单动作为 {@code conversation.rename}（R6 重裁——只持
 * {@code conversation.read} 的调用必须 403，见 {@code LocalAdminRouteDispatchTest}），
 * 不新增 canonical 动作。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@RequiredArgsConstructor
public class RecommendedQuestionController {

    private final RecommendedQuestionService recommendedQuestionService;

    /**
     * 生成推荐追问问题
     */
    @PostMapping("/conversations/messages/{messageId}/recommended-questions")
    public ApiEnvelope<RecommendedQuestionsPayload> generate(@PathVariable String messageId) {
        ExecutionPrincipal principal = PrincipalContext.require();
        return ApiEnvelope.ok(recommendedQuestionService.generate(messageId, principal.userId()));
    }
}
