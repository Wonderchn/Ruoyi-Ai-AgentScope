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

package com.nageoffer.ai.ragent.agent.controller;

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentMetaVO;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
import com.nageoffer.ai.ragent.runtime.config.EngineModelAuthority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /agent/v1/meta} 的边界与配置权威判据（C13.4 + C13.6 + C1.1）。
 *
 * <p><b>为什么这个端点的测试要从"能力清单"升级成"权威归属"。</b>
 * 原判据只断言能力清单与 {@code mcpConfigured} 自洽 —— 那对"模型名从哪来"完全无感，
 * 而 C13.6 指出的正是这一点：{@code model} 取自 YAML（{@code agent.chat.model}），
 * 是 D02 要取消的第二权威形态。因此这里补上**方向相反的负例**：
 * YAML 里写一个模型名、数据库权威给另一个，对外声明的必须是**数据库**那个。
 * 只断言"返回了某个非空字符串"是无法发现回归的。
 */
@Tag("dev")
class AgentMetaControllerTest {

    private AgentToolCatalog toolCatalog;
    private EngineModelAuthority modelAuthority;
    private AgentProperties properties;
    private AgentMetaController controller;
    private ExecutionPrincipal previousPrincipal;

    @BeforeEach
    void setUp() {
        toolCatalog = mock(AgentToolCatalog.class);
        modelAuthority = mock(EngineModelAuthority.class);
        properties = new AgentProperties();
        // 刻意在 YAML 侧写一个**不同**的模型名：如果实现回退 YAML，下面的判据立刻失败。
        properties.getChat().setProvider("deepseek");
        properties.getChat().setModel("yaml-should-not-be-used");
        controller = new AgentMetaController(properties, toolCatalog, modelAuthority);
        previousPrincipal = PrincipalContext.get();
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.restore(previousPrincipal);
    }

    private void authenticate(Set<String> scopes) {
        PrincipalContext.set(new ExecutionPrincipal(
                "000000", "1", "platform:000000:1", 3, 4, scopes,
                "jti-meta-test", "test", 0L, Long.MAX_VALUE));
    }

    private void publishedModel(String modelId) {
        when(modelAuthority.requirePublished("agent.execute")).thenReturn(new EngineModelAuthority.PublishedModel(
                "000000", "rev-1", 1L, "bailian", modelId, "catalog-1", "sha256:params",
                "env:DASHSCOPE_API_KEY", "operator-1", Instant.parse("2026-10-06T00:00:00Z")));
    }

    @Test
    @DisplayName("能力清单与 mcpConfigured 自洽（既有判据，保留）")
    void shouldNotClaimMcpToolsWhenNoneAvailable() {
        authenticate(Set.of("agent.execute"));
        publishedModel("qwen-max");
        when(toolCatalog.mcpToolCount()).thenReturn(0);

        AgentMetaVO meta = controller.meta().data();

        assertThat(meta.capabilities()).containsExactly("react", "knowledge-base");
        assertThat(meta.mcpConfigured()).isFalse();
    }

    @Test
    void shouldClaimMcpToolsWhenAvailable() {
        authenticate(Set.of("agent.execute"));
        publishedModel("qwen-max");
        when(toolCatalog.mcpToolCount()).thenReturn(2);

        AgentMetaVO meta = controller.meta().data();

        assertThat(meta.capabilities()).containsExactly("react", "knowledge-base", "mcp-tools");
        assertThat(meta.mcpConfigured()).isTrue();
    }

    @Test
    @DisplayName("C13.6/C1.4：对外模型名来自数据库发布版本，YAML 的模型名不得胜出")
    void modelComesFromPublishedRevisionNotYaml() {
        authenticate(Set.of("agent.execute"));
        publishedModel("db-authoritative-model");
        when(toolCatalog.mcpToolCount()).thenReturn(0);

        AgentMetaVO meta = controller.meta().data();

        assertThat(meta.model())
                .as("YAML 的 agent.chat.model=%s 是第二权威，绝不能压过数据库发布版本",
                        properties.getChat().getModel())
                .isEqualTo("db-authoritative-model");
    }

    @Test
    @DisplayName("C1.1/C1.5-3：读不到权威时返回 503 拒绝，不回退 YAML、不返回空串")
    void configAuthorityUnavailableIsRejectedAndNeverFallsBackToYaml() {
        authenticate(Set.of("agent.execute"));
        when(toolCatalog.mcpToolCount()).thenReturn(0);
        when(modelAuthority.requirePublished("agent.execute"))
                .thenThrow(new ConfigAuthorityUnavailable("no published config revision"));

        assertThatThrownBy(() -> controller.meta())
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .as("权威不可得是依赖失败（503），不是 500，也不是 YAML 成功路径")
                .isEqualTo(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
    }

    @Test
    @DisplayName("C6：缺执行主体一律拒绝（401），不得退化成『无租户限定』")
    void missingPrincipalIsRejected() {
        principalCleared();

        assertThatThrownBy(() -> controller.meta())
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.AUTH_REQUIRED);
    }

    @Test
    @DisplayName("C3.1：主体在但不持有 agent.execute scope 即 403")
    void missingScopeIsRejected() {
        authenticate(Set.of("run.stream"));

        assertThatThrownBy(() -> controller.meta())
                .isInstanceOf(P04AiException.class)
                .extracting(thrown -> ((P04AiException) thrown).errorCode())
                .isEqualTo(P04AiErrorCode.FORBIDDEN);
    }

    /** 清空主体：{@code PrincipalContext} 是线程绑定，必须显式清到"无主体"而不是靠未设置。 */
    private void principalCleared() {
        PrincipalContext.clear();
    }
}
