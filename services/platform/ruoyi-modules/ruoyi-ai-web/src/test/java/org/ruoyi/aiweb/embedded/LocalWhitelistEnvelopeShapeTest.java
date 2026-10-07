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

import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeBaseController;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeChunkController;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeDocumentController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 的<b>系统性护栏</b>：白名单放行了某个知识管理面 ⇒ 该面的内层 handler 必须是整数
 * {@code code} 的信封（{@link ApiEnvelope}），不能是 {@code Result}。
 *
 * <p><b>为什么需要它。</b>D2 是这样漏过去的：{@code LocalWhitelistHandlerCoverageTest} 只断言
 * "白名单每条路由都有内层 handler"，而 {@code LocalKnowledgeChunkEnvelopeDispatchTest} 只覆盖
 * 当前已登记的那几条。两条判据之间有一个空档——<b>"新放行一条路由，而它的 handler 返回字符串 code"</b>
 * 不会被任何一条挡住，运行期表现是网关 503（{@code missing envelope code}）。
 * 本类把这条规则显式化：**放行 = 必须整数信封**。
 *
 * <p><b>知识库/文档面当前未放行</b>（{@code AiEmbeddedKnowledgeAdminConfiguration} 默认只装分块面，
 * T0 也只登记了 6 条分块路由），因此它们的 {@code Result} 目前不构成 D2，本判据对它们是"未放行 ⇒ 不检查"。
 * 一旦 T0 在白名单里加它们的路由，本判据立刻变红，直到同法改为 {@link ApiEnvelope} —— 这正是
 * 把"将来会踩的坑"变成"集成时就报错"。
 *
 * <p>路由表从 {@code AiGatewayController} <b>源码</b>解析（与该包既有护栏同一手法），
 * 内层 handler 的返回类型用反射读（控制器在测试类路径上）。
 */
@Tag("dev")
class LocalWhitelistEnvelopeShapeTest {

    private static final Path MODULES = Path.of("services", "platform", "ruoyi-modules");

    private static final Pattern ROUTE = Pattern.compile(
            "new\\s+Route\\(\\s*\"([A-Z]+)\"\\s*,\\s*\"([^\"]+)\"");

    /**
     * 单数知识管理面 {@code /knowledge-base} 的边界匹配。
     *
     * <p>不能写成 {@code startsWith("/knowledge-base")}：复数资源面 {@code /knowledge-bases}
     * 也满足它，而那是 {@code AiResourceController} 的面（本来就是整数信封）。
     * 这条误判在本判据第一次运行时真的发生过——护栏"能红"这件事因此有了实证。
     */
    private static final Pattern SINGULAR_KNOWLEDGE_SURFACE =
            Pattern.compile("^/knowledge-base(/|$)");

    @Test
    @DisplayName("白名单里放行的知识管理面，其内层 handler 必须返回整数 code 的信封")
    void whitelistedKnowledgeSurfacesMustUseIntegralCodeEnvelope() throws IOException {
        List<String> patterns = whitelistedPatterns();
        assertThat(patterns)
                .as("锚点：必须真的解析到路由表（否则本判据空跑）")
                .isNotEmpty()
                .hasSizeGreaterThan(40);

        List<String> offenders = new ArrayList<>();
        for (String pattern : patterns) {
            // 只看**单数**管理面 /knowledge-base/**：复数 /knowledge-bases/** 是 AiResourceController
            // 的 AI 资源面（返回 ApiEnvelope，本来就合规）。前缀比较必须带边界，
            // 否则 "/knowledge-bases" 也会 startsWith("/knowledge-base") 被误判。
            if (!SINGULAR_KNOWLEDGE_SURFACE.matcher(pattern).find()) {
                continue;
            }
            Class<?> controller = knowledgeControllerFor(pattern);
            for (Method method : mappedMethods(controller)) {
                if (!isIntegralEnvelope(method)) {
                    offenders.add(pattern + " → " + controller.getSimpleName() + "."
                            + method.getName() + " 返回 " + method.getReturnType().getSimpleName());
                }
            }
        }

        assertThat(offenders)
                .as("白名单放行了这些知识面，但内层 handler 不是 ApiEnvelope："
                        + "LocalAiGatewayClient.requireSingleJsonObject 要求整数 code，"
                        + "字符串 code（Result 的 \"0\"）经网关必然 503")
                .isEmpty();
    }

    @Test
    @DisplayName("判定器自身可证：喂它一条放行路由，它真的能指出字符串 code 的 handler")
    void checkerItselfRejectsAStringCodeHandler() {
        // 正例：分块面已改成 ApiEnvelope
        assertThat(stringCodeHandlers(KnowledgeChunkController.class))
                .as("分块面已改造，不该再被判为字符串 code").isEmpty();

        // 负例（证判定器非恒真）：知识库/文档面仍是 Result，判定器必须点出来
        assertThat(stringCodeHandlers(KnowledgeBaseController.class))
                .as("知识库面仍是 Result，判定器必须能识别（否则上面的判据是空跑）")
                .isNotEmpty();
        assertThat(stringCodeHandlers(KnowledgeDocumentController.class))
                .as("文档面仍是 Result，判定器必须能识别")
                .isNotEmpty();
    }

    // ------------------------------------------------------------------ 判定

    /**
     * 该控制器里<b>不合规</b>的映射方法：既不是 {@link ApiEnvelope}，也不是
     * 体内装 {@code ApiEnvelope} 的 {@code ResponseEntity}（GET 走字节分支时要靠它带回执头）。
     */
    private static List<String> stringCodeHandlers(Class<?> controller) {
        List<String> offenders = new ArrayList<>();
        for (Method method : mappedMethods(controller)) {
            if (isIntegralEnvelope(method)) {
                continue;
            }
            offenders.add(method.getName() + " -> " + method.getReturnType().getSimpleName());
        }
        return offenders;
    }

    private static boolean isIntegralEnvelope(Method method) {
        if (method.getReturnType() == ApiEnvelope.class) {
            return true;
        }
        return method.getReturnType() == ResponseEntity.class
                && method.getGenericReturnType().getTypeName().contains("ApiEnvelope");
    }

    private static List<Method> mappedMethods(Class<?> controller) {
        List<Method> mapped = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            if (method.isAnnotationPresent(GetMapping.class)
                    || method.isAnnotationPresent(PostMapping.class)
                    || method.isAnnotationPresent(PutMapping.class)
                    || method.isAnnotationPresent(DeleteMapping.class)
                    || method.isAnnotationPresent(PatchMapping.class)) {
                mapped.add(method);
            }
        }
        return mapped;
    }

    /**
     * 路由 → 知识控制器：段形状唯一的判定。
     *
     * <p>{@code .../docs/{id}/chunks*} → 分块面；{@code .../docs/...} → 文档面；其余 → 知识库面。
     * 顺序不能颠倒：{@code /knowledge-base/docs/{id}} 也以 {@code /knowledge-base} 开头。
     */
    private static Class<?> knowledgeControllerFor(String pattern) {
        if (pattern.contains("/docs/") && pattern.contains("/chunks")) {
            return KnowledgeChunkController.class;
        }
        if (pattern.contains("/docs/")) {
            return KnowledgeDocumentController.class;
        }
        return KnowledgeBaseController.class;
    }

    /** 从网关源码解析白名单 pattern（保留声明顺序，顺序本身是路由语义的一部分）。 */
    private static List<String> whitelistedPatterns() throws IOException {
        Path source = locate(MODULES.resolve("ruoyi-ai-integration").resolve("src").resolve("main")
                .resolve("java").resolve("org").resolve("ruoyi").resolve("aiintegration")
                .resolve("web").resolve("AiGatewayController.java"));
        String text = Files.readString(source, StandardCharsets.UTF_8);
        int routesStart = text.indexOf("ROUTES = List.of(");
        assertThat(routesStart).as("找不到 ROUTES 定义（源码形状变了？）").isGreaterThan(0);
        int routesEnd = text.indexOf(");", routesStart);
        Matcher matcher = ROUTE.matcher(text.substring(routesStart, routesEnd));
        List<String> patterns = new ArrayList<>();
        while (matcher.find()) {
            patterns.add(matcher.group(2));
        }
        return patterns;
    }

    /** 与既有护栏同一手法：相对路径在 reactor 根找不到时，逐级向上找。 */
    private static Path locate(Path relative) {
        Path current = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && current != null; i++) {
            Path candidate = current.resolve(relative);
            if (Files.isDirectory(candidate) || Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到 " + relative);
    }

    @SuppressWarnings("unused")
    private static Stream<Path> unusedGuard() {
        return Stream.empty();
    }
}
