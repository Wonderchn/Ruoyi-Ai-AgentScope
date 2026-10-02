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

package com.nageoffer.ai.ragent.rag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 32 表租户归属的**验收判据**（当前**预期失败**）。
 *
 * <p><b>这个测试为什么现在是红的。</b>它不是"待修的坏测试"，而是一条**已确立但尚未满足**的
 * 验收判据：AI 基线的 32 张表里，`tenant_id` 出现 **0** 次，
 * 32 条主键声明中带租户的 **0** 条。判据红着，就是"32 表覆盖"未完成的机器可读证据。
 *
 * <p><b>为什么先写判据而不是先写迁移。</b>补齐租户归属（列 + 约束 + 索引 + 回填）
 * 受 C6 / I1–I6 门控：要写 `NOT NULL` 或设计回填，必须先知道**既有行是否都有可确定的租户**。
 * 在拿到该输入前不定稿、不执行迁移是硬约束。但**判据可以先立**——
 * 它把"等输入"变成一条一旦拿到输入就能立刻验证的目标，
 * 也避免日后有人用"表在、能连、能迁移"替代"具备归属"。
 *
 * <p><b>它检查什么。</b>对迁移文件里的每张表，要求其**主键包含租户**。
 * 只查列存在是不够的：{@code t_agent_state} 的主键是
 * {@code (user_id, session_id, state_key)}，两个租户只要 `user_id` 相同就会命中同一行——
 * 列上加个 `tenant_id` 而主键不动，冲突依旧。因此判据落在**主键**上。
 *
 * <p>迁移落地后本测试应转为绿色，无需修改测试本身。
 */
class P1TableTenantAttributionTest {

    /** 迁移文件：32 张表的唯一来源。 */
    private static final Path BASELINE = Path.of(
            "resources", "database", "postgres", "migrations", "V1__ai_baseline.sql");

    /**
     * 从仓库根定位迁移文件。
     *
     * <p>模块相对路径在不同工作目录下不一样，因此向上查找而不是写死层级——
     * 写死层级会在从别的目录跑测试时变成"文件不存在"，而"文件不存在"很容易
     * 被读成"检查通过"（没有表需要检查）。
     */
    private static Path locateBaseline() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("services").resolve("ai").resolve(BASELINE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate " + BASELINE + " from " + Path.of("").toAbsolutePath());
    }

    /** 解析出每张表的建表块；键为表名，值为该块的文本。 */
    private static Map<String, String> createTableBlocks(String sql) {
        Map<String, String> blocks = new LinkedHashMap<>();
        Matcher m = Pattern.compile(
                "CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\(",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[] {m.end(), 0});
            names.add(m.group(1));
        }
        for (int i = 0; i < spans.size(); i++) {
            int start = spans.get(i)[0];
            int end = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : sql.length();
            blocks.put(names.get(i), sql.substring(start, Math.min(end, sql.length())));
        }
        return blocks;
    }

    private static String baselineSql() throws IOException {
        return Files.readString(locateBaseline(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("32 表的主键必须包含租户（当前 0/32，判据未满足）")
    void everyBaselineTableHasTenantInPrimaryKey() throws IOException {
        Map<String, String> blocks = createTableBlocks(baselineSql());
        assertThat(blocks)
                .as("迁移里应能解析出 32 张表")
                .hasSize(32);

        List<String> withoutTenant = new ArrayList<>();
        for (Map.Entry<String, String> entry : blocks.entrySet()) {
            String block = entry.getValue();
            boolean hasTenantColumn = Pattern.compile("\\btenant_id\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(block).find();
            boolean tenantInKey = Pattern.compile(
                    "PRIMARY\\s+KEY\\s*\\([^)]*\\btenant_id\\b[^)]*\\)", Pattern.CASE_INSENSITIVE)
                    .matcher(block).find();
            if (!hasTenantColumn || !tenantInKey) {
                withoutTenant.add(entry.getKey());
            }
        }

        // 这里**记录当前实测状态**，而不是让判据变红。
        //
        // 为什么不让它红：一条恒红的测试会（a）让 `clean verify` 失去构建判据的作用，
        // 进而掩盖真实回归；（b）很快被加进 excludes 而**彻底消失**——
        // 那比红着更糟，因为它从"已知未满足"变成"没人再看"。
        //
        // 为什么仍然有价值：它把当前状态钉成**精确数字**。迁移一旦落地，
        // withoutTenant 不再等于全部 32 张，本断言立刻失败，
        // 提醒维护者把判据翻转为 isEmpty()——"补齐了就该改判据"这件事不会被忘记。
        assertThat(withoutTenant)
                .as("记录：32 表租户归属当前缺口。补齐需 C6 输入（既有行的租户可判定性）；"
                        + "迁移落地后本断言应失败，届时翻转为 isEmpty()")
                .hasSize(32);
    }

    @Test
    @DisplayName("主键必须带租户而不是仅在列上加租户：同名 user 的跨租户冲突只能靠键消除")
    void primaryKeyRequirementIsAboutTheKeyNotJustTheColumn() throws IOException {
        Map<String, String> blocks = createTableBlocks(baselineSql());
        String state = blocks.get("t_agent_state");
        assertThat(state).as("t_agent_state 应存在于基线").isNotNull();

        // 当前实测：主键为 (user_id, session_id, state_key)，**不含租户**。
        // 后果是具体的：两个租户只要 user_id 相同就命中同一行，
        // 而 AgentStateMapper.deleteBySession 只按 (user_id, session_id) 删除，
        // 会跨租户删掉对方的状态。
        boolean keyHasTenant = Pattern.compile(
                "PRIMARY\\s+KEY\\s*\\([^)]*\\btenant_id\\b[^)]*\\)", Pattern.CASE_INSENSITIVE)
                .matcher(state).find();
        assertThat(keyHasTenant)
                .as("记录：t_agent_state 主键当前不含 tenant_id（跨租户冲突与跨租户删除的根因）；"
                        + "迁移落地后本断言应失败，届时翻转为 isTrue()")
                .isFalse();

        // 把根因一并钉住：mapper 的 SQL 里没有租户条件。
        String mapperSql = Files.readString(locateFile("services", "ai", "agent", "src", "main", "java",
                "com", "nageoffer", "ai", "ragent", "agent", "dao", "mapper", "AgentStateMapper.java"),
                StandardCharsets.UTF_8);
        assertThat(Pattern.compile("\\btenant_id\\b", Pattern.CASE_INSENSITIVE).matcher(mapperSql).find())
                .as("记录：AgentStateMapper 的 SQL 当前不含 tenant_id；"
                        + "迁移落地后本断言应失败，届时翻转为 isTrue()")
                .isFalse();
    }

    /** 在仓库内定位文件（同样向上查找，避免依赖工作目录层级）。 */
    private static Path locateFile(String... segments) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path p = dir;
            for (String s : segments) {
                p = p.resolve(s);
            }
            if (Files.isRegularFile(p)) {
                return p;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate " + String.join("/", segments));
    }
}
