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

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeBaseController;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeChunkController;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeDocumentController;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 的<b>系统性护栏</b>：白名单放行了某个知识管理面 ⇒ 该面的内层 handler 必须是整数
 * {@code code} 的信封（{@link ApiEnvelope}），不能是 {@code Result}。
 *
 * <p><b>为什么需要它。</b>D2 是这样漏过去的：{@code LocalWhitelistHandlerCoverageTest} 只断言
 * "白名单每条路由都有内层 handler"，而 {@code LocalKnowledgeAdminEnvelopeDispatchTest} 只覆盖
 * 当前已登记的那几条。两条判据之间有一个空档——<b>"新放行一条路由，而它的 handler 返回字符串 code"</b>
 * 不会被任何一条挡住，运行期表现是网关 503（{@code missing envelope code}）。
 * 本类把这条规则显式化：<b>放行 = 必须整数信封</b>。
 *
 * <p><b>知识库面/文档面的信封状态（RW-04-R8 改造，S2-F05-A1 放行）。</b>改造前这两个面返回
 * platform {@code Result}（字符串 {@code code}），当时因为它们<b>未被放行</b>、也不是 bean
 * （内嵌装配默认关闭）而不构成 D2。RW-04-R8 已把它们改成整数 {@link ApiEnvelope}
 * （GET 另带回执头），于是本类的判定从"未放行 ⇒ 不检查"变成
 * <b>"这两个面现在确实是整数 code"</b>。
 * <b>S2-F05-A1 起闭包闭合、17 条路由已登记</b>——"放行 = 必须整数信封"因此对这三个面
 * <b>全部生效</b>（不再有"未放行 ⇒ 豁免"的缝），逐条清单由
 * {@link #knowledgeAdminFacesAreReleasedAndCounted()} 钉住。
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
            // 走与自证判据**同一个**判定器：它同时带"裸字节面"豁免（返回 void +
            // HttpServletResponse 参数，恰一条 file，由 rawByteStreamShapeIsExplicit 与
            // KnowledgeDocumentPrivateDownloadTest 钉住）。S2-F05-A1 放行文档面后这条差异
            // 才暴露出来——此前单数面只有分块路由，控制器里没有裸字节方法，判定器等价。
            for (String offender : stringCodeHandlers(controller)) {
                offenders.add(pattern + " → " + controller.getSimpleName() + "." + offender);
            }
        }

        assertThat(offenders)
                .as("白名单放行了这些知识面，但内层 handler 不是 ApiEnvelope："
                        + "LocalAiGatewayClient.requireSingleJsonObject 要求整数 code，"
                        + "字符串 code（Result 的 \"0\"）经网关必然 503")
                .isEmpty();
    }

    @Test
    @DisplayName("判定器自身可证：喂它一个字符串 code 的 handler，它真的能指出来")
    void checkerItselfRejectsAStringCodeHandler() {
        // RW-04-R8 之前，这条负例是"知识库面/文档面现在仍是 Result，判定器必须点出来"。
        // 改造后这两个面已经合规，若继续拿它们当负例，就等于把判据钉成恒绿——
        // 那正是本卡明令禁止的"削弱成恒绿"。因此负例改用**只存在于本测试装配里**的
        // StringCodeProbeController：判定器仍然被证明"能红"，但不再依赖产品代码保持有缺陷。
        List<String> probeOffenders = stringCodeHandlers(StringCodeProbeController.class);
        assertThat(probeOffenders)
                .as("判定器必须能识别字符串 code 的 handler（否则上面的判据是空跑）")
                .isNotEmpty()
                .anyMatch(offender -> offender.startsWith("stringCodeProbe -> Result"));

        // 正例：三个知识面现在都必须是整数 code（这是本卡改造后的判定方向）。
        for (Class<?> controller : List.of(KnowledgeChunkController.class,
                KnowledgeBaseController.class, KnowledgeDocumentController.class)) {
            assertThat(stringCodeHandlers(controller))
                    .as(controller.getSimpleName() + " 面必须是整数 code 的 ApiEnvelope；"
                            + "这些方法经网关时 LocalAiGatewayClient.requireSingleJsonObject "
                            + "要求 code 是整数，而 Result 的 code 是字符串 \"0\" ⇒ 必然 503")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("信封判据只有一条豁免：文档面的裸字节面 .../file，且必须恰好一条")
    void rawByteStreamShapeIsExplicit() {
        // 豁免是"按形状限定"的（返回 void + 直接写 HttpServletResponse），因此必须钉住它的数量：
        // 多出一条就说明有人新开了裸字节面而没走回执头判据；一条都没有就说明文档面被改窄了。
        assertThat(rawByteStreamHandlers(KnowledgeDocumentController.class))
                .as("文档面只允许一条裸字节面（file）；它的回执头由 ruoyi-ai-rag 的 "
                        + "KnowledgeDocumentPrivateDownloadTest#fileShouldCarryDeliveryReceiptHeaders 钉住")
                .containsExactly("file");
        for (Class<?> controller : List.of(KnowledgeChunkController.class, KnowledgeBaseController.class)) {
            assertThat(rawByteStreamHandlers(controller))
                    .as(controller.getSimpleName() + " 面不应有裸字节面")
                    .isEmpty();
        }
        // 豁免判定本身不能恒真：探针控制器（返回 Result，无 HttpServletResponse 参数）不在豁免范围内
        assertThat(stringCodeHandlers(StringCodeProbeController.class))
                .as("返回 Result 的 handler 不能被裸字节面豁免吞掉")
                .isNotEmpty();
    }

    @Test
    @DisplayName("红线（S2-F05-A1 改向）：知识管理面 23 条 = 分块 6 + 知识库 5 + 文档 12，逐条在列且无未审增量")
    void knowledgeAdminFacesAreReleasedAndCounted() throws IOException {
        List<String> routes = whitelistedRoutes();
        List<String> singular = routes.stream()
                .filter(route -> SINGULAR_KNOWLEDGE_SURFACE.matcher(patternOf(route)).find())
                .toList();

        // 锚点：必须真的读到单数 /knowledge-base 面的全部路由（RW-04-R1 的 6 + S2-F05-A1 的 17）。
        assertThat(singular)
                .as("锚点：必须真的读到单数 /knowledge-base 面已登记的路由")
                .hasSizeGreaterThanOrEqualTo(23);

        // 逐条清点（清单 = 三个控制器的映射方法实测；"15 条"是 RW-04-R8 期的过期口径）。
        // containsExactlyInAnyOrder 是**两侧**判据：少一条（放行被回退）或
        // 多一条（未审的新放行）都会红。
        assertThat(singular)
                .as("知识管理面公开路由清单：分块面 6（RW-04-R1）+ 知识库面 5 + 文档面 12（S2-F05-A1）。"
                        + "新增任何一条都要先回到本清单（放行 = 有人接 + 整数信封 + 动作已在白名单动作集内）")
                .containsExactlyInAnyOrder(
                        // 分块面 6（RW-04-R1 已放行）
                        "GET /knowledge-base/docs/{docId}/chunks",
                        "POST /knowledge-base/docs/{docId}/chunks",
                        "PUT /knowledge-base/docs/{docId}/chunks/{chunkId}",
                        "DELETE /knowledge-base/docs/{docId}/chunks/{chunkId}",
                        "PATCH /knowledge-base/docs/{docId}/chunks/{chunkId}/enable",
                        "PATCH /knowledge-base/docs/{docId}/chunks/batch-enable",
                        // 知识库面 5（S2-F05-A1）
                        "GET /knowledge-base",
                        "POST /knowledge-base",
                        "GET /knowledge-base/{kbId}",
                        "PUT /knowledge-base/{kbId}",
                        "DELETE /knowledge-base/{kbId}",
                        // 文档面 12（S2-F05-A1）
                        "GET /knowledge-base/docs/ingestion-spec-schema",
                        "GET /knowledge-base/docs/search",
                        "GET /knowledge-base/docs/{docId}",
                        "GET /knowledge-base/docs/{docId}/chunk-logs",
                        "GET /knowledge-base/docs/{docId}/preview",
                        "GET /knowledge-base/docs/{docId}/file",
                        "POST /knowledge-base/{kbId}/docs/upload",
                        "POST /knowledge-base/docs/{docId}/chunk",
                        "PUT /knowledge-base/docs/{docId}",
                        "DELETE /knowledge-base/docs/{docId}",
                        "PATCH /knowledge-base/docs/{docId}/enable",
                        "GET /knowledge-base/{kbId}/docs");
    }

    @Test
    @DisplayName("红线：3 段字面量路由必须排在同段的 {docId} 形之前（matchRoute 取声明序首个命中）")
    void literalThreeSegmentRoutesPrecedeTheDocIdShape() throws IOException {
        // 网关 matchRoute 逐条比较**声明序**，段数相同即可能互相遮蔽：
        // docs/ingestion-spec-schema 与 docs/search 都是 3 段，若排在 docs/{docId} 之后，
        // 它们会被 {docId} 吃掉（`{x}` 匹配任意非空段），运行期表现为
        // "schema/search 恒 404 或落到详情语义"——静态看两条路由都在，只有真请求会发现。
        List<String> singular = whitelistedRoutes().stream()
                .filter(route -> SINGULAR_KNOWLEDGE_SURFACE.matcher(patternOf(route)).find())
                .toList();
        int docDetail = singular.indexOf("GET /knowledge-base/docs/{docId}");

        assertThat(docDetail)
                .as("锚点：变量形详情路由必须真的在表里")
                .isGreaterThanOrEqualTo(0);
        for (String literal : List.of("GET /knowledge-base/docs/ingestion-spec-schema",
                "GET /knowledge-base/docs/search")) {
            int index = singular.indexOf(literal);
            assertThat(index)
                    .as(literal + " 未登记")
                    .isGreaterThanOrEqualTo(0);
            assertThat(index)
                    .as(literal + " 排在 GET /knowledge-base/docs/{docId} 之后会被其遮蔽")
                    .isLessThan(docDetail);
        }
    }

    // ------------------------------------------------------------------ 判定

    /**
     * 该控制器里<b>不合规</b>的映射方法：既不是 {@link ApiEnvelope}，也不是
     * 体内装 {@code ApiEnvelope} 的 {@code ResponseEntity}（GET 走字节分支时要靠它带回执头）。
     *
     * <p><b>唯一的豁免是"裸字节面"</b>（{@link #isRawByteStream}）：返回 {@code void} 且
     * 直接写 {@code HttpServletResponse} 的 GET（文档面的 {@code .../file}）。它不产 JSON，
     * 所以根本没有信封可言；它要满足的是回执头条件，由 {@code rawByteStreamShapeIsExplicit}
     * 与 {@code KnowledgeDocumentPrivateDownloadTest#fileShouldCarryDeliveryReceiptHeaders} 钉住。
     * 这条豁免是<b>按形状限定</b>的（返回 void + 有 HttpServletResponse 参数），
     * 不是按类名或方法名开洞——把某个 {@code Result} 方法改名也躲不过去。
     */
    private static List<String> stringCodeHandlers(Class<?> controller) {
        List<String> offenders = new ArrayList<>();
        for (Method method : mappedMethods(controller)) {
            if (isIntegralEnvelope(method) || isRawByteStream(method)) {
                continue;
            }
            offenders.add(method.getName() + " -> " + method.getReturnType().getSimpleName());
        }
        return offenders;
    }

    /** 裸字节面：返回 {@code void} 且直接写 {@code HttpServletResponse} 的映射方法。 */
    private static boolean isRawByteStream(Method method) {
        return method.getReturnType() == void.class
                && Arrays.asList(method.getParameterTypes()).contains(HttpServletResponse.class);
    }

    private static List<String> rawByteStreamHandlers(Class<?> controller) {
        List<String> raw = new ArrayList<>();
        for (Method method : mappedMethods(controller)) {
            if (isRawByteStream(method)) {
                raw.add(method.getName());
            }
        }
        return raw;
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

    /** {@code "METHOD /pattern"} → {@code "/pattern"}。 */
    private static String patternOf(String route) {
        return route.substring(route.indexOf(' ') + 1);
    }

    /** 从网关源码解析白名单 pattern（保留声明顺序，顺序本身是路由语义的一部分）。 */
    private static List<String> whitelistedPatterns() throws IOException {
        List<String> patterns = new ArrayList<>();
        for (String entry : whitelistedRoutes()) {
            patterns.add(entry.substring(entry.indexOf(' ') + 1));
        }
        return patterns;
    }

    /** 同上，但产出 {@code "METHOD /pattern"} 形式（放行判据要连方法一起看）。 */
    private static List<String> whitelistedRoutes() throws IOException {
        Path source = locate(MODULES.resolve("ruoyi-ai-integration").resolve("src").resolve("main")
                .resolve("java").resolve("org").resolve("ruoyi").resolve("aiintegration")
                .resolve("web").resolve("AiGatewayController.java"));
        String text = Files.readString(source, StandardCharsets.UTF_8);
        int routesStart = text.indexOf("ROUTES = List.of(");
        assertThat(routesStart).as("找不到 ROUTES 定义（源码形状变了？）").isGreaterThan(0);
        int routesEnd = text.indexOf(");", routesStart);
        Matcher matcher = ROUTE.matcher(text.substring(routesStart, routesEnd));
        List<String> routes = new ArrayList<>();
        while (matcher.find()) {
            routes.add(matcher.group(1) + " " + matcher.group(2));
        }
        return routes;
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

    /**
     * 判定器自证用的<b>负例</b>：返回字符串 {@code code} 的内层 handler。
     *
     * <p>只存在于本测试的源码里，生产不注册（不被任何 auto-configuration {@code @Import}）。
     * 它让"判定器能红"这件事与产品代码的当前状态解耦：RW-04-R8 之前这条负例用的是
     * 真实产品控制器，改造完成后那样做就等于把护栏钉成恒绿。
     */
    @RestController
    @RequestMapping("/internal/ai/v1/probe")
    static class StringCodeProbeController {

        @GetMapping("/knowledge-base/string-code-probe")
        Result<String> stringCodeProbe() {
            return Results.success("probe");
        }
    }
}