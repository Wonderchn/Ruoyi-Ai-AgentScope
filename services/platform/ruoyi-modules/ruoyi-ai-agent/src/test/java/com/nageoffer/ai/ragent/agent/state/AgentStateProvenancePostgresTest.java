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

package com.nageoffer.ai.ragent.agent.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.authorization.dao.AiAclEpochMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceAclMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper;
import com.nageoffer.ai.ragent.authorization.dao.ResourceSourceRefMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F16-WIRE / F-AC6-1：{@code ai_agent_state} 写侧 provenance 的真库判据（红先行主件）。
 *
 * <p><b>为什么必须有这一条。</b>{@code PgAgentStateStore.queryPayload} 在
 * {@code provenanceRequired}（内嵌形态恒 true）时要求三列 provenance 非空且
 * {@link ResourceAuthorizationService#sourcesCurrent} 通过，否则<b>静默返回 null</b>——
 * 而写侧 {@code AgentStateMapper.upsert} 的 {@code ON CONFLICT ... DO UPDATE} 曾把三列
 * <b>恒清 NULL</b>：于是真实轮次写出的行永远读不回，框架每轮退回 {@code freshState}，
 * state 上下文不跨轮积累（F16:2/3 压缩门结构性不可达的根因）。
 * 本测试把"写出的行必须能被同一主体读回"钉在真 PG 上：修前红、修后绿。
 *
 * <p><b>夹具形状（不 stub 授权服务）。</b>真 {@link AiResourceAuthorizationService}
 * （FactPort/SubjectMatchPort 同一实例）＋真 DAO 读真表：
 * <ul>
 *   <li>registry：{@code ai_resource(CONVERSATION, ACTIVE, resource_version=1)}，owner=主体成员；</li>
 *   <li>ACL：{@code ai_resource_acl(subject_type=MEMBER, subject_id=<membershipId>,
 *       action='conversation.read')}；</li>
 *   <li>epoch：{@code ai_acl_epoch(version=3)} 与主体 av 一致（读闸第二道）；</li>
 *   <li>表形状从冻结迁移抽取（建表＋后续 ALTER，同 FrozenTableDdl 语义，不跨模块引用
 *       runtime 测试源码）；V7 里 provenance 三列来自 DO 动态块，正则抽不到，按该块
 *       原文在本测试内补齐（列与约束名逐字同源）。</li>
 * </ul>
 *
 * <p><b>负例不弱化。</b>无主体 / 无 registry 行（非会话 session）/ 非 ACTIVE /
 * resource_version 漂移 / epoch 漂移 / 跨租户，六条在修前修后都必须"读不到"；
 * 每条同时断言"保存仍成功且三列写 NULL"——降级写 NULL 与修前行为逐字一致，
 * 且保存失败不得阻断框架轮末回调链。
 *
 * <p>每用例独立随机 schema，结束后 DROP CASCADE 自清；连接与凭据同既有真库先例
 * （{@code ragent.state.provenance.test.jdbc-url} 门控；用户名/口令只经
 * {@code RAGENT_TEST_PG_USER}/{@code RAGENT_TEST_PG_PASSWORD} 环境注入，不落盘）。
 */
@Tag("dev")
@EnabledIfSystemProperty(named = AgentStateProvenancePostgresTest.URL_PROPERTY, matches = ".+",
        disabledReason = "Requires private PostgreSQL via -D" + AgentStateProvenancePostgresTest.URL_PROPERTY
                + "=jdbc:postgresql://host:port/db")
class AgentStateProvenancePostgresTest {

    static final String URL_PROPERTY = "ragent.state.provenance.test.jdbc-url";
    static final String USER_PROPERTY = "ragent.state.provenance.test.jdbc-user";
    static final String PASSWORD_PROPERTY = "ragent.state.provenance.test.jdbc-password";

    private static final String TENANT = "t-f16wire-a";
    private static final String OTHER_TENANT = "t-f16wire-b";
    private static final String USER_ID = "2101";
    private static final String OTHER_USER_ID = "3102";
    private static final String MEMBER = "platform:" + TENANT + ":" + USER_ID;
    private static final String OTHER_MEMBER = "platform:" + OTHER_TENANT + ":" + OTHER_USER_ID;
    private static final String SESSION_ID = "conv-f16wire-1";
    private static final String STATE_KEY = "agent_state";
    private static final int POLICY_VERSION = 7;
    private static final int ACL_VERSION = 3;
    /** 冻结迁移里这些表的 DDL 来源；顺序即 FK 依赖序（acl 引用 resource）。 */
    private static final List<String> TABLES =
            List.of("ai_resource", "ai_resource_acl", "ai_acl_epoch", "ai_agent_state");

    private PGSimpleDataSource adminSource;
    private PGSimpleDataSource source;
    private String schema;
    private JdbcTemplate jdbc;
    private SqlSession session;
    private PgAgentStateStore store;

    @BeforeAll
    static void initJsonCodec() {
        // payload 编解码依赖 AgentScope 的静态 codec；测试 JVM 无人装配它
        JsonUtils.resetToDefault();
    }

    @BeforeEach
    void setUp() throws Exception {
        schema = "r17f16wire_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getProperty(URL_PROPERTY);
        adminSource = newSource(url);
        try (Connection connection = adminSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        source = newSource(url, schema);
        jdbc = new JdbcTemplate(source);
        for (String table : TABLES) {
            for (String ddl : frozenDdl(table)) {
                jdbc.execute(ddl);
            }
        }
        for (String ddl : provenanceColumnDdl()) {
            jdbc.execute(ddl);
        }
        session = mybatisSession(source);
        store = new PgAgentStateStore(session.getMapper(AgentStateMapper.class));
        asPrincipal(TENANT, USER_ID);
    }

    @AfterEach
    void tearDown() throws Exception {
        PrincipalContext.clear();
        try {
            if (session != null) {
                session.close();
            }
        } finally {
            if (schema != null && adminSource != null) {
                try (Connection connection = adminSource.getConnection();
                     var statement = connection.createStatement()) {
                    statement.execute("DROP SCHEMA " + schema + " CASCADE");
                }
            }
        }
    }

    // ------------------------------------------------------------ 红/绿主件

    @Test
    @DisplayName("活会话事实被写成 provenance：同一主体 save→get 必须读回既有 state，且库中三列是活事实实值")
    void liveConversationFactIsPersistedAsProvenanceAndStateReadsBack() throws Exception {
        seedConversation(TENANT, SESSION_ID, MEMBER, "ACTIVE", 1L);
        seedOwnerAcl(TENANT, SESSION_ID, MEMBER);
        seedAclEpoch(TENANT, ACL_VERSION);
        storeWithLiveAuthorization();

        store.save(USER_ID, SESSION_ID, STATE_KEY, new PayloadState("v1"));

        // 判据主体：写侧 provenance 与读闸同源；修前 upsert 恒清三列 ⇒ 这里返回 empty
        assertThat(store.get(USER_ID, SESSION_ID, STATE_KEY, PayloadState.class))
                .as("provenance 必须能过读闸：写出的行必须被同一主体读回")
                .contains(new PayloadState("v1"));

        Row row = stateRow(TENANT, MEMBER, SESSION_ID);
        assertThat(row.payload()).as("payload 本体照写").isNotNull();
        assertLiveConversationRefs(row.sourceRefs(), SESSION_ID, 1);
        assertThat(row.policyVersion()).as("pv 取主体实值").isEqualTo(POLICY_VERSION);
        assertThat(row.aclVersion()).as("av 取主体实值").isEqualTo(ACL_VERSION);

        // 真实轮次是同一键反复 upsert（框架轮末回调每轮都写）：冲突分支必须同样保留 provenance
        // ——历史缺陷正是那里的 DO UPDATE SET source_* = NULL，只测首次插入会漏掉它
        store.save(USER_ID, SESSION_ID, STATE_KEY, new PayloadState("v2"));
        assertThat(store.get(USER_ID, SESSION_ID, STATE_KEY, PayloadState.class))
                .as("冲突分支不得清掉 provenance：第二轮起的 state 也必须读回")
                .contains(new PayloadState("v2"));
        assertThat(stateRow(TENANT, MEMBER, SESSION_ID).payload()).contains("v2");
    }

    // ------------------------------------------------------------ 负例（修前修后都必须读不到）

    @Test
    @DisplayName("缺执行主体：save/get 在 DAO 之前拒绝，零落库")
    void missingPrincipalIsRejectedBeforeAnyDaoAccess() {
        seedConversation(TENANT, SESSION_ID, MEMBER, "ACTIVE", 1L);
        seedOwnerAcl(TENANT, SESSION_ID, MEMBER);
        seedAclEpoch(TENANT, ACL_VERSION);
        storeWithLiveAuthorization();
        PrincipalContext.clear();

        assertThatThrownBy(() -> store.save(USER_ID, SESSION_ID, STATE_KEY, new PayloadState("v1")))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> store.get(USER_ID, SESSION_ID, STATE_KEY, PayloadState.class))
                .isInstanceOf(ClientException.class);

        // 拒绝发生在任何 DAO 访问之前：匿名流量没有落库路径
        assertThat(rowsInStateTable()).as("缺主体不得产生任何行").isZero();
    }

    @Test
    @DisplayName("session 不是会话资源（无 registry 行）：保存成功但三列 NULL，读闸前 fail-closed")
    void sessionWithoutConversationRegistryIsSavedUnprovenancedAndUnreadable() {
        // 只给 epoch：证明"读不到"不是因为租户未开通，而是因为没有可核验的会话事实
        // （registry 行被硬删是同一条事实缺席路径）
        seedAclEpoch(TENANT, ACL_VERSION);
        storeWithLiveAuthorization();

        store.save(USER_ID, SESSION_ID, STATE_KEY, new PayloadState("v1"));

        Row row = stateRow(TENANT, MEMBER, SESSION_ID);
        assertThat(row.payload()).as("保存不得被 provenance 解析失败阻断").isNotNull();
        assertThat(row.sourceRefs()).isNull();
        assertThat(row.policyVersion()).isNull();
        assertThat(row.aclVersion()).isNull();
        assertThat(store.get(USER_ID, SESSION_ID, STATE_KEY, PayloadState.class))
                .as("无活会话事实 ⇒ 读不到（fail-closed 不弱化）").isEmpty();
    }

    @Test
    @DisplayName("registry 行非 ACTIVE（tombstone）：保存成功但三列 NULL，读闸前 fail-closed")
    void tombstonedConversationRegistryIsSavedUnprovenancedAndUnreadable() {
        seedConversation(TENANT, SESSION_ID, MEMBER, "TOMBSTONED", 2L);
        seedOwnerAcl(TENANT, SESSION_ID, MEMBER);
        seedAclEpoch(TENANT, ACL_VERSION);
        storeWithLiveAuthorization();

        store.save(USER_ID, SESSION_ID, STATE_KEY, new PayloadState("v1"));

        Row row = stateRow(TENANT, MEMBER, SESSION_ID);
        assertThat(row.payload()).isNotNull();
        assertThat(row.sourceRefs()).isNull();
        assertThat(row.policyVersion()).isNull();
        assertThat(row.aclVersion()).isNull();
        assertThat(store.get(USER_ID, SESSION_ID, STATE_KEY, PayloadState.class))
                .as("撤权/tombstone 后旧的 provenance 不得复活，新写入也不得带 provenance").isEmpty();
    }

    @Test
    @DisplayName("写入后 resource_version 漂移（rename bump）：既定 provenance 与当前事实不符，读不到")
    void resourceVersionDriftAfterWriteMakesStateUnreadable() throws Exception {
        seedConversation(TENANT, SESSION_ID, MEMBER, "ACTIVE", 1L);
        seedOwnerAcl(TENANT, SESSION_ID, MEMBER);
        seedAclEpoch(TENANT, ACL_VERSION);
        storeWithLiveAuthorization();

        store.save(USER_ID, SESSION_ID, STATE_KEY, new PayloadState("v1"));
        // 写侧确实落了当时的活事实（不是"什么都没写"导致的读不到）
        assertLiveConversationRefs(stateRow(TENANT, MEMBER, SESSION_ID).sourceRefs(), SESSION_ID, 1);

        jdbc.update("UPDATE ai_resource SET resource_version = resource_version + 1"
                + " WHERE tenant_id = ? AND resource_type = 'CONVERSATION' AND resource_id = ?", TENANT, SESSION_ID);

        assertThat(store.get(USER_ID, SESSION_ID, STATE_KEY, PayloadState.class))
                .as("版本失配 ⇒ 读不到（provenance 是版本化事实，不是一次性通行证）").isEmpty();
    }

    @Test
    @DisplayName("写入后 epoch 漂移（撤权 bump）：既定 av 与当前 epoch 不符，读不到")
    void aclEpochDriftAfterWriteMakesStateUnreadable() {
        seedConversation(TENANT, SESSION_ID, MEMBER, "ACTIVE", 1L);
        seedOwnerAcl(TENANT, SESSION_ID, MEMBER);
        seedAclEpoch(TENANT, ACL_VERSION);
        storeWithLiveAuthorization();

        store.save(USER_ID, SESSION_ID, STATE_KEY, new PayloadState("v1"));
        assertThat(stateRow(TENANT, MEMBER, SESSION_ID).aclVersion()).isEqualTo(ACL_VERSION);

        jdbc.update("UPDATE ai_acl_epoch SET version = version + 1 WHERE tenant_id = ?", TENANT);

        assertThat(store.get(USER_ID, SESSION_ID, STATE_KEY, PayloadState.class))
                .as("epoch 漂移 ⇒ 读不到（撤权屏障对 state 回读同样生效）").isEmpty();
    }

    @Test
    @DisplayName("跨租户：他租户主体读同一 session 读不到，且不串改本租户的行")
    void crossTenantPrincipalCannotReadForeignConversationState() throws Exception {
        seedConversation(TENANT, SESSION_ID, MEMBER, "ACTIVE", 1L);
        seedOwnerAcl(TENANT, SESSION_ID, MEMBER);
        seedAclEpoch(TENANT, ACL_VERSION);
        // 他租户自己的活事实齐备（版本刻意不同：证明事实读取按调用方租户收窄）：
        // 读不到只能归因于键不同，不是他租户"未开通"
        seedConversation(OTHER_TENANT, SESSION_ID, OTHER_MEMBER, "ACTIVE", 5L);
        seedOwnerAcl(OTHER_TENANT, SESSION_ID, OTHER_MEMBER);
        seedAclEpoch(OTHER_TENANT, ACL_VERSION);
        storeWithLiveAuthorization();

        store.save(USER_ID, SESSION_ID, STATE_KEY, new PayloadState("tenant-a"));
        asPrincipal(OTHER_TENANT, OTHER_USER_ID);
        store.save(OTHER_USER_ID, SESSION_ID, STATE_KEY, new PayloadState("tenant-b"));

        assertThat(store.get(OTHER_USER_ID, SESSION_ID, STATE_KEY, PayloadState.class))
                .as("他租户主体只能读到自己的行").contains(new PayloadState("tenant-b"));
        // 键的前两维取自执行主体（userId 入参只是展示引用）：他租户用同一 session/key 发问，
        // 命中的必然是自己的行——对方那一行在结构上就够不到
        assertThat(store.get(USER_ID, SESSION_ID, STATE_KEY, PayloadState.class))
                .as("同名 session/key：键由主体决定，读到的是自己那行而不是对方行")
                .contains(new PayloadState("tenant-b"));
        assertThat(jdbc.queryForList("SELECT payload->>'value' FROM ai_agent_state WHERE tenant_id = ?",
                String.class, TENANT))
                .as("对方租户的行仍是自己的 payload，未被跨租户写入覆盖").containsExactly("tenant-a");

        asPrincipal(TENANT, USER_ID);
        assertThat(store.get(USER_ID, SESSION_ID, STATE_KEY, PayloadState.class))
                .as("本租户的行不受他租户写入影响").contains(new PayloadState("tenant-a"));
        assertThat(rowsInStateTable()).as("两租户各一行，物理隔离").isEqualTo(2);
        assertLiveConversationRefs(stateRow(TENANT, MEMBER, SESSION_ID).sourceRefs(), SESSION_ID, 1);
        assertLiveConversationRefs(stateRow(OTHER_TENANT, OTHER_MEMBER, SESSION_ID).sourceRefs(), SESSION_ID, 5);
    }

    // ------------------------------------------------------------ 夹具

    private PgAgentStateStore storeWithLiveAuthorization() {
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(source);
        ResourceAuthorizationService authorization = new AiResourceAuthorizationService(
                new AiResourceMapper(named), new AiResourceAclMapper(named), new AiAclEpochMapper(named),
                new ResourceSourceRefMapper(named), Clock.systemUTC());
        store.configureSources(true, provider(authorization));
        return store;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ResourceAuthorizationService> provider(ResourceAuthorizationService value) {
        ObjectProvider<ResourceAuthorizationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static void asPrincipal(String tenantId, String userId) {
        PrincipalContext.set(new ExecutionPrincipal(tenantId, userId, "platform:" + tenantId + ":" + userId,
                POLICY_VERSION, ACL_VERSION, Set.of("conversation.read"), "jti-" + tenantId, "platform",
                1_700_000_000L, 4_000_000_000L));
    }

    private void seedConversation(String tenantId, String sessionId, String memberId, String status, long version) {
        jdbc.update("INSERT INTO ai_resource (tenant_id, resource_type, resource_id, owner_member_id,"
                        + " status, resource_version) VALUES (?, 'CONVERSATION', ?, ?, ?, ?)",
                tenantId, sessionId, memberId, status, version);
    }

    private void seedOwnerAcl(String tenantId, String sessionId, String memberId) {
        jdbc.update("INSERT INTO ai_resource_acl (id, tenant_id, resource_type, resource_id,"
                        + " subject_type, subject_id, action, granted_by) VALUES (?, ?, 'CONVERSATION', ?,"
                        + " 'MEMBER', ?, 'conversation.read', ?)",
                "acl-" + UUID.randomUUID().toString().substring(0, 8), tenantId, sessionId, memberId, memberId);
    }

    private void seedAclEpoch(String tenantId, int version) {
        jdbc.update("INSERT INTO ai_acl_epoch (tenant_id, version) VALUES (?, ?)", tenantId, version);
    }

    private long rowsInStateTable() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM ai_agent_state", Long.class);
        return count == null ? 0 : count;
    }

    private Row stateRow(String tenantId, String memberId, String sessionId) {
        return jdbc.queryForObject("SELECT source_refs::text AS source_refs, source_policy_version,"
                        + " source_acl_version, payload::text AS payload FROM ai_agent_state"
                        + " WHERE tenant_id = ? AND member_id = ? AND session_id = ? AND state_key = ?",
                (rs, rowNum) -> new Row(rs.getString("source_refs"), rs.getObject("source_policy_version", Integer.class),
                        rs.getObject("source_acl_version", Integer.class), rs.getString("payload")),
                tenantId, memberId, sessionId, STATE_KEY);
    }

    private static void assertLiveConversationRefs(String sourceRefs, String sessionId, int expectedVersion)
            throws Exception {
        assertThat(sourceRefs).as("source_refs 必须是活会话事实的 JSON 数组").isNotNull();
        var refs = new ObjectMapper().readTree(sourceRefs);
        assertThat(refs.isArray()).isTrue();
        assertThat(refs.size()).as("单 ref 形态：只有会话自指一条").isEqualTo(1);
        assertThat(refs.get(0).size()).as("每项恰两键 ref/version").isEqualTo(2);
        assertThat(refs.get(0).path("ref").asText()).isEqualTo("conv:" + sessionId);
        assertThat(refs.get(0).path("version").asInt()).isEqualTo(expectedVersion);
    }

    /** 一行 provenance 快照；三列为 NULL 时字段为 null（与"读闸拒绝"一一对应）。 */
    private record Row(String sourceRefs, Integer policyVersion, Integer aclVersion, String payload) {
    }

    /**Jackson 可序列化的最小 State 实现（同 P1 测试先例）。 */
    private record PayloadState(String value) implements State {
    }

    // ------------------------------------------------------------ 真库装配

    private static SqlSession mybatisSession(DataSource dataSource) {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("r17-f16-wire", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(AgentStateMapper.class);
        // 自动提交会话：state 保存是框架轮末回调，写入即落库
        return new SqlSessionFactoryBuilder().build(configuration).openSession(true);
    }

    private static PGSimpleDataSource newSource(String url) {
        return newSource(url, null);
    }

    private static PGSimpleDataSource newSource(String url, String searchPath) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        String effective = searchPath == null ? url
                : url + (url.contains("?") ? "&" : "?") + "currentSchema=" + searchPath;
        dataSource.setUrl(effective);
        // 凭据优先经环境注入（不进命令行/构建 anchor）；系统属性仅作显式覆盖
        String user = envOrProp("RAGENT_TEST_PG_USER", USER_PROPERTY);
        if (user != null && !user.isBlank()) {
            dataSource.setUser(user);
        }
        String password = envOrProp("RAGENT_TEST_PG_PASSWORD", PASSWORD_PROPERTY);
        if (password != null && !password.isBlank()) {
            dataSource.setPassword(password);
        }
        return dataSource;
    }

    private static String envOrProp(String envKey, String propKey) {
        String value = System.getenv(envKey);
        return value != null ? value : System.getProperty(propKey);
    }

    /**
     * 冻结迁移 DDL 抽取（FrozenTableDdl 同法，本测试内实现，不跨模块引用 runtime 测试源码）：
     * 按版本序抽 {@code CREATE TABLE platform.<t>} 与后续 {@code ALTER TABLE platform.<t>}
     * （含 PK 换复合键、tenant/member 列与 NOT NULL），再把 {@code platform.} 前缀重写进随机 schema。
     */
    private List<String> frozenDdl(String table) throws IOException {
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
            Matcher created = create.matcher(sql);
            if (created.find()) {
                out.add(created.group().replace("platform.", schema + "."));
            }
            Matcher altered = alter.matcher(sql);
            while (altered.find()) {
                out.add(altered.group().replace("platform.", schema + "."));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("冻结迁移里找不到 " + table + " 的 DDL");
        }
        return out;
    }

    /**
     * V7 的 provenance 三列来自 {@code DO $migration$ ... EXECUTE format('ALTER TABLE platform.%I
     * ADD COLUMN source_refs jsonb, ...')} 动态块（目标表是循环变量，文本抽取够不到），
     * 这里按该块原文把 ai_agent_state 的三列与三条 CHECK 补齐，约束名同源
     * （{@code <table>_source_pv_positive} / {@code _source_av_positive} / {@code _source_refs_array}）。
     */
    private static List<String> provenanceColumnDdl() {
        return List.of(
                "ALTER TABLE ai_agent_state ADD COLUMN source_refs jsonb,"
                        + " ADD COLUMN source_policy_version integer, ADD COLUMN source_acl_version integer",
                "ALTER TABLE ai_agent_state ADD CONSTRAINT ai_agent_state_source_pv_positive"
                        + " CHECK (source_policy_version IS NULL OR source_policy_version > 0)",
                "ALTER TABLE ai_agent_state ADD CONSTRAINT ai_agent_state_source_av_positive"
                        + " CHECK (source_acl_version IS NULL OR source_acl_version > 0)",
                "ALTER TABLE ai_agent_state ADD CONSTRAINT ai_agent_state_source_refs_array"
                        + " CHECK (source_refs IS NULL OR jsonb_typeof(source_refs) = 'array')");
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

    private static int versionOf(Path path) {
        Matcher matcher = Pattern.compile("^V(\\d+)__").matcher(path.getFileName().toString());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
