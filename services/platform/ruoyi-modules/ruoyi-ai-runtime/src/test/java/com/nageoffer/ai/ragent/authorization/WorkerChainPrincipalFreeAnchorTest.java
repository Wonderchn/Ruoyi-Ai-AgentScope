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

package com.nageoffer.ai.ragent.authorization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F23-N3 可达性锚点（门禁 14：结构性不可达要记录，不伪造触发）。
 *
 * <p><b>结构性事实</b>：worker 执行链（{@code RunWorker} → {@code RunExecutorRegistry} →
 * {@code RagChatExecutor}/{@code DocumentIngestExecutor}）按 D02 设计**不读
 * {@code PrincipalContext}**——执行主体在受理期绑定进 run 行（RunConfigBinding/subject），
 * 执行线程只携带 (tenantId, runId, workerId, fence)。"线程复用串主体"这条负例在当前
 * 设计里**没有可达路径**：执行线程上根本没有主体可串。
 *
 * <p><b>本锚点钉住的就是这个"没有"</b>：扫描执行链四文件的源码，
 * 断言其中没有任何 {@code PrincipalContext} 引用。将来谁在执行链里读了请求级主体
 * （例如为了图方便取 tenantId），本锚点立即变红，并强制其补一条真实的
 * "线程复用串主体"负例——那时这条负例才是可达的。
 *
 * <p><b>对照（已覆盖面，不重复）</b>：HTTP 过滤器线程的残留清理已由
 * {@code P1PrincipalSecurityTest} 钉住（各用例后 {@code assertNull(PrincipalContext.get())}，
 * 含成功路径）；值对象面（引用不带 bearer/scopes）由 {@code P1AsyncContextIsolationTest} 钉住。
 */
@Tag("dev")
class WorkerChainPrincipalFreeAnchorTest {

    /** 执行链上不允许出现请求级主体的文件（runtime/worker-chain 租约域）。 */
    private static final List<String> EXECUTION_CHAIN_SOURCES = List.of(
            "src" + java.io.File.separator + "main" + java.io.File.separator + "java"
                    + java.io.File.separator + "com/nageoffer/ai/ragent/runtime/RunWorker.java",
            "src" + java.io.File.separator + "main" + java.io.File.separator + "java"
                    + java.io.File.separator + "com/nageoffer/ai/ragent/runtime/RunLifecycleService.java",
            "src" + java.io.File.separator + "main" + java.io.File.separator + "java"
                    + java.io.File.separator + "com/nageoffer/ai/ragent/runtime/exec/RagChatExecutor.java",
            "src" + java.io.File.separator + "main" + java.io.File.separator + "java"
                    + java.io.File.separator + "com/nageoffer/ai/ragent/runtime/exec/DocumentIngestExecutor.java");

    private static final String FORBIDDEN_MARKER = "PrincipalContext";

    @Test
    @DisplayName("可达性锚点：worker 执行链源码不得引用 PrincipalContext（执行线程无请求级主体可串）")
    void executionChainNeverReadsTheRequestScopedPrincipal() throws IOException {
        Path root = worktreeRoot();
        for (String moduleRelative : EXECUTION_CHAIN_SOURCES) {
            Path file = root.resolve(Paths.get("services", "platform", "ruoyi-modules", "ruoyi-ai-runtime"))
                    .resolve(moduleRelative);
            assertThat(Files.exists(file)).as("执行链源必须存在：%s", file).isTrue();
            String source = Files.readString(file, StandardCharsets.UTF_8);
            // 采集锚点：每个被扫文件必须真的包含"执行链"特征（防扫错文件的恒真）
            assertThat(source).as("被扫文件必须是执行链本体：%s", file)
                    .containsAnyOf("class RunWorker", "class RunLifecycleService",
                            "class RagChatExecutor", "class DocumentIngestExecutor");
            assertThat(source)
                    .as("执行链文件 %s 不得引用 PrincipalContext——执行主体在受理期绑定进 run 行，"
                            + "执行线程无请求级主体；出现引用说明有人把请求上下文带进了 worker 线程，"
                            + "必须补真实的'线程复用串主体'负例（F23-N3）", moduleRelative)
                    .doesNotContain(FORBIDDEN_MARKER);
        }
    }

    private static Path worktreeRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path marker = Paths.get("services", "platform", "ruoyi-modules", "ruoyi-ai-runtime");
        while (dir != null && !Files.isDirectory(dir.resolve(marker))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("cannot locate worktree root from user.dir="
                    + System.getProperty("user.dir"));
        }
        return dir;
    }
}
