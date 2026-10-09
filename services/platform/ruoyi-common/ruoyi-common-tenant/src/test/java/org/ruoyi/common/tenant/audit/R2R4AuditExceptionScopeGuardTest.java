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

package org.ruoyi.common.tenant.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-2-R4 验收（裁决 §7.1 执行范围第 3 条）：<b>审计例外扩不到别处</b>。
 *
 * <p>裁决要求"缺上下文的例外仅限这两张表的专用审计 INSERT"，且"不得保留可执行任意业务回调的
 * 通用空值 {@code ignore} 分支"。这两条都是<b>结构性</b>要求，所以这里用结构判据钉住：
 *
 * <ol>
 *   <li>全仓生产代码里带 {@code tenantLine = "true"} 的 {@code @InterceptorIgnore} 必须
 *       <b>恰好</b>是登记的那几处（两张审计表各一条 INSERT + 各一条平台审计读）；
 *       任何"把例外扩到业务表"的改动都会立刻让本判据变红；</li>
 *   <li>两个审计写入器<b>不得</b>再出现 {@code TenantHelper.ignore} 或
 *       {@code withCapturedTenant}（通用回调分支已删除）；</li>
 *   <li>两个审计写入器必须经 {@link PlatformAuditAttribution} 显式判定归属。</li>
 * </ol>
 */
@Tag("dev")
class R2R4AuditExceptionScopeGuardTest {

    private static final Path PLATFORM_MAIN = Path.of("services", "platform");

    /** 生产代码里允许出现租户行拦截例外的<b>全部</b>位置。 */
    private static final Set<String> EXPECTED_IGNORE_SITES = new TreeSet<>(List.of(
            "services/platform/ruoyi-modules/ruoyi-system/src/main/java/org/ruoyi/system/mapper/SysOperLogMapper.java",
            "services/platform/ruoyi-modules/ruoyi-system/src/main/java/org/ruoyi/system/mapper/SysLogininforMapper.java"
    ));

    /**
     * <b>方法级</b>允许清单（R-2-R5 加强，回应第四轮复核的"判据强度限制"）。
     *
     * <p>只钉"文件集合"是不够的：在**同一个**审计 mapper 里给**别的方法**加注解（例如给
     * `updateById`/`deleteById` 加上例外）不会变红。这里把例外钉到 <b>文件#方法</b> 粒度，
     * 于是"加在错的方法上"和"加在错的文件上"都会失败。
     */
    private static final Set<String> EXPECTED_IGNORE_METHODS = new TreeSet<>(List.of(
            "SysOperLogMapper#insert",
            "SysOperLogMapper#selectPlatformAudit",
            "SysLogininforMapper#insert",
            "SysLogininforMapper#selectPlatformAudit"
    ));

    /** 允许出现例外的两张审计表。 */
    private static final Set<String> AUDIT_MAPPERS = Set.of("SysOperLogMapper.java", "SysLogininforMapper.java");

    @Test
    @DisplayName("例外只出现在两张审计表的 mapper 上（扩到业务表即失败）")
    void theTenantLineExceptionExistsOnlyOnTheTwoAuditMappers() throws IOException {
        List<String> offenders = new ArrayList<>();
        Set<String> found = new TreeSet<>();
        // 必须同时匹配简单名与全限定名两种写法：只匹配 "@InterceptorIgnore" 会漏掉
        // "@com.baomidou.mybatisplus.annotation.InterceptorIgnore(...)"（P4 变异实测到的缺口）。
        Pattern annotation = Pattern.compile(
                "@(?:[\\w]+\\.)*InterceptorIgnore\\s*\\(\\s*tenantLine\\s*=\\s*\"true\"\\s*\\)");

        for (Path file : javaSources(locate(PLATFORM_MAIN))) {
            String relative = file.toString().replace('\\', '/');
            // 只看代码：注释里提到注解名不算"使用了例外"
            String text = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (annotation.matcher(text).find()) {
                found.add(relative.substring(relative.indexOf("services/platform")));
                if (!AUDIT_MAPPERS.contains(file.getFileName().toString())) {
                    offenders.add(relative);
                }
            }
        }

        assertThat(offenders)
                .as("租户行拦截例外只允许出现在两张审计表的 mapper 上")
                .isEmpty();
        assertThat(found)
                .as("出现例外的文件集合必须恰好是登记的两个审计 mapper（新增/删除都要改这条判据）")
                .isEqualTo(EXPECTED_IGNORE_SITES);
    }

    @Test
    @DisplayName("R-2-R5：例外钉到方法级——同一 mapper 的其它方法被加上注解也要失败")
    void theTenantLineExceptionIsPinnedToExactMethodsNotJustFiles() throws IOException {
        Set<String> measured = measureIgnoreMethods();

        assertThat(measured)
                .as("例外必须恰好落在登记的方法上；把注解加到同一 mapper 的别的方法（或别的 mapper）都要改这条判据")
                .isEqualTo(EXPECTED_IGNORE_METHODS);
    }

    /**
     * 枚举生产代码里带 {@code tenantLine="true"} 的注解所标注的 <b>{@code 类#方法}</b>。
     *
     * <p>做法：定位每处注解，然后向下找第一条"标识符 + 左括号"的方法签名行。只看代码不看注释，
     * 且同时接受简单名与全限定名两种注解写法（第四轮 P4 暴露过只匹配简单名的缺口）。
     */
    private static Set<String> measureIgnoreMethods() throws IOException {
        Pattern annotation = Pattern.compile(
                "@(?:[\\w]+\\.)*InterceptorIgnore\\s*\\(\\s*tenantLine\\s*=\\s*\"true\"\\s*\\)");
        Pattern method = Pattern.compile("^\\s*(?:[\\w<>\\[\\],.\\s]+\\s+)?([A-Za-z_]\\w*)\\s*\\(");
        Set<String> measured = new TreeSet<>();

        for (Path file : javaSources(locate(PLATFORM_MAIN))) {
            String text = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            String simpleName = file.getFileName().toString().replace(".java", "");
            Matcher matcher = annotation.matcher(text);
            while (matcher.find()) {
                String tail = text.substring(matcher.end());
                String methodName = null;
                // 跳过注解本身（@Select 可能跨行：用括号配平判断注解何时结束），
                // 然后第一条真正的成员行就是被注解的方法。
                boolean inAnnotation = false;
                int depth = 0;
                for (String line : tail.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    int balance = countParens(trimmed);
                    if (inAnnotation) {
                        depth += balance;
                        if (depth <= 0) {
                            inAnnotation = false;
                        }
                        continue;
                    }
                    if (trimmed.startsWith("@")) {
                        depth = balance;
                        inAnnotation = depth > 0;
                        continue;
                    }
                    Matcher methodMatcher = method.matcher(line);
                    if (methodMatcher.find()) {
                        methodName = methodMatcher.group(1);
                    }
                    break;
                }
                assertThat(methodName)
                        .as("%s 里有一处 @InterceptorIgnore 后面找不到方法签名（解析器失效，不能静默跳过）", file)
                        .isNotNull();
                measured.add(simpleName + "#" + methodName);
            }
        }
        return measured;
    }

    private static int countParens(String line) {
        int balance = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '(') {
                balance++;
            } else if (c == ')') {
                balance--;
            }
        }
        return balance;
    }

    @Test
    @DisplayName("两个审计写入器不再含通用空值 ignore 分支，且必须显式判定归属")
    void auditWritersHaveNoGenericIgnoreBranchAndResolveAttributionExplicitly() throws IOException {
        List<Path> writers = new ArrayList<>();
        for (Path file : javaSources(locate(PLATFORM_MAIN))) {
            String name = file.getFileName().toString();
            if (name.equals("SysOperLogServiceImpl.java") || name.equals("SysLogininforServiceImpl.java")) {
                writers.add(file);
            }
        }
        assertThat(writers).as("两个真实审计写入器必须都在仓内").hasSize(2);

        for (Path writer : writers) {
            String text = Files.readString(writer, StandardCharsets.UTF_8);
            assertThat(text)
                    .as("%s 不得保留可执行任意业务回调的通用空值 ignore 分支", writer.getFileName())
                    .doesNotContain("TenantHelper.ignore")
                    .doesNotContain("withCapturedTenant");
            assertThat(text)
                    .as("%s 必须经 PlatformAuditAttribution 显式判定归属", writer.getFileName())
                    .contains("PlatformAuditAttribution.resolveWithReason");
            assertThat(text)
                    .as("%s 必须显式拒绝归属为空的行（防退化成依赖 DDL 默认值）", writer.getFileName())
                    .contains("insertAuditRow");
        }
    }

    @Test
    @DisplayName("租户工具里不再存在通用 withCapturedTenant 便捷方法")
    void tenantHelperHasNoGenericCapturedTenantHelper() throws IOException {
        Path helper = locate(PLATFORM_MAIN).resolve(
                "ruoyi-common/ruoyi-common-tenant/src/main/java/org/ruoyi/common/tenant/helper/TenantHelper.java");
        String text = Files.readString(helper, StandardCharsets.UTF_8);
        assertThat(text)
                .as("通用空值 ignore 便捷方法已按裁决删除")
                .doesNotContain("public static <T> T withCapturedTenant")
                .doesNotContain("public static void withCapturedTenant");
    }

    @Test
    @DisplayName("保留值只有一个集中定义点，且不落在任何租户业务表以外的地方")
    void theReservedMarkerIsDefinedOnce() throws IOException {
        int definitions = 0;
        List<String> literalUsers = new ArrayList<>();
        Pattern literal = Pattern.compile("\"__platform_audit__\"");
        for (Path file : javaSources(locate(PLATFORM_MAIN))) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.contains("String PLATFORM_AUDIT_TENANT_ID")) {
                definitions++;
            }
            Matcher m = literal.matcher(text);
            while (m.find()) {
                literalUsers.add(file.getFileName().toString());
            }
        }
        assertThat(definitions)
                .as("保留值必须只有一个集中定义点（专用常量）")
                .isEqualTo(1);
        assertThat(literalUsers)
                .as("除常量定义处外不得再出现字面量")
                .containsExactly("TenantConstants.java");
    }

    /** 去掉块注释与行注释，避免"注释里提到注解"被当成使用。 */
    private static String stripComments(String text) {
        String withoutBlocks = text.replaceAll("(?s)/\\*.*?\\*/", " ");
        return withoutBlocks.replaceAll("//[^\\n]*", " ");
    }

    private static List<Path> javaSources(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                    .toList();
        }
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
