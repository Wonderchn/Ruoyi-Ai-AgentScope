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

package com.nageoffer.ai.ragent.knowledge.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RW-04-R2：V29 迁移的**静态护栏**。
 *
 * <p><b>为什么是静态判据。</b>本卡没有真库（真库迁移执行由 T8/RW-26 在专属窗口复核，记 NOT_RUN），
 * 所以能在这里钉住的是"这份脚本本身不可能做出危险动作、并且可重复执行"：
 * 幂等 DDL、既有行回填、后置校验、只碰一张表、无破坏性语句。
 * 这不能替代真库执行证据，只能防止"脚本写错方向"这一类事故。
 *
 * <p>V29 编号由 T0 现查后分配（V21/V25/V26 空缺未占用，平台侧已用至 V28）；
 * 冻结迁移 V7/V9/V10/V11 只读，本卡未改任何既有迁移文件（逐文件 SHA256 见
 * {@code .scratch/remaining-agent-handoff-20261007/RW-04-R2/frozen-migration-hashes.txt}）。
 */
@Tag("dev")
class KnowledgeDocumentVersionMigrationTest {

    /** 模块目录是 services/platform/ruoyi-modules/ruoyi-ai-rag ⇒ 上两级即 services/platform。 */
    private static final Path MIGRATION = Path.of("..", "..", "docs", "script", "sql", "postgres",
            "V29__knowledge_document_version_counter.sql").toAbsolutePath().normalize();

    private static final String TABLE = "platform.ai_knowledge_document";

    private String sql() throws IOException {
        assertTrue(Files.exists(MIGRATION), "缺少 V29 迁移文件: " + MIGRATION);
        return Files.readString(MIGRATION);
    }

    @Test
    @DisplayName("V29 是幂等的加列 + 回填 + 收敛，并且带后置校验")
    void migrationIsIdempotentAndSelfVerifying() throws IOException {
        String sql = sql();

        // 加列的幂等形式：显式存在性判断（不是 ADD COLUMN IF NOT EXISTS，原因见迁移注释
        // 与 P1MergedAppendColumnRegistryGuardTest 的 V9 口径耦合）
        assertTrue(Pattern.compile(
                        "IF\\s+NOT\\s+EXISTS\\s*\\(\\s*SELECT\\s+1\\s+FROM\\s+information_schema\\.columns",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql).find(),
                "加列必须先做存在性判断，否则重跑会报 duplicate column");
        assertTrue(Pattern.compile("ALTER\\s+TABLE\\s+" + Pattern.quote(TABLE)
                        + "\\s+ADD\\s+COLUMN\\s+version\\s+bigint",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql).find(),
                "缺加列语句（version bigint）");
        // 只在**可执行 SQL** 上判：注释里解释"为什么不用 IF NOT EXISTS"是正当的溯源说明
        assertFalse(Pattern.compile("ADD\\s+COLUMN\\s+IF\\s+NOT\\s+EXISTS", Pattern.CASE_INSENSITIVE)
                        .matcher(stripComments(sql)).find(),
                "可执行 SQL 里不得用 ADD COLUMN IF NOT EXISTS：它会被追加列登记护栏当作冻结 V9 口径的追加列");

        assertTrue(Pattern.compile("UPDATE\\s+" + Pattern.quote(TABLE)
                        + "\\s+SET\\s+version\\s*=\\s*0\\s+WHERE\\s+version\\s+IS\\s+NULL",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql).find(),
                "必须有既有行回填（WHERE version IS NULL），否则重跑时会改写已递增的计数器");
        assertTrue(sql.contains("ALTER COLUMN version SET DEFAULT 0"), "默认值必须收敛为 0");
        assertTrue(sql.contains("ALTER COLUMN version SET NOT NULL"), "必须收敛为 NOT NULL");

        // 约束先 DROP IF EXISTS 再 ADD：重复执行不会因"约束已存在"失败
        assertTrue(sql.contains("DROP CONSTRAINT IF EXISTS ck_knowledge_document_version_nonneg"),
                "约束必须可重复建立（先 DROP IF EXISTS）");
        assertTrue(sql.contains("ADD CONSTRAINT ck_knowledge_document_version_nonneg"),
                "缺非负约束：计数器允许负数等于允许版本回退");

        // 后置校验：形状不对就整体失败，不留"看起来迁移过了"的库
        assertTrue(sql.contains("RAISE EXCEPTION"), "后置校验必须能失败（只有断言的校验等于没有）");
        assertTrue(sql.contains("WHERE version IS NULL"), "后置校验必须查 NULL 残留行");
        assertTrue(sql.contains("ck_knowledge_document_version_nonneg"), "后置校验必须复核约束存在");
    }

    @Test
    @DisplayName("V29 只碰 ai_knowledge_document 一张表，且不含破坏性语句")
    void migrationOnlyTouchesTheDocumentTableAndIsNotDestructive() throws IOException {
        String sql = sql();

        Matcher tables = Pattern.compile("platform\\.([a-z_]+)", Pattern.CASE_INSENSITIVE).matcher(sql);
        List<String> touched = new ArrayList<>();
        while (tables.find()) {
            String name = tables.group(1).toLowerCase();
            if (!touched.contains(name)) {
                touched.add(name);
            }
        }
        assertTrue(touched.equals(List.of("ai_knowledge_document")),
                "V29 只应触及 platform.ai_knowledge_document，实际触及：" + touched);

        String upper = sql.toUpperCase();
        for (String forbidden : List.of("DROP TABLE", "DROP COLUMN", "TRUNCATE", "DELETE FROM", "ALTER TABLE SYS_",
                "INSERT INTO SYS_", "UPDATE SYS_")) {
            assertFalse(upper.contains(forbidden),
                    "迁移里不得出现破坏性或越界语句：" + forbidden);
        }
    }

    @Test
    @DisplayName("V29 不改冻结迁移：可执行 SQL 里不引用、也不重定义既有版本号")
    void migrationDoesNotRewriteFrozenMigrations() throws IOException {
        // 只看**去掉注释**后的可执行 SQL：注释里写"表名以冻结 V7__… 的实际定义为准"是正当的
        // 溯源说明，不是"改冻结迁移"。判据要盯的是有没有对冻结对象的可执行动作。
        String executable = stripComments(sql());

        for (String frozen : List.of("V7__", "V9__", "V10__", "V11__")) {
            assertFalse(executable.contains(frozen),
                    "V29 的可执行 SQL 不得引用/改写冻结迁移：" + frozen);
        }
        assertFalse(Pattern.compile("\\bCREATE\\s+TABLE\\b", Pattern.CASE_INSENSITIVE).matcher(executable).find(),
                "V29 只加列，不建表（建表说明走错了口径）");
        assertFalse(Pattern.compile("\\bALTER\\s+TABLE\\s+platform\\.(?!ai_knowledge_document)",
                        Pattern.CASE_INSENSITIVE).matcher(executable).find(),
                "V29 的 ALTER TABLE 只允许落在 platform.ai_knowledge_document");
    }

    /** 去掉 {@code --} 行注释与 {@code /* *}{@code /} 块注释，只留可执行 SQL。 */
    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)--[^\\n]*", " ");
    }
}
