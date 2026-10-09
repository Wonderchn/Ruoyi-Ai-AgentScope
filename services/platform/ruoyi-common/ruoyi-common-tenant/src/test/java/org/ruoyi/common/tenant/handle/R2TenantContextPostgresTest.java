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

package org.ruoyi.common.tenant.handle;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.postgresql.ds.PGSimpleDataSource;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.tenant.exception.TenantException;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-2 验收（二）：真实 PostgreSQL + <b>真实 MyBatis-Plus 租户行拦截器</b>。
 *
 * <p>与 {@link R2TenantContextFailClosedTest} 的分工：那条判据验证"判定逻辑"，
 * 这条验证"判定发生在数据库操作之前"以及"限域真的落在 SQL 上"——用的是
 * 生产同一个 {@code TenantLineInnerInterceptor} + 生产 handler，只把数据源换成隔离 schema。
 *
 * <p>覆盖：
 * <ol>
 *   <li><b>缺租户</b>：租户业务表被拒绝，且 {@code prepareStatement/createStatement}
 *       <b>调用次数为 0</b>——"在数据库操作前拒绝"不是措辞而是计数事实；</li>
 *   <li><b>跨租户</b>：A/B 两租户写入<b>主键相同</b>的行，T-A 上下文下读不到 T-B 的行；</li>
 *   <li><b>合法系统任务</b>：无租户列的共享表（{@code flow_spel}，真实 excludes 项）
 *       在<b>缺上下文</b>时仍可读——若被误加 {@code tenant_id} 谓词，该表没有那一列，
 *       SQL 会直接报错，因此这条判据同时钉住"不得一刀切"。</li>
 * </ol>
 *
 * <p>需要外部 PostgreSQL：{@code -Dragent.tenant.test.jdbc-url=...}。未提供时整类跳过
 * （{@link EnabledIfSystemProperty}），不做假适配。
 */
@Tag("dev")
@EnabledIfSystemProperty(named = "ragent.tenant.test.jdbc-url", matches = ".+",
        disabledReason = "需要经 ragent.tenant.test.jdbc-url 提供 PostgreSQL")
class R2TenantContextPostgresTest {

    private static final String TENANT_A = "T-A";
    private static final String TENANT_B = "T-B";
    /** 与生产同一个常量（R-2-R4）。 */
    private static final String PLATFORM_AUDIT =
            org.ruoyi.common.core.constant.TenantConstants.PLATFORM_AUDIT_TENANT_ID;

    private PGSimpleDataSource source;
    private CountingDataSource counted;
    private String schema;
    private SqlSession session;
    private TenantProbeMapper probeMapper;
    private SharedProbeMapper sharedMapper;

    @BeforeEach
    void setUp() throws Exception {
        R2TenantTestContext.install(true);
        R2TenantTestContext.clearTenantContext();

        source = new PGSimpleDataSource();
        source.setUrl(System.getProperty("ragent.tenant.test.jdbc-url"));
        schema = "r2_tenant_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE SCHEMA " + schema)) {
            statement.execute();
        }
        source.setCurrentSchema(schema);
        try (Connection connection = source.getConnection();
             PreparedStatement create = connection.prepareStatement(
                     // 租户业务表：有 tenant_id 列，不在 excludes 里。
                     // 主键是 (id, tenant_id) —— 这样两个租户可以持有<b>同一个业务 id</b>，
                     // 于是"读得到哪一行"只可能由租户谓词决定。
                     // R-2-R4：探针表<b>刻意不给 DDL 默认值</b>（只保留 NOT NULL），与裁决 §7.1
                     // 验收第 1 条要求的"去掉 DDL 默认值但保留 NOT NULL 的探针表"一致：
                     // 这样任何"靠默认值/靠补齐上下文"落库的行都会直接失败，
                     // 只有真正显式提交了归属值的写入才能成功。
                     "CREATE TABLE " + schema + ".r2_tenant_probe ("
                             + "id varchar(20) NOT NULL, tenant_id varchar(20) NOT NULL, "
                             + "label varchar(64), PRIMARY KEY (id, tenant_id))")) {
            create.execute();
        }
        try (Connection connection = source.getConnection();
             PreparedStatement create = connection.prepareStatement(
                     // 真实共享表形状：flow_spel 在冻结 DDL 里没有 tenant_id 列，且是真实 excludes 项
                     "CREATE TABLE " + schema + ".flow_spel ("
                             + "id bigint PRIMARY KEY, component_name varchar(128), method_name varchar(128))")) {
            create.execute();
        }

        counted = new CountingDataSource(source);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("r2-tenant", new JdbcTransactionFactory(), counted));
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(R2TenantTestContext.handler()));
        configuration.addInterceptor(interceptor);
        configuration.addMapper(TenantProbeMapper.class);
        configuration.addMapper(SharedProbeMapper.class);

        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
        probeMapper = session.getMapper(TenantProbeMapper.class);
        sharedMapper = session.getMapper(SharedProbeMapper.class);

        // 两租户同主键：只有租户谓词能把它们分开，主键本身分不开
        seed("same-id", TENANT_A, "A-row");
        seed("same-id", TENANT_B, "B-row");
        seed("only-b", TENANT_B, "B-only");
        seedShared(1L, "spelRuleComponent", "selectDeptLeaderById");
    }

    @AfterEach
    void tearDown() throws Exception {
        R2TenantTestContext.clearTenantContext();
        if (session != null) {
            session.close();
        }
        if (schema != null) {
            try (Connection connection = source.getConnection();
                 PreparedStatement drop = connection.prepareStatement("DROP SCHEMA " + schema + " CASCADE")) {
                drop.execute();
            }
        }
    }

    private void seed(String id, String tenant, String label) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO " + schema + ".r2_tenant_probe (id, tenant_id, label) VALUES (?, ?, ?)")) {
            insert.setString(1, id);
            insert.setString(2, tenant);
            insert.setString(3, label);
            insert.execute();
        }
    }

    private void seedShared(long id, String component, String method) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO " + schema + ".flow_spel (id, component_name, method_name) VALUES (?, ?, ?)")) {
            insert.setLong(1, id);
            insert.setString(2, component);
            insert.setString(3, method);
            insert.execute();
        }
    }

    // ------------------------------------------------------------------ 1) 缺租户：数据库操作前拒绝

    @Test
    @DisplayName("缺租户：拒绝发生在数据库操作之前（语句准备次数为 0）")
    void missingTenantIsRefusedBeforeAnyStatementIsPrepared() {
        R2TenantTestContext.clearTenantContext();
        counted.reset();

        assertThatThrownBy(() -> probeMapper.selectList(null))
                .as("缺租户读租户业务表必须被拒绝")
                .satisfies(throwable -> assertThat(rootTenantException(throwable))
                        .isNotNull()
                        .hasFieldOrPropertyWithValue("code", "tenant.context.missing"));

        assertThat(counted.prepared())
                .as("拒绝必须发生在数据库操作之前：不得有任何语句被准备")
                .isZero();
        assertThat(counted.created())
                .as("拒绝必须发生在数据库操作之前：不得有任何语句被创建")
                .isZero();
    }

    @Test
    @DisplayName("缺租户：真实 SQL 层同样拒绝，且表内容未被改动")
    void missingTenantLeavesDataUntouched() throws Exception {
        R2TenantTestContext.clearTenantContext();
        assertThatThrownBy(() -> probeMapper.selectById("same-id")).isInstanceOf(Exception.class);
        R2TenantTestContext.clearTenantContext();
        assertThatThrownBy(() -> probeMapper.selectCount(null)).isInstanceOf(Exception.class);

        try (Connection connection = source.getConnection();
             PreparedStatement count = connection.prepareStatement(
                     "SELECT count(*) FROM " + schema + ".r2_tenant_probe");
             var rs = count.executeQuery()) {
            rs.next();
            assertThat(rs.getInt(1)).as("拒绝不得产生任何副作用").isEqualTo(3);
        }
    }

    @Test
    @DisplayName("F3：已登录但主体无租户 ⇒ 真实 SQL 层拒绝，且语句准备次数为 0")
    void loggedInPrincipalWithoutTenantIsRefusedBeforeAnyStatement() {
        // 复核者反例的精确形态：isLogin=true / loginTenant=null / 动态租户=任意值
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isLogin).thenReturn(true);
            login.when(LoginHelper::getTenantId).thenReturn(null);
            login.when(LoginHelper::isSuperAdmin).thenReturn(false);

            TenantHelper.setDynamic(TENANT_B, false);
            counted.reset();

            assertThatThrownBy(() -> probeMapper.selectList(null))
                    .as("已登录却没有主体租户 == 缺可信 tenant，必须拒绝")
                    .satisfies(throwable -> assertThat(rootTenantException(throwable))
                            .isNotNull()
                            .hasFieldOrPropertyWithValue("code", "tenant.context.missing"));

            assertThat(counted.prepared())
                    .as("拒绝必须发生在数据库操作之前")
                    .isZero();
            assertThat(counted.created())
                    .as("拒绝必须发生在数据库操作之前")
                    .isZero();
        }
    }

    @Test
    @DisplayName("F3：写路径同样拒绝（INSERT 不得凭空主体租户落库）")
    void loggedInPrincipalWithoutTenantIsRefusedOnInsertToo() {
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isLogin).thenReturn(true);
            login.when(LoginHelper::getTenantId).thenReturn(null);
            login.when(LoginHelper::isSuperAdmin).thenReturn(false);

            TenantHelper.setDynamic(TENANT_B, false);
            counted.reset();

            TenantProbe probe = new TenantProbe();
            probe.setId("f3-insert");
            probe.setTenantId(TENANT_B);
            probe.setLabel("should-not-land");

            assertThatThrownBy(() -> probeMapper.insert(probe)).isInstanceOf(Exception.class);
            assertThat(counted.prepared()).as("写路径同样在数据库操作前拒绝").isZero();
        }
    }

    // ------------------------------------------------------------------ 2) 跨租户

    @Test
    @DisplayName("跨租户：主键相同的对方租户行不可见，只能看到本租户行")
    void crossTenantRowsAreInvisibleEvenWithTheSamePrimaryKey() {
        TenantHelper.setDynamic(TENANT_A, false);
        assertThat(probeMapper.selectById("same-id"))
                .as("同主键的 T-B 行不得被 T-A 读到")
                .isNotNull()
                .extracting(TenantProbe::getLabel)
                .isEqualTo("A-row");

        List<TenantProbe> all = probeMapper.selectList(null);
        assertThat(all).extracting(TenantProbe::getLabel).containsExactly("A-row");

        R2TenantTestContext.clearTenantContext();
        TenantHelper.setDynamic(TENANT_B, false);
        assertThat(probeMapper.selectById("same-id"))
                .isNotNull()
                .extracting(TenantProbe::getLabel)
                .isEqualTo("B-row");
        assertThat(probeMapper.selectList(null))
                .extracting(TenantProbe::getLabel)
                .containsExactlyInAnyOrder("B-row", "B-only");
    }

    @Test
    @DisplayName("跨租户：谓词确实落在 SQL 上（同一主键查询，两个租户各得各的行）")
    void theTenantPredicateIsWhatSeparatesTheTwoRows() {
        TenantHelper.setDynamic(TENANT_A, false);
        String labelA = probeMapper.selectById("same-id").getLabel();
        R2TenantTestContext.clearTenantContext();
        TenantHelper.setDynamic(TENANT_B, false);
        String labelB = probeMapper.selectById("same-id").getLabel();
        assertThat(labelA).isEqualTo("A-row");
        assertThat(labelB).isEqualTo("B-row");
        assertThat(labelA).as("主键相同 ⇒ 区分只可能来自租户谓词").isNotEqualTo(labelB);
    }

    // ------------------------------------------------------------------ 3) 合法系统任务：没有误伤

    @Test
    @DisplayName("合法系统任务：无租户列的共享表在缺上下文时仍可读（不得一刀切）")
    void sharedTableWithoutTenantColumnIsReadableWithoutContext() {
        R2TenantTestContext.clearTenantContext();
        List<SharedProbe> rows = sharedMapper.selectList(null);
        assertThat(rows)
                .as("flow_spel 没有 tenant_id 列；若被误加谓词这里会直接 SQL 报错")
                .extracting(SharedProbe::getMethodName)
                .containsExactly("selectDeptLeaderById");
    }

    @Test
    @DisplayName("合法系统任务：共享表在有上下文时也不被过滤")
    void sharedTableIsStillReadableWithContext() {
        TenantHelper.setDynamic(TENANT_A, false);
        assertThat(sharedMapper.selectList(null)).hasSize(1);
    }

    // ------------------------------------------------------------------ R-2-R4：审计例外的作用范围

    @Test
    @DisplayName("R-2-R4：审计 INSERT 在无任何上下文、且探针表无 DDL 默认值时仍落库，落的是显式提交的保留值")
    void platformAuditInsertLandsWithTheExplicitReservedValue() throws Exception {
        counted.reset();
        // 刻意不设任何租户上下文、也不设任何 ignore 作用域：
        // 唯一凭据就是 mapper 方法上的 @InterceptorIgnore（逐方法例外）。
        TenantProbe probe = new TenantProbe();
        probe.setId("audit-1");
        probe.setTenantId(PLATFORM_AUDIT);
        probe.setLabel("platform-audit-row");

        Integer rows = probeMapper.insertPlatformAudit(probe);

        assertThat(rows).as("无归属审计行必须落库（此前会被 fail-closed 拒绝）").isEqualTo(1);
        assertThat(counted.prepared()).as("写入真的走了数据库").isPositive();
        assertThat(readTenantOf("audit-1"))
                .as("落库值必须是显式提交的保留值——探针表没有 DDL 默认值，靠默认值不可能成功")
                .isEqualTo(PLATFORM_AUDIT);
    }

    @Test
    @DisplayName("R-2-R4：审计例外只覆盖那一条语句——同一 mapper 的普通 INSERT/SELECT 仍受限域约束")
    void theAuditExceptionIsScopedToOneStatementOnOneTable() {
        counted.reset();
        TenantProbe plain = new TenantProbe();
        plain.setId("audit-2");
        plain.setTenantId(TENANT_A);
        plain.setLabel("plain-row");

        // 同一 mapper 的普通 insert：没有上下文 ⇒ 仍被拒绝
        assertThatThrownBy(() -> probeMapper.insert(plain))
                .as("例外不得扩大到同一 mapper 的普通 INSERT")
                .satisfies(throwable -> assertThat(rootTenantException(throwable))
                        .isNotNull()
                        .hasFieldOrPropertyWithValue("code", "tenant.context.missing"));
        assertThat(counted.prepared()).as("拒绝发生在数据库操作之前").isZero();

        // 同一 mapper 的普通 select：没有上下文 ⇒ 仍被拒绝
        assertThatThrownBy(() -> probeMapper.selectList(null))
                .as("例外不得扩大到 SELECT")
                .isInstanceOf(Exception.class);
        assertThat(counted.prepared()).isZero();
    }

    @Test
    @DisplayName("R-2-R4：租户读不到无归属审计行；平台审计读被钉在保留值上（伪造租户也改不了范围）")
    void tenantReadsCannotReachUnattributedRowsAndThePlatformReadIsPinned() {
        // 一条 T-A 行 + 一条无归属行
        TenantHelper.setDynamic(TENANT_A, false);
        TenantProbe own = new TenantProbe();
        own.setId("own-1");
        own.setLabel("own-row");
        assertThat(probeMapper.insert(own)).isEqualTo(1);
        TenantHelper.clearDynamic();

        TenantProbe unattributed = new TenantProbe();
        unattributed.setId("audit-3");
        unattributed.setTenantId(PLATFORM_AUDIT);
        unattributed.setLabel("unattributed");
        assertThat(probeMapper.insertPlatformAudit(unattributed)).isEqualTo(1);

        // 1) 真实租户 A 的查询读不到无归属行（setUp 里已有一条 T-A 的 same-id 行）
        TenantHelper.setDynamic(TENANT_A, false);
        assertThat(probeMapper.selectList(null)).extracting(TenantProbe::getId)
                .containsExactlyInAnyOrder("same-id", "own-1");
        // 2) 真实默认租户 000000 同样读不到
        TenantHelper.clearDynamic();
        TenantHelper.setDynamic("000000", false);
        assertThat(probeMapper.selectList(null)).extracting(TenantProbe::getId).isEmpty();
        // 3) 平台审计读（注解 + 值内联在 SQL 里）：即便处在 T-A 作用域，返回的也只有无归属行
        TenantHelper.clearDynamic();
        TenantHelper.setDynamic(TENANT_A, false);
        assertThat(probeMapper.selectPlatformAudit()).extracting(TenantProbe::getId).containsExactly("audit-3");
        // 4) 伪造：保留值不是租户，不得作为切租户目标——设成它会被直接拒绝，
        //    因此"伪造保留值"不构成任何访问能力（裁决 §7.1 第 4 条）
        TenantHelper.clearDynamic();
        assertThatThrownBy(() -> TenantHelper.setDynamic(PLATFORM_AUDIT, false))
                .as("保留值不得作为普通动态切租户目标")
                .isInstanceOf(org.ruoyi.common.tenant.exception.TenantException.class)
                .hasFieldOrPropertyWithValue("code", "tenant.context.audit.marker.not.a.tenant");
        assertThat(TenantHelper.getTenantId()).as("拒绝后仍无上下文").isNull();
    }

    @Test
    @DisplayName("R-2-R4：无归属写入不使用任何环境上下文（没有可泄漏/需恢复的 ignore 作用域）")
    void platformAuditInsertNeedsNoAmbientContextAtAll() {
        counted.reset();
        List<String> ambient = new ArrayList<>();
        TenantProbe probe = new TenantProbe();
        probe.setId("audit-4");
        probe.setTenantId(PLATFORM_AUDIT);
        probe.setLabel("no-scope");

        assertThat(TenantHelper.getTenantId()).as("前置：确实没有上下文").isNull();
        Integer rows = probeMapper.insertPlatformAudit(probe);
        ambient.add(TenantHelper.getTenantId());

        assertThat(rows).isEqualTo(1);
        assertThat(ambient).as("写入后上下文仍为空——例外不建立也不破坏任何上下文").containsExactly((String) null);
        assertThat(TenantHelper.getTenantId()).as("写入后上下文仍为空").isNull();
    }

    private String readTenantOf(String id) {
        try (Connection connection = source.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT tenant_id FROM " + schema + ".r2_tenant_probe WHERE id = ?")) {
            query.setString(1, id);
            try (var rs = query.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读回失败", e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static TenantException rootTenantException(Throwable throwable) {
        for (Throwable cursor = throwable; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof TenantException tenantException) {
                return tenantException;
            }
        }
        return null;
    }

    public interface TenantProbeMapper extends BaseMapper<TenantProbe> {

        /**
         * R-2-R4：审计 INSERT 的逐方法例外（与生产 mapper 同形）。作用对象只有这一条语句。
         */
        @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
        @org.apache.ibatis.annotations.Insert(
                "INSERT INTO r2_tenant_probe (id, tenant_id, label) VALUES (#{id}, #{tenantId}, #{label})")
        int insertPlatformAudit(TenantProbe entity);

        /** R-2-R4：平台审计读——归属值内联，调用方无法用参数改变范围。 */
        @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
        @org.apache.ibatis.annotations.Select(
                "SELECT * FROM r2_tenant_probe WHERE tenant_id = '"
                        + org.ruoyi.common.core.constant.TenantConstants.PLATFORM_AUDIT_TENANT_ID + "'")
        List<TenantProbe> selectPlatformAudit();
    }

    public interface SharedProbeMapper extends BaseMapper<SharedProbe> {
    }

    @TableName("r2_tenant_probe")
    public static class TenantProbe {
        @TableId
        private String id;
        private String tenantId;
        private String label;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

    @TableName("flow_spel")
    public static class SharedProbe {
        @TableId
        private Long id;
        private String componentName;
        private String methodName;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getComponentName() {
            return componentName;
        }

        public void setComponentName(String componentName) {
            this.componentName = componentName;
        }

        public String getMethodName() {
            return methodName;
        }

        public void setMethodName(String methodName) {
            this.methodName = methodName;
        }
    }

    /**
     * 计数数据源：只用于证明"拒绝发生在数据库操作之前"。
     * 统计 {@code prepareStatement} / {@code createStatement} 的调用次数，
     * 其余方法原样委托。
     */
    static final class CountingDataSource extends DelegatingDataSource {

        private final AtomicInteger prepared = new AtomicInteger();
        private final AtomicInteger created = new AtomicInteger();

        CountingDataSource(DataSource target) {
            super(target);
        }

        void reset() {
            prepared.set(0);
            created.set(0);
        }

        int prepared() {
            return prepared.get();
        }

        int created() {
            return created.get();
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return count(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws java.sql.SQLException {
            return count(super.getConnection(username, password));
        }

        private Connection count(Connection connection) {
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if (name.startsWith("prepareStatement") || name.startsWith("prepareCall")) {
                    prepared.incrementAndGet();
                } else if (name.startsWith("createStatement")) {
                    created.incrementAndGet();
                }
                try {
                    return method.invoke(connection, args);
                } catch (InvocationTargetException e) {
                    throw e.getTargetException();
                }
            };
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class}, handler);
        }
    }
}
