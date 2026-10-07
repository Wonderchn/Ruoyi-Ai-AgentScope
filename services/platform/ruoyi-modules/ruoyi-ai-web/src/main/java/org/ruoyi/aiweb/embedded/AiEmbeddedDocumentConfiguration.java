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
import com.nageoffer.ai.ragent.ingest.DocumentDao;
import com.nageoffer.ai.ragent.ingest.FsPrivateObjectStore;
import com.nageoffer.ai.ragent.ingest.PrivateObjectStore;
import com.nageoffer.ai.ragent.ingest.UploadController;
import com.nageoffer.ai.ragent.ingest.UploadIntentService;
import com.nageoffer.ai.ragent.ingest.UploadService;
import com.nageoffer.ai.ragent.runtime.P2FaultInjector;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * documents 上传/摄取路由组的内嵌装配（E3 单元二收尾五）：{@code /documents/{id}/meta}、
 * {@code /knowledge-bases/{id}/documents}、{@code /documents/{id}/ingestions}、
 * {@code /documents/{id}/tombstone}（JSON 面）。
 *
 * <p>门控与独立 AI 应用一致（{@code ai.integration.enabled=true} + {@code transport=local}
 * + {@code p2.enabled=true}）。
 *
 * <p>依赖图：{@link DocumentDao}（JdbcTemplate）、{@link UploadIntentService}（事务）、
 * {@link P2FaultInjector}（测试故障注入，缺省全部未布置；其 arming 端点
 * {@code P2TestControlController} <b>不</b>装配）、{@link FsPrivateObjectStore}
 * （p2 运行时私有对象存储；{@code p2.object-store.root} 缺失即启动失败，不静默回退）、
 * {@link RunAdmissionService}（run 组提供）→ {@link UploadService} →
 * {@link UploadController}（含 {@link DeliveryPermits} 交付许可）。
 *
 * <p>上传（multipart）与私有 PDF 取流（{@code /documents/{id}/source}）属流式传输面，
 * 当前由网关 fail-closed 503，待专用本地流传输落地。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedDocumentConfiguration {

    /** 本地传输下的 documents 装配。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    public static class LocalTransport {

        /** P2 运行面门控（上传/摄取/私有对象存储只在 p2 运行时存在）。 */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnEmbeddedLocal
        @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
        @EnableConfigurationProperties(P2RuntimeProperties.class)
        public static class P2Enabled {

            @Bean
            @ConditionalOnMissingBean
            public DocumentDao documentDao(JdbcTemplate jdbc) {
                return new DocumentDao(jdbc);
            }

            @Bean
            @ConditionalOnMissingBean
            public P2FaultInjector p2FaultInjector() {
                // 故障注入点缺省全部未布置；arming 端点不装配，生产不可布置
                return new P2FaultInjector();
            }

            @Bean
            @ConditionalOnMissingBean
            public UploadIntentService uploadIntentService(JdbcTemplate jdbc,
                                                           PlatformTransactionManager transactionManager) {
                return new UploadIntentService(jdbc, transactionManager);
            }

            @Bean
            @ConditionalOnMissingBean
            public PrivateObjectStore privateObjectStore(P2RuntimeProperties properties) {
                // p2 运行时只认 fs；s3/minio 未实现即响亮失败（与独立运行一致，不静默回退）
                return new FsPrivateObjectStore(properties);
            }

            @Bean
            @ConditionalOnMissingBean
            public UploadService uploadService(DocumentDao documentDao, PrivateObjectStore objectStore,
                                               RunAdmissionService runAdmissionService,
                                               P2RuntimeProperties properties, P2FaultInjector p2FaultInjector,
                                               UploadIntentService uploadIntentService,
                                               ObjectProvider<AiResourceAuthorizationService> authorization) {
                return new UploadService(documentDao, objectStore, runAdmissionService, properties,
                        p2FaultInjector, uploadIntentService, authorization);
            }

            @Bean
            @ConditionalOnMissingBean
            public UploadController uploadController(UploadService uploadService, DeliveryPermits deliveryPermits) {
                return new UploadController(uploadService, deliveryPermits);
            }
        }
    }
}
