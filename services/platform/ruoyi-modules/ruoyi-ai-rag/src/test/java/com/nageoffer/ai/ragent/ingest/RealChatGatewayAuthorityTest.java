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

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable;
import com.nageoffer.ai.ragent.runtime.config.ProviderConnectionPort;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBinding;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D02 的三条不可协商约束的判据（全部为**负例**：必须证明它能触发）。
 *
 * <ol>
 *   <li>硬编码 provider/model/endpoint 归零（含源码扫描，带"确实读到文件"的锚点）；</li>
 *   <li>「无 run 身份」必须**显式拒绝**（3 参 stream / 无参 provider / 无参 model）；</li>
 *   <li>无绑定事实、无连接引导时**一律拒绝**，不回退任何默认值。</li>
 * </ol>
 */
@Tag("dev")
class RealChatGatewayAuthorityTest {

    private static final URI LOOPBACK = URI.create("http://127.0.0.1:1/");

    private static EgressPolicy allowed() {
        var policy = new EgressPolicy();
        policy.setEnabled(true);
        policy.setAllowedProviders("deepseek");
        return policy;
    }

    private static RunConfigBinding bound(String providerId, String modelId) {
        return new RunConfigBinding("t1", "run-1", "rag.chat", "rev-1", 1L,
                providerId, modelId, "cat-v1", "params-hash", "cred-ref", "op-1", Instant.EPOCH);
    }

    /** 约束 2 的负例：无 run 身份面**必须抛异常**，不得回退默认模型。 */
    @Test
    void withoutRunIdentityTheGatewayRefusesInsteadOfUsingADefaultModel() {
        var gateway = new RealChatGateway("test-key", allowed(), LOOPBACK);
        assertThrows(ConfigAuthorityUnavailable.class, gateway::provider);
        assertThrows(ConfigAuthorityUnavailable.class, gateway::model);
        assertThrows(ConfigAuthorityUnavailable.class,
                () -> gateway.stream(List.of(ChatMessage.user("q")), 16, delta -> { }));
        // 正对照：同一个无身份面上，**显式合成 double** 允许返回常量身份 ⇒ 上面的抛异常
        // 不是"所有实现都会抛"的恒真断言，而是**真实实现特有**的拒绝。
        assertDoesNotThrow(() -> new SyntheticChatGateway(0).provider());
        assertDoesNotThrow(() -> new SyntheticChatGateway(0).model());
    }

    /** 约束 3 的负例：绑定事实缺 provider/model ⇒ 在**任何外发之前**拒绝。 */
    @Test
    void bindingWithoutProviderOrModelIsRefusedBeforeAnyOutboundCall() {
        var gateway = new RealChatGateway("test-key", allowed(), LOOPBACK);
        assertThrows(ConfigAuthorityUnavailable.class, () -> gateway.provider(null));
        assertThrows(ConfigAuthorityUnavailable.class, () -> gateway.model(bound(" ", "model-x")));
        assertThrows(ConfigAuthorityUnavailable.class,
                () -> gateway.stream(bound("provider-x", "  "), List.of(ChatMessage.user("q")), 16, delta -> { }));
    }

    /** 约束 1/3 的负例：端点**没有默认值** —— 无引导端口、或引导查不到该 providerId，都拒绝。 */
    @Test
    void endpointMustComeFromConnectionBootstrapAndHasNoDefault() {
        // 生产形态（无显式 loopback 端点、连接引导未装配）⇒ 拒绝
        var unwired = new RealChatGateway("test-key", allowed());
        var refused = assertThrows(ConfigAuthorityUnavailable.class,
                () -> unwired.stream(bound("deepseek", "model-x"), List.of(ChatMessage.user("q")), 16, delta -> { }));
        assertTrue(refused.getMessage().contains("bootstrap"), refused.getMessage());

        // 引导端口存在但**没有**该 providerId 的连接 ⇒ 拒绝（不回落到"唯一配置项"）
        var wired = new RealChatGateway("test-key", allowed());
        wired.configureConnections(new ProviderConnectionPort() {
            @Override
            public ProviderConnection requireConnection(String providerId, String credentialRef) {
                throw new ConfigAuthorityUnavailable("no connection bootstrap for providerId=" + providerId);
            }
        });
        var unknown = assertThrows(ConfigAuthorityUnavailable.class,
                () -> wired.stream(bound("unregistered-provider", "model-x"), List.of(ChatMessage.user("q")), 16, delta -> { }));
        assertTrue(unknown.getMessage().contains("unregistered-provider"), unknown.getMessage());
    }

    /**
     * 约束 1 的判据：源码级归零扫描。
     *
     * <p>锚点（防"扫不到 ⇒ 判据恒真"，BRIEF §4 的实测坑）：先断言**确实读到了**
     * `RealChatGateway.java` 且读到的是**改后**的 run 作用域形态；再断言三类字面量归零。
     * {@code deepseek} 允许**恰好一处**出现，且必须落在凭据引导的属性名里
     * （属性名是部署侧引导键，不是"选中某个提供方"的代码字面量）。
     */
    @Test
    void realGatewaySourceKeepsNoProviderModelOrEndpointLiteral() throws Exception {
        Path source = Paths.get(fromModuleRoot("src/main/java/com/nageoffer/ai/ragent/ingest/RealChatGateway.java"));
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertTrue(text.contains("class RealChatGateway implements ChatGateway"),
                "anchor failed: the scanned file is not RealChatGateway.java");
        assertTrue(text.contains("RunConfigBinding binding"),
                "anchor failed: the scanned file is not the run-scoped form");

        // 正例：当前源码必须干净
        assertNoProviderModelOrEndpointLiteral(text);

        // **变异对照（在内存副本上做，不动产品文件）**：把三类字面量各放回去一次，
        // 判据必须**逐条**报错 ⇒ 证明它不是"永远通过"的摆设。
        assertThrows(AssertionError.class,
                () -> assertNoProviderModelOrEndpointLiteral(text + "\n// model=\"deepseek-flash\"\n"));
        assertThrows(AssertionError.class,
                () -> assertNoProviderModelOrEndpointLiteral(text + "\n// endpoint=\"https://api.deepseek.com/chat/completions\"\n"));
        assertThrows(AssertionError.class,
                () -> assertNoProviderModelOrEndpointLiteral(text + "\n// provider=\"deepseek\"\n"));
    }

    /** 三类字面量归零 + `deepseek` 仅允许出现在凭据引导属性名里。 */
    private static void assertNoProviderModelOrEndpointLiteral(String text) {
        assertFalse(text.contains("deepseek-flash"), "hardcoded model literal remains");
        assertFalse(text.contains("api.deepseek.com"), "hardcoded endpoint literal remains");
        assertFalse(text.contains("chat/completions"), "the endpoint path literal must be gone");
        assertEquals(1, text.lines().filter(line -> line.contains("deepseek")).count(),
                "'provider' may only appear once, as the credential bootstrap property name");
        assertTrue(text.contains("p2.providers.deepseek.api-key"),
                "the single occurrence must be the credential bootstrap property name");
    }

    /** 从当前工作目录向上找模块根（Windows 反斜杠安全；找不到就**失败**，不静默通过）。 */
    private static String fromModuleRoot(String relative) {
        Path dir = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && dir != null; depth++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate.toString();
            }
        }
        throw new IllegalStateException("anchor failed: cannot locate " + relative
                + " from " + Paths.get("").toAbsolutePath());
    }
}
