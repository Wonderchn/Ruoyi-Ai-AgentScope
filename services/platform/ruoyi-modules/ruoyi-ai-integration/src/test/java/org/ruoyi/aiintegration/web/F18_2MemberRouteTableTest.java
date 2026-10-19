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
 * F18:2 网关白名单：成员端两条 mine 路由必须登记，且字面量必须排在 {@code {id}} 之前。
 *
 * <p>{@link AiGatewayController#matchRoute(String, String)} 是<b>声明序首个命中</b>：
 * 若 {@code GET /biz-change-logs/{id}} 先声明，两段字面量 {@code /biz-change-logs/mine}
 * 会被变量形吃掉 → 成员端请求被当成管理端详情。本测试同时钉住"在表"、"动作"、
 * "声明序"与"实际匹配结果"四件事。
 */
@Tag("dev")
class F18_2MemberRouteTableTest {

    @Test
    @DisplayName("两条 mine 路由在表且动作为 run.get")
    void memberRoutesAreRegisteredWithRunGetAction() throws Exception {
        List<RouteView> routes = routes();

        assertThat(routes)
                .as("成员端列表路由必须登记（action 复用 run.get，不新增 canonical 动作）")
                .anySatisfy(route -> {
                    assertThat(route.method()).isEqualTo("GET");
                    assertThat(route.pattern()).isEqualTo("/biz-change-logs/mine");
                    assertThat(route.action()).isEqualTo("run.get");
                });
        assertThat(routes)
                .as("成员端详情路由必须登记")
                .anySatisfy(route -> {
                    assertThat(route.method()).isEqualTo("GET");
                    assertThat(route.pattern()).isEqualTo("/biz-change-logs/mine/{id}");
                    assertThat(route.action()).isEqualTo("run.get");
                });
    }

    @Test
    @DisplayName("字面量 /biz-change-logs/mine 的声明序在 /biz-change-logs/{id} 之前")
    void literalMemberRoutePrecedesVariableDetailRoute() throws Exception {
        List<RouteView> routes = routes();
        int mine = indexOf(routes, "GET", "/biz-change-logs/mine");
        int variable = indexOf(routes, "GET", "/biz-change-logs/{id}");

        assertThat(mine).as("mine 字面量必须在白名单里").isGreaterThanOrEqualTo(0);
        assertThat(variable).as("管理端详情仍在白名单里").isGreaterThanOrEqualTo(0);
        assertThat(mine)
                .as("matchRoute 取声明序首个命中：%s 必须排在 %s 之前", "/biz-change-logs/mine", "/biz-change-logs/{id}")
                .isLessThan(variable);
    }

    @Test
    @DisplayName("实际匹配：mine 命中自己的路由，admin 详情仍命中 {id}")
    void matchRouteResolvesMemberAndAdminPathsSeparately() {
        Object mine = matchedRoute("GET", "/biz-change-logs/mine");
        assertThat(mine).as("两段字面量不得被 /biz-change-logs/{id} 遮蔽").isNotNull();
        assertThat(property(mine, "pattern")).isEqualTo("/biz-change-logs/mine");
        assertThat(property(mine, "action")).isEqualTo("run.get");

        Object mineDetail = matchedRoute("GET", "/biz-change-logs/mine/log-1");
        assertThat(mineDetail).isNotNull();
        assertThat(property(mineDetail, "pattern")).isEqualTo("/biz-change-logs/mine/{id}");
        assertThat(property(mineDetail, "action")).isEqualTo("run.get");

        Object adminDetail = matchedRoute("GET", "/biz-change-logs/log-1");
        assertThat(adminDetail).as("管理端详情语义不变").isNotNull();
        assertThat(property(adminDetail, "pattern")).isEqualTo("/biz-change-logs/{id}");
    }

    private static Object matchedRoute(String method, String path) {
        var matched = AiGatewayController.matchRoute(method, path);
        return matched.orElse(null);
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
            RouteView route = routes.get(i);
            if (route.method().equals(method) && route.pattern().equals(pattern)) {
                return i;
            }
        }
        return -1;
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
