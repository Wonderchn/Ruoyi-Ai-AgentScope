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

package com.nageoffer.ai.ragent.user.config;

import com.nageoffer.ai.ragent.framework.integration.SaasEntryFilter;
import com.nageoffer.ai.ragent.framework.security.AiRequestIdFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1.2a 产品 HTTP 入口边界（{@link SaasEntryFilter}）的关闭契约。
 *
 * <p><b>为什么不用"路径没有映射"来证明 404</b>：那样只能证明 Spring 没找到 handler，
 * 不能证明边界在 handler 之前就拒绝了。本类的替身 controller 把每条旧业务路径都注册上，
 * 并用一个计数协作方记录"handler 是否被调用、它看到了什么身份字段"：
 * 关闭必须是"handler 0 次执行 + 身份字段一个都没进去"，而不是"跑了再返回 404"。
 *
 * <p><b>正向对照</b>：{@code GET} 探活形状的请求必须真的执行到 handler（否则整类断言可能只是因为链路不通而恒真）。
 */
class SaasEntryBoundaryTest {

    /**
     * P04 外显口径的关闭响应，刻意逐字节复制。
     *
     * <p>主源集常量 {@code SaasEntryFilter.CLOSED_BODY} 是 framework 模块包内可见，跨模块取不到；
     * 复制一份可让"关闭正文被改动"必须是一次显式修改测试的决定，而不是悄悄漂移。
     */
    private static final String CLOSED_BODY =
            "{\"code\":404,\"msg\":\"资源不存在或无权访问\","
                    + "\"data\":{\"errorCode\":\"RESOURCE_NOT_FOUND_OR_FORBIDDEN\"}}";

    private static final String CLOSED_ERROR_CODE = "RESOURCE_NOT_FOUND_OR_FORBIDDEN";

    /** 与 {@link AiRequestIdFilter} 的白名单字符集一致：不合法就必须被替换。 */
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final String TENANT = "tenant-from-header";
    private static final String USER = "user-from-header";
    private static final String CREDENTIAL = "service-credential-from-header";
    private static final String BEARER = "Bearer fake.header.payload";

    private HandlerRecorder recorder;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        recorder = new HandlerRecorder();
        mockMvc = MockMvcBuilders.standaloneSetup(new LegacyEndpoint(recorder))
                // 顺序与产品装配一致（SaasBoundaryConfiguration）：requestId 在最前，
                // 入口边界紧随其后——被拒绝的响应因此也能带上 X-Request-Id
                .addFilters(new AiRequestIdFilter(), new SaasEntryFilter())
                .build();
    }

    @Test
    @DisplayName("旧业务路径与未知路径一律 404，且已注册的 handler 一次都没跑")
    void legacyBusinessAndUnknownPathsAreRejectedBeforeAnyHandlerRuns() throws Exception {
        List<Attempt> rejected = List.of(
                new Attempt("POST", "/auth/login"),
                new Attempt("POST", "/auth/logout"),
                new Attempt("POST", "/users"),
                new Attempt("GET", "/users"),
                new Attempt("GET", "/user/me"),
                new Attempt("GET", "/knowledge-base"),
                new Attempt("POST", "/internal/ai/v1/runs"),
                new Attempt("POST", "/p04/control/reset"),
                new Attempt("GET", "/p04"),
                new Attempt("GET", "/"),
                // 未注册路径同样拒绝：判定不依赖"有没有 handler"
                new Attempt("GET", "/not-registered"),
                new Attempt("POST", "/not/registered/either"));

        for (Attempt attempt : rejected) {
            assertClosed(attempt.method(), attempt.path(), null);
            assertEquals(0, recorder.invocations(), () -> attempt.path() + " 不得执行任何 handler");
        }
        assertTrue(recorder.observations().isEmpty(), "被拒绝的请求不得把任何事实带进 handler");
    }

    @Test
    @DisplayName("关闭正文与 P04 外显口径逐字节一致（HTTP status == body.code == 404）")
    void closedResponseBodyMatchesP04ExposedContract() throws Exception {
        MvcResult result = perform("GET", "/knowledge-base", null)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        // 客户端按 errorCode 分支、运维按 msg 对口径；正文变了就是对外契约变了
        assertEquals(CLOSED_BODY, result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("带 X-Tenant/X-User/服务凭据/伪 Bearer/身份正文的请求照样 404，身份字段进不了 handler")
    void identityCarryingRequestsAreRejectedAndLeakNothingIntoHandlers() throws Exception {
        String body = "{\"userId\":\"" + USER + "\",\"tenantId\":\"" + TENANT + "\"}";
        MvcResult result = assertClosed("POST", "/internal/ai/v1/runs", request -> {
            request.addHeader("X-Tenant", TENANT);
            request.addHeader("X-User", USER);
            request.addHeader("X-P04-Service-Credential", CREDENTIAL);
            request.addHeader("Authorization", BEARER);
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
        });

        // 请求头与正文里的身份字段一个都不许到达业务代码：
        // 这是"关闭发生在任何 IO / UserContext 设置之前"的可观测形式
        assertEquals(0, recorder.invocations(), "身份字段不得把 handler 拉起来");
        assertTrue(recorder.observations().isEmpty(), "身份字段不得进入任何 handler");

        String responseBody = result.getResponse().getContentAsString();
        for (String secret : List.of(TENANT, USER, CREDENTIAL, BEARER)) {
            assertFalse(responseBody.contains(secret), () -> "关闭响应不得回显身份输入: " + secret);
        }
    }

    @Test
    @DisplayName("对照：放行的探活请求确实会把身份字段带进 handler（证明上面的记录器不是失灵）")
    void recorderCapturesIdentityWhenAHandlerReallyRuns() throws Exception {
        MvcResult result = perform("GET", "/p04/health", request -> {
                    request.addHeader("X-Tenant", TENANT);
                    request.addHeader("X-User", USER);
                })
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(1, recorder.invocations());
        String observation = recorder.observations().get(0);
        assertTrue(observation.contains("tenant=" + TENANT),
                () -> "记录器必须能看到身份字段，否则'没进 handler'的结论就是空转: " + observation);
        assertTrue(result.getResponse().getContentAsString().contains("/p04/health"));
        // 同时说明：边界只放行"无内容探活形状"，身份校验仍归后续 SaToken 拦截器，
        // 因此探活 handler 自身不得读写任何客户数据
    }

    @Test
    @DisplayName("正向对照：GET 探活形状放行到已注册 handler（证明链路可达，不是恒 404）")
    void healthShapedGetRequestsReachRegisteredHandlers() throws Exception {
        assertHandlerReached("GET", "/p04/health");
        assertHandlerReached("GET", "/actuator/health");
        assertEquals(2, recorder.invocations(), "两条探活路径都必须真的执行到 handler");
    }

    @Test
    @DisplayName("裸 /health：实测被长度守卫拒绝（与'最后一段是 health 就放行'的注释不一致，已上报）")
    void bareRootHealthPathIsRejectedByTheLengthGuard() throws Exception {
        // 预期口径是 GET /health → 200；主代码 isInfrastructurePath 要求
        // path.length() > "/health".length()，裸 /health 因此既不进 handler 也不是 200。
        // 这里按实测行为断言（不削弱、也不改主代码）：一旦主代码放开裸 /health，本用例会立刻失败并要求复核。
        assertClosed("GET", "/health", null);
        assertEquals(0, recorder.invocations(), "裸 /health 不得执行探活 handler");
    }

    @Test
    @DisplayName("探活形状必须是 GET 且最后一段正好是 health：其它方法/尾斜杠/相似后缀都不放行")
    void healthShapeRequiresGetMethodAndExactLastSegment() throws Exception {
        List<Attempt> rejected = List.of(
                new Attempt("POST", "/p04/health"),
                new Attempt("GET", "/p04/health/"),
                new Attempt("GET", "/p04/health-check"),
                new Attempt("GET", "/p04/healthz"));

        for (Attempt attempt : rejected) {
            assertClosed(attempt.method(), attempt.path(), null);
        }
        assertEquals(0, recorder.invocations(), "形状不精确的请求不得进入 handler");
    }

    @Test
    @DisplayName("探活形状但无 handler：边界只放行，404 由容器给出，不得伪造成健康 API 可用")
    void healthShapedPathWithoutHandlerIsNotFabricated() throws Exception {
        MvcResult result = perform("GET", "/not-registered/health", null)
                .andExpect(status().isNotFound())
                .andReturn();

        // 正文不是边界的关闭响应（为空），说明请求确实穿过了边界：404 来自"没有 handler"，
        // 而不是边界伪造出来的。产品没有 health handler 时不得假装健康 API 存在。
        String body = result.getResponse().getContentAsString();
        assertNotEquals(CLOSED_BODY, body, "该 404 不应来自入口边界的关闭响应");
        assertTrue(body.isBlank(), () -> "无 handler 的 404 不应带业务正文，实际: " + body);
        assertRequestIdHeader(result);
        assertEquals(0, recorder.invocations());
    }

    @Test
    @DisplayName("大小写混写的探活形状：边界按小写形状放行，但映射区分大小写，handler 依然跑不到")
    void caseFoldedHealthShapeStillReachesNoHandler() throws Exception {
        MvcResult result = perform("GET", "/P04/HEALTH", null)
                .andExpect(status().isNotFound())
                .andReturn();

        assertRequestIdHeader(result);
        assertEquals(0, recorder.invocations(), "大写字面量不得命中 /p04/health 的 handler");
    }

    @Test
    @DisplayName("路径形状花招不得把请求送进 handler（尾斜杠/多轮编码/反斜杠/;参数/大小写混写）")
    void pathShapeTricksNeverReachAnyHandler() throws Exception {
        List<Attempt> tricks = List.of(
                new Attempt("POST", "/auth/login/"),
                new Attempt("GET", "/user/me/"),
                new Attempt("GET", "/knowledge-base/"),
                new Attempt("POST", "/internal/ai/v1/runs/"),
                new Attempt("GET", "/auth/%2e%2e/login"),
                new Attempt("GET", "/auth/%252e%252e/login"),
                new Attempt("GET", "/auth\\login"),
                new Attempt("GET", "/user/me;jsessionid=1"),
                new Attempt("GET", "/user/me/."),
                new Attempt("GET", "/user/me/.."),
                new Attempt("GET", "/../auth/login"),
                new Attempt("POST", "/AUTH/LOGIN"),
                new Attempt("POST", "/Users"),
                new Attempt("GET", "/USER/ME"));

        for (Attempt trick : tricks) {
            assertClosed(trick.method(), trick.path(), null);
        }
        assertEquals(0, recorder.invocations(), "任何形状花招都不得进入 handler");
        assertTrue(recorder.observations().isEmpty());
    }

    @Test
    @DisplayName("OPTIONS 预检：204 + CORS 头，不执行 handler，也不代表业务动作可用")
    void preflightIsAnsweredWithoutRunningAnyHandler() throws Exception {
        for (String path : List.of("/auth/login", "/users", "/internal/ai/v1/runs", "/not-registered")) {
            MvcResult result = perform("OPTIONS", path, null)
                    .andExpect(status().isNoContent())
                    .andReturn();

            assertRequestIdHeader(result);
            assertHeaderContains(result, "Access-Control-Allow-Methods", "POST");
            assertHeaderContains(result, "Access-Control-Allow-Headers", "x-request-id");
            assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        }
        assertEquals(0, recorder.invocations(), "预检不得执行 handler");
    }

    @Test
    @DisplayName("分派类型：ASYNC/ERROR 放行，FORWARD 拒绝（否则异步收尾与错误页会被自身拦成 404）")
    void asyncAndErrorDispatchesPassWhileForwardIsRejected() throws Exception {
        perform("POST", "/auth/login", request -> request.setDispatcherType(DispatcherType.ASYNC))
                .andExpect(status().isOk())
                .andReturn();
        // 这里刻意不断言 X-Request-Id：真实容器里 ASYNC/ERROR 分派复用原始 REQUEST 分派的响应对象，
        // 该头已由 AiRequestIdFilter 在首次 REQUEST 分派写入；而 OncePerRequestFilter 按契约跳过
        // 异步/错误分派，本用例是一次性合成分派 + 全新响应对象，断言它等于在测 mock 而不是测产品
        assertEquals(1, recorder.invocations(), "ASYNC 是异步收尾分派，必须能走完链路");

        recorder.reset();

        // 真实容器在 ERROR 分派上必带 jakarta.servlet.error.request_uri（Servlet 规范），
        // OncePerRequestFilter 正是据此跳过本过滤器；补上该属性才是复现容器行为，
        // 而不是凭空造一个容器不会产生的"裸 ERROR"
        perform("POST", "/auth/login", request -> {
            request.setDispatcherType(DispatcherType.ERROR);
            request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, "/auth/login");
        })
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(1, recorder.invocations(), "ERROR 分派必须放行，否则错误页会被拦成递归 404");

        recorder.reset();

        // FORWARD 绕过客户端可见路径，一律拒绝
        assertClosed("POST", "/auth/login", request -> request.setDispatcherType(DispatcherType.FORWARD));
        assertEquals(0, recorder.invocations(), "FORWARD 分派不得执行 handler");
    }

    @Test
    @DisplayName("X-Request-Id：每个响应都有且合法；非法客户端值必须被替换而不是回显")
    void requestIdIsAlwaysValidAndUnsafeClientValuesAreReplaced() throws Exception {
        MvcResult generated = assertClosed("POST", "/auth/login", null);
        String generatedId = generated.getResponse().getHeader(AiRequestIdFilter.HEADER);
        assertTrue(generatedId.matches("[0-9a-f]{32}"), () -> "服务端应生成 32 位十六进制值，实际: " + generatedId);

        // 合法客户端值原样回显，才能跨服务串联同一条链路
        MvcResult echoed = assertClosed("POST", "/auth/login",
                request -> request.addHeader(AiRequestIdFilter.HEADER, "trace-abc-123"));
        assertEquals("trace-abc-123", echoed.getResponse().getHeader(AiRequestIdFilter.HEADER));

        // 非法值必须被替换：含空格/换行/超长的值一旦回显就是日志注入与下游关联污染
        for (String unsafe : List.of("bad id", "id;drop", "x".repeat(65), "line\nbreak")) {
            MvcResult result = assertClosed("POST", "/auth/login",
                    request -> request.addHeader(AiRequestIdFilter.HEADER, unsafe));
            String actual = result.getResponse().getHeader(AiRequestIdFilter.HEADER);
            assertNotEquals(unsafe, actual, () -> "非法 X-Request-Id 必须被替换: " + unsafe);
        }
        assertEquals(0, recorder.invocations());
    }

    /**
     * 断言"被入口边界拒绝"：404 + P04 关闭正文 + 合法 X-Request-Id。
     *
     * <p>不能只看状态码：本类替身 controller 全部注册着 handler，只看 404 无法区分
     * "边界拒绝"与"handler 自己返回 404"。
     */
    private MvcResult assertClosed(String method, String uri, Consumer<MockHttpServletRequest> customizer)
            throws Exception {
        MvcResult result = perform(method, uri, customizer)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.data.errorCode").value(CLOSED_ERROR_CODE))
                .andReturn();
        assertRequestIdHeader(result);
        return result;
    }

    /**
     * 手搓 {@link MockHttpServletRequest} 而不是用 {@code MockMvcRequestBuilders}：
     * 路径形状用例必须逐字节控制 requestURI，构建器会重新解析/编码 URI，
     * 那样测到的是构建器规范化后的形状，而不是过滤器真实收到的形状。
     */
    private ResultActions perform(String method, String uri, Consumer<MockHttpServletRequest> customizer)
            throws Exception {
        return mockMvc.perform(servletContext -> {
            MockHttpServletRequest request = new MockHttpServletRequest(servletContext, method, uri);
            if (customizer != null) {
                customizer.accept(request);
            }
            return request;
        });
    }

    private void assertHandlerReached(String method, String uri) throws Exception {
        MvcResult result = perform(method, uri, null)
                .andExpect(status().isOk())
                .andReturn();
        assertRequestIdHeader(result);
        // getContentAsString() 带受检异常，先取值再断言，避免把它塞进 lambda
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"reached\":\"" + uri + "\""), () -> "handler 应返回可识别标记，实际: " + body);
    }

    private static void assertRequestIdHeader(MvcResult result) {
        String requestId = result.getResponse().getHeader(AiRequestIdFilter.HEADER);
        assertNotNull(requestId, "每个响应（含被拒绝的）都必须带 X-Request-Id");
        assertFalse(requestId.isBlank(), "X-Request-Id 不得为空白");
        assertTrue(VALID_REQUEST_ID.matcher(requestId).matches(), () -> "X-Request-Id 形状非法: " + requestId);
    }

    private static void assertHeaderContains(MvcResult result, String name, String expected) {
        String value = result.getResponse().getHeader(name);
        assertNotNull(value, () -> "缺少响应头 " + name);
        assertTrue(value.contains(expected), () -> name + " 应包含 " + expected + "，实际: " + value);
    }

    /** 一条被期望拒绝的请求。 */
    private record Attempt(String method, String path) {
    }

    /** 计数协作方：handler 被调用才计数，并记下它能看到的身份字段与原始正文。 */
    static final class HandlerRecorder {

        private final AtomicInteger invocations = new AtomicInteger();
        private final List<String> observations = new CopyOnWriteArrayList<>();

        void recordInvocation(HttpServletRequest request) {
            invocations.incrementAndGet();
            observations.add(describe(request));
        }

        int invocations() {
            return invocations.get();
        }

        List<String> observations() {
            return List.copyOf(observations);
        }

        void reset() {
            invocations.set(0);
            observations.clear();
        }

        private static String describe(HttpServletRequest request) {
            return "uri=" + request.getRequestURI()
                    + " tenant=" + header(request, "X-Tenant")
                    + " user=" + header(request, "X-User")
                    + " credential=" + header(request, "X-P04-Service-Credential")
                    + " authorization=" + header(request, "Authorization")
                    + " body=" + body(request);
        }

        private static String header(HttpServletRequest request, String name) {
            String value = request.getHeader(name);
            return value == null ? "" : value;
        }

        private static String body(HttpServletRequest request) {
            try {
                return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "<unreadable:" + e.getClass().getSimpleName() + ">";
            }
        }
    }

    /**
     * 旧业务入口 + 探活入口的最小替身。
     *
     * <p>每条被测路径都注册 handler，且每个方法先把"自己看到了什么"记进 {@link HandlerRecorder} 再返回：
     * 只有 handler 真的存在，"被拒绝时 0 次执行"才是有信息量的断言。
     */
    @RestController
    static class LegacyEndpoint {

        private final HandlerRecorder recorder;

        LegacyEndpoint(HandlerRecorder recorder) {
            this.recorder = recorder;
        }

        @PostMapping("/auth/login")
        String login(HttpServletRequest request) {
            return reached(request);
        }

        @PostMapping("/auth/logout")
        String logout(HttpServletRequest request) {
            return reached(request);
        }

        @PostMapping("/users")
        String createUser(HttpServletRequest request) {
            return reached(request);
        }

        @GetMapping("/users")
        String pageUsers(HttpServletRequest request) {
            return reached(request);
        }

        @GetMapping("/user/me")
        String currentUser(HttpServletRequest request) {
            return reached(request);
        }

        @GetMapping("/knowledge-base")
        String knowledgeBase(HttpServletRequest request) {
            return reached(request);
        }

        @PostMapping("/internal/ai/v1/runs")
        String acceptRun(HttpServletRequest request) {
            return reached(request);
        }

        @PostMapping("/p04/control/reset")
        String resetP04(HttpServletRequest request) {
            return reached(request);
        }

        @GetMapping("/p04")
        String p04Root(HttpServletRequest request) {
            return reached(request);
        }

        @GetMapping("/p04/health")
        String p04Health(HttpServletRequest request) {
            return reached(request);
        }

        @GetMapping("/actuator/health")
        String actuatorHealth(HttpServletRequest request) {
            return reached(request);
        }

        @GetMapping("/health")
        String rootHealth(HttpServletRequest request) {
            return reached(request);
        }

        private String reached(HttpServletRequest request) {
            recorder.recordInvocation(request);
            return "{\"reached\":\"" + request.getRequestURI() + "\"}";
        }
    }
}
