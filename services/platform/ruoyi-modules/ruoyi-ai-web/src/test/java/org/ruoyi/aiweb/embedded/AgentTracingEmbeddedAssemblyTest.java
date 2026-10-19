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

import com.nageoffer.ai.ragent.agent.trace.AgentTraceEnrichmentMiddleware;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceProperties;
import com.nageoffer.ai.ragent.agent.trace.AgentTracingConfiguration;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F18:4 T1：Agent 调用链 OTel 装配的内嵌门控。
 *
 * <p>判据：
 * <ul>
 *   <li><b>默认关闭</b>：{@code agent.trace.enabled=false}（缺省）⇒ 零 tracing bean、
 *       零 exporter、启动不失败；引擎闭包里的 {@code ObjectProvider} 两件保持缺席合法；</li>
 *   <li><b>启用 + endpoint</b> ⇒ SDK/exporter/两个中间件在场；{@code capture-content}
 *       默认 {@code false}（纯骨架，不落提示词/工具出入参）；</li>
 *   <li><b>启用缺 endpoint</b> ⇒ 启动 fail-fast，错误文案<b>指名配置键</b>
 *       {@code agent.trace.endpoint}；</li>
 *   <li>公钥/私钥只配一侧 ⇒ fail-fast（不把半套凭据发给接收端）；</li>
 *   <li><b>装配位置</b>：内嵌引擎门（{@code AiEmbeddedAgentEngineConfiguration.EngineEnabled}）
 *       显式 {@code @Import} tracing 配置——本测试按源码判据钉住，避免"类在树内但不是 bean"。</li>
 * </ul>
 *
 * <p><b>属性键说明</b>：既有配置段是 {@code agent.trace}（{@code AgentTraceProperties}
 * 与其既有测试/独立应用 yaml 均用该键），不是 brief 口述的 {@code agent.tracing}；
 * 本切片沿用既有键，真机窗请设置 {@code agent.trace.enabled=true}。
 */
@Tag("dev")
class AgentTracingEmbeddedAssemblyTest {

    private static final String ENGINE = "ragent.engine.type=agent";
    private static final String APP_NAME = "spring.application.name=ragent-embedded-tracing";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AgentTracingConfiguration.class);

    @AfterEach
    void resetGlobalOpenTelemetry() {
        // SDK bean 会把自身装进全局位置；容器 close 不会清它，留给下一条用例会撞"已被占用"。
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    @DisplayName("默认关闭：零 tracing bean/零 exporter，启动不失败")
    void disabledByDefaultHasNoTracingBeans() {
        runner.withPropertyValues(ENGINE, APP_NAME).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AgentTraceProperties.class);
            assertThat(context).doesNotHaveBean(OpenTelemetrySdk.class);
            assertThat(context).doesNotHaveBean(OtelTracingMiddleware.class);
            assertThat(context).doesNotHaveBean(AgentTraceEnrichmentMiddleware.class);
        });
    }

    @Test
    @DisplayName("启用 + endpoint：SDK/两个中间件在场，capture-content 默认 false")
    void enabledWithEndpointAssemblesTracingAndKeepsContentOff() {
        runner.withPropertyValues(ENGINE, APP_NAME,
                        "agent.trace.enabled=true",
                        "agent.trace.endpoint=http://127.0.0.1:4318/v1/traces")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AgentTraceProperties.class);
                    assertThat(context.getBean(AgentTraceProperties.class).isCaptureContent()).isFalse();
                    assertThat(context).hasSingleBean(OpenTelemetrySdk.class);
                    assertThat(context).hasSingleBean(OtelTracingMiddleware.class);
                    assertThat(context).hasSingleBean(AgentTraceEnrichmentMiddleware.class);
                });
    }

    @Test
    @DisplayName("启用缺 endpoint：启动 fail-fast 且错误指名 agent.trace.endpoint")
    void enabledWithoutEndpointFailsFastNamingTheKey() {
        runner.withPropertyValues(ENGINE, APP_NAME,
                        "agent.trace.enabled=true",
                        "agent.trace.endpoint=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("agent.trace.endpoint");
                });
    }

    @Test
    @DisplayName("公钥/私钥只配一侧：fail-fast，不发送半套凭据")
    void partialCredentialFailsFast() {
        runner.withPropertyValues(ENGINE, APP_NAME,
                        "agent.trace.enabled=true",
                        "agent.trace.endpoint=http://127.0.0.1:4318/v1/traces",
                        "agent.trace.public-key=pk-only")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("agent.trace.public-key")
                            .hasStackTraceContaining("agent.trace.secret-key");
                });
    }

    @Test
    @DisplayName("内嵌引擎门显式装配 tracing 配置（不是只有类在树内）")
    void embeddedEngineGateImportsTracingConfiguration() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/ruoyi/aiweb/embedded/AiEmbeddedAgentEngineConfiguration.java"));

        assertThat(source)
                .contains("com.nageoffer.ai.ragent.agent.trace.AgentTracingConfiguration")
                .contains("AgentTracingConfiguration.class");
    }
}
