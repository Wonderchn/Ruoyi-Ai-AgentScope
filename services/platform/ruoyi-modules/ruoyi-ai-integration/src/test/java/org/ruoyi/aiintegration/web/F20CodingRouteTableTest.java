package org.ruoyi.aiintegration.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20-A：编程助手公开面逐条登记。22 条 JSON 路由进 {@code AiGatewayController.ROUTES}；
 * 两条 SSE（{@code /coding/chat}、{@code .../events/stream}）由
 * {@code AiGatewayStreamController} 精确映射、刻意不放进 ROUTES（避免不可达死路由）。
 */
@Tag("dev")
class F20CodingRouteTableTest {

    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("POST /coding/sessions", "coding.write");
        EXPECTED.put("GET /coding/sessions", "coding.use");
        EXPECTED.put("GET /coding/sessions/{sessionId}", "coding.use");
        EXPECTED.put("PUT /coding/sessions/{sessionId}/pin", "coding.write");
        EXPECTED.put("DELETE /coding/sessions/{sessionId}", "coding.write");
        EXPECTED.put("POST /coding/sessions/{sessionId}/restore", "coding.write");
        EXPECTED.put("GET /coding/sessions/{sessionId}/messages", "coding.use");
        EXPECTED.put("POST /coding/sessions/{sessionId}/runs", "coding.write");
        EXPECTED.put("GET /coding/sessions/{sessionId}/runs", "coding.use");
        EXPECTED.put("GET /coding/sessions/{sessionId}/runs/{runId}", "coding.use");
        EXPECTED.put("GET /coding/sessions/{sessionId}/runs/{runId}/events", "coding.use");
        EXPECTED.put("POST /coding/sessions/{sessionId}/runs/{runId}/inputs", "coding.write");
        EXPECTED.put("POST /coding/sessions/{sessionId}/runs/{runId}/cancel", "coding.write");
        EXPECTED.put("POST /coding/sessions/{sessionId}/runs/{runId}/resume", "coding.write");
        EXPECTED.put("POST /coding/sessions/{sessionId}/runs/{runId}/approvals/{approvalId}/resolve",
                "coding.approve");
        EXPECTED.put("POST /coding/sessions/{sessionId}/runs/{runId}/plan/approve", "coding.approve");
        EXPECTED.put("POST /coding/sessions/{sessionId}/runs/{runId}/plan/revision", "coding.approve");
        EXPECTED.put("GET /coding/models", "coding.use");
        EXPECTED.put("GET /coding/workspace", "coding.use");
        EXPECTED.put("GET /coding/file", "coding.use");
        EXPECTED.put("PUT /coding/file", "coding.write");
        EXPECTED.put("POST /coding/command", "coding.command");
    }

    @Test
    @DisplayName("22 条 JSON 路由全部在表且动作映射正确")
    void everyJsonRouteIsRegisteredWithExpectedAction() throws Exception {
        List<RouteView> routes = routes();
        for (Map.Entry<String, String> expected : EXPECTED.entrySet()) {
            String[] parts = expected.getKey().split(" ", 2);
            assertThat(routes)
                    .as("缺少路由 %s", expected.getKey())
                    .anySatisfy(route -> {
                        assertThat(route.method()).isEqualTo(parts[0]);
                        assertThat(route.pattern()).isEqualTo(parts[1]);
                        assertThat(route.action()).isEqualTo(expected.getValue());
                    });
        }
        assertThat(routes)
                .as("不得出现通配 /coding/**")
                .noneMatch(route -> route.pattern().contains("**"));
    }

    @Test
    @DisplayName("SSE 路由只在专用流控制器精确映射，不进入 ROUTES")
    void sseRoutesAreStreamOnly() throws Exception {
        List<RouteView> routes = routes();
        assertThat(routes).noneMatch(route -> route.pattern().equals("/coding/chat"));
        assertThat(routes).noneMatch(route -> route.pattern().endsWith("/events/stream"));

        Method chat = AiGatewayStreamController.class.getDeclaredMethod("codingChat",
                jakarta.servlet.http.HttpServletRequest.class, jakarta.servlet.http.HttpServletResponse.class);
        assertThat(chat.getAnnotation(PostMapping.class).value()).containsExactly("/coding/chat");

        Method stream = AiGatewayStreamController.class.getDeclaredMethod("codingEvents",
                String.class, String.class, jakarta.servlet.http.HttpServletRequest.class,
                jakarta.servlet.http.HttpServletResponse.class);
        assertThat(stream.getAnnotation(GetMapping.class).value())
                .containsExactly("/coding/sessions/{sessionId}/runs/{runId}/events/stream");
    }

    @Test
    @DisplayName("实际 matchRoute：会话/运行/审批/命令逐条命中自己的路由")
    void matchRouteResolvesCodingPaths() {
        assertMatch("POST", "/coding/sessions", "coding.write");
        assertMatch("GET", "/coding/sessions/s1/runs/r1/events", "coding.use");
        assertMatch("POST", "/coding/sessions/s1/runs/r1/approvals/a1/resolve", "coding.approve");
        assertMatch("POST", "/coding/sessions/s1/runs/r1/plan/approve", "coding.approve");
        assertMatch("POST", "/coding/command", "coding.command");
        assertMatch("PUT", "/coding/file", "coding.write");
    }

    private static void assertMatch(String method, String path, String action) {
        Object matched = AiGatewayController.matchRoute(method, path).orElse(null);
        assertThat(matched).as("路径 %s %s 必须命中", method, path).isNotNull();
        assertThat(property(matched, "action")).isEqualTo(action);
    }

    private record RouteView(String method, String pattern, String action) {
    }

    @SuppressWarnings("unchecked")
    private static List<RouteView> routes() throws Exception {
        Field field = AiGatewayController.class.getDeclaredField("ROUTES");
        field.setAccessible(true);
        List<Object> raw = (List<Object>) field.get(null);
        List<RouteView> routes = new ArrayList<>();
        for (Object route : raw) {
            routes.add(new RouteView(String.valueOf(property(route, "method")),
                    String.valueOf(property(route, "pattern")),
                    String.valueOf(property(route, "action"))));
        }
        return routes;
    }

    private static Object property(Object target, String name) {
        try {
            Method method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
