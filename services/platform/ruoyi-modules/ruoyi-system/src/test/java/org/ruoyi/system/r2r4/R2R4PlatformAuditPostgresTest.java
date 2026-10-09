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

package org.ruoyi.system.r2r4;

import io.github.linpeilie.Converter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.ruoyi.common.core.constant.TenantConstants;
import org.ruoyi.common.core.utils.SpringUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.tenant.audit.PlatformAuditAccess;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.system.domain.bo.SysLogininforBo;
import org.ruoyi.system.domain.bo.SysOperLogBo;
import org.ruoyi.system.domain.SysUser;
import org.ruoyi.system.mapper.SysLogininforMapper;
import org.ruoyi.system.mapper.SysOperLogMapper;
import org.ruoyi.system.mapper.SysUserMapper;
import org.ruoyi.system.service.ISysClientService;
import org.ruoyi.system.service.impl.SysLogininforServiceImpl;
import org.ruoyi.system.service.impl.SysOperLogServiceImpl;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.ruoyi.common.tenant.handle.PlusTenantLineHandler;
import org.ruoyi.common.tenant.properties.TenantProperties;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-2-R4 验收（裁决 §7.1 验收第 1、2 条）：<b>两个真实审计写入器 + 真实 mapper + 真实
 * MyBatis-Plus 租户行拦截器 + 真实 PostgreSQL</b>，核对实际落库行数与 {@code tenant_id}。
 *
 * <p><b>探针表刻意不给 DDL 默认值</b>（{@code tenant_id varchar(20) NOT NULL}，与裁决
 * "去掉 DDL 默认值但保留 NOT NULL 的探针表"一致）：任何靠默认值或靠"补齐上下文"落库的行
 * 都会直接失败，只有真正显式提交了归属值的写入才能成功。
 *
 * <p><b>为什么用 {@link DriverManagerDataSource} 而不是 PGSimpleDataSource</b>：本模块的
 * 测试类路径上没有 PostgreSQL 驱动（POM 由 T0 单独管理，本卡不改 POM）。用 spring-jdbc 的
 * 数据源可以在<b>不引入编译期 PG 依赖</b>的前提下接真实库；驱动只需在运行期存在于类路径。
 * 未提供 {@code -Dragent.audit.test.jdbc-url} 时整类跳过。
 */
@Tag("dev")
@EnabledIfSystemProperty(named = "ragent.audit.test.jdbc-url", matches = ".+",
        disabledReason = "需要经 ragent.audit.test.jdbc-url 提供 PostgreSQL")
class R2R4PlatformAuditPostgresTest {

    private static final String RESERVED = TenantConstants.PLATFORM_AUDIT_TENANT_ID;
    private static final String PROP = "r2r4-tenant-enable";

    private DriverManagerDataSource source;
    private String schema;
    private SqlSession session;
    private ConfigurableEnvironment environment;

    @BeforeEach
    void setUp() throws Exception {
        org.ruoyi.system.rw14.Rw14TestContext.install();
        StaticApplicationContext context = (StaticApplicationContext) SpringUtils.context();
        if (context.getBeanFactory().getBeanNamesForType(Converter.class).length == 0) {
            context.getBeanFactory().registerSingleton("converter", new Converter());
        }
        environment = (ConfigurableEnvironment) context.getEnvironment();
        environment.getPropertySources().remove(PROP);
        environment.getPropertySources().addFirst(new MapPropertySource(PROP, Map.of("tenant.enable", "true")));
        TenantHelper.clearDynamic();

        // 随机 schema：先用引导连接建 schema，再把 currentSchema 钉进 URL——
        // DriverManagerDataSource 每次新建连接，会话级 SET search_path 不会保留。
        String baseUrl = System.getProperty("ragent.audit.test.jdbc-url");
        schema = "r2r4_audit_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource bootstrap = new DriverManagerDataSource();
        bootstrap.setUrl(baseUrl);
        try (Connection connection = bootstrap.getConnection();
             PreparedStatement statement = connection.prepareStatement("CREATE SCHEMA " + schema)) {
            statement.execute();
        }
        source = new DriverManagerDataSource();
        source.setUrl(baseUrl + "&currentSchema=" + schema);
        // 真实列名 + 真实 NOT NULL + <b>没有</b> DDL 默认值
        execute("CREATE TABLE " + schema + ".sys_oper_log ("
                + "oper_id bigint PRIMARY KEY, tenant_id varchar(20) NOT NULL, title varchar(50), "
                + "business_type integer, method varchar(200), request_method varchar(10), operator_type integer, "
                + "oper_name varchar(50), dept_name varchar(50), oper_url varchar(255), oper_ip varchar(128), "
                + "oper_location varchar(255), oper_param varchar(2000), json_result varchar(2000), status integer, "
                + "error_msg varchar(2000), oper_time timestamp, cost_time bigint)");
        execute("CREATE TABLE " + schema + ".sys_logininfor ("
                + "info_id bigint PRIMARY KEY, tenant_id varchar(20) NOT NULL, user_name varchar(50), "
                + "client_key varchar(32), device_type varchar(32), ipaddr varchar(128), login_location varchar(255), "
                + "browser varchar(50), os varchar(50), status varchar(1), msg varchar(255), login_time timestamp)");
        execute("CREATE TABLE " + schema + ".sys_client (client_id varchar(64) PRIMARY KEY, client_key varchar(32), "
                + "client_secret varchar(255), grant_type varchar(255), device_type varchar(32), active_timeout bigint, "
                + "timeout bigint, status varchar(1), del_flag varchar(1))");
        // R-2-R5：注册写的是 sys_user —— 一张**不享有审计例外**的租户业务表。
        // tenant_id 同样刻意不给 DDL 默认值。
        execute("CREATE TABLE " + schema + ".sys_user ("
                + "user_id bigint PRIMARY KEY, tenant_id varchar(20) NOT NULL, user_name varchar(30), "
                + "nick_name varchar(30), user_type varchar(10), open_id varchar(64), user_balance numeric, "
                + "dept_id bigint, email varchar(50), phonenumber varchar(11), sex char(1), avatar bigint, "
                + "password varchar(100), status char(1), del_flag char(1), login_ip varchar(128), "
                + "login_date timestamp, remark varchar(500), create_dept bigint, create_by bigint, "
                + "create_time timestamp, update_by bigint, update_time timestamp)");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("r2r4-audit", new JdbcTransactionFactory(), source));
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        TenantProperties properties = new TenantProperties();
        properties.setEnable(true);
        properties.setExcludes(List.of("sys_menu", "sys_tenant", "sys_tenant_package", "sys_role_dept",
                "sys_role_menu", "sys_user_post", "sys_user_role", "sys_client", "sys_oss_config",
                "flow_spel", "ai_flow_trace_run", "ai_flow_trace_node", "ai_legacy_user",
                "ai_provider_envelope", "ai_provider_spend"));
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new PlusTenantLineHandler(properties)));
        configuration.addInterceptor(interceptor);
        configuration.addMapper(SysOperLogMapper.class);
        configuration.addMapper(SysLogininforMapper.class);
        configuration.addMapper(SysUserMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TenantHelper.clearDynamic();
        if (session != null) {
            session.close();
        }
        if (environment != null) {
            environment.getPropertySources().remove(PROP);
        }
        if (schema != null) {
            try (Connection connection = source.getConnection();
                 PreparedStatement drop = connection.prepareStatement("DROP SCHEMA " + schema + " CASCADE")) {
                drop.execute();
            }
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private long countOperLog() throws Exception {
        return scalar("SELECT count(*) FROM " + schema + ".sys_oper_log");
    }

    private String operLogTenant(long id) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT tenant_id FROM " + schema + ".sys_oper_log WHERE oper_id = ?")) {
            query.setLong(1, id);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private long scalar(String sql) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement query = connection.prepareStatement(sql);
             ResultSet rs = query.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------ 验收 1：两个真实写入器

    @Test
    @DisplayName("验收1：操作日志写入器——有可信租户记真实租户，无可信租户记保留值（探针表无 DDL 默认值）")
    void realOperLogWriterCoversBothAttributions() throws Exception {
        SysOperLogMapper mapper = session.getMapper(SysOperLogMapper.class);
        SysOperLogServiceImpl service = new SysOperLogServiceImpl(mapper);

        SysOperLogBo trusted = new SysOperLogBo();
        trusted.setTenantId("T-A");
        trusted.setOperId(1001L);
        trusted.setTitle("trusted");
        service.insertOperlog(trusted);

        SysOperLogBo unattributed = new SysOperLogBo();
        unattributed.setTenantId(null);
        unattributed.setOperId(1002L);
        unattributed.setTitle("unattributed");
        service.insertOperlog(unattributed);

        assertThat(countOperLog()).as("两条审计行都必须落库（无归属的不得丢行）").isEqualTo(2);
        assertThat(operLogTenant(1001L)).as("有归属 ⇒ 真实租户").isEqualTo("T-A");
        assertThat(operLogTenant(1002L))
                .as("无归属 ⇒ 保留值；探针表没有 DDL 默认值，靠默认值不可能成功")
                .isEqualTo(RESERVED);
    }

    @Test
    @DisplayName("验收1：登录审计写入器——核验过的租户记真实租户，未核验的记保留值")
    void realLogininforWriterCoversBothAttributions() throws Exception {
        SysLogininforMapper mapper = session.getMapper(SysLogininforMapper.class);
        SysLogininforServiceImpl service =
                new SysLogininforServiceImpl(mapper, mockClientService());

        SysLogininforBo verified = new SysLogininforBo();
        verified.setTenantId("T-A");
        verified.setTenantVerified(true);
        verified.setInfoId(2001L);
        verified.setUserName("verified");
        service.insertLogininfor(verified);

        SysLogininforBo declaredOnly = new SysLogininforBo();
        declaredOnly.setTenantId("T-B");
        declaredOnly.setTenantVerified(false);   // 请求自行声明，服务端未核验
        declaredOnly.setInfoId(2002L);
        declaredOnly.setUserName("declared");
        service.insertLogininfor(declaredOnly);

        assertThat(scalar("SELECT count(*) FROM " + schema + ".sys_logininfor")).isEqualTo(2);
        assertThat(logininforTenant(2001L)).isEqualTo("T-A");
        assertThat(logininforTenant(2002L))
                .as("未核验的声明租户不得冒充该租户")
                .isEqualTo(RESERVED);
    }

    private String logininforTenant(long id) throws Exception {
        try (Connection connection = source.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT tenant_id FROM " + schema + ".sys_logininfor WHERE info_id = ?")) {
            query.setLong(1, id);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private ISysClientService mockClientService() {
        return Mockito.mock(ISysClientService.class);
    }

    // ------------------------------------------------------------------ 验收 2：读侧隔离

    @Test
    @DisplayName("验收2：真实租户 A/B/000000 的查询都读不到无归属行；平台审计上下文能读到")
    void tenantScopedReadsCannotReachUnattributedRows() throws Exception {
        SysOperLogMapper mapper = session.getMapper(SysOperLogMapper.class);
        SysOperLogServiceImpl service = new SysOperLogServiceImpl(mapper);

        for (Object[] row : new Object[][]{{3001L, "T-A"}, {3002L, "T-B"}, {3003L, "000000"}, {3004L, null}}) {
            SysOperLogBo bo = new SysOperLogBo();
            bo.setOperId((Long) row[0]);
            bo.setTenantId((String) row[1]);
            bo.setTitle("row-" + row[0]);
            service.insertOperlog(bo);
        }
        assertThat(countOperLog()).isEqualTo(4);

        for (String tenant : new String[]{"T-A", "T-B", "000000"}) {
            TenantHelper.setDynamic(tenant, false);
            List<?> visible = mapper.selectList(null);
            assertThat(visible).as("租户 %s 只应看到自己的行", tenant).hasSize(1);
            TenantHelper.clearDynamic();
        }

        // 平台审计读：只返回无归属行
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isSuperAdmin).thenReturn(true);
            login.when(LoginHelper::getUserId).thenReturn(1L);
            assertThat(service.selectPlatformAudit(PlatformAuditAccess.requirePlatformSuperAdmin()))
                    .as("平台审计上下文能读到无归属行")
                    .hasSize(1);
        }

        // 伪造：保留值不是租户，不得作为切租户目标——设成它会被直接拒绝，
        // 因此"伪造保留值"读不到任何东西，也不构成一种访问能力
        TenantHelper.clearDynamic();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TenantHelper.setDynamic(RESERVED, false))
                .as("保留值不得作为普通动态切租户目标")
                .isInstanceOf(org.ruoyi.common.tenant.exception.TenantException.class)
                .hasFieldOrPropertyWithValue("code", "tenant.context.audit.marker.not.a.tenant");
        assertThat(TenantHelper.getTenantId()).as("拒绝后仍无上下文").isNull();
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isSuperAdmin).thenReturn(false);
            login.when(LoginHelper::getUserId).thenReturn(2018858143199662082L);
            assertThat(org.assertj.core.api.Assertions.catchThrowable(PlatformAuditAccess::requirePlatformSuperAdmin))
                    .as("非超管（含租户管理员）拿不到平台审计凭据")
                    .isInstanceOf(org.ruoyi.common.core.exception.ServiceException.class);
        }
    }

    // ------------------------------------------------------------------ R-2-R5：管理操作 + 注册表无例外

    @Test
    @DisplayName("R-2-R5 管理操作：租户上下文下删不到无归属行；无上下文的删除被拒")
    void tenantScopedManagementCannotTouchUnattributedRows() throws Exception {
        SysOperLogMapper mapper = session.getMapper(SysOperLogMapper.class);
        SysOperLogServiceImpl service = new SysOperLogServiceImpl(mapper);

        SysOperLogBo own = new SysOperLogBo();
        own.setOperId(4001L);
        own.setTenantId("T-A");
        own.setTitle("own");
        service.insertOperlog(own);

        SysOperLogBo unattributed = new SysOperLogBo();
        unattributed.setOperId(4002L);
        unattributed.setTenantId(null);
        unattributed.setTitle("unattributed");
        service.insertOperlog(unattributed);
        assertThat(countOperLog()).isEqualTo(2);

        // 1) T-A 上下文按主键删除无归属行：影响 0 行，行仍存活
        TenantHelper.setDynamic("T-A", false);
        assertThat(mapper.deleteById(4002L)).as("租户删不到无归属行").isZero();
        assertThat(operLogTenant(4002L)).as("无归属行必须存活").isEqualTo(RESERVED);
        // 2) 真实默认租户 000000 同样删不到
        TenantHelper.clearDynamic();
        TenantHelper.setDynamic("000000", false);
        assertThat(mapper.deleteById(4002L)).isZero();
        assertThat(operLogTenant(4002L)).isEqualTo(RESERVED);
        // 3) "清理"整表的 wrapper 删除：T-A 上下文只删自己的行
        TenantHelper.clearDynamic();
        TenantHelper.setDynamic("T-A", false);
        int cleaned = mapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>());
        assertThat(cleaned).as("清理只影响本租户的行").isEqualTo(1);
        assertThat(operLogTenant(4002L)).as("清理不得触及无归属行").isEqualTo(RESERVED);
        assertThat(operLogTenant(4001L)).as("本租户的行被清掉").isNull();
        // 4) 无任何上下文的管理操作：在数据库操作前被拒
        TenantHelper.clearDynamic();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> mapper.deleteById(4002L)))
                .as("无上下文的管理操作仍被拒绝")
                .isInstanceOf(Exception.class);
        assertThat(operLogTenant(4002L)).isEqualTo(RESERVED);
    }

    @Test
    @DisplayName("R-2-R5 注册表无例外：sys_user 在租户上下文内可写，缺上下文时仍被拒")
    void sysUserIsNotCoveredByTheAuditException() throws Exception {
        SysUserMapper userMapper = session.getMapper(SysUserMapper.class);

        TenantHelper.setDynamic("T-A", false);
        SysUser user = new SysUser();
        user.setUserId(5001L);
        user.setTenantId("T-A");
        user.setUserName("legal");
        assertThat(userMapper.insert(user)).as("合法注册的写在租户作用域内成功").isEqualTo(1);
        TenantHelper.clearDynamic();

        // 同一条语句在没有上下文时仍被拒绝——sys_user 不在审计例外之列
        SysUser orphan = new SysUser();
        orphan.setUserId(5002L);
        orphan.setTenantId("T-A");
        orphan.setUserName("orphan");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> userMapper.insert(orphan)))
                .as("sys_user 不享有审计例外，缺上下文必须拒绝")
                .isInstanceOf(Exception.class);

        try (Connection connection = source.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT count(*) FROM " + schema + ".sys_user");
             ResultSet rs = query.executeQuery()) {
            rs.next();
            assertThat(rs.getLong(1)).as("只有租户作用域内的那一行落库").isEqualTo(1);
        }
    }
}
