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

import com.nageoffer.ai.ragent.ingestion.service.impl.IntentTreeServiceImpl;
import com.nageoffer.ai.ragent.rag.controller.IntentTreeController;
import com.nageoffer.ai.ragent.rag.controller.QueryTermMappingController;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryTermMappingCacheManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.service.impl.QueryTermMappingAdminServiceImpl;
import com.nageoffer.ai.ragent.sample.controller.SampleQuestionController;
import com.nageoffer.ai.ragent.sample.service.impl.SampleQuestionServiceImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 意图 / 词映射 / 示例问题管理面的内嵌装配（RW-22-R1，T3）。
 *
 * <p><b>为什么需要这一组。</b>{@link IntentTreeController}、{@link QueryTermMappingController}、
 * {@link SampleQuestionController} 都在 {@code com.nageoffer.ai.ragent.**} 下，而 platform 的扫描根是
 * {@code org.ruoyi} ⇒ 内嵌形态里它们<b>从来不是 bean</b>，方法级路径一条都不存在（404）。
 * 同时它们的类级前缀在 RW-22-R1 已归位到 {@code /internal/ai/v1}
 * （公开面由 {@code AiGatewayController} 白名单逐条放行为 {@code /api/ai/v1/**}）。
 * 本类必须由 T0 追加到
 * {@code ruoyi-ai-web/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 才生效（该文件不在本卡租约内）。
 *
 * <p><b>本组只装配闭包已经闭合的三面——逐项核过依赖（不是"看着差不多就装"）：</b>
 * <ul>
 *   <li>{@link IntentTreeServiceImpl} ← {@code IntentNodeMapper}/{@code KnowledgeBaseMapper}
 *       （{@code AiEmbeddedMapperConfiguration} 的 {@code @MapperScan} 数组已含
 *       {@code rag.dao.mapper} 与 {@code knowledge.dao.mapper}）、
 *       {@code IntentTreeCacheManager}（{@code StringRedisTemplate} + {@code ObjectMapper}）、
 *       {@code BizChangeLogContext}（{@code AiEmbeddedAgentCatalogConfiguration} 已 {@code @Import}）；
 *       另需 {@code IntentTreeService} 的 {@code @Service} 实现本身被登记；</li>
 *   <li>{@link QueryTermMappingAdminServiceImpl} ← {@code QueryTermMappingMapper}（同上已扫）、
 *       {@code QueryTermMappingCacheManager}（{@code StringRedisTemplate} + {@code ObjectMapper}）、
 *       {@code BizChangeLogContext}；</li>
 *   <li>{@link SampleQuestionServiceImpl} ← {@code SampleQuestionMapper}（{@code sample.dao.mapper}
 *       已在扫描数组内）、{@code BizChangeLogContext} —— <b>无外部依赖</b>。</li>
 * </ul>
 *
 * <p><b>Redis 是既有前置，不是本组新引入的依赖。</b>{@code IntentTreeCacheManager} 与
 * {@code QueryTermMappingCacheManager} 都需要 {@code StringRedisTemplate}；而 shipped
 * {@code application-embedded.yml} 已因 {@code agent.conversation.enabled=true} 明文要求 Redis
 * （"需要 Redis 保护在途会话删除 … 缺 Redis 时明确启动失败"）。因此本组<b>不新增</b>部署前置，
 * 但真机是否可用仍以 T8 的实例窗口为准（本卡记 NOT_RUN）。
 *
 * <p><b>刻意未装 {@code RecommendedQuestionController}（F17 的推荐追问）。</b>
 * 其服务闭包为 {@code RecommendedQuestionServiceImpl} ← {@code RecommendedQuestionGenerator}
 * ← {@code AgentPromptResolver} + {@code LLMService} —— 落在<b>模型/提供方路由闭包</b>上
 * （与 RW-08 的 {@code IntentNodeRegistry} 闭包共用 {@code LLMService} 这一环）。
 * 闭包未确认前装配会以"no qualifying bean"让<b>整个应用启动失败</b>（fail-closed，
 * 与 {@code AiEmbeddedKnowledgeAdminConfiguration} 对 KB/文档面的处置同形）。
 * 该控制器的类级前缀归位已在 RW-22-R1 完成（源码正确），但**不登记 bean、不放行路由**，
 * 待模型闭包确认后另开一张卡。这与 V27 迁移注释"7137-7140/7142 留给 F11 卡接管"同一纪律：
 * <b>不制造"有权限无端点"或"有端点无实现"的假公开面。</b>
 *
 * <p><b>授权不在本层</b>：不加 {@code @SaCheckPermission}；公开面必须过网关白名单 +
 * {@code AiCanonicalAction} 的精确 scope 比较（动作复用 {@code config.read}/{@code config.publish}/
 * {@code kb.read}，<b>不新增 canonical 动作</b>）。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedIntentConfiguration {

    /**
     * 意图树 + 词映射 + 示例问题三面。
     *
     * <p>用 {@code @Import} 登记实现类与控制器，让构造注入照常工作——
     * 与 {@code AiEmbeddedKnowledgeAdminConfiguration} / {@code AiEmbeddedAgentCatalogConfiguration}
     * 的写法一致，避免手写 {@code @Bean} 方法时把参数顺序抄错。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
    @Import({IntentTreeServiceImpl.class, IntentTreeController.class,
            QueryTermMappingAdminServiceImpl.class, QueryTermMappingController.class,
            SampleQuestionServiceImpl.class, SampleQuestionController.class})
    static class IntentAdmin {
        @Bean
        @ConditionalOnMissingBean
        IntentTreeCacheManager intentTreeCacheManager(StringRedisTemplate redis, ObjectMapper mapper) {
            return new IntentTreeCacheManager(redis, mapper);
        }

        @Bean
        @ConditionalOnMissingBean
        QueryTermMappingCacheManager queryTermMappingCacheManager(StringRedisTemplate redis, ObjectMapper mapper) {
            return new QueryTermMappingCacheManager(redis, mapper);
        }
    }
}
