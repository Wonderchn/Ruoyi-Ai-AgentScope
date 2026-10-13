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

package org.ruoyi.aiidentity;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.nageoffer.ai.ragent.authorization.TenantConversationReadRepository;
import com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.runtime.port.ConversationHistoryPort;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.MyBatisSystemException;
import org.ruoyi.aiweb.embedded.AiEmbeddedFeedbackConfiguration;
import org.ruoyi.aiweb.embedded.AiEmbeddedMapperConfiguration;
import org.ruoyi.common.core.utils.SpringUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.tenant.exception.TenantException;
import org.ruoyi.common.tenant.handle.PlusTenantLineHandler;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.common.tenant.properties.TenantProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * s2-chathist-a2（P0）：<b>带 platform 租户行拦截器的聊天历史写链</b>判据。
 *
 * <p><b>要证的缺陷（真机原文，r17/p1win AC4 f03）。</b>带 {@code conversationId} 的
 * {@code rag.chat} run 在终态写聊天历史时（{@code RagChatExecutor → ConversationHistoryAdapter
 * → ConversationMessageMapper.insert}）被 platform MyBatis 租户拦截器拒绝：
 * {@code PlusTenantLineHandler} 对租户业务表 {@code ai_message} 要求可信租户上下文，
 * 而 p2-worker 线程既无登录主体也无动态租户 ⇒ run 直接 FAILED（ai_message rows=0）。
 * 单测与本地真库 E2E 没抓住，是因为 {@code ruoyi-ai-rag}/{@code ruoyi-ai-web} 的测试类路径
 * <b>看不到</b> {@code org.ruoyi.common.tenant.*}（AI 侧模块经 {@code TenantScopeBinder}
 * 端口桥接，不依赖 platform 命名空间）——只有部署装配（本模块 ruoyi-admin）同时看得见
 * 两者，与真机同一条类路径。</p>
 *
 * <p><b>链。</b>真 {@code PlusTenantLineHandler} + 真 {@code TenantLineInnerInterceptor}
 * （{@link MybatisPlusInterceptor} 第一位，与 {@code MybatisPlusConfig} 同序）
 * → 真 {@code ConversationMessageMapper}（{@code platform.ai_message}，真 PostgreSQL，
 * 冻结迁移逐条抽取的 DDL）→ 真 {@code ConversationMessageServiceImpl}
 * → 生产装配的 {@link ConversationHistoryPort} 适配器（{@code AiEmbeddedFeedbackConfiguration}
 * 的 {@code conversationHistoryPort} @Bean，经 {@link RuoYiTenantScopeBinder} 提供租户端口）。</p>
 *
 * <p><b>两条判据。</b>
 * <ol>
 *   <li><b>正例</b>：工作线程（无登录主体/无动态租户，与 p2-worker 同形）上一次
 *       {@code appendTurn} 必须过门并落下两行；写点探针必须看到<b>运行态租户</b>
 *       （不是别的值）、调用返回后动态租户必须复原（不然=把租户泄漏给同线程后续任务）；
 *       DB 行必须带 run 身份（tenant/member/user）、助手行回指用户行。</li>
 *   <li><b>负例（反豁免）</b>：同一条链<b>撤掉租户绑定端口</b>后，写入必须在数据库操作前
 *       被拒（原文指纹：{@code TenantException} code={@code tenant.context.missing}，
 *       经 {@code MyBatisSystemException} 包裹于 {@code ConversationMessageMapper.insert}），
 *       且零行落库。若有人把修复做成 {@code ignore}/{@code @InterceptorIgnore} 之类的
 *       "豁免"，本判据会红。</li>
 * </ol>
 *
 * <p><b>需要隔离 PostgreSQL（本类自建/自删 platform 表）：</b>
 * {@code -Dragent.conversation.test.jdbc-url=jdbc:postgresql://host:port/db}
 * （凭据可经 {@code -D…jdbc-user} / {@code -D…jdbc-password} 覆盖，缺省 {@code postgres}/空）；
 * 未提供时显式跳过，不会静默变绿。</p>
 */
@Tag("dev")
@EnabledIfSystemProperty(named = ConversationHistoryTenantGateChainTest.URL_PROPERTY, matches = ".+",
        disabledReason = "需要隔离 PostgreSQL：-D" + ConversationHistoryTenantGateChainTest.URL_PROPERTY
                + "=jdbc:postgresql://host:port/db")
class ConversationHistoryTenantGateChainTest {

    static final String URL_PROPERTY = "ragent.conversation.test.jdbc-url";
    static final String USER_PROPERTY = "ragent.conversation.test.jdbc-user";
    static final String PASSWORD_PROPERTY = "ragent.conversation.test.jdbc-password";

    // ---------------------------------------------------------------- 数据锚点
    private static final String TENANT = "T-HIST-A2";
    private static final String USER = "9201";
    private static final String MEMBER = "platform:T-HIST-A2:9201";
    private static final String CONV = "conv-hist-a2-1";
    private static final String CONV_ROW_ID = "c-hist-a2-1";
    private static final String QUESTION = "chathist-a2 租户门链问题 4d17";
    private static final String ANSWER = "带租户门的写链回答";

    /** 负例腿：同链但上下文里<b>没有</b>租户绑定端口（=撤掉修复的形态）。 */
    private static final String TENANT_NO_BIND = "T-HIST-A2-N";
    private static final String USER_NO_BIND = "9301";
    private static final String MEMBER_NO_BIND = "platform:T-HIST-A2-N:9301";
    private static final String CONV_NO_BIND = "conv-hist-a2-n1";
    private static final String CONV_NO_BIND_ROW_ID = "c-hist-a2-n1";

    /** 与 ruoyi-admin/application.yml 的 tenant.excludes 同源（Handler 构造即校验 ⊆ 代码清单）。 */
    private static final List<String> EXCLUDES = List.of(
            "sys_menu", "sys_tenant", "sys_tenant_package", "sys_role_dept", "sys_role_menu",
            "sys_user_post", "sys_user_role", "sys_client", "sys_oss_config", "flow_spel",
            "ai_flow_trace_run", "ai_flow_trace_node",
            "ai_legacy_user", "ai_provider_envelope", "ai_provider_spend");

    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext contextWithBinder;
    private static AnnotationConfigApplicationContext contextWithoutBinder;

    // ---------------------------------------------------------------- 真库准备

    @BeforeAll
    static void setUp() throws Exception {
        String url = System.getProperty(URL_PROPERTY);
        assertThat(url).as("缺少 -D" + URL_PROPERTY).isNotBlank();
        jdbc = new JdbcTemplate(testDataSource(url));
        createFrozenTables();
        seed();

        contextWithBinder = startContext(ChainWithBinderConfig.class);
        // TenantHelper.isEnable()/getTenantId() 是"静态取 Spring 上下文属性"（hutool SpringUtil）：
        // 显式安装最小环境，让真 Handler 的 fail-closed 判定在不起 Spring Boot 的前提下成立
        // （与 ruoyi-common-tenant 的 R2TenantTestContext 同一手法）。
        new SpringUtils().setApplicationContext(contextWithBinder);
        contextWithoutBinder = startContext(ChainWithoutBinderConfig.class);
    }

    @AfterAll
    static void tearDown() {
        TenantHelper.clearDynamic();
        if (contextWithoutBinder != null) {
            contextWithoutBinder.close();
        }
        if (contextWithBinder != null) {
            contextWithBinder.close();
        }
        if (jdbc != null) {
            jdbc.execute("DROP TABLE IF EXISTS platform.ai_message CASCADE");
            jdbc.execute("DROP TABLE IF EXISTS platform.ai_conversation CASCADE");
        }
    }

    private static AnnotationConfigApplicationContext startContext(Class<?> fixture) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "ai.integration.enabled", "true",
                "ai.integration.transport", "local",
                "tenant.enable", "true")));
        context.register(fixture);
        context.refresh();
        return context;
    }

    /** 测试连接：URL 补 currentSchema，凭据按系统属性覆盖（缺省=便携 PG 口径）。 */
    private static DriverManagerDataSource testDataSource(String rawUrl) {
        String url = rawUrl.contains("currentSchema") ? rawUrl
                : rawUrl + (rawUrl.contains("?") ? "&" : "?") + "currentSchema=platform";
        return new DriverManagerDataSource(url,
                System.getProperty(USER_PROPERTY, "postgres"),
                System.getProperty(PASSWORD_PROPERTY, ""));
    }

    /**
     * 表结构 = <b>冻结迁移逐条抽取</b>（CREATE + 后续 ALTER 原样；与 ai-web 既有
     * {@code LocalConversationHistoryPostgresE2ETest} 的 frozen-subset 同一口径）。
     * 本链只碰两张表：{@code ai_conversation}（归属读 + 外键父行）与 {@code ai_message}（写目标）。
     */
    private static void createFrozenTables() throws IOException {
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_message CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_conversation CASCADE");
        for (String table : List.of("ai_conversation", "ai_message")) {
            for (String ddl : frozenDdl(table)) {
                jdbc.execute(ddl);
            }
        }
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='platform'"
                        + " AND table_name='ai_message'", String.class);
        assertThat(columns)
                .as("ai_message 形状必须来自冻结迁移（含身份列、写侧列与 reply 列）")
                .contains("id", "conversation_id", "user_id", "role", "content", "message_status", "deleted",
                        "tenant_id", "member_id", "create_time", "update_time", "reply_to_message_id",
                        "model_name", "total_tokens");
    }

    /** 从冻结迁移里抽某张表的真实 DDL（CREATE + 全部 ALTER，按版本序）。 */
    private static List<String> frozenDdl(String table) throws IOException {
        Path dir = locate(Path.of("services", "platform", "docs", "script", "sql", "postgres"));
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
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
        assertThat(out).as("冻结迁移里必须能找到 %s 的 DDL", table).isNotEmpty();
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
        throw new IllegalStateException("cannot locate " + relative);
    }

    private static int versionOf(Path p) {
        Matcher m = Pattern.compile("^V(\\d+)__").matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static void seed() {
        jdbc.update("INSERT INTO platform.ai_conversation (id, conversation_id, user_id, title, last_time,"
                        + " create_time, update_time, deleted, tenant_id, member_id)"
                        + " VALUES (?,?,?,?, now(), now(), now(), 0, ?, ?)",
                CONV_ROW_ID, CONV, USER, "chathist-a2 正例", TENANT, MEMBER);
        jdbc.update("INSERT INTO platform.ai_conversation (id, conversation_id, user_id, title, last_time,"
                        + " create_time, update_time, deleted, tenant_id, member_id)"
                        + " VALUES (?,?,?,?, now(), now(), now(), 0, ?, ?)",
                CONV_NO_BIND_ROW_ID, CONV_NO_BIND, USER_NO_BIND, "chathist-a2 负例", TENANT_NO_BIND, MEMBER_NO_BIND);
    }

    // ---------------------------------------------------------------- 判据

    @Test
    @DisplayName("正例：worker 线程写聊天历史必须过真租户门——写点绑定 run 租户、写完复原、两行落库")
    void theWorkerWriteChainPassesTheRealTenantGateWithTheRunDerivedTenant() {
        // 前置：与 p2-worker 同形——线程无登录主体、无动态租户（这正是真机被拒的条件）
        assertThat(LoginHelper.isLogin()).as("判据必须跑在'无登录主体'的线程形态上").isFalse();
        assertThat(TenantHelper.getTenantId()).as("前置：本线程没有动态租户").isBlank();

        TenantWriteProbe probe = contextWithBinder.getBean(TenantWriteProbe.class);
        probe.clear();
        ConversationHistoryPort history = contextWithBinder.getBean(ConversationHistoryPort.class);

        history.appendTurn(TENANT, MEMBER, USER, CONV, QUESTION, ANSWER);

        // ① 写点（每次 ai_message insert）必须看到运行态租户——不是空、也不是别的值
        assertThat(probe.observedTenants())
                .as("两次 insert 各必须处在租户作用域内（否则真机 tenant.context.missing）")
                .containsExactly(TENANT, TENANT);
        // ② 退出后复原：动态租户不得留在工作线程上（共享池线程，泄漏=跨租户污染）
        assertThat(TenantHelper.getDynamic())
                .as("appendTurn 返回后必须复原动态租户（finally 清理，不泄漏给同线程后续任务）")
                .isNull();

        // ③ 真库真行：一轮问答 = 两行，身份列与回复关系逐列核对
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, role, content, user_id, member_id, reply_to_message_id, message_status,"
                        + " model_name, total_tokens FROM platform.ai_message"
                        + " WHERE tenant_id=? AND conversation_id=? AND deleted=0 ORDER BY create_time, id",
                TENANT, CONV);
        assertThat(rows).as("受理轮必须落成 platform.ai_message 两行").hasSize(2);
        Map<String, Object> userRow = byRole(rows, "user");
        Map<String, Object> assistantRow = byRole(rows, "assistant");
        assertThat(userRow.get("content")).isEqualTo(QUESTION);
        assertThat(userRow.get("user_id")).isEqualTo(USER);
        assertThat(userRow.get("member_id")).isEqualTo(MEMBER);
        assertThat(userRow.get("message_status")).isEqualTo("NORMAL");
        assertThat(userRow.get("reply_to_message_id")).isNull();
        assertThat(assistantRow.get("content")).isEqualTo(ANSWER);
        assertThat(assistantRow.get("user_id")).isEqualTo(USER);
        assertThat(assistantRow.get("member_id")).isEqualTo(MEMBER);
        assertThat(assistantRow.get("message_status")).isEqualTo("NORMAL");
        assertThat(assistantRow.get("reply_to_message_id"))
                .as("助手行必须回指本轮用户行").isEqualTo(userRow.get("id"));
    }

    @Test
    @DisplayName("负例：同链撤掉租户绑定端口 ⇒ 数据库操作前按原文拒绝（tenant.context.missing），零行落库")
    void withoutTenantBindingTheSameChainStaysFailClosedWithTheOriginalFingerprint() {
        assertThat(LoginHelper.isLogin()).as("判据必须跑在'无登录主体'的线程形态上").isFalse();
        TenantHelper.clearDynamic();
        ConversationHistoryPort history = contextWithoutBinder.getBean(ConversationHistoryPort.class);

        Throwable thrown = catchThrowable(
                () -> history.appendTurn(TENANT_NO_BIND, MEMBER_NO_BIND, USER_NO_BIND, CONV_NO_BIND,
                        QUESTION, ANSWER));

        // 原文拒绝指纹：ConversationMessageMapper.insert → MyBatisSystemException → TenantException
        // code=tenant.context.missing（与 r17/p1win AC4 真机日志同一判定点）。
        // 若有人把修复做成 ignore/@InterceptorIgnore 之类的"豁免"，这里会直接变绿 —— 判据会红。
        assertThat(thrown).isInstanceOf(MyBatisSystemException.class);
        Throwable root = thrown;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).as("根因必须是租户门拒绝，不是连接/语法/别的错").isInstanceOf(TenantException.class);
        assertThat(((TenantException) root).getCode()).isEqualTo("tenant.context.missing");

        Long rows = jdbc.queryForObject(
                "SELECT count(*) FROM platform.ai_message WHERE tenant_id=?", Long.class, TENANT_NO_BIND);
        assertThat(rows).as("拒绝必须发生在数据库操作前：零行落库").isZero();
        assertThat(TenantHelper.getDynamic()).as("拒绝路径也不得留下动态租户").isNull();
    }

    private static Map<String, Object> byRole(List<Map<String, Object>> rows, String role) {
        return rows.stream().filter(row -> role.equals(row.get("role"))).findFirst()
                .orElseThrow(() -> new AssertionError("no " + role + " row in " + rows));
    }

    // ---------------------------------------------------------------- 装配（与生产同形的写链）

    /**
     * 公共装备：真 PG + 真 MyBatis(-Plus) + 真 mapper 扫描 + 真历史写服务/适配器
     * （{@code LocalTransportAssembly} 是生产 {@code conversationHistoryPort} @Bean 的所在）。
     *
     * <p>与生产唯一的差别：{@code TenantLineInnerInterceptor} 的注册从
     * {@code MybatisPlusConfig}（Spring 上下文取 bean）改为本夹具直接
     * {@code new TenantLineInnerInterceptor(new PlusTenantLineHandler(props))}——
     * 同一构造形状（{@code TenantConfig.MybatisPlusConfiguration} 也这么 new），
     * 链序同为第一位；另加一个只读探针拦截器观察写点租户（不改行为）。
     */
    @Configuration
    @Import({AiEmbeddedFeedbackConfiguration.LocalTransportAssembly.class, AiEmbeddedMapperConfiguration.class})
    static class CommonChainFixtures {

        @Bean
        DataSource dataSource() {
            return testDataSource(System.getProperty(URL_PROPERTY));
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager platformTransactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        TransactionOperations transactionOperations(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        /** 写点探针：每次 Executor.update 记录当时的 TenantHelper.getTenantId()（只读，不改链行为）。 */
        @Bean
        TenantWriteProbe tenantWriteProbe() {
            return new TenantWriteProbe();
        }

        /** MyBatis-Plus：真 Mapper → 真 PG；MetaObjectHandler/IdentifierGenerator 与 platform 侧同语义。 */
        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource, TenantWriteProbe tenantWriteProbe) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setMetaObjectHandler(new MyMetaObjectHandler());
            globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
            factory.setGlobalConfig(globalConfig);
            // 真租户行门（第一位，与 MybatisPlusConfig 链序一致）+ 只读探针
            TenantProperties properties = new TenantProperties();
            properties.setEnable(true);
            properties.setExcludes(EXCLUDES);
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new PlusTenantLineHandler(properties)));
            factory.setPlugins(interceptor, tenantWriteProbe);
            return factory.getObject();
        }

        @Bean
        TenantConversationReadRepository tenantConversationReadRepository(JdbcTemplate jdbcTemplate) {
            return new TenantConversationReadRepository(jdbcTemplate);
        }
    }

    /** 正例：租户绑定端口在位（生产组装里 ruoyi-admin 的 RuoYiTenantScopeBinder，条件同 ai.integration.enabled）。 */
    @Configuration
    @Import(CommonChainFixtures.class)
    static class ChainWithBinderConfig {

        @Bean
        TenantScopeBinder tenantScopeBinder() {
            return new RuoYiTenantScopeBinder();
        }
    }

    /** 负例：同链、同表、同 handler，只是没有租户绑定端口（=修复缺席的形态）。 */
    @Configuration
    @Import(CommonChainFixtures.class)
    static class ChainWithoutBinderConfig {
    }

    /**
     * 写点租户探针：在 {@code Executor.update}（insert 走这里）上记录当时的动态/登录租户。
     * 纯观察，不修改调用；用于把"写点确实处在运行态租户作用域内"变成可断言事实
     * （只绑错值/没绑都能被这条抓住）。
     */
    @Intercepts(@Signature(type = Executor.class, method = "update",
            args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class}))
    static class TenantWriteProbe implements Interceptor {

        private final List<String> observed = Collections.synchronizedList(new ArrayList<>());

        void clear() {
            observed.clear();
        }

        List<String> observedTenants() {
            return List.copyOf(observed);
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            observed.add(TenantHelper.getTenantId());
            return invocation.proceed();
        }
    }
}
