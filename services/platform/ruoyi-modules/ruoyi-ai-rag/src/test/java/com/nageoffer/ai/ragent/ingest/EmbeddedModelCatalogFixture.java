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

package com.nageoffer.ai.ragent.ingest;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * R17-F05:3-M2 测试夹具：把 shipped {@code application-embedded.yml} 的 {@code ai.*} 块
 * 绑定成真实的 {@link AIModelProperties}。
 *
 * <p>刻意读<b>真实发出文件</b>而不是测试自造属性：判据要证明的是"线上那份目录里
 * 登记的嵌入候选真的能被 {@code selectEmbeddingCandidates()} 解析到"，测试自造目录
 * 无法绑定这一事实。
 */
final class EmbeddedModelCatalogFixture {

    /** rag 模块基于目录相对定位 admin 的 shipped profile（与 ai-web 既有探针同深度）。 */
    static final Path EMBEDDED_YML = Path.of("..", "..", "ruoyi-admin", "src", "main",
            "resources", "application-embedded.yml").toAbsolutePath().normalize();

    private EmbeddedModelCatalogFixture() {
    }

    static AIModelProperties bindShippedEmbeddedCatalog() {
        if (!Files.exists(EMBEDDED_YML)) {
            throw new IllegalStateException("内嵌 profile 文件不在预期位置（工作目录可能不是模块目录）: " + EMBEDDED_YML);
        }
        List<PropertySource<?>> sources;
        try {
            sources = new YamlPropertySourceLoader().load("shipped-embedded", new FileSystemResource(EMBEDDED_YML));
        } catch (IOException e) {
            throw new IllegalStateException("读取内嵌 profile 失败: " + EMBEDDED_YML, e);
        }
        // Binder 只接受 ConfigurationPropertySource；占位符解析也必须接上——目录里 dimension
        // 写的是 ${rag.default.dimension}，没有解析器时绑定到 Integer 会直接失败。
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        return new Binder(ConfigurationPropertySources.from(propertySources),
                new PropertySourcesPlaceholdersResolver(propertySources))
                .bind("ai", Bindable.of(AIModelProperties.class))
                .orElseThrow(() -> new IllegalStateException("application-embedded.yml 里没有可绑定的 ai.* 块"));
    }
}
