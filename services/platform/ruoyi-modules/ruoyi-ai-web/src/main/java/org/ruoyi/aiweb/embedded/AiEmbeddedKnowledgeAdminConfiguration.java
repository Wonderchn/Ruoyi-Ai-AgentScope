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

import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeBaseController;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeChunkController;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeDocumentController;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeBaseServiceImpl;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeChunkServiceImpl;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 知识<b>管理面</b>（{@code /knowledge-base/**}）的内嵌装配（RW-04-R1）。
 *
 * <p><b>为什么需要这一组。</b>三个知识控制器
 * （{@link KnowledgeBaseController}/{@link KnowledgeDocumentController}/{@link KnowledgeChunkController}）
 * 都在 {@code com.nageoffer.ai.ragent.knowledge.controller} 下，而 platform 的扫描根是
 * {@code org.ruoyi} ⇒ 内嵌形态里它们<b>从来不是 bean</b>，方法级路径一条都不存在（404）。
 * 这不是"接口写错"，是"没装配"。
 *
 * <p><b>形态取自既有 precedent（{@code UploadController}）。</b>控制器带类级
 * {@code @RequestMapping("/internal/ai/v1")}（RW-04-R1 已改），本类显式登记 bean，
 * 公开面再由 {@code AiGatewayController} 白名单逐条放行为
 * {@code /api/ai/v1/knowledge-base/**}（单数 = 管理面；复数 {@code /knowledge-bases/**}
 * 是 {@code AiResourceController} 的 AI 资源面，两者不同面、不冲突）。
 * <b>不用</b>"保留裸路径 + 平台 Sa-Token 直挂"：裸路径不在
 * {@code DelegatedPrincipalFilter.PROTECTED_PREFIX} 之下，拿不到委托主体，
 * 而"缺主体必须拒绝"是硬约束。
 *
 * <p><b>本类只装配闭包已经闭合的那一面——分块管理面。</b>判断依据是逐项核过依赖：
 * <ul>
 *   <li>{@code KnowledgeChunkServiceImpl} ← {@code KnowledgeChunkMapper}/{@code KnowledgeDocumentMapper}/
 *       {@code KnowledgeBaseMapper}（{@code AiEmbeddedMapperConfiguration} 的 {@code @MapperScan}
 *       覆盖 {@code knowledge.dao.mapper}）、{@code ChunkEmbeddingService}/{@code TokenCounterService}/
 *       {@code VectorStoreService}（{@code AiEmbeddedKnowledgeConfiguration}，门控
 *       {@code rag.vector.type=pg}）、{@code TransactionOperations}（Boot 的 {@code TransactionTemplate}）、
 *       {@code BizChangeLogContext}（{@code AiEmbeddedAgentCatalogConfiguration} 已 {@code @Import}）
 *       ⇒ 只剩 {@link VectorTargetResolver} 需要本类补一个 bean；</li>
 *   <li>因此本组还沿用 {@code AiEmbeddedKnowledgeConfiguration} 的 {@code rag.vector.type=pg} 判据，
 *       避免出现"控制器装了、向量后端没装"的半装配态。</li>
 * </ul>
 *
 * <p><b>知识库面与文档面<b>刻意未装</b>（{@code knowledge-admin.full=false} 默认）。</b>
 * 不是遗漏，而是闭包没算清就盲装会反复重启试错（与 {@code AiEmbeddedAgentCatalogConfiguration}
 * 对 F11 Skills 的处置同形）：
 * <ul>
 *   <li>{@code KnowledgeBaseServiceImpl} → {@code FileStorageService} →
 *       {@code DefaultFileStorageService} → {@code ObjectStorageClient}（S3/OSS）+
 *       {@code RagStorageProperties}：对象存储与凭据属部署级配置，本批不在内嵌装配里；</li>
 *   <li>{@code KnowledgeDocumentServiceImpl}（22 个构造依赖）→ 除上面那条外，还要
 *       {@code ParserRegistry}（及其 {@code DocumentParser} 闭包：Tika/Excel/CSV/Markdown/Image/MinerU）、
 *       {@code ChunkingService}、{@code ChunkIndexWriter}、{@code DefaultIngestionKernel}、
 *       {@code IngestionEngine}（及其 {@code IngestionNode} 闭包）、{@code IngestionSpecCodec}、
 *       {@code IngestionPipelineService}、{@code RemoteFileFetcher}、
 *       {@code KnowledgeScheduleProperties}、{@code KnowledgeDocumentScheduleService}
 *       ——这是一张独立的装配图，另立卡算清闭包再装。</li>
 * </ul>
 * 需要打开时把 {@code ai.embedded.knowledge-admin.full=true} 并同时补齐上述闭包；
 * <b>缺 bean 时启动会响亮失败</b>（fail-closed），不会静默变成一个 404 的管理面。
 *
 * <p><b>授权不在本层</b>：内层前缀对外由 {@code AiInternalAccessBoundaryFilter} 关成 404，
 * 公开面必须过网关白名单 + {@code AiCanonicalAction} 的 scope 比较，因此内层 controller
 * 不加 {@code @SaCheckPermission}（与 {@code AiResourceController}/{@code AgentChatController} 同形）。
 * 逐条"公开路由 → canonical 动作"映射见 RW-04 报告 §11.4。
 *
 * <p><b>本类必须由 T0 追加到</b>
 * {@code ruoyi-ai-web/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 才生效（该文件不在本卡租约内）。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedKnowledgeAdminConfiguration {

    /**
     * 分块管理面：闭包已闭合（见类注释），随内嵌 local 传输 + PG 向量后端装配。
     *
     * <p>用 {@code @Import} 登记实现类与控制器，让构造注入照常工作——
     * 与 {@code AiEmbeddedAgentCatalogConfiguration} 的写法一致，避免手写 {@code @Bean}
     * 方法时把参数顺序抄错。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
    @Import({KnowledgeChunkServiceImpl.class, KnowledgeChunkController.class})
    static class ChunkAdmin {

        /**
         * 向量落点解析：{@code AiEmbeddedKnowledgeConfiguration} 只登记了
         * {@link RAGDefaultProperties}，解析器本身要在 {@code org.ruoyi} 侧补登记。
         *
         * <p>维度是 fail-closed 的：{@code rag.default.dimension} 缺失时
         * {@link VectorTargetResolver} 直接拒绝，不猜一个维度去写库。
         */
        @Bean
        @ConditionalOnMissingBean
        public VectorTargetResolver vectorTargetResolver(RAGDefaultProperties ragDefaultProperties) {
            return new VectorTargetResolver(ragDefaultProperties);
        }
    }

    /**
     * 知识库面 + 文档面：<b>默认关闭</b>，闭包见类注释。
     *
     * <p>打开后若依赖闭包仍缺，启动阶段即以"no qualifying bean"失败——这是刻意的
     * fail-closed：宁可启动失败，也不要一个"装了但永远 404/500"的管理面。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "ai.embedded.knowledge-admin.full", havingValue = "true")
    @Import({KnowledgeBaseServiceImpl.class, KnowledgeBaseController.class,
            KnowledgeDocumentServiceImpl.class, KnowledgeDocumentController.class})
    static class FullAdmin {
    }
}
