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

import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C13 引擎面路径契约（WP-033，T0 于 2026-10-06 核实源码后发布）。
 *
 * <p><b>本判据要打的两个真缺陷。</b>
 * <ol>
 *   <li><b>不可达</b>：{@code AgentChatController}/{@code AgentMetaController} 此前没有类级
 *       {@code @RequestMapping}，方法路径直接写 {@code /agent/v1/...}，于是注册在应用根的
 *       公开路径空间里；而网关 {@code AiGatewayController} 的转送目标恒为
 *       {@code base + "/internal/ai/v1" + subPath}（{@code :80/:271}）→ 四条端点经
 *       {@code /api/ai/v1/**} <b>必然 404</b>。</li>
 *   <li><b>也不是"外部不可达"</b>：它们落在 {@code AiInternalAccessBoundaryFilter} 的
 *       {@code /internal/ai/v1/*} 关闭范围之外。</li>
 * </ol>
 * 两条合起来是同一个失效形态："这个公开面既到不了、也没关掉"，而所有静态装配判据都会通过。
 *
 * <p><b>为什么用反射读运行期映射，而不是只扫源码字符串。</b>
 * 源码扫描读不到 {@code INTERNAL_PREFIX + "/x"} 的常量拼接结果，也分不清
 * "注解放对了但方法签名被改坏"。反射读到的是 Spring 真正会用来注册 handler 的那份值，
 * 因此"注解 ↔ 实际映射"这一层没有解释空间。源码扫描只用于一条源码层才能表达的目标：
 * <b>残留清零</b>（{@code services/platform} 下不得再有"根级 {@code /agent/v1/**}"控制器）。
 */
@Tag("dev")
class AgentEngineSurfaceRouteTest {

    /** 内层可达前缀：与网关 {@code AiGatewayController.AI_INTERNAL_PREFIX} 同值。 */
    private static final String INTERNAL_PREFIX = "/internal/ai/v1";

    /** 网关自身控制器所在的外部前缀（客户端可见路径空间，不是残留）。 */
    private static final Set<String> EXTERNAL_PREFIXES = Set.of("/api/ai/v1");

    /**
     * 允许保留根级 {@code /agent/v1/**} 的例外（**逐条写明理由**，不做隐式放行）。
     *
     * <p><b>为什么必须有这个 allowlist 而不是"全仓清零"。</b>本判据的第一版把目标写成
     * "services/platform 里 {@code /agent/v1/**} 归零"，那**比它实际能覆盖的范围强**
     * ——T0 复核时指出 {@code AgentConversationController} 仍逐条写着
     * {@code /agent/v1/conversations/**}。两种收敛方式里选了"显式例外"：
     * 让允许的形态与禁止的形态分开可读，任何**新增**的根级公开面仍然立刻失败。
     *
     * <p><b>{@code AgentConversationController} 为何可以保留（逐条理由）。</b>
     * <ol>
     *   <li>它在 platform 内嵌形态里<b>不是 bean</b>：platform 应用扫描根是 {@code org.ruoyi}，
     *       {@code com.nageoffer.ai.ragent} 不在其中，且 {@code services/platform} 主源码里
     *       没有任何 {@code @ComponentScan}/{@code scanBasePackages} 覆盖它。因此这些注解
     *       **注册不出任何 handler**，不构成"第二份公开面"。</li>
     *   <li>它在内嵌形态的**可达对应物**是同形的
     *       {@code AiEmbeddedAgentConversationConfiguration.ConversationSurface}，
     *       后者已经在 {@code /internal/ai/v1} 之下（WP-034 修过的那条）。</li>
     *   <li>把本类也迁到内部前缀会与 {@code ConversationSurface} 在
     *       {@code /internal/ai/v1/agent/v1/conversations/**} 上产生**同一路径的重复
     *       handler mapping**：一旦将来有配置让两者同时成为 bean，容器会以
     *       "Ambiguous mapping" 启动失败。**改它比不改它更危险**，所以按下不动。</li>
     * </ol>
     * 键是相对 {@code services/platform/ruoyi-modules} 的路径。
     */
    private static final Map<String, String> ROOT_AGENT_V1_EXCEPTIONS = Map.of(
            "ruoyi-ai-agent/src/main/java/com/nageoffer/ai/ragent/agent/controller/AgentConversationController.java",
            "内嵌态不是 bean（扫描根 org.ruoyi）；可达面是已在内部前缀下的 ConversationSurface；"
                    + "迁移会与其产生同一路径的重复 handler mapping");

    private static final Path MODULES = Path.of("services", "platform", "ruoyi-modules");

    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping\\s*\\(([^)]*)\\)");
    private static final Pattern FIRST_LITERAL = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern AGENT_V1_LITERAL = Pattern.compile("\"(/agent/v1/[^\"]*)\"");

    @Test
    @DisplayName("四条引擎端点在运行期映射到 /internal/ai/v1 之下（网关转送目标）")
    void engineEndpointsAreMappedUnderTheInternalPrefix() throws Exception {
        assertThat(resolvedMappings(AgentChatController.class))
                .as("chat/confirm/stop 三条必须落在内部前缀下，否则网关转送必 404")
                .containsExactlyInAnyOrder(
                        "GET " + INTERNAL_PREFIX + "/agent/v1/chat",
                        "POST " + INTERNAL_PREFIX + "/agent/v1/chat/confirm",
                        "POST " + INTERNAL_PREFIX + "/agent/v1/stop");
        assertThat(resolvedMappings(AgentMetaController.class))
                .containsExactly("GET " + INTERNAL_PREFIX + "/agent/v1/meta");
    }

    @Test
    @DisplayName("JSON 两条返回整数 code 包络，SSE 两条返回 SseEmitter（包络形状是 503 的成因）")
    void jsonEndpointsCarryIntegerCodeEnvelope() throws Exception {
        // 网关 AiGatewayClient.requireSingleJsonObject 要求"单 JSON 对象 + 整数 code 等于状态"。
        // AI 侧旧 Result 的 code 是字符串 "0" —— 会被判为 "missing envelope code" 并收敛为 503，
        // WP-034B 已经因为同一形态丢过一次公开面。所以 JSON 两条的返回类型必须是 ApiEnvelope。
        assertThat(AgentMetaController.class.getMethod("meta").getReturnType())
                .isEqualTo(ApiEnvelope.class);
        assertThat(AgentChatController.class.getMethod("stop", String.class).getReturnType())
                .isEqualTo(ApiEnvelope.class);
        assertThat(AgentChatController.class.getMethod("chat", String.class, String.class).getReturnType())
                .isEqualTo(SseEmitter.class);
        assertThat(AgentChatController.class.getMethod("confirm",
                com.nageoffer.ai.ragent.agent.controller.request.ConfirmRequest.class).getReturnType())
                .isEqualTo(SseEmitter.class);
    }

    @Test
    @DisplayName("残留清零（实际范围）：除逐条登记的例外外，不得有未加内部/外部前缀的根级 /agent/v1/** 控制器")
    void noNewControllerExposesAgentV1AtTheApplicationRoot() throws IOException {
        List<String> offenders = new ArrayList<>();
        Set<String> recognisedExceptions = new LinkedHashSet<>();
        Path modules = locate(MODULES);
        try (Stream<Path> stream = Files.walk(modules)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                // Windows 上 Files.walk 给的是反斜杠路径，必须归一化后再判定：
                // 直接用 "/src/main/java/" 会一条都匹配不到，判据于是退化成恒真
                // （本判据第一版就踩过这个坑：offenders 恒空、而 allowlist 命中数恒 0）。
                if (!file.toString().replace('\\', '/').contains("/src/main/java/")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!AGENT_V1_LITERAL.matcher(source).find()) {
                    continue;
                }
                String key = relative(modules, file);
                boolean allowedException = ROOT_AGENT_V1_EXCEPTIONS.containsKey(key);
                String classPrefix = classLevelPrefix(source);
                for (Map.Entry<String, Boolean> mapping : agentV1Mappings(source).entrySet()) {
                    // 覆盖来源有两种，缺一不可（与 LocalWhitelistHandlerCoverageTest 的扫描模型一致）：
                    //   ① 方法级 INTERNAL_PREFIX 常量拼接（ConversationSurface 的形态）；
                    //   ② 类级前缀落在 /internal/ai/v1 或 /api/ai/v1 之下。
                    // 只认 ② 会把已修好的会话面误报成残留 —— 本判据第一版正是这样误报了四条。
                    boolean covered = mapping.getValue()
                            || (classPrefix != null
                                && (classPrefix.startsWith(INTERNAL_PREFIX) || isExternalGatewayPrefix(classPrefix)));
                    if (allowedException) {
                        recognisedExceptions.add(key);
                        continue;
                    }
                    if (!covered) {
                        offenders.add(key + " → " + mapping.getKey()
                                + "（类级前缀=" + (classPrefix == null ? "无" : classPrefix) + "）");
                    }
                }
            }
        }
        assertThat(offenders)
                .as("这些位置把 /agent/v1/** 暴露在应用根路径空间里：既不可经网关到达"
                        + "（网关转送目标恒为 %s + subPath），也不在 AiInternalAccessBoundaryFilter"
                        + " 的关闭范围内。修法：加类级 @RequestMapping(\"%s\")（引擎面，C13.3-1）"
                        + "或 /api/ai/v1（网关专用传输，C13.3-3）；确属有意保留则逐条登记进"
                        + " ROOT_AGENT_V1_EXCEPTIONS 并写明理由。",
                        INTERNAL_PREFIX, INTERNAL_PREFIX)
                .isEmpty();
        // 例外必须真的还在、且理由仍成立：允许项若已迁走就要连带删掉，否则 allowlist 会
        // 悄悄变成"永久豁免"，把判据的覆盖范围越缩越小。
        assertThat(recognisedExceptions)
                .as("ROOT_AGENT_V1_EXCEPTIONS 里的条目必须仍然命中；命中不到说明该文件已迁移，"
                        + "应把条目删除而不是留着当永久豁免")
                .containsExactlyInAnyOrderElementsOf(ROOT_AGENT_V1_EXCEPTIONS.keySet());
    }

    @Test
    @DisplayName("锚点：本判据必须真的读到四份运行期映射与全部源码，否则会退化成恒真")
    void anchorsProveTheGuardrailActuallyReadsSomething() throws Exception {
        assertThat(resolvedMappings(AgentChatController.class)).hasSize(3);
        assertThat(resolvedMappings(AgentMetaController.class)).hasSize(1);
        assertThat(locate(MODULES)).as("必须能定位到 %s", MODULES).isDirectory();
    }

    // ------------------------------------------------------------------ helpers

    /** 反射读出类级前缀 + 方级路径的组合结果（{@code METHOD /absolute/path}）。 */
    private static Set<String> resolvedMappings(Class<?> controller) {
        RequestMapping classMapping = controller.getAnnotation(RequestMapping.class);
        String prefix = classMapping == null || classMapping.value().length == 0
                ? "" : classMapping.value()[0];
        Set<String> mappings = new LinkedHashSet<>();
        for (Method method : controller.getDeclaredMethods()) {
            for (Annotation annotation : method.getAnnotations()) {
                String httpMethod = httpMethodOf(annotation);
                if (httpMethod == null) {
                    continue;
                }
                String path = mappingPath(annotation);
                mappings.add(httpMethod + " " + prefix + path);
            }
        }
        return mappings;
    }

    private static String httpMethodOf(Annotation annotation) {
        if (annotation instanceof GetMapping) {
            return "GET";
        }
        if (annotation instanceof PostMapping) {
            return "POST";
        }
        if (annotation instanceof PutMapping) {
            return "PUT";
        }
        if (annotation instanceof DeleteMapping) {
            return "DELETE";
        }
        if (annotation instanceof PatchMapping) {
            return "PATCH";
        }
        return null;
    }

    private static String mappingPath(Annotation annotation) {
        try {
            Method value = annotation.annotationType().getMethod("value");
            String[] paths = (String[]) value.invoke(annotation);
            return paths.length == 0 ? "" : paths[0];
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read mapping path", e);
        }
    }

    private static String classLevelPrefix(String source) {
        Matcher matcher = Pattern.compile("@RequestMapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"")
                .matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean isExternalGatewayPrefix(String prefix) {
        return EXTERNAL_PREFIXES.stream().anyMatch(prefix::startsWith);
    }

    /**
     * 方法级 mapping 里的 {@code /agent/v1/...} 字面量 → 该映射是否由 {@code INTERNAL_PREFIX}
     * 常量拼接构成（即已经落在内层可达前缀下）。
     *
     * <p>常量拼接在源码里是符号、运行期才是 {@code /internal/ai/v1}，所以必须单独识别：
     * 漏掉这一种形态会把 {@code ConversationSurface} 那四条**已修好的**映射误报成根级残留。
     */
    private static Map<String, Boolean> agentV1Mappings(String source) {
        Matcher methodMappings = METHOD_MAPPING.matcher(source);
        Map<String, Boolean> mappings = new LinkedHashMap<>();
        while (methodMappings.find()) {
            String args = methodMappings.group(2);
            boolean viaInternalConstant = args.contains("INTERNAL_PREFIX");
            Matcher literal = FIRST_LITERAL.matcher(args);
            if (literal.find() && literal.group(1).startsWith("/agent/v1/")) {
                mappings.put(literal.group(1), viaInternalConstant);
            }
        }
        return mappings;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static Path locate(Path relative) {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && cursor != null; i++) {
            Path candidate = cursor.resolve(relative);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("cannot locate " + relative + " from "
                + Path.of("").toAbsolutePath());
    }
}
