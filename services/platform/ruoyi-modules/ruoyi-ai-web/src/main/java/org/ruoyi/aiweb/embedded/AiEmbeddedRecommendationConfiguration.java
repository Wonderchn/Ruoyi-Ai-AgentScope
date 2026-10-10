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

package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.rag.controller.RecommendedQuestionController;
import com.nageoffer.ai.ragent.rag.service.impl.RecommendedQuestionGenerator;
import com.nageoffer.ai.ragent.rag.service.impl.RecommendedQuestionServiceImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 推荐追问面（F17）的内嵌装配（RW-22-R1-R7，T0）。
 *
 * <p><b>为什么此前刻意不装，现在可以装。</b>{@code AiEmbeddedIntentConfiguration} 的类注释记录过
 * 当时的处置：该面闭包经 {@code RecommendedQuestionGenerator} 抵达 {@code LLMService}，
 * 落在<b>模型/提供方路由闭包</b>上（与 RW-08 的 {@code IntentNodeRegistry} 闭包共用同一环），
 * 闭包未确认前装配会以 {@code no qualifying bean} 让<b>整个应用启动失败</b>，
 * 因此只把类级前缀归位、不登记 bean、不放行路由。
 *
 * <p>现在该前置已由只读核清闭合（{@code reports/T3/RW-08-CLOSURE-R6.md}），逐项已核实：
 * <ul>
 *   <li>{@code RecommendedQuestionServiceImpl} ← {@code ConversationMessageMapper}
 *       （{@code AiEmbeddedMapperConfiguration} 的 {@code @MapperScan} 已覆盖
 *       {@code rag.dao.mapper}）+ {@link RecommendedQuestionGenerator}；</li>
 *   <li>{@link RecommendedQuestionGenerator} ← {@code AgentPromptResolver} +
 *       {@code LLMService}。前者由 {@code AiEmbeddedAgentCatalogConfiguration} 显式导入
 *       （其 {@code AgentPromptCacheManager} 需 {@code StringRedisTemplate}）；
 *       后者由 {@code AiEmbeddedModelConfiguration.LocalTransport.ModelEnabled} 在
 *       {@code ai.model.enabled=true} 下提供；</li>
 *   <li>{@code StringRedisTemplate} 是 <b>既有</b>前置，不是本组新引入的依赖
 *       （{@code AiEmbeddedIntentConfiguration} 的两个缓存管理器已要求它）；</li>
 *   <li>模型/提供方链自身的闭包由 {@code AiEmbeddedModelConfigurationTest} 的正负腿钉住。</li>
 * </ul>
 * ⇒ 本组<b>只</b>导入这三个类，不导入 {@code RecommendedQuestionService} 的其它实现、
 * 不动 {@code IntentAdmin}（给它套模型门控会连带关掉不依赖模型的意图/词映射/示例问题面）。
 *
 * <p><b>门控（三开关全开才装配）。</b>{@code ai.integration.enabled=true} +
 * {@code ai.integration.transport=local}（由 {@link ConditionalOnEmbeddedLocal} 表达）、
 * {@code p2.enabled=true}、{@code ai.model.enabled=true}。任一不满足即整组不装配 ——
 * 与 {@code AiEmbeddedModelConfiguration} 同口径：<b>调用方以缺 bean 明确失败，不做静默降级</b>。
 * 三开关是 AND 关系，因此"模型链不在场却装了派生调用方"这种启动失败形态在结构上不可能出现。
 *
 * <p><b>公开面。</b>唯一端点 {@code POST /api/ai/v1/conversations/messages/{messageId}/recommended-questions}
 * （与既有 {@code .../feedback} 同一形状），由 T0 在 {@code AiGatewayController.ROUTES} 逐条登记。
 * 控制器类级前缀已是 {@code /internal/ai/v1}、返回整数 {@code code} 的
 * {@code ApiEnvelope}，且以 {@code PrincipalContext.require()} 取规范主体（缺主体即拒绝）——
 * 三件套（内层前缀 / 显式装配 / 白名单登记）在本组完成后齐全。
 *
 * <p><b>限域（本卡同时修的缺陷）。</b>{@code RecommendedQuestionServiceImpl} 原先只有
 * {@code user_id} 谓词，而内嵌态下平台 {@code TenantLine} 插件取的是 {@code TenantHelper}
 * （只读 {@code LoginHelper}/dynamic），与委托主体 {@code PrincipalContext} 并非同一权威，
 * 租户为空时插件还会直接 {@code return true} 跳过 —— 只修 SQL 仍会跨租户串读。
 * 故本卡改为：入口 {@code PrincipalContext.require()} 并校验调用方 userId；
 * 两处 SELECT 显式带 canonical {@code tenant_id} + {@code member_id} + {@code user_id}；
 * 落库改条件 {@code update} 并校验影响行数恰 1，0 行即拒绝、<b>不返回生成内容</b>。
 *
 * <p><b>回执与动作。</b>本面是写面（POST），<b>不铸</b> GET 交付回执
 * （网关仅在字节分支释放许可，JSON 分支铸造会变成许可泄漏）。
 * 授权复用既有 canonical 动作 {@code conversation.rename}（R6 重裁：只持
 * {@code conversation.read} 的调用必须 403，见 {@code LocalAdminRouteDispatchTest}），
 * <b>不新增动作、不新增权限行、不新增迁移</b>（{@code P1CurrentAuthorizationTest} 的动作条数护栏不变）。
 *
 * <p><b>诚实边界。</b>本组装配正确性由 MVC/dispatch 判据与上下文装载判据证明；
 * <b>真实 provider 外发</b>（无当前授权密钥）、真机、浏览器 E2E 仍为 {@code NOT_RUN}。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedRecommendationConfiguration {

    /**
     * 运行链门控层（{@code p2.enabled}）。
     *
     * <p>与 {@code ai.model.enabled} 分层写成两个嵌套配置类，是因为
     * {@code @ConditionalOnProperty} 不可重复标注 —— {@code AiEmbeddedModelConfiguration}
     * 的 {@code LocalTransport}/{@code ModelEnabled} 用的就是这个写法。
     * 条件不满足时本层不被处理，其成员类（含下面 {@code ModelEnabled}）也不会被处理，
     * 因此不需要在子层重复 {@code p2.enabled}。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
    static class P2Enabled {

        /**
         * 模型链门控层（{@code ai.model.enabled}）：与 {@code LLMService} 的提供条件对齐。
         *
         * <p>本层存在与否<b>只</b>取决于模型开关，不做"缺 LLMService 就跳过一个 bean"的补救：
         * 那会把"配置写错"变成"运行期才 500"，正是本项目反复禁止的静默降级。
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnEmbeddedLocal
        @ConditionalOnProperty(name = "ai.model.enabled", havingValue = "true")
        @Import({RecommendedQuestionController.class,
                RecommendedQuestionServiceImpl.class,
                RecommendedQuestionGenerator.class})
        static class ModelEnabled {
        }
    }
}
