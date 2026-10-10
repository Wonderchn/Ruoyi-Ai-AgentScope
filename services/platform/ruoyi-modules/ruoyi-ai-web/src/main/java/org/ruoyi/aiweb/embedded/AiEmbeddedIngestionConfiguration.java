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

import com.nageoffer.ai.ragent.ingestion.controller.IngestionPipelineController;
import com.nageoffer.ai.ragent.ingestion.service.impl.IngestionPipelineServiceImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 摄取**管线 CRUD**管理面的内嵌装配（S2-F06-A1，2026-10-10）。
 *
 * <p><b>为什么需要这一组（五件套同形先例见 RW-06/RW-22-R1）。</b>
 * {@link IngestionPipelineController} 在 {@code com.nageoffer.ai.ragent.**} 下，
 * platform 的扫描根是 {@code org.ruoyi} ⇒ 内嵌形态里它<b>从来不是 bean</b>，
 * 一条端点都不存在；其类级前缀在本次装配归位到 {@code /internal/ai/v1}
 * （公开面由 {@code AiGatewayController} 白名单逐条放行为
 * {@code /api/ai/v1/ingestion/pipelines/**}）。
 * 本类必须出现在
 * {@code ruoyi-ai-web/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 才生效（已同批追加）。
 *
 * <p><b>闭包逐项核过（不是"看着差不多就装"）：</b>
 * {@link IngestionPipelineServiceImpl} ← {@code IngestionPipelineMapper} +
 * {@code IngestionPipelineNodeMapper}（{@code AiEmbeddedMapperConfiguration} 的
 * {@code @MapperScan} 数组已含 {@code com.nageoffer.ai.ragent.ingestion.dao.mapper}）、
 * {@code ObjectMapper}（platform 既有 bean）、{@code BizChangeLogContext}
 * （{@code AiEmbeddedAgentCatalogConfiguration} 已 {@code @Import}）——<b>无 Redis、
 * 无引擎依赖</b>，故不新增任何部署前置。
 *
 * <p><b>任务/引擎面刻意不入本组</b>：任务分页（{@code IngestionTaskController}）牵出
 * 引擎全闭包（ParserRegistry 启动自检 / MinerU-Redisson / LLM），闭包未闭合前盲装会以
 * "no qualifying bean"让整个应用启动失败。本组只装管线服务+控制器两端，
 * 与 F13-SLICE-1（flow 面）"逐条登记、不用通配"的纪律一致。
 *
 * <p><b>写身份不在本层</b>：{@code ai_ingestion_pipeline.owner_member_id} 是 V7 NOT NULL，
 * 写入由 {@code IngestionPipelineServiceImpl.create} 经 {@code AiDomainWriteIdentity.apply}
 * 从执行主体填充（缺主体 fail-closed 拒绝）。
 *
 * <p><b>授权不在本层</b>：控制器不加 {@code @SaCheckPermission}；公开面必须过网关白名单 +
 * {@code AiCanonicalAction} 的精确 scope 比较（动作复用 {@code config.read}/{@code config.publish}，
 * <b>不新增 canonical 动作、不新增权限行、不新增迁移</b>）。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedIngestionConfiguration {

    /**
     * 管线 CRUD 面：用 {@code @Import} 登记实现类与控制器，让构造注入照常工作
     * ——与 {@code AiEmbeddedIntentConfiguration} / {@code AiEmbeddedKnowledgeAdminConfiguration}
     * 的写法一致，避免手写 {@code @Bean} 方法时把参数顺序抄错。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
    @Import({IngestionPipelineServiceImpl.class, IngestionPipelineController.class})
    static class IngestionAdmin {
    }
}
