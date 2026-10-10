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

package org.ruoyi.aiintegration.authorization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ai.api.action.AiCanonicalAction;
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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP-034A：AI 公开面的三处权威必须互相一致。
 *
 * <p>一个动作要真的能用，需要三样同时存在，而它们分散在三个地方：
 * <ol>
 *   <li>{@link AiCanonicalAction} 的规范动作 → 平台权限映射（{@code ruoyi-ai-api}）；</li>
 *   <li>{@code sys_menu} 的权限行（{@code docs/script/sql/postgres/V4/V5/V6/V12}）；</li>
 *   <li>网关白名单路由（{@link AiGatewayController} 的 {@code ROUTES}）。</li>
 * </ol>
 * 任何一处漏掉都会产生一种"看起来配好了、实际不可用"的状态：
 * 有路由没权限行 → 作用域永远拿不到该权限，端点恒 403；
 * 有权限行没动作 → 授予了一个谁都映射不到的动作；
 * 有动作没路由 → 声明了能力但没有公开面。
 *
 * <p>本判据把 1↔2 做成<b>双向相等</b>（集合必须完全一致），并把 3 的每个动作都要求
 * 落在 1 里。WP-034A 新增的两个会话写动作<b>刻意还没有路由</b>——那一条是显式登记的
 * 绊线：等 {@code ConversationController} 装配完成、路由真的放行时，必须同时改这里，
 * 不允许悄悄多出一条没人跟进的公开面。
 */
@Tag("dev")
class P1AiPermissionContractTest {

    private static final Path PLATFORM_SQL = Path.of("services", "platform", "docs", "script", "sql",
            "postgres");

    /** 权限行只在版本化迁移里声明；用 {@code menu_type='F'} 的行来定位。 */
    private static final Pattern PERMISSION_ROW = Pattern.compile("^\\('?\\d+'?,.*'F'.*$");
    private static final Pattern AI_PERMISSION = Pattern.compile("'(ai:[a-z:]+)'");

    @Test
    @DisplayName("规范动作 ↔ 权限行：两侧集合必须完全一致（双向）")
    void canonicalActionsAndPermissionRowsMatchExactly() throws IOException {
        Set<String> canonicalPermissions = new TreeSet<>();
        for (String action : AiCanonicalAction.knownActions()) {
            canonicalPermissions.add(AiCanonicalAction.permissionOf(action).orElseThrow());
        }
        Set<String> declared = declaredPermissions();

        assertThat(declared)
                .as("锚点：必须真的从迁移里读到权限行，否则本判据是空跑")
                .hasSizeGreaterThanOrEqualTo(25)
                .contains("ai:kb:list", "ai:run:submit", "ai:agent:execute", "ai:conversation:read");
        assertThat(canonicalPermissions)
                .as("锚点：规范动作表必须真的读到 25 个权限")
                .hasSizeGreaterThanOrEqualTo(25);

        assertThat(declared)
                .as("有路由/动作却没有可授予的权限行 → 作用域永远拿不到它，端点恒 403")
                .containsAll(canonicalPermissions);
        assertThat(canonicalPermissions)
                .as("声明了权限行却没有任何规范动作映射到它 → 授予了一个映射不到的动作")
                .containsAll(declared);
    }

    @Test
    @DisplayName("网关白名单的每个动作都必须是已知规范动作")
    void everyWhitelistedRouteUsesAKnownCanonicalAction() throws Exception {
        List<String> actions = whitelistedActions();
        assertThat(actions)
                .as("锚点：必须真的读到网关路由表")
                .hasSizeGreaterThanOrEqualTo(30)
                .contains("kb.list", "run.submit", "conversation.read");

        List<String> unknown = new ArrayList<>();
        for (String action : actions) {
            if (AiCanonicalAction.permissionOf(action).isEmpty()) {
                unknown.add(action);
            }
        }
        assertThat(unknown)
                .as("未知动作不会被默认放行，但让路由引用一个映射不到的动作本身就是配置错误")
                .isEmpty();
    }

    /**
     * WP-034B：这两个动作的路由已经放行，服务端由已装配的 {@code AiResourceController} 承接
     * （它本来就在 {@code /internal/ai/v1} 上服务会话读取，写入走同一个授权顺序）。
     * 原来的"绊线"（断言无路由）在放行时必须同时改这里，所以它已被替换为下面的正向断言。
     */
    @Test
    @DisplayName("WP-034B 的会话写动作：权限行已注册，路由已放行")
    void conversationWriteActionsAreRouted() throws Exception {
        assertThat(AiCanonicalAction.permissionOf("conversation.rename"))
                .contains("ai:conversation:write");
        assertThat(AiCanonicalAction.permissionOf("conversation.delete"))
                .contains("ai:conversation:delete");
        assertThat(declaredPermissions())
                .as("V12 必须真的注册了这两行权限")
                .contains("ai:conversation:write", "ai:conversation:delete");
        assertThat(whitelistedActions())
                .as("放行后网关白名单必须真的出现这两个动作")
                .contains("conversation.rename", "conversation.delete");
    }

    @Test
    @DisplayName("负例：未知动作一律没有权限，且网关要求权限时抛错而不是放行")
    void unknownActionsAreRejected() {
        assertThat(AiCanonicalAction.permissionOf("no-such-action")).isEmpty();
        assertThat(AiCanonicalAction.permissionOf("")).isEmpty();
        assertThat(AiCanonicalAction.permissionOf(null)).isEmpty();
        assertThatThrownBy(() -> AiActionRegistry.requirePermission("no-such-action"))
                .as("网关路径上未知动作必须抛错，不能返回 null 让调用方误以为无需权限")
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * WP-034：F10 Agent 会话面必须**逐条**出现在白名单里，且**不得**通配。
     *
     * <p>放行这批路由的意义是"客户端真的能经 {@code /api/ai/v1} 到达 WP-033B 交付的会话面"。
     * 之所以逐条断言而不是断言"至少有一个 /agent/v1 路由"：
     * 一条通配 {@code /agent/v1/**} 会同时满足后者，却把该前缀下任何将来新增的控制器
     * 一并放行——那等于取消白名单，正是计划 §7 明令禁止的形态。
     */
    @Test
    @DisplayName("WP-034 + F10-A1：F10 的 6 条 Agent 会话路由逐条放行，且不存在 /agent/v1/** 通配")
    void agentConversationRoutesAreWhitelistedIndividually() throws Exception {
        List<String> patterns = whitelistedPatterns();

        assertThat(patterns)
                .as("六条会话路由必须逐条登记（列表/消息/新建/改名/单删/批量删除）")
                .contains("GET /agent/v1/conversations",
                        "GET /agent/v1/conversations/{id}/messages",
                        "POST /agent/v1/conversations",
                        "PUT /agent/v1/conversations/{id}/title",
                        "DELETE /agent/v1/conversations/{id}",
                        "POST /agent/v1/conversations/batch-delete");
        assertThat(patterns)
                .as("禁止开放通配 /agent/v1/**：那会把同前缀下任何新增控制器一起放行")
                .noneMatch(pattern -> pattern.contains("/agent/v1/**")
                        || pattern.endsWith("/agent/**") || pattern.contains("**"));
        // RW-01（T0 集成，2026-10-07）：原口径"批量删除的多资源授权是计划 §13 待决定项"。
        // D05 已作出决定（上限 100、整体授权、整体事务；服务端契约与负例已交付），
        // 故 F03 的 POST /conversations/batch-delete 必须**逐条**放行；仍然禁止用通配替代。
        assertThat(patterns)
                .as("F03 批量删除逐条放行（D05 已决定），且不得用通配替代")
                .contains("POST /conversations/batch-delete")
                .noneMatch(pattern -> pattern.contains("/conversations/**"));
        // F10-A1（2026-10-10）：Agent 路径按同一 D05 先例放行（镜像 general 路径，
        // 复用既有 conversation.delete 动作与受控服务契约）；仍必须逐条登记、不得通配。
        assertThat(patterns)
                .as("Agent 侧批量删除（F10-A1）逐条放行：镜像 F03 general 路径的 D05 口径")
                .contains("POST /agent/v1/conversations/batch-delete")
                .noneMatch(pattern -> pattern.contains("/agent/v1/conversations/**"));
        // 动作必须是已有规范动作：不新增权限行（Agent 会话与普通会话是同一"用户自己的会话"语义）
        assertThat(whitelistedActions())
                .as("四条路由分别绑定会话读/改名/删除动作")
                .containsSubsequence("conversation.read", "conversation.rename", "conversation.delete");
    }

    // ------------------------------------------------------------------ helpers

    /** 反射读网关的私有路由表：白名单是运行期固定表，测试要枚举它而不是抄一份。 */
    private static List<String> whitelistedActions() throws Exception {
        Field field = AiGatewayController.class.getDeclaredField("ROUTES");
        field.setAccessible(true);
        List<?> routes = (List<?>) field.get(null);
        List<String> actions = new ArrayList<>();
        for (Object route : routes) {
            Method action = route.getClass().getDeclaredMethod("action");
            action.setAccessible(true);
            actions.add((String) action.invoke(route));
        }
        return actions;
    }

    /** 同上，取 {@code method + " " + pattern} 形态，用于"逐条放行 + 禁止通配"的断言。 */
    private static List<String> whitelistedPatterns() throws Exception {
        Field field = AiGatewayController.class.getDeclaredField("ROUTES");
        field.setAccessible(true);
        List<?> routes = (List<?>) field.get(null);
        List<String> patterns = new ArrayList<>();
        for (Object route : routes) {
            Method method = route.getClass().getDeclaredMethod("method");
            method.setAccessible(true);
            Method pattern = route.getClass().getDeclaredMethod("pattern");
            pattern.setAccessible(true);
            patterns.add(method.invoke(route) + " " + pattern.invoke(route));
        }
        return patterns;
    }

    /** 从版本化迁移里读 {@code sys_menu} 的 {@code ai:*} 权限行。 */
    private static Set<String> declaredPermissions() throws IOException {
        Path sqlDir = locate(PLATFORM_SQL);
        Set<String> out = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.list(sqlDir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted().toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String stripped = line.strip();
                    if (stripped.startsWith("--") || !PERMISSION_ROW.matcher(stripped).matches()) {
                        continue;
                    }
                    Matcher m = AI_PERMISSION.matcher(stripped);
                    while (m.find()) {
                        out.add(m.group(1));
                    }
                }
            }
        }
        return out;
    }

    private static Path locate(Path relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate " + relative + " from " + Path.of("").toAbsolutePath());
    }
}
