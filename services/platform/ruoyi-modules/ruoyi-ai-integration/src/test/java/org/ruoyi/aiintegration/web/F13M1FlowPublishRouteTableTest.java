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

package org.ruoyi.aiintegration.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R17 F13-M1 网关白名单：发布/公开面六条路由的登记、动作与声明序。
 *
 * <p>{@link AiGatewayController#matchRoute(String, String)} 取<b>声明序首个命中</b>：
 * 四条 {@code /flows/public/**} 必须排在 {@code GET /flows/{uuid}} 与
 * {@code GET /flows/{uuid}/nodes} 之前；否则 {@code public} 会被 {@code {uuid}} 吃掉，
 * 公开面被静默改写成「我的工作流详情 / 图读」语义。
 *
 * <p>动作映射（本批唯一新动作 {@code flow.publish} → {@code ai:flow:publish}）：
 * <ul>
 *   <li>两条写面（enabled / public）→ {@code flow.publish}；</li>
 *   <li>公开搜索 → {@code flow.list}；公开详情 → {@code flow.read}；</li>
 *   <li>操作符 {@code flow.list}；公开组件库 {@code flow.read}。</li>
 * </ul>
 */
@Tag("dev")
class F13M1FlowPublishRouteTableTest {

    private static final String PUBLIC_SEARCH = "GET /flows/public/search";
    private static final String PUBLIC_OPERATORS = "GET /flows/public/operators";
    private static final String PUBLIC_COMPONENTS = "GET /flows/public/component/list";
    private static final String PUBLIC_DETAIL = "GET /flows/public/{uuid}";
    private static final String ENABLED = "POST /flows/{uuid}/enabled";
    private static final String SET_PUBLIC = "POST /flows/{uuid}/public";

    @Test
    @DisplayName("六条发布/公开路由逐条登记，动作分别为 flow.list / flow.read / flow.publish")
    void publishAndPublicRoutesAreRegisteredWithExpectedActions() throws Exception {
        List<RouteView> routes = routes();

        assertRoute(routes, PUBLIC_SEARCH, "flow.list");
        assertRoute(routes, PUBLIC_OPERATORS, "flow.list");
        assertRoute(routes, PUBLIC_COMPONENTS, "flow.read");
        assertRoute(routes, PUBLIC_DETAIL, "flow.read");
        assertRoute(routes, ENABLED, "flow.publish");
        assertRoute(routes, SET_PUBLIC, "flow.publish");
    }

    @Test
    @DisplayName("公开字面量/公开详情必须排在既有 GET /flows/{uuid} 与 /flows/{uuid}/nodes 之前")
    void publicRoutesPrecedeVariableFlowRoutes() throws Exception {
        List<RouteView> routes = routes();
        int detailVariable = indexOf(routes, "GET", "/flows/{uuid}");
        int nodesVariable = indexOf(routes, "GET", "/flows/{uuid}/nodes");

        assertThat(detailVariable).as("既有 GET /flows/{uuid} 必须仍在表里").isGreaterThanOrEqualTo(0);
        assertThat(nodesVariable).as("既有 GET /flows/{uuid}/nodes 必须仍在表里").isGreaterThanOrEqualTo(0);

        for (String literal : List.of(PUBLIC_SEARCH, PUBLIC_OPERATORS, PUBLIC_COMPONENTS, PUBLIC_DETAIL)) {
            int index = indexOf(routes, literal);
            assertThat(index).as(literal + " 未登记").isGreaterThanOrEqualTo(0);
            assertThat(index)
                    .as("%s 排在 GET /flows/{uuid} 之后会被其遮蔽", literal)
                    .isLessThan(detailVariable);
            assertThat(index)
                    .as("%s 排在 GET /flows/{uuid}/nodes 之后会被其遮蔽", literal)
                    .isLessThan(nodesVariable);
        }
    }

    @Test
    @DisplayName("实际匹配：public 字面量先命中，真实 uuid 仍落到既有语义")
    void matchRouteResolvesPublicPathsBeforeVariableShapes() {
        assertMatch(PUBLIC_SEARCH, "/flows/public/search", "flow.list");
        assertMatch(PUBLIC_OPERATORS, "/flows/public/operators", "flow.list");
        assertMatch(PUBLIC_COMPONENTS, "/flows/public/component/list", "flow.read");
        assertMatch(PUBLIC_DETAIL.replace("{uuid}", "wf-public-1"),
                "/flows/public/{uuid}", "flow.read");
        assertMatch("GET /flows/wf-own-1", "/flows/{uuid}", "flow.read");
        assertMatch("GET /flows/wf-own-1/nodes", "/flows/{uuid}/nodes", "flow.read");
        assertMatch(ENABLED.replace("{uuid}", "wf-own-1"), "/flows/{uuid}/enabled", "flow.publish");
        assertMatch(SET_PUBLIC.replace("{uuid}", "wf-own-1"), "/flows/{uuid}/public", "flow.publish");
    }

    // ------------------------------------------------------------------ helpers

    private static void assertRoute(List<RouteView> routes, String key, String action) {
        List<RouteView> matching = routes.stream()
                .filter(route -> (route.method() + " " + route.pattern()).equals(key))
                .toList();
        assertThat(matching).as("%s 必须逐条登记（恰好一次）", key).hasSize(1);
        assertThat(matching.get(0).action()).as("%s 的动作", key).isEqualTo(action);
    }

    private static void assertMatch(String key, String pattern, String action) {
        String[] parts = key.split(" ", 2);
        Object matched = AiGatewayController.matchRoute(parts[0], parts[1]).orElse(null);
        assertThat(matched).as("%s 必须能命中路由", key).isNotNull();
        assertThat(property(matched, "pattern")).isEqualTo(pattern);
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

    private static int indexOf(List<RouteView> routes, String method, String pattern) {
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).method().equals(method) && routes.get(i).pattern().equals(pattern)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(List<RouteView> routes, String key) {
        String[] parts = key.split(" ", 2);
        return indexOf(routes, parts[0], parts[1]);
    }

    private static Object property(Object route, String accessor) {
        try {
            Method method = route.getClass().getDeclaredMethod(accessor);
            method.setAccessible(true);
            return method.invoke(route);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read route." + accessor, e);
        }
    }
}
