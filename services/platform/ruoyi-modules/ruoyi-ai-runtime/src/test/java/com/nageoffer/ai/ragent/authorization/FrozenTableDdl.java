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

package com.nageoffer.ai.ragent.authorization;

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
 * 测试用：从**冻结迁移**里抽出某张表的真实 DDL（建表 + 后续 ALTER），供真库测试执行。
 *
 * <p>为什么坚持解析而不是在测试里手写 {@code CREATE TABLE}：手写一份立刻产生
 * "测试通过、生产列不同"的分叉——被测的必须是冻结形状本身。表加列（V9/V10/…）也自动跟上，
 * 因为 ALTER 是一起抽出来的。
 */
final class FrozenTableDdl {

    private FrozenTableDdl() {
    }

    /** @return 按版本顺序排列的可直接执行的 DDL 语句 */
    static List<String> forTable(String table) throws IOException {
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
        Pattern alter = Pattern.compile(
                "ALTER\\s+TABLE\\s+platform\\." + table + "\\s+[^;]*;",
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

    /**
     * 某张表在冻结形状里的**列名**集合（建表列 + 后续 {@code ADD COLUMN}）。
     *
     * <p>给不需要数据库的构建期护栏用：它只需要"冻结形状有哪些列"，
     * 不需要真的把表建起来。
     */
    static java.util.Set<String> columnNamesOf(String table) throws IOException {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String ddl : forTable(table)) {
            Matcher create = Pattern.compile(
                    "CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+platform\\." + table + "\\s*\\((.*)\\)\\s*;",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(ddl);
            if (create.matches()) {
                for (String def : splitTopLevel(create.group(1))) {
                    String d = def.strip();
                    if (d.isEmpty() || d.startsWith("--")) {
                        continue;
                    }
                    if (d.matches("(?is)^(PRIMARY|UNIQUE|FOREIGN|CONSTRAINT|CHECK|KEY|INDEX|EXCLUDE)\\b.*")) {
                        continue;
                    }
                    Matcher col = Pattern.compile("^\"?([a-zA-Z_0-9]+)\"?\\s+\\S").matcher(d);
                    if (col.find()) {
                        out.add(col.group(1).toLowerCase(java.util.Locale.ROOT));
                    }
                }
                continue;
            }
            Matcher add = Pattern.compile(
                    "ADD\\s+COLUMN(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+\"?([a-zA-Z_0-9]+)\"?",
                    Pattern.CASE_INSENSITIVE).matcher(ddl);
            while (add.find()) {
                out.add(add.group(1).toLowerCase(java.util.Locale.ROOT));
            }
        }
        return out;
    }

    /** 顶层逗号切分（括号内的逗号不切）。 */
    private static List<String> splitTopLevel(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char ch : body.toCharArray()) {
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
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
