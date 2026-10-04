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

package com.nageoffer.ai.ragent.rag.core.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Tag;

/**
 * 可选后端（Milvus / ES）在 P1 验收期保持 NOT_RUN + CLOSED 的结构证明。
 *
 * <p><b>为什么是结构断言而不是运行期断言。</b>验收主机上没有 Milvus / ES 服务，
 * 运行期隔离无从谈起（U07 契约：可选后端未做共享实例集成验证前保持禁用，
 * 不阻塞 PG 首期验收，也不得宣称其运行隔离已证实）。能如实证明的是三件结构事实：
 *
 * <ol>
 *   <li><b>属性缺省不再等于 Milvus。</b>修复前四个 Milvus 装配类都是
 *       {@code matchIfMissing = true}——把 {@code rag.vector.type} 从配置里删掉，
 *       Milvus 反而被静默启用。修复后缺省即无向量后端（消费方装配失败、启动大声报错），
 *       而不是静默连上一个未经隔离验证的后端。</li>
 *   <li><b>随仓库分发的默认姿态是 PG + none。</b>application.yaml 显式
 *       {@code vector.type: pg}、{@code keyword.type: none}，关键词通道总开关 false。</li>
 *   <li><b>不存在运行期降级/回落。</b>PG 与 Milvus 实现互不引用——
 *       PG 不可用时不存在"退到 Milvus"的路径（这正是共享物理表上
 *       降级会放回跨租户覆盖缺陷的原因，PgVectorStoreService 已注释拒绝该设计）。</li>
 * </ol>
 *
 * <p>显式配置 {@code rag.vector.type=milvus} 仍可装配 Milvus——那是"显式受控部署"
 * 的入口，P1 不禁止其存在，但验收运行从不设置它，且共享实例集成证据出现之前
 * 它没有运行期隔离声明。本测试钉住的是：缺省不误开、默认是 PG、没有降级。
 */
@Tag("dev")
class P1OptionalBackendsClosedTest {

    @Test
    @DisplayName("Milvus 装配不再 matchIfMissing：属性缺省不得静默启用未验证后端")
    void milvusWiringRequiresExplicitSelection() throws IOException {
        for (String[] cls : new String[][] {
                {"config", "MilvusConfig.java"},
                {"core", "vector", "MilvusVectorStoreService.java"},
                {"core", "vector", "MilvusVectorStoreAdmin.java"},
                {"core", "vector", "MilvusVectorRetrieverService.java"}}) {
            String source = read(cls);
            assertThat(source)
                    .as("%s 必须显式选择（matchIfMissing=false），缺省不得静默装配 Milvus", cls[cls.length - 1])
                    .contains("matchIfMissing = false");
            assertThat(Pattern.compile("havingValue\\s*=\\s*\"milvus\"").matcher(source).find())
                    .as("%s 仍按 rag.vector.type=milvus 精确匹配", cls[cls.length - 1])
                    .isTrue();
        }
    }

    @Test
    @DisplayName("随仓库分发的默认姿态：PG 向量 + ES 关闭")
    void shippedDefaultsArePgAndNone() throws IOException {
        String yaml = Files.readString(locateBootstrapYaml(), StandardCharsets.UTF_8);
        assertThat(Pattern.compile("(?m)^\\s{2}vector:\\s*$\\n\\s{4}type:\\s*pg(\\s*#.*)?$")
                .matcher(yaml).find())
                .as("默认 rag.vector.type 必须是 pg（共享物理表的首期已验证后端）")
                .isTrue();
        assertThat(Pattern.compile("(?m)^\\s{2}keyword:\\s*$\\n\\s{4}type:\\s*none(\\s*#.*)?$")
                .matcher(yaml).find())
                .as("默认 rag.keyword.type 必须是 none（ES 无共享实例集成证据）")
                .isTrue();
        assertThat(Pattern.compile("(?m)^\\s{6}keyword:\\s*$\\n\\s{8}enabled:\\s*false(\\s*#.*)?$")
                .matcher(yaml).find())
                .as("关键词检索通道默认关闭")
                .isTrue();
    }

    @Test
    @DisplayName("无运行期降级：PG 与 Milvus 实现互不引用")
    void noRuntimeFallbackBetweenBackends() throws IOException {
        String pg = read("core", "vector", "PgVectorStoreService.java");
        String pgRetriever = read("core", "vector", "PgVectorRetrieverService.java");
        String milvus = read("core", "vector", "MilvusVectorStoreService.java");
        String milvusRetriever = read("core", "vector", "MilvusVectorRetrieverService.java");

        assertThat(pg + pgRetriever)
                .as("PG 实现不得引用 Milvus 类型（无降级/回落路径）")
                .doesNotContain("MilvusVector");
        assertThat(milvus + milvusRetriever)
                .as("Milvus 实现不得引用 PG 类型作为回落")
                .doesNotContain("PgVectorStore");
    }

    @Test
    @DisplayName("ES 装配按 rag.keyword.type=es 精确开启，缺省不装配")
    void esWiringRequiresExplicitSelection() throws IOException {
        String es = read("config", "EsClientConfig.java");
        assertThat(es).contains("havingValue = \"es\"");
        assertThat(es).doesNotContain("matchIfMissing = true");
        String channel = read("core", "retrieval", "channel", "KeywordSearchChannel.java");
        assertThat(channel)
                .as("关键词通道必须与 ES 同条件门控")
                .contains("havingValue = \"es\"");
    }

    // ---------- 定位与读取（rag 模块相对仓库根向上查找） ----------

    private static Path locate(String... segments) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path p = dir.resolve("services").resolve("ai").resolve("rag").resolve("src")
                    .resolve("main").resolve("java").resolve("com").resolve("nageoffer")
                    .resolve("ai").resolve("ragent").resolve("rag");
            for (String s : segments) {
                p = p.resolve(s);
            }
            if (Files.isRegularFile(p)) {
                return p;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate " + String.join("/", segments));
    }

    private static Path locateBootstrapYaml() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path p = dir.resolve("services").resolve("ai").resolve("bootstrap")
                    .resolve("src").resolve("main").resolve("resources").resolve("application.yaml");
            if (Files.isRegularFile(p)) {
                return p;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate bootstrap application.yaml");
    }

    private static String read(String... segments) throws IOException {
        return Files.readString(locate(segments), StandardCharsets.UTF_8);
    }
}
