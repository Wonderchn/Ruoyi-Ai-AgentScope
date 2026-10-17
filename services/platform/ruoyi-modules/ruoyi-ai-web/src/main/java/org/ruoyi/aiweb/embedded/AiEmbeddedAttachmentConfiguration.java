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

import com.nageoffer.ai.ragent.attachment.ChatAttachmentController;
import com.nageoffer.ai.ragent.attachment.ChatAttachmentService;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.authorization.TenantObjectReferenceRepository;
import com.nageoffer.ai.ragent.ingest.PrivateObjectStore;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * F04 private chat attachments: embedded local wiring (AI surface under the platform app).
 *
 * <p>Registers the attachment surface on the AI-side assembly:
 * {@code POST /internal/ai/v1/chat/attachments} (multipart upload) and
 * {@code GET /internal/ai/v1/chat/attachments/{attachmentId}/content} (authenticated private
 * content). Requires {@code ai.integration.enabled=true} + {@code transport=local} +
 * {@code p2.enabled=true}: the private object store and the run admission surface only exist
 * in that profile, exactly like the documents upload surface.</p>
 *
 * <p>The registered {@link ChatAttachmentService} bean is also the single
 * {@code ChatAttachmentPort} consumed by run admission ({@code validateAdmission} before the
 * budget lock), the RAG history adapter ({@code bind} inside the message transaction) and the
 * agent executor ({@code persistAgentTurn} inside the fenced checkpoint).</p>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedAttachmentConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    public static class LocalTransport {

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnEmbeddedLocal
        @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
        public static class P2Enabled {

            @Bean
            @ConditionalOnMissingBean
            public ChatAttachmentService chatAttachmentService(JdbcTemplate jdbc,
                                                              PrivateObjectStore objectStore,
                                                              TenantObjectReferenceRepository objects,
                                                              AiResourceAuthorizationService authorization,
                                                              PlatformTransactionManager transactionManager) {
                return new ChatAttachmentService(jdbc, objectStore, objects, authorization, transactionManager);
            }

            @Bean
            @ConditionalOnMissingBean
            public ChatAttachmentController chatAttachmentController(ChatAttachmentService chatAttachmentService,
                                                                     DeliveryPermits deliveryPermits) {
                return new ChatAttachmentController(chatAttachmentService, deliveryPermits);
            }
        }
    }
}
