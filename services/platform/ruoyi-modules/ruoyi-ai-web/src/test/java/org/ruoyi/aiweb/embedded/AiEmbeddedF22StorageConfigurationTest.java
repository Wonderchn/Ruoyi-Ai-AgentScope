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

import com.nageoffer.ai.ragent.rag.core.storage.ObjectStorageClient;
import com.nageoffer.ai.ragent.rag.core.storage.OssObjectStorageClient;
import com.nageoffer.ai.ragent.rag.core.storage.S3ObjectStorageClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F22:3：内嵌知识面的对象存储后端条件装配（{@code rag.storage.type}）。
 *
 * <p>F22:2 的真机缺口：{@code type=s3} 时内嵌闭包只有 fs 后端（且 fs 只在 type=fs 注册），
 * {@code DefaultFileStorageService} 的 {@code ObjectStorageClient} 构造参数无 bean，启动失败。
 * 本测试钉住：s3/oss 按 standalone 等价实现装配、缺配置启动期可读拒绝、fs 逐字节不变。
 */
@Tag("dev")
class AiEmbeddedF22StorageConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedKnowledgeAdminConfiguration.StorageBackends.class)
            // 与生产同一门控：embedded-local + knowledge-admin.full
            .withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                    "ai.embedded.knowledge-admin.full=true");

    @Test
    @DisplayName("type=s3：注册 S3ObjectStorageClient 与 S3Client/S3Presigner")
    void s3RegistersStandaloneEquivalentClient() {
        runner.withPropertyValues(
                        "rag.storage.type=s3",
                        "rag.storage.s3.endpoint=http://127.0.0.1:9000",
                        "rag.storage.s3.access-key=test-access",
                        "rag.storage.s3.secret-key=test-secret",
                        "rag.storage.kb-bucket=ragent-sources")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ObjectStorageClient.class);
                    assertThat(context.getBean(ObjectStorageClient.class))
                            .isInstanceOf(S3ObjectStorageClient.class);
                    assertThat(context).hasSingleBean(S3Client.class);
                    assertThat(context).hasSingleBean(S3Presigner.class);
                });
    }

    @Test
    @DisplayName("type=s3 缺 endpoint/accessKey/secretKey/bucket：启动期 fail-closed，不回退 fs")
    void s3MissingConfigFailsClosed() {
        runner.withPropertyValues(
                        "rag.storage.type=s3",
                        "rag.storage.s3.endpoint=",
                        "rag.storage.s3.access-key=",
                        "rag.storage.s3.secret-key=",
                        "rag.storage.kb-bucket=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessage("rag.storage.s3.endpoint 为空：rag.storage.type=s3 已选择，"
                                    + "拒绝启动（不静默回退 fs）。请配置 rag.storage.s3.endpoint，"
                                    + "或显式设回 rag.storage.type=fs。");
                });
    }

    @Test
    @DisplayName("type=oss：注册 OssObjectStorageClient 与 OSS 客户端")
    void ossRegistersStandaloneEquivalentClient() {
        runner.withPropertyValues(
                        "rag.storage.type=oss",
                        "rag.storage.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
                        "rag.storage.oss.access-key=test-access",
                        "rag.storage.oss.secret-key=test-secret",
                        "rag.storage.kb-bucket=ragent-sources")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ObjectStorageClient.class);
                    assertThat(context.getBean(ObjectStorageClient.class))
                            .isInstanceOf(OssObjectStorageClient.class);
                    assertThat(context).hasSingleBean(com.aliyun.oss.OSS.class);
                    assertThat(context).doesNotHaveBean(S3Client.class);
                });
    }

    @Test
    @DisplayName("type=fs：既有本地后端与行为不变，零 S3/OSS 客户端")
    void fsKeepsExistingBehavior() {
        runner.withPropertyValues("rag.storage.type=fs", "rag.storage.root=./data/kb-objects")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ObjectStorageClient.class);
                    assertThat(context.getBean(ObjectStorageClient.class))
                            .isInstanceOf(EmbeddedFsObjectStorageClient.class);
                    assertThat(context).doesNotHaveBean(S3Client.class);
                    assertThat(context).doesNotHaveBean(com.aliyun.oss.OSS.class);
                });
    }
}
