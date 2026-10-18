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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.integration.SaasBoundaryProperties;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener;
import com.nageoffer.ai.ragent.framework.mq.producer.TransactionChecker;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.mq.KnowledgeBaseCleanupConsumer;
import com.nageoffer.ai.ragent.knowledge.mq.KnowledgeBaseCleanupTransactionChecker;
import com.nageoffer.ai.ragent.knowledge.mq.KnowledgeDocumentChunkConsumer;
import com.nageoffer.ai.ragent.knowledge.mq.KnowledgeDocumentChunkTransactionChecker;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * F22:2 真机第三缺口：内嵌形态未登记旧知识链 MQ 消费者。
 *
 * <p>真机日志（{@code w2/app-6044-f222-lrmq2.log:65}）显示启动只注册了
 * {@code messageFeedbackConsumer}；{@link KnowledgeDocumentChunkConsumer} 是
 * {@code com.nageoffer} 下的 {@code @Component}，不在 {@code org.ruoyi} 扫描根，
 * 于是事务消息 SEND_OK/COMMIT 之后 chunk 永不执行。
 *
 * <p>本组钉住：
 * <ol>
 *   <li>{@code ai.integration.enabled=true × ai.integration.legacy-listeners-enabled=true}
 *       时两个消费者与两个事务回查器都是 bean，消费者带可读的
 *       {@link RocketMQMessageListener} 元数据，回查器按 topic 注册进
 *       {@link DelegatingTransactionListener}（broker 回查的实际查找方式）；</li>
 *   <li>开关关闭或总开关关闭时零 bean（shipped 默认零变化）。</li>
 * </ol>
 * 不需要真 broker：容器与注解元数据在 ContextRunner 里即可断言。
 */
@Tag("dev")
class AiEmbeddedKnowledgeMqLegacyAssemblyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedKnowledgeConfiguration.class, Fixture.class);

    @Test
    @DisplayName("legacy 打开：两个知识链消费者与两个事务回查器显式登记，监听元数据可读")
    void legacyConsumersAndCheckersAreRegisteredWhenEnabled() {
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                        "ai.integration.legacy-listeners-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(KnowledgeDocumentChunkConsumer.class);
                    assertThat(context).hasSingleBean(KnowledgeBaseCleanupConsumer.class);
                    assertThat(context).hasSingleBean(KnowledgeDocumentChunkTransactionChecker.class);
                    assertThat(context).hasSingleBean(KnowledgeBaseCleanupTransactionChecker.class);
                    assertThat(context.getBeansOfType(TransactionChecker.class)).hasSize(2);

                    RocketMQMessageListener chunkListener = KnowledgeDocumentChunkConsumer.class
                            .getAnnotation(RocketMQMessageListener.class);
                    assertThat(chunkListener).isNotNull();
                    assertThat(chunkListener.topic()).contains("knowledge-document-chunk_topic");
                    assertThat(chunkListener.consumerGroup()).contains("knowledge-document-chunk_cg");

                    RocketMQMessageListener cleanupListener = KnowledgeBaseCleanupConsumer.class
                            .getAnnotation(RocketMQMessageListener.class);
                    assertThat(cleanupListener).isNotNull();
                    assertThat(cleanupListener.topic()).contains("knowledge-base-cleanup_topic");
                    assertThat(cleanupListener.consumerGroup()).contains("knowledge-base-cleanup_cg");

                    // 回查器在 @PostConstruct 里按 topic 注册；broker 回查走同一张表。
                    Map<?, ?> checkerMap = (Map<?, ?>) ReflectionTestUtils.getField(
                            context.getBean(DelegatingTransactionListener.class), "checkerMap");
                    assertThat(checkerMap.keySet().stream().map(String::valueOf).toList())
                            .as("两个事务回查器必须注册到对应 topic，否则回查默认 ROLLBACK")
                            .contains("knowledge-document-chunk_topic",
                                    "knowledge-base-cleanup_topic");
                });
    }

    @Test
    @DisplayName("legacy 关闭或总开关关闭：消费者/回查器零 bean")
    void noLegacyBeansWhenSwitchIsOff() {
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertNoLegacyBeans(context);
                });

        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                        "ai.integration.legacy-listeners-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertNoLegacyBeans(context);
                });

        runner.withPropertyValues("ai.integration.enabled=false", "ai.integration.transport=local",
                        "ai.integration.legacy-listeners-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertNoLegacyBeans(context);
                });
    }

    private static void assertNoLegacyBeans(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        assertThat(context).doesNotHaveBean(KnowledgeDocumentChunkConsumer.class);
        assertThat(context).doesNotHaveBean(KnowledgeBaseCleanupConsumer.class);
        assertThat(context).doesNotHaveBean(KnowledgeDocumentChunkTransactionChecker.class);
        assertThat(context).doesNotHaveBean(KnowledgeBaseCleanupTransactionChecker.class);
    }

    /** 消费者/回查器的构造协作者与事务监听器；行为断言不依赖真实实现。 */
    @Configuration(proxyBeanMethods = false)
    static class Fixture {

        @Bean
        KnowledgeDocumentService knowledgeDocumentService() {
            return mock(KnowledgeDocumentService.class);
        }

        @Bean
        KnowledgeBaseMapper knowledgeBaseMapper() {
            return mock(KnowledgeBaseMapper.class);
        }

        @Bean
        KnowledgeDocumentMapper knowledgeDocumentMapper() {
            return mock(KnowledgeDocumentMapper.class);
        }

        @Bean
        VectorStoreAdmin vectorStoreAdmin() {
            return mock(VectorStoreAdmin.class);
        }

        @Bean
        FileStorageService fileStorageService() {
            return mock(FileStorageService.class);
        }

        @Bean
        SaasCapabilityBoundary saasCapabilityBoundary() {
            // 维护者已批准窗口打开 legacy；边界与消费者同门控、同真值来源。
            return new SaasCapabilityBoundary(new SaasBoundaryProperties(
                    true, true, new SaasBoundaryProperties.CustomerApi(true), true));
        }

        @Bean
        DelegatingTransactionListener delegatingTransactionListener() {
            return new DelegatingTransactionListener();
        }

        @Bean
        PlatformTransactionManager platformTransactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
