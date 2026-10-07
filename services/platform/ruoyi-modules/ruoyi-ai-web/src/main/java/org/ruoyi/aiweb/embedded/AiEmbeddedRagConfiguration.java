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

import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.authorization.AiResourceController;
import com.nageoffer.ai.ragent.authorization.AiResourceWriteService;
import com.nageoffer.ai.ragent.authorization.AuthorizedDownloadService;
import com.nageoffer.ai.ragent.authorization.AuthorizedExportService;
import com.nageoffer.ai.ragent.authorization.DefaultRevocationGuard;
import com.nageoffer.ai.ragent.authorization.TenantConversationReadRepository;
import com.nageoffer.ai.ragent.authorization.TenantEventReadRepository;
import com.nageoffer.ai.ragent.authorization.TenantObjectReferenceRepository;
import com.nageoffer.ai.ragent.authorization.TenantRunReadRepository;
import com.nageoffer.ai.ragent.authorization.dao.AiAclEpochMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceAclMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper;
import com.nageoffer.ai.ragent.authorization.dao.ResourceSourceRefMapper;
import com.nageoffer.ai.ragent.framework.security.AuthorizationChecker;
import com.nageoffer.ai.ragent.framework.security.PlatformFactsPort;
import com.nageoffer.ai.ragent.framework.security.PlatformPermitPort;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.ruoyi.aiintegration.authorization.OrganizationMatchController;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.identity.ProductionAuthorizationProvider;
import org.ruoyi.aiweb.local.AiDeliveryReleaserAdapter;
import org.ruoyi.aiweb.local.LocalPlatformAuthorizationChecker;
import org.ruoyi.aiweb.local.LocalPlatformFacts;
import org.ruoyi.aiweb.local.LocalPlatformPermits;
import org.ruoyi.aiweb.transport.AiDeliveryReleaser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/**
 * AI 授权/资源路由组的内嵌装配（E3 单元二收尾，{@code transport=local}）。
 *
 * <p>把 {@code /internal/ai/v1} 下 rag 授权域控制器及其依赖图显式登记进 platform
 * 上下文（V2 §3：显式列举，不做根包扫描）。AI 类全部位于
 * {@code com.nageoffer.ai.ragent.**}，不在 platform 组件扫描路径内，只能在此逐项注册。
 *
 * <p>登记范围（首组：资源 + 知识库路由组）：
 * <ul>
 *   <li>{@link AiResourceController}：{@code /knowledge-bases}、{@code /documents/{id}}、
 *       {@code /conversations/**}、{@code /runs/**}、{@code /memories} 的只读与资源写入口；</li>
 *   <li>判定与写入服务：{@link AiResourceAuthorizationService}（FactPort/SubjectMatchPort）、
 *       {@link AiResourceWriteService}、{@link DefaultRevocationGuard}；</li>
 *   <li>读路径仓储与交付服务：{@link TenantConversationReadRepository}、
 *       {@link TenantRunReadRepository}、{@link TenantEventReadRepository}、
 *       {@link TenantObjectReferenceRepository}、{@link AuthorizedDownloadService}、
 *       {@link AuthorizedExportService}；</li>
 *   <li>DAO（{@code @Repository} + JdbcTemplate，非 MyBatis，无需 MapperScan）；</li>
 *   <li>本地端口：{@link PlatformFactsPort}/{@link PlatformPermitPort}/{@link AuthorizationChecker}
 *       ——替换 AI → platform 的三处 localhost HTTP 回调点；</li>
 *   <li>{@link AiDeliveryReleaser}：交付回执的本地出口；</li>
 *   <li>{@link AiInternalExceptionResolver}：AI 信封异常解析（最高优先级）。</li>
 * </ul>
 *
 * <p>数据源/事务：全部经 platform 唯一主数据源与事务管理器（C7）；
 * {@code TransactionOperations} 由 Boot 的 TransactionTemplate 提供。
 * 流式路由（SSE/上传/私有 PDF）不属本组，仍在传输层 fail-closed 503。
 *
 * <p>测试可覆盖任意 bean（{@code @ConditionalOnMissingBean}）：容器测试用 mock DAO
 * 驱动真实控制器与服务，不需要数据库。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedRagConfiguration {

    /** 本地传输下的装配（{@code transport=local} 才生效；http 传输不注册任何 AI 侧 bean）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    public static class LocalTransportAssembly {

        // ------------------------------------------------------------------ DAO（JdbcTemplate 原生 SQL，无 MyBatis）

        @Bean
        @ConditionalOnMissingBean
        public AiResourceMapper aiResourceMapper(NamedParameterJdbcTemplate jdbc) {
            return new AiResourceMapper(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean
        public AiResourceAclMapper aiResourceAclMapper(NamedParameterJdbcTemplate jdbc) {
            return new AiResourceAclMapper(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean
        public AiAclEpochMapper aiAclEpochMapper(NamedParameterJdbcTemplate jdbc) {
            return new AiAclEpochMapper(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean
        public ResourceSourceRefMapper resourceSourceRefMapper(NamedParameterJdbcTemplate jdbc) {
            return new ResourceSourceRefMapper(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean
        public TenantObjectReferenceRepository tenantObjectReferenceRepository(
                AiResourceMapper resourceMapper, NamedParameterJdbcTemplate jdbc) {
            return new TenantObjectReferenceRepository(resourceMapper, jdbc);
        }

        // ------------------------------------------------------------------ 读路径仓储

        @Bean
        @ConditionalOnMissingBean
        public TenantConversationReadRepository tenantConversationReadRepository(JdbcTemplate jdbc) {
            return new TenantConversationReadRepository(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean
        public TenantRunReadRepository tenantRunReadRepository(JdbcTemplate jdbc) {
            return new TenantRunReadRepository(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean
        public TenantEventReadRepository tenantEventReadRepository(JdbcTemplate jdbc) {
            return new TenantEventReadRepository(jdbc);
        }

        // ------------------------------------------------------------------ 判定 / 写入 / 撤权

        @Bean
        @ConditionalOnMissingBean
        public AiResourceAuthorizationService aiResourceAuthorizationService(
                AiResourceMapper resourceMapper, AiResourceAclMapper aclMapper,
                AiAclEpochMapper epochMapper, ResourceSourceRefMapper sourceRefMapper,
                ObjectProvider<java.time.Clock> clock) {
            return new AiResourceAuthorizationService(resourceMapper, aclMapper, epochMapper, sourceRefMapper, clock);
        }

        @Bean
        @ConditionalOnMissingBean
        public AiResourceWriteService aiResourceWriteService(NamedParameterJdbcTemplate jdbc,
                                                             AiResourceMapper resourceMapper,
                                                             AiResourceAclMapper aclMapper,
                                                             AiAclEpochMapper epochMapper,
                                                             TransactionOperations transactionOperations) {
            return new AiResourceWriteService(jdbc, resourceMapper, aclMapper, epochMapper, transactionOperations);
        }

        @Bean
        @ConditionalOnMissingBean(RevocationGuard.class)
        public DefaultRevocationGuard defaultRevocationGuard(JdbcTemplate jdbc) {
            return new DefaultRevocationGuard(jdbc);
        }

        // ------------------------------------------------------------------ 交付（下载 / 导出）

        @Bean
        @ConditionalOnMissingBean
        public AuthorizedDownloadService authorizedDownloadService(
                ObjectProvider<ResourceAuthorizationService> authorizationService,
                TenantObjectReferenceRepository objectReferences,
                ObjectProvider<org.ruoyi.ai.api.runtime.DocumentFileReader> fileStorage) {
            return new AuthorizedDownloadService(authorizationService, objectReferences, fileStorage);
        }

        @Bean
        @ConditionalOnMissingBean
        public AuthorizedExportService authorizedExportService(
                ObjectProvider<ResourceAuthorizationService> authorizationService, JdbcTemplate jdbc) {
            return new AuthorizedExportService(authorizationService, jdbc);
        }

        // ------------------------------------------------------------------ 控制器

        @Bean
        @ConditionalOnMissingBean
        public AiResourceController aiResourceController(AiResourceAuthorizationService authorization,
                                                         AiResourceWriteService writeService) {
            return new AiResourceController(authorization, writeService);
        }

        // ------------------------------------------------------------------ 本地端口（HTTP → 本地接口替换点）

        @Bean
        @ConditionalOnMissingBean
        public PlatformFactsPort localPlatformFacts(
                ObjectProvider<PlatformIdentitySource> identitySource,
                ObjectProvider<OrganizationMatchController.SubjectMatchSource> subjectMatchSource) {
            return new LocalPlatformFacts(identitySource, subjectMatchSource);
        }

        @Bean
        @ConditionalOnMissingBean
        public PlatformPermitPort localPlatformPermits(ObjectProvider<ProductionAuthorizationProvider> provider) {
            return new LocalPlatformPermits(provider);
        }

        @Bean
        @ConditionalOnMissingBean
        public AuthorizationChecker localPlatformAuthorizationChecker(
                ObjectProvider<PlatformIdentitySource> identitySource) {
            return new LocalPlatformAuthorizationChecker(identitySource);
        }

        @Bean
        @ConditionalOnMissingBean
        public AiDeliveryReleaser aiDeliveryReleaser(ObjectProvider<RevocationGuard> revocations) {
            return new AiDeliveryReleaserAdapter(revocations);
        }

        // ------------------------------------------------------------------ 异常解析

        @Bean
        @ConditionalOnMissingBean
        public AiInternalExceptionResolver aiInternalExceptionResolver() {
            return new AiInternalExceptionResolver();
        }
    }
}
