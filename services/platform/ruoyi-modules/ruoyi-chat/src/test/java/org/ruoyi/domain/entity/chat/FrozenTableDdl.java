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

package org.ruoyi.domain.entity.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 测试用：从**冻结迁移**里抽出某张表的真实 DDL（建表 + 后续 ALTER）。
 *
 * <p>真库判据必须执行冻结形状本身，而不是测试里手写的 {@code CREATE TABLE}——
 * 手写一份立刻产生"测试通过、生产列不同"的分叉。表加列（V9/V10/…）也自动跟上，
 * 因为针对该表的 ALTER 是一起抽出来的。
 *
 * <p>本模块（{@code ruoyi-chat}）此前把这份逻辑内联在单个测试类里；出现第二个用例后
 * 提到这里共享，避免同一段解析出现第三份副本。
 */
final class FrozenTableDdl {

    private FrozenTableDdl() {
    }

    /** @return 按版本顺序排列、可直接执行的 DDL 语句 */
    static List<String> ddlOf(String table) throws IOException {
        Path sqlDir = locate(Path.of("services", "platform", "docs", "script", "sql", "postgres"));
        List<Path> files;
        try (Stream<Path> stream = Files.list(sqlDir)) {
            files = stream.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted((a, b) -> Integer.compare(versionOf(a), versionOf(b)))
                    .toList();
        }
        Pattern create = Pattern.compile(
                "CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+platform\\." + table + "\\s*\\([^;]*?\\)\\s*;",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Pattern alter = Pattern.compile("ALTER\\s+TABLE\\s+platform\\." + table + "\\s+[^;]*;",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        List<String> out = new ArrayList<>();
        for (Path file : files) {
            String sql = Files.readString(file, StandardCharsets.UTF_8);
            Matcher c = create.matcher(sql);
            if (c.find()) {
                out.add(c.group());
            }
            Matcher a = alter.matcher(sql);
            while (a.find()) {
                out.add(a.group());
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("冻结迁移里找不到 " + table + " 的 DDL");
        }
        return out;
    }

    static Path locate(Path relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate " + relative);
    }

    private static int versionOf(Path p) {
        Matcher m = Pattern.compile("^V(\\d+)__").matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
