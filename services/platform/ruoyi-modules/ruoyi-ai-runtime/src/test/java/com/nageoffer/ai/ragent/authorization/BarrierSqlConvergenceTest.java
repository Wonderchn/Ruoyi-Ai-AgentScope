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

import com.nageoffer.ai.ragent.authorization.dao.AiAclEpochMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceAclMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceAclMapper.AclRow;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper.AiResourceRow;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W3-1 drain 收敛护栏（L3-T2R-AUTHZ 任务②）："活跃 permit 判据"在全工程必须**只有一份**。
 *
 * <p>收敛前的形态：{@code AiResourceWriteService} 的 drain 轮询（writeInternal 内）与提交前复核
 * 各内联一份与 {@link TenantBarrierReconciler#SQL_ACTIVE_PERMITS_OUTSIDE} 逐字相同的 SQL 文本
 * —— 物理上三份、语义上一份，改判据必须同步三处，漏一处就是 G-55b"同表两套口径"同族缺陷
 * （drain 与 reconciler 对"还有没有活跃 permit"给出不同答案）。
 *
 * <p>本类钉住三件事：
 * <ol>
 *   <li><b>行为收敛</b>：writeInternal 真实跑一次，捕获它发给 JDBC 的全部活跃集 SQL，
 *       逐条断言 {@code ==} 常量（不是"长得像"，是同一份文本）；</li>
 *   <li><b>文本收敛</b>（反向证明锚点）：扫两个产品源文件，{@code expires_at > CURRENT_TIMESTAMP}
 *       字面量必须恰好 1 处且在 reconciler —— 把常量里的过期子句摘掉（变异副本），本条变红；
 *       把 drain 改回内联副本（未收敛），本条同样变红。</li>
 *   <li><b>行为锚点</b>：drain 侧发出的 SQL 恒等于常量 ⇒ 常量里的
 *       {@code expires_at > CURRENT_TIMESTAMP} 就是"过期 permit 不挡 drain"的实现载体 ——
 *       它被摘掉时第 1 条与第 2 条<b>一起</b>变红（只单点变红 = 没收敛）。</li>
 * </ol>
 *
 * <p><b>变异测试纪律</b>（BRIEF §4）：反向证明只用**变异副本**做，产品文件当场还原并复跑确认绿；
 * 禁止把变异状态留在工作树里跑其他验证。
 */
@Tag("dev")
class BarrierSqlConvergenceTest {

    private static final String EXPIRY_LITERAL = "expires_at > CURRENT_TIMESTAMP";
    /** SQL 字符串片段形态（带引号）：只计真实 SQL 拼接片段，不算 javadoc/注释里的字样。 */
    private static final String SQL_EXPIRY_FRAGMENT = "\" AND expires_at > CURRENT_TIMESTAMP\"";
    private static final String WRITE_SERVICE_SOURCE =
            "src" + java.io.File.separator + "main" + java.io.File.separator + "java"
                    + java.io.File.separator + "com/nageoffer/ai/ragent/authorization/AiResourceWriteService.java";
    private static final String RECONCILER_SOURCE =
            "src" + java.io.File.separator + "main" + java.io.File.separator + "java"
                    + java.io.File.separator + "com/nageoffer/ai/ragent/authorization/TenantBarrierReconciler.java";

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    // ------------------------------------------------------------------ 1. 行为收敛

    @Test
    @DisplayName("drain 轮询与提交前复核发给 JDBC 的活跃集 SQL，与 reconciler 常量逐字同一份")
    void drainLoopAndCommitRecheckUseTheSingleActivePermitsSql() {
        PrincipalContext.set(principal());
        when(resourceMapper.findByPk(TENANT, "KB", "kb-1"))
                .thenReturn(Optional.of(new AiResourceRow(TENANT, "KB", "kb-1", MEMBER, null, null, null, "ACTIVE", 1L)));
        when(aclMapper.findByResource(TENANT, "KB", "kb-1")).thenReturn(List.of());
        AiResourceWriteService writeService = writeService();

        writeService.grantAclRule("kb-1", new AiResourceWriteService.AclGrant("member", MEMBER, "kb.read", null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.times(2))
                .queryForObject(sql.capture(), anyMap(), eq(Long.class));
        List<String> captured = sql.getAllValues();

        // 锚点：两处活跃集观察都必须真实发生（没有它们，下面的断言就是空集合恒真）
        assertThat(captured).as("writeInternal 必须恰好两次活跃集计数（drain 轮询 + 提交前复核）")
                .hasSize(2);
        assertThat(captured.get(0))
                .as("drain 轮询必须引用 reconciler 的同一份常量文本（收敛前这里是内联副本）")
                .isEqualTo(TenantBarrierReconciler.SQL_ACTIVE_PERMITS_OUTSIDE)
                // drain 侧的**行为**变红锚点：常量里的过期子句被摘掉时，drain 发出的 SQL
                // 失去该子句 ⇒ 本断言与 reconciler 锚点（测试 3）一起变红——证明两处同源。
                .contains(EXPIRY_LITERAL);
        assertThat(captured.get(1))
                .as("提交前复核必须引用 reconciler 的同一份常量文本")
                .isEqualTo(TenantBarrierReconciler.SQL_ACTIVE_PERMITS_OUTSIDE)
                .contains(EXPIRY_LITERAL);
    }

    // ------------------------------------------------------------------ 2. 文本收敛（反向证明锚点）

    @Test
    @DisplayName("产品源里过期子句的 SQL 片段形态恰好 1 处且只在 reconciler（drain 不再有内联副本）")
    void expiryLiteralExistsExactlyOnceInProductSourcesInsideReconciler() throws IOException {
        String writeServiceSource = productSource(WRITE_SERVICE_SOURCE);
        String reconcilerSource = productSource(RECONCILER_SOURCE);

        // 采集锚点：扫描目标必须真实含收敛接线（否则下面的"0 处"是扫错文件的恒真）
        assertThat(writeServiceSource)
                .as("AiResourceWriteService 必须引用 reconciler 常量（收敛接线仍在）")
                .contains("TenantBarrierReconciler.SQL_ACTIVE_PERMITS_OUTSIDE");

        // 口径：只计 SQL 字符串片段形态（`" AND expires_at > CURRENT_TIMESTAMP"`，带引号），
        // javadoc/注释里的 {@code expires_at > CURRENT_TIMESTAMP} 字样**不算**——
        // 否则注释措辞一变，判据就静默漂移（BRIEF §4"判据会静默退化"同族）。
        int inWriteService = countSqlExpiryFragments(writeServiceSource);
        int inReconciler = countSqlExpiryFragments(reconcilerSource);
        assertThat(inWriteService)
                .as("AiResourceWriteService 里不得再有任何过期子句 SQL 片段（drain 已收敛）")
                .isZero();
        assertThat(inReconciler)
                .as("TenantBarrierReconciler 是活跃集判据的唯一持有者")
                .isEqualTo(1);
        assertThat(inWriteService + inReconciler).as("两文件合计恰好 1 处").isEqualTo(1);

        // 反向证明（文本层变异）：把常量里的过期子句片段摘掉 ⇒ 本断言变红。
        // 端到端变异（变异副本编译+实跑）在交付流程里用副本文件另行执行，产品文件不动。
        String mutated = reconcilerSource.replace(SQL_EXPIRY_FRAGMENT, "");
        assertThat(countSqlExpiryFragments(mutated)).as("变异副本（去掉过期子句）必须让本护栏变红").isZero();
    }

    // ------------------------------------------------------------------ 3. 行为锚点

    @Test
    @DisplayName("drain 侧 SQL 恒等于常量 ⇒ 常量的过期子句就是'过期 permit 不挡 drain'的载体")
    void drainSideExpiryBehaviourRidesOnTheSharedConstant() {
        // 行为口径：drain 与 reconciler 用同一份 SQL（测试 1 已证），因此
        // "过期 permit 是否挡 drain"完全由常量的 WHERE 决定 —— 这里钉住常量必须携带过期子句。
        assertThat(TenantBarrierReconciler.SQL_ACTIVE_PERMITS_OUTSIDE)
                .contains(EXPIRY_LITERAL)
                .contains("status='ACTIVE'")
                .contains("permit_id<>:permit");
    }

    // ------------------------------------------------------------------ 装配辅助（自足的 writeInternal 驱动，不连库）

    private static final String TENANT = "w31t1";
    private static final String MEMBER = "platform:w31t1:2101";

    private NamedParameterJdbcTemplate jdbc;
    private AiResourceMapper resourceMapper = mock(AiResourceMapper.class);
    private AiResourceAclMapper aclMapper = mock(AiResourceAclMapper.class);
    private AiAclEpochMapper epochMapper = mock(AiAclEpochMapper.class);

    private AiResourceWriteService writeService() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        TransactionOperations txOps = mock(TransactionOperations.class);
        // 块 lambda + 显式 return：Answer.answer 声明返回 Object，
        // 表达式 lambda 里塞 void 的 Consumer.accept() 会编译失败（T0 边界①实测 exit 1）。
        doAnswer(inv -> {
            ((Consumer<?>) inv.getArgument(0)).accept(null);
            return null;
        }).when(txOps).executeWithoutResult(any());
        doAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)))
                .when(txOps).execute(any(TransactionCallback.class));
        when(jdbc.update(anyString(), anyMap())).thenReturn(1);
        when(jdbc.query(contains("FOR UPDATE"), anyMap(), any(RowMapper.class))).thenReturn(List.of(3));
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);
        when(epochMapper.bump(anyString())).thenReturn(Optional.of(4));
        when(aclMapper.insert(any(AclRow.class))).thenReturn(1);

        var guard = mock(com.nageoffer.ai.ragent.framework.security.RevocationGuard.class);
        when(guard.enter(any(), anyString(), anyString()))
                .thenReturn(new com.nageoffer.ai.ragent.framework.security.RevocationGuard.Operation(guard, "permit", "op"));
        var resources = mock(com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.class);
        when(resources.check(any(), anyString(), anyString())).thenReturn(Verdict.GRANT);
        var platform = mock(com.nageoffer.ai.ragent.framework.security.AuthorizationChecker.class);
        when(platform.check(any(), anyString(), anyString()))
                .thenReturn(new com.nageoffer.ai.ragent.framework.security.AuthorizationChecker.AuthorizeResult(true, 7));

        AiResourceWriteService service = new AiResourceWriteService(
                jdbc, resourceMapper, aclMapper, epochMapper, txOps);
        service.configureExecution(guard, resources, platform);
        service.setHighRiskEnabled(true);
        return service;
    }

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal(TENANT, "2101", MEMBER, 7, 3,
                Set.of("kb.acl.manage", "kb.write", "kb.delete"), "jti-w31", "platform", 0, Long.MAX_VALUE);
    }

    private static int countSqlExpiryFragments(String source) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(SQL_EXPIRY_FRAGMENT, index)) >= 0) {
            count++;
            index += SQL_EXPIRY_FRAGMENT.length();
        }
        return count;
    }

    private static String productSource(String moduleRelative) throws IOException {
        Path file = worktreeRoot()
                .resolve(Paths.get("services", "platform", "ruoyi-modules", "ruoyi-ai-runtime"))
                .resolve(moduleRelative);
        assertThat(Files.exists(file)).as("产品源必须存在：%s", file).isTrue();
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** 从测试 CWD（模块目录）向上找含 reactor 模块的工作树根；找不到即失败，不猜。 */
    private static Path worktreeRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path marker = Paths.get("services", "platform", "ruoyi-modules", "ruoyi-ai-runtime");
        while (dir != null && !Files.isDirectory(dir.resolve(marker))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("cannot locate worktree root from user.dir="
                    + System.getProperty("user.dir"));
        }
        return dir;
    }
}
