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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.aiintegration.web.AiGatewayController;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-034：网关白名单的**每一条路由都必须有一个内层 handler 承接**。
 *
 * <p><b>为什么必须有这一条（本判据是本包缺陷的直接产物）。</b>
 * 网关 {@code AiGatewayController} 的转送目标恒为
 * {@code base + "/internal/ai/v1" + subPath}，而内层 handler 由
 * {@code RequestMappingHandlerMapping} 在该路径上查找。**这两件事之间没有任何编译期约束**：
 * 白名单里写了一条路由、但内层没有任何控制器映射到 {@code /internal/ai/v1 + subPath} 时，
 * 一切静态检查、一切"装配判据"都会通过——只有真实请求会拿到 404。
 *
 * <p>WP-033B 就是这样交付了一条**永远不可达**的公开面：控制器注册在
 * {@code /agent/v1/conversations}（缺内部前缀），而白名单里当时也确实没有它；
 * 本包加入白名单后再把控制器迁到内部前缀下才真正接通。当时没有任何判据能发现这件事。
 *
 * <p><b>本判据做什么。</b>把两侧各自当作**数据**读出来再求交集：
 * <ul>
 *   <li>白名单：反射 {@link AiGatewayController} 的私有 {@code ROUTES}（运行期固定表，
 *       不抄一份——抄一份就会与产品代码一起漂移）；</li>
 *   <li>内层 handler：扫描 {@code services/platform/ruoyi-modules/*\/src/main/java} 下
 *       所有源文件的类级 {@code @RequestMapping} 与方级 mapping 注解，
 *       拼出 {@code METHOD /绝对路径}（{@code {x}} 归一为 {@code {}}）。</li>
 * </ul>
 * 然后断言**白名单的每一条都在内层 handler 集合里**。
 *
 * <p><b>为什么扫描源码而不是启真实容器。</b>启容器只能覆盖"当前 profile 下装配成功"的组，
 * 而漏配一个组恰恰是这类缺陷的常见形态（未装配的组内层没有 handler，白名单却已登记）；
 * 源码扫描覆盖**所有**已登记的控制器，与是否装配无关——两者是不同维度，
 * 装配与放行的**行为**由各自的端到端判据负责。
 *
 * <p>反向不成立也不报错：内层可以存在未被白名单放行的 handler（例如有意不放行的
 * {@code batch-delete}、仅供内部调用的 barrier/release 端点）。那是**允许**的形态，
 * 本判据只禁止"放行了但没人接"。
 */
@Tag("dev")
class LocalWhitelistHandlerCoverageTest {

    private static final Path MODULES = Path.of("services", "platform", "ruoyi-modules");

    /** 内层可达前缀（与网关 {@code AI_INTERNAL_PREFIX} 同值，含反向锚点断言）。 */
    private static final String INTERNAL_PREFIX = "/internal/ai/v1";

    /** 方级 mapping 注解：GET/POST/PUT/DELETE/PATCH。 */
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping\\s*(?:\\(([^)]*)\\))?");

    /** 类级前缀注解。 */
    private static final Pattern CLASS_MAPPING = Pattern.compile(
            "@RequestMapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");

    /** 注解里第一个字符串字面量（方法级 mapping 的路径）。 */
    private static final Pattern FIRST_LITERAL = Pattern.compile("\"([^\"]*)\"");

    @Test
    @DisplayName("网关白名单的每一条路由都有内层 handler 承接（防止再次出现『放行了但没人接』）")
    void everyWhitelistedRouteHasAnInternalHandler() throws Exception {
        List<String> routes = whitelistRoutes();
        Set<String> handlers = internalHandlers();

        // 锚点：两侧都必须真的读到东西，否则本判据会退化成恒真
        assertThat(routes)
                .as("锚点：必须真的读到网关路由表（含本包新增的 F10 四条）")
                .hasSizeGreaterThanOrEqualTo(34)
                .contains("GET /agent/v1/conversations", "DELETE /conversations/{id}", "POST /runs");
        assertThat(handlers)
                .as("锚点：必须真的扫到内层 handler（含会话面与 run 面）")
                .contains("GET " + INTERNAL_PREFIX + "/conversations",
                        "POST " + INTERNAL_PREFIX + "/runs",
                        "DELETE " + INTERNAL_PREFIX + "/agent/v1/conversations/{}");

        List<String> unreachable = new ArrayList<>();
        Set<String> routeKeys = new LinkedHashSet<>();
        for (String route : routes) {
            routeKeys.add(normalize(route));
        }
        for (String route : routeKeys) {
            int split = route.indexOf(' ');
            String method = route.substring(0, split);
            String subPath = route.substring(split + 1);
            String expected = method + " " + INTERNAL_PREFIX + normalizePath(subPath);
            if (!handlers.contains(expected)) {
                unreachable.add(expected);
            }
        }

        assertThat(unreachable)
                .as("这些路由在白名单里、但内层没有任何 handler 映射到 %s + 子路径 → "
                        + "客户端会拿到 404，而所有静态检查都会通过。"
                        + "修法二选一：把控制器移到内部前缀下（WP-034 的做法），或把该路由从白名单移除。",
                        INTERNAL_PREFIX)
                .isEmpty();
    }

    @Test
    @DisplayName("内层前缀常量两侧一致（网关与控制器各自持有，必须逐字相等）")
    void bothSidesAgreeOnTheInternalPrefix() throws Exception {
        // 网关侧：真实请求前会拼这个前缀，取它的常量值
        Field field = AiGatewayController.class.getDeclaredField("AI_INTERNAL_PREFIX");
        field.setAccessible(true);
        String gatewayPrefix = (String) field.get(null);

        assertThat(gatewayPrefix)
                .as("网关的内部前缀变了，本判据与内层常量必须同步；"
                        + "两侧漂移会让全部 36 条白名单路由一起 404")
                .isEqualTo(INTERNAL_PREFIX);
        // 控制器侧常量（WP-034 引入）必须逐字相同
        assertThat(AiEmbeddedAgentConversationConfiguration.LocalTransport.ConversationEnabled
                .ConversationSurface.INTERNAL_PREFIX)
                .as("会话面的内部前缀必须与网关一致")
                .isEqualTo(gatewayPrefix);
    }

    // ------------------------------------------------------------------ helpers

    /** 反射读网关私有 {@code ROUTES}，产出 {@code METHOD subPath} 列表。 */
    private static List<String> whitelistRoutes() throws Exception {
        Field field = AiGatewayController.class.getDeclaredField("ROUTES");
        field.setAccessible(true);
        List<?> routes = (List<?>) field.get(null);
        List<String> out = new ArrayList<>();
        for (Object route : routes) {
            out.add(String.valueOf(invoke(route, "method")) + " " + invoke(route, "pattern"));
        }
        return out;
    }

    private static Object invoke(Object target, String accessor) throws Exception {
        Method method = target.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(target);
    }

    /**
     * 扫描全部模块源码，产出内层 handler 的 {@code METHOD 绝对路径} 集合。
     *
     * <p>内层前缀有两种写法，两种都要覆盖：
     * <ul>
     *   <li><b>类级前缀</b>：{@code @RequestMapping("/internal/ai/v1")} +
     *       方级相对路径（{@code AiResourceController}、{@code RunController} 等）；</li>
     *   <li><b>方级全路径</b>：没有类级 mapping，方级直接写
     *       {@code INTERNAL_PREFIX + "/agent/v1/..."}（WP-034 的会话面就是这种）。
     *       源码里读不到运行期常量值，按常量字面量还原。</li>
     * </ul>
     * 只保留拼出的绝对路径确实落在内部前缀下的条目——那正是"内层可达面"的定义。
     */
    private static Set<String> internalHandlers() throws IOException {
        Path modules = locate(MODULES);
        Set<String> handlers = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.walk(modules)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);

                // 类级前缀：只有落在内部前缀下才算内层可达面
                Matcher classMapping = CLASS_MAPPING.matcher(source);
                String classPrefix = "";
                if (classMapping.find()) {
                    String declared = classMapping.group(1);
                    if (!declared.startsWith(INTERNAL_PREFIX)) {
                        continue;
                    }
                    classPrefix = declared;
                }

                boolean any = false;
                Matcher methods = METHOD_MAPPING.matcher(source);
                while (methods.find()) {
                    String httpMethod = methods.group(1).toUpperCase(java.util.Locale.ROOT);
                    String path = resolvePath(methods.group(2));
                    if (path == null) {
                        // 只给了 produces/consumes 而没给路径：类级前缀即完整路径
                        path = "";
                    }
                    String full = normalizePath(classPrefix + path);
                    if (!full.startsWith(INTERNAL_PREFIX)) {
                        continue;
                    }
                    handlers.add(httpMethod + " " + full);
                    any = true;
                }
                // 有类级内部前缀却没有方级 mapping 的类不是可达面；反之亦然。
                // 这里不做额外断言：`any` 仅用于可读性，实际以集合内容为准。
                if (!any) {
                    continue;
                }
            }
        }
        return handlers;
    }

    /**
     * 从注解参数里取路径；支持 {@code INTERNAL_PREFIX + "/x"} 常量拼接。
     *
     * <p>拼接形态要**还原成绝对路径**（常量在源码里是符号，运行期才是
     * {@code /internal/ai/v1}）——否则 WP-034 的会话面会被漏扫，
     * 而漏扫正是本判据要防的"看起来没人接"。
     */
    private static String resolvePath(String annotationArgs) {
        if (annotationArgs == null) {
            return ""; // Bare @GetMapping maps the exact class prefix (e.g. /sample-questions).
        }
        if (annotationArgs.contains("INTERNAL_PREFIX")) {
            Matcher literal = Pattern.compile("INTERNAL_PREFIX\\s*\\+\\s*\"([^\"]*)\"")
                    .matcher(annotationArgs);
            if (literal.find()) {
                return INTERNAL_PREFIX + literal.group(1);
            }
            return null;
        }
        Matcher literal = FIRST_LITERAL.matcher(annotationArgs);
        return literal.find() ? literal.group(1) : null;
    }

    /** 路径变量归一：{@code {id}} 与 {@code {conversationId}} 视为同一形状。 */
    private static String normalizePath(String path) {
        return path.replaceAll("\\{[^}]*\\}", "{}");
    }

    /** {@code METHOD /a/{x}} → {@code METHOD /a/{}}。 */
    private static String normalize(String route) {
        int split = route.indexOf(' ');
        return route.substring(0, split) + " " + normalizePath(route.substring(split + 1));
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
