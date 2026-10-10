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

package org.ruoyi.aiweb.embedded;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler;
import com.nageoffer.ai.ragent.ingest.PrivateObjectStore;
import com.nageoffer.ai.ragent.runtime.RunWorker;
import com.nageoffer.ai.ragent.runtime.P2Configuration;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.RunLifecycleService;
import com.nageoffer.ai.ragent.runtime.config.RuntimeAuthorityConfiguration;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.usage.PlatformFactsClient;
import com.nageoffer.ai.ragent.authorization.AiDeliveryPermitConfiguration;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.core.StandardWrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.ai.api.identity.AiIdentityPort;
import org.ruoyi.ai.api.runtime.DocumentPort;
import org.ruoyi.aiintegration.authorization.OrganizationMatchController;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.identity.ProductionAuthorizationProvider;
import org.ruoyi.aiintegration.web.AiGatewayController;
import org.ruoyi.aiintegration.web.AiGatewayStreamController;
import org.ruoyi.aiintegration.web.GatewayAuthorizer;
import org.ruoyi.aiweb.AiWebEmbeddedConfiguration;
import org.ruoyi.aiweb.security.AiInternalAccessBoundaryFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RW-02-F03-PG-R7 / T2r：F03 的<b>真库公开面端到端</b>判据。
 *
 * <p><b>要证明的一件事。</b>经公开网关受理一轮 {@code rag.chat} 后，
 * {@code GET /api/ai/v1/conversations/{id}/messages} <b>必须看到这一轮</b>；另一个租户用同一个
 * conversationId 读同一路由<b>必须看不到</b>。这正是 F03 曾经的缺口（写路径落 A 表、读路径读 B 表）。
 *
 * <p><b>为什么必须走公开网关、且必须选"只有被测控制器能产生"的响应特征。</b>
 * 路由匹配/schema 校验能把 403、404、空数组都判成"通过"——那等于没判。所以本判据的<b>正例</b>
 * 要求响应体里出现<b>受理时刻才产生</b>的唯一问题串（nonce）与执行器真实产生的答案，
 * 并且返回的消息 id 必须等于真库里 {@code platform.ai_message} 的 id：
 * <ul>
 *   <li>nonce 只在 {@code POST /api/ai/v1/runs} 的 {@code input.text} 里出现一次，
 *       经执行器写库后只能由 {@code GET .../messages} 读回 —— 路由匹配、403、空列表都产生不了它；</li>
 *   <li>id 相等把"公开读"与"真库行"钉在一起，排除"读到了别的会话/别的租户的行"。</li>
 * </ul>
 * <b>负例</b>（另一租户 + 不存在的 conversationId）证明同一条路由<b>不是恒真</b>：
 * 它们必须 404，且响应体不得包含 nonce。
 *
 * <p><b>真实的部分（判据的承重墙）。</b>
 * <ul>
 *   <li>真 PostgreSQL：表结构从<b>冻结迁移</b>逐条抽取（{@link #frozenDdl}，与 RW-25 的
 *       frozen-subset 同一口径，不手写 DDL、不改迁移文件）；</li>
 *   <li>真 MyBatis-Plus Mapper：{@code ConversationMessageMapper/ConversationMapper/...} 经
 *       {@code AiEmbeddedMapperConfiguration} 的 {@code @MapperScan} 注册，插的正是
 *       {@code platform.ai_message}；读侧是 {@code TenantConversationReadRepository} 的真实 SQL；</li>
 *   <li>真公开网关：真实 Tomcat + {@code AiGatewayController} + {@code LocalAiGatewayClient}
 *       （同进程转送，含白名单/身份/信封/交付回执），外部路径就是 {@code /api/ai/v1/...}；</li>
 *   <li>真受理与执行：{@code RunController} → {@code RunAdmissionService}（真 run 台账/预算/
 *       事件/outbox）→ {@code RunWorker}（认领/续租/fence）→ {@code RagChatExecutor}
 *       → {@code ConversationHistoryAdapter} → {@code ConversationMessageServiceImpl}；</li>
 *   <li>真授权/交付链：{@code AiResourceAuthorizationService} + {@code DefaultRevocationGuard}
 *       + {@code AiDeliveryPermitConfiguration}（permit 登记/释放）。</li>
 * </ul>
 *
 * <p><b>被替身化的部分（诚实边界，不是判据本身）。</b>
 * <ul>
 *   <li><b>platform 身份/许可端口</b>（{@code PlatformIdentitySource}/{@code PlatformPermitPort}/
 *       {@code PlatformFactsClient}）：platform 后端不在本仓库切片里，生产实现是 localhost
 *       HTTP/同进程服务；这里给固定身份（租户 T1/T2、pv=av=7）与"许可回执合法"的替身，
 *       其余（ACL/epoch/permit 落库、判定链）都是真的；</li>
 *   <li><b>外部提供方</b>：{@code DocumentPort} 检索返回空（便携 PG 没有 pgvector，真向量检索
 *       在本环境跑不了）；chat/embedding 走仓库既有的 synthetic mode。检索为空时执行器按既有语义
 *       产出"证据不足"答案并<b>照常落历史</b>——本判据要证的是写→读闭环，不是模型效果。</li>
 * </ul>
 *
 * <p><b>不签收的范围。</b>本类只签"公开面受理 → 公开历史回读看到该轮 + 跨租户不可见"这一条；
 * 它不签 F03 的其它验收点，也不替代适配器级/内层 dispatch 级判据（那些各自的卡另有其判据）。
 *
 * <p>需要隔离库：{@code -Dragent.conversation.test.jdbc-url=jdbc:postgresql://host:port/db}
 * （凭据可经 {@code -D…jdbc-user} / {@code -D…jdbc-password} 覆盖，缺省 {@code postgres}/空）；
 * 未提供时显式跳过（与既有 PG 判据同一开关），不会静默变绿。
 * <b>本类会自建/自删 platform 表，必须连独占隔离库</b>，不得指向共享主库。
 */
@Tag("dev")
@EnabledIfSystemProperty(named = LocalConversationHistoryPostgresE2ETest.URL_PROPERTY, matches = ".+",
        disabledReason = "需要隔离 PostgreSQL：-D" + LocalConversationHistoryPostgresE2ETest.URL_PROPERTY
                + "=jdbc:postgresql://host:port/db")
class LocalConversationHistoryPostgresE2ETest {

    static final String URL_PROPERTY = "ragent.conversation.test.jdbc-url";
    /**
     * 连接凭据可用系统属性覆盖（缺省保持历史口径：便携 PG 的 {@code postgres}/空密码）。
     * 独占隔离库（本类会自建/自删 platform 表，必须连隔离库）用专用测试角色注入
     * （F17-A1 真库复跑：{@code f17hist}，测试工件、非敏感，与 {@code MergedTableMapperPostgresTest}
     * 的 {@code migrate_platform} 同一先例）。
     */
    static final String USER_PROPERTY = "ragent.conversation.test.jdbc-user";
    static final String PASSWORD_PROPERTY = "ragent.conversation.test.jdbc-password";

    /** 测试连接：URL 补 currentSchema，凭据按系统属性覆盖（缺省=便携 PG 口径）。 */
    private static DriverManagerDataSource testDataSource(String rawUrl) {
        String url = rawUrl.contains("currentSchema") ? rawUrl
                : rawUrl + (rawUrl.contains("?") ? "&" : "?") + "currentSchema=platform";
        return new DriverManagerDataSource(url,
                System.getProperty(USER_PROPERTY, "postgres"),
                System.getProperty(PASSWORD_PROPERTY, ""));
    }

    // ---------------------------------------------------------------- 身份（两个租户）
    private static final String T1 = "T1";
    private static final String U1 = "2101";
    private static final String M1 = "platform:T1:2101";
    private static final String T2 = "T2";
    private static final String U2 = "2202";
    private static final String M2 = "platform:T2:2202";
    private static final int PV = 7;
    private static final int AV = 7;

    /** 主体可执行的平台权限（canonical 动作由 LocalAiGatewayClient 反查，与生产同形）。 */
    private static final Member MEMBER_T1 = new Member(T1, U1, M1,
            Set.of("ai:run:submit", "ai:conversation:read", "ai:kb:read"));
    private static final Member MEMBER_T2 = new Member(T2, U2, M2, Set.of("ai:conversation:read"));

    /** 当前生效身份；负例靠切换它来换租户（网关每请求重新解析）。 */
    private static final AtomicReference<Member> ACTIVE = new AtomicReference<>(MEMBER_T1);

    // ---------------------------------------------------------------- 数据锚点
    private static final String CONV = "conv-f03pg-r7";
    private static final String CONV_ROW_ID = "c-f03pg-r7-1";
    /** 受理载荷里的唯一问题串：只有"真写→真读"闭环能让它在公开读里出现。 */
    private static final String QUESTION = "F03-PG-R7 nonce 7f3a1c question";
    /** 执行器在"检索无证据"分支的真实回答（既有语义，不是本判据新造的）。 */
    private static final String ANSWER = "未检索到足够证据，无法回答该问题。";
    private static final String KB = "kb-f03-r7-1";
    private static final String REVISION = "rev-f03-r7-1";

    private static final String ADMISSION_BODY = "{"
            + "\"schemaVersion\":1,"
            + "\"action\":\"rag.chat\","
            + "\"input\":{\"text\":\"" + QUESTION + "\",\"conversationId\":\"" + CONV + "\"},"
            + "\"resourceRefs\":[{\"type\":\"knowledge_base\",\"id\":\"" + KB + "\"}],"
            + "\"budget\":{\"maxTokens\":2000}"
            + "}";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** 外部检索面：便携 PG 无 pgvector，检索返回空；执行器据此走"证据不足"真实分支。 */
    private static final DocumentPort DOCUMENTS = mock(DocumentPort.class);
    private static final PrivateObjectStore OBJECT_STORE = mock(PrivateObjectStore.class);

    private static JdbcTemplate jdbc;
    private static AnnotationConfigWebApplicationContext context;
    private static Tomcat tomcat;
    private static int port;

    // 流程证据（@BeforeAll 里产生，各判据只读）
    private static int acceptStatus;
    private static String acceptBody;
    private static String runId;
    private static String runTerminalStatus;
    private static String runErrorCode;
    private static List<Map<String, Object>> persistedMessages = new ArrayList<>();

    /** 证据行：同时进日志与 {@code target/f03-r7-evidence.txt}（UTF-8，避免控制台代码页把中文打坏）。 */
    private static final List<String> EVIDENCE = new ArrayList<>();

    private static void evidence(String line) {
        EVIDENCE.add(line);
        System.out.println("[F03-R7] " + line);
    }

    // ---------------------------------------------------------------- 真库 + 真容器

    @BeforeAll
    static void setUp() throws Exception {
        String url = System.getProperty(URL_PROPERTY);
        assertThat(url).as("缺少 -D" + URL_PROPERTY).isNotBlank();
        DataSource dataSource = testDataSource(url);
        jdbc = new JdbcTemplate(dataSource);
        createFrozenTables();
        seed();
        startContainer();

        // ---- 公开网关受理一轮 rag.chat（真 RunController/RunAdmissionService） ----
        HttpResponse<String> accepted = exchange("POST", "/api/ai/v1/runs", ADMISSION_BODY, true, "f03-r7-idem-1");
        acceptStatus = accepted.statusCode();
        acceptBody = accepted.body();
        assertThat(acceptStatus)
                .as("公开网关受理 rag.chat 必须 202；实际 status=%s body=%s", acceptStatus, acceptBody)
                .isEqualTo(202);
        JsonNode envelope = JSON.readTree(acceptBody);
        assertThat(envelope.path("code").asInt()).as("受理信封整数 code=200，实际=%s", acceptBody).isEqualTo(200);
        runId = envelope.path("data").path("runId").asText();
        assertThat(runId).as("受理必须返回 runId，实际=%s", acceptBody).isNotBlank();
        evidence("POST /api/ai/v1/runs -> " + acceptStatus + " " + acceptBody);

        // ---- 真 worker 认领执行（真 RunWorker → RagChatExecutor → 写 ai_message） ----
        awaitTerminal();

        persistedMessages = jdbc.queryForList(
                "SELECT id, role, content FROM platform.ai_message WHERE tenant_id=? AND member_id=?"
                        + " AND conversation_id=? AND deleted=0 ORDER BY create_time, id", T1, M1, CONV);
        evidence("runId=" + runId + " terminal=" + runTerminalStatus + " errorCode=" + runErrorCode);
        evidence("platform.ai_message rows=" + persistedMessages);
    }

    @AfterAll
    static void tearDown() throws LifecycleException, IOException {
        try {
            Files.writeString(Path.of("target", "f03-r7-evidence.txt"), String.join(System.lineSeparator(), EVIDENCE),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // 证据落盘失败不能掩盖判据结果；控制台日志仍有一份
        }
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
        if (context != null) {
            context.close();
        }
        // 清场：本类建的表全部 DROP CASCADE。**必须做**：同一隔离库里的既有 PG 判据
        // （如 ConversationHistoryPostgresTest）用的是非 CASCADE 的 `DROP TABLE ... ai_message`，
        // 本类留下的 ai_message_feedback 外键会让它直接报错 —— 那是"台架污染别人判据"，
        // 不是别人的缺陷。
        if (jdbc != null) {
            for (int i = TABLES.size() - 1; i >= 0; i--) {
                jdbc.execute("DROP TABLE IF EXISTS platform." + TABLES.get(i) + " CASCADE");
            }
        }
    }

    // ---------------------------------------------------------------- 判据

    @Test
    @DisplayName("执行器真的把这一轮写进了读路径所读的表（真库行数/角色/内容）")
    void theAcceptedRoundReachedTheConversationHistoryTable() {
        assertThat(runTerminalStatus)
                .as("受理的 run 必须被真 worker 执行到终态 SUCCEEDED；诊断=%s", diagnostics())
                .isEqualTo("SUCCEEDED");
        assertThat(persistedMessages)
                .as("刚好一轮问答 = platform.ai_message 两行；诊断=%s", diagnostics())
                .hasSize(2);
        assertThat(persistedMessages.get(0).get("role")).isEqualTo("user");
        assertThat(persistedMessages.get(0).get("content")).isEqualTo(QUESTION);
        assertThat(persistedMessages.get(1).get("role")).isEqualTo("assistant");
        assertThat(persistedMessages.get(1).get("content")).isEqualTo(ANSWER);
    }

    @Test
    @DisplayName("公开历史回读必须看到该轮（nonce + 真库 id + 回复关系）")
    void thePublicHistoryRouteReturnsExactlyThatRound() throws Exception {
        ACTIVE.set(MEMBER_T1);

        HttpResponse<String> response = exchange("GET", "/api/ai/v1/conversations/" + CONV + "/messages",
                null, true, null);
        evidence("GET (owner) /api/ai/v1/conversations/" + CONV + "/messages -> "
                + response.statusCode() + " " + response.body());

        assertThat(response.statusCode())
                .as("公开历史路由必须 200（403/404/空数组都不能算通过）；实际 body=%s", response.body())
                .isEqualTo(200);

        JsonNode envelope = JSON.readTree(response.body());
        assertThat(envelope.path("code").asInt())
                .as("网关单 JSON 对象 + 整数 code=200；实际=%s", response.body())
                .isEqualTo(200);
        JsonNode data = envelope.path("data");
        assertThat(data.isArray()).as("data 必须是消息数组；实际=%s", response.body()).isTrue();
        assertThat(data.size())
                .as("必须是恰好这一轮（两条）；实际=%s", response.body())
                .isEqualTo(2);

        // ① 只有真写→真读闭环才能产生 nonce 与真实回答（路由匹配/无权/空列表都产生不了）
        assertThat(response.body())
                .as("响应体必须原样带上受理时的问题串 —— 这是【只有被测控制器能产生】的特征（路由匹配/403/空列表都产生不了它）")
                .contains(QUESTION);
        assertThat(data.get(0).path("content").asText()).isEqualTo(QUESTION);
        assertThat(data.get(0).path("role").asText()).isEqualTo("user");
        assertThat(data.get(1).path("content").asText()).isEqualTo(ANSWER);
        assertThat(data.get(1).path("role").asText()).isEqualTo("assistant");

        // ② 公开读到的 id 必须等于真库行 id（排除"读到别的会话/别的租户的行"）
        List<String> returnedIds = List.of(data.get(0).path("id").asText(), data.get(1).path("id").asText());
        List<String> dbIds = persistedMessages.stream().map(row -> String.valueOf(row.get("id"))).toList();
        assertThat(returnedIds)
                .as("公开读返回的 id 必须与 platform.ai_message 的两行逐一对齐")
                .containsExactlyElementsOf(dbIds);
        // ③ 助手行必须回指本轮的提问行（真库 reply_to_message_id 也是真写路径落的）
        assertThat(data.get(1).path("replyToMessageId").asText()).isEqualTo(returnedIds.get(0));
    }

    @Test
    @DisplayName("F17-A1：公开历史读面回传当前用户的反馈（vote）——他人行不计入、取消后回到 null")
    void thePublicHistoryRouteCarriesTheCurrentUsersVote() throws Exception {
        ACTIVE.set(MEMBER_T1);
        String assistantId = String.valueOf(persistedMessages.get(1).get("id"));
        String mine = "f17fb-e2e-mine";
        String other = "f17fb-e2e-other";
        try {
            // 当前用户（T1/U1）对助手消息的有效赞
            jdbc.update("INSERT INTO platform.ai_message_feedback (id, message_id, conversation_id, user_id,"
                            + " vote, create_time, update_time, deleted, tenant_id, member_id)"
                            + " VALUES (?,?,?,?,?, now(), now(), 0, ?, ?)",
                    mine, assistantId, CONV, U1, 1, T1, M1);
            // 对抗性负例：同租户、同成员维度、另一个 user_id 的行（冻结 schema 允许：
            // FK 只约束 tenant+message，user 是投票人维度）——不得计入 U1 的读面。
            jdbc.update("INSERT INTO platform.ai_message_feedback (id, message_id, conversation_id, user_id,"
                            + " vote, create_time, update_time, deleted, tenant_id, member_id)"
                            + " VALUES (?,?,?,?,?, now(), now(), 0, ?, ?)",
                    other, assistantId, CONV, U2, -1, T1, M1);

            HttpResponse<String> response = exchange("GET", "/api/ai/v1/conversations/" + CONV + "/messages",
                    null, true, null);
            JsonNode data = JSON.readTree(response.body()).path("data");
            evidence("F17-A1 GET (owner, with vote) -> " + response.statusCode()
                    + " assistantVote=" + data.get(1).path("vote"));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(data.get(1).path("vote").asInt())
                    .as("助手消息必须回传当前用户自己的有效反馈；实际=%s", response.body())
                    .isEqualTo(1);
            assertThat(data.get(0).path("vote").isMissingNode() || data.get(0).path("vote").isNull())
                    .as("无反馈的消息不得伪造 vote；实际=%s", response.body())
                    .isTrue();

            // 取消占位（vote=0, deleted=1）：读面回到"无反馈"（与旧链 getUserVotes 的 deleted=0 口径一致）
            jdbc.update("UPDATE platform.ai_message_feedback SET vote = 0, deleted = 1, update_time = now()"
                    + " WHERE id = ?", mine);
            HttpResponse<String> afterCancel = exchange("GET", "/api/ai/v1/conversations/" + CONV + "/messages",
                    null, true, null);
            JsonNode cancelledVote = JSON.readTree(afterCancel.body()).path("data").get(1).path("vote");
            evidence("F17-A1 GET (owner, cancelled) -> " + afterCancel.statusCode() + " assistantVote=" + cancelledVote);
            assertThat(cancelledVote.isMissingNode() || cancelledVote.isNull())
                    .as("取消后的助手消息不得再回传 vote；实际=%s", afterCancel.body())
                    .isTrue();
        } finally {
            jdbc.update("DELETE FROM platform.ai_message_feedback WHERE id IN (?, ?)", mine, other);
        }
    }

    @Test
    @DisplayName("跨租户不可见：另一租户用同一个 conversationId 读不到这一轮")
    void anotherTenantCannotSeeTheRound() throws Exception {
        ACTIVE.set(MEMBER_T2);
        try {
            HttpResponse<String> response = exchange("GET", "/api/ai/v1/conversations/" + CONV + "/messages",
                    null, true, null);
            evidence("GET (other tenant " + T2 + ") /api/ai/v1/conversations/" + CONV + "/messages -> "
                    + response.statusCode() + " " + response.body());

            assertThat(response.statusCode())
                    .as("跨租户读同一 conversationId 必须失败（不得 200）；实际 body=%s", response.body())
                    .isEqualTo(404);
            assertThat(response.body())
                    .as("跨租户响应不得泄露这一轮的任何内容")
                    .doesNotContain(QUESTION)
                    .doesNotContain(ANSWER);
            assertThat(response.body()).contains("\"code\":404");
        } finally {
            ACTIVE.set(MEMBER_T1);
        }

        // 真库里也只有 T1 名下这两行；T2 名下零行（负例不是靠上层拦截伪造出来的）
        Long otherTenantRows = jdbc.queryForObject(
                "SELECT count(*) FROM platform.ai_message WHERE tenant_id=? AND conversation_id=?",
                Long.class, T2, CONV);
        assertThat(otherTenantRows).as("T2 名下不得存在同一 conversationId 的消息行").isZero();
    }

    @Test
    @DisplayName("同租户不存在的 conversationId 仍然 404（证明这条路由不是恒真）")
    void unknownConversationOnTheOwnerTenantIsStillNotFound() throws Exception {
        ACTIVE.set(MEMBER_T1);

        HttpResponse<String> response = exchange("GET",
                "/api/ai/v1/conversations/conv-f03pg-r7-nope/messages", null, true, null);
        evidence("GET (owner, unknown conv) -> " + response.statusCode() + " " + response.body());

        assertThat(response.statusCode())
                .as("不存在的会话必须 404，否则上面的 200 不能证明任何东西；实际 body=%s", response.body())
                .isEqualTo(404);
    }

    // ---------------------------------------------------------------- 驱动

    /** 真 worker 认领并执行；轮询到终态（fail 时把 run 台账诊断带进断言消息）。 */
    private static void awaitTerminal() throws InterruptedException {
        RunWorker worker = context.getBean(RunWorker.class);
        // 90s 在并发压测下够、但余量太小（实测同一台机上 7s~80s 波动）：
        // 超时会被判成"未闭环"的假失败，所以给足 240s。
        long deadline = System.currentTimeMillis() + 240_000;
        String status = statusOfRun();
        while (!Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)
                && System.currentTimeMillis() < deadline) {
            worker.tick();
            Thread.sleep(100);
            status = statusOfRun();
        }
        runTerminalStatus = status;
        runErrorCode = jdbc.queryForObject(
                "SELECT error_code FROM platform.ai_run WHERE tenant_id=? AND run_id=?", String.class, T1, runId);
    }

    private static String statusOfRun() {
        return jdbc.queryForObject(
                "SELECT status FROM platform.ai_run WHERE tenant_id=? AND run_id=?", String.class, T1, runId);
    }

    /** 失败诊断：run 状态/错误码 + 事件 + 步骤 + 已落消息行数与内容。 */
    private static String diagnostics() {
        StringBuilder sb = new StringBuilder("status=").append(runTerminalStatus)
                .append(" errorCode=").append(runErrorCode);
        try {
            sb.append(" events=").append(jdbc.queryForList(
                    "SELECT seq, event_type FROM platform.ai_run_event WHERE tenant_id=? AND run_id=? ORDER BY seq",
                    T1, runId));
            sb.append(" steps=").append(jdbc.queryForList(
                    "SELECT step_id, state FROM platform.ai_run_step WHERE tenant_id=? AND run_id=? ORDER BY created_at",
                    T1, runId));
            sb.append(" messages=").append(jdbc.queryForList(
                    "SELECT id, role, content FROM platform.ai_message WHERE tenant_id=? AND conversation_id=?",
                    T1, CONV));
        } catch (RuntimeException ignored) {
            // 诊断本身不能再抛：原始断言失败才是证据
        }
        return sb.toString();
    }

    private static HttpResponse<String> exchange(String method, String path, String body, boolean login,
                                                 String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path));
        if (login) {
            builder.header("Authorization", "Bearer synthetic-session");
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- 真库准备

    private static final List<String> TABLES = List.of(
            "ai_conversation", "ai_message", "ai_message_feedback", "ai_resource", "ai_resource_acl",
            "ai_acl_epoch", "ai_tenant_barrier", "ai_execution_permit", "ai_run", "ai_run_event",
            "ai_run_step", "outbox_event", "ai_tenant_budget", "ai_budget_reservation", "ai_model_call",
            "ai_runtime_config_revision", "ai_chat_message");

    /**
     * 表结构 = <b>冻结迁移逐条抽取</b>（CREATE + 后续 ALTER 原样），与 RW-25-PG-R6 的
     * frozen-subset 同一口径：不手写 DDL、不改迁移文件、不跑完整链（便携 PG 无 pgvector）。
     */
    private static void createFrozenTables() throws IOException {
        for (int i = TABLES.size() - 1; i >= 0; i--) {
            jdbc.execute("DROP TABLE IF EXISTS platform." + TABLES.get(i) + " CASCADE");
        }
        for (String table : TABLES) {
            for (String ddl : frozenDdl(table)) {
                jdbc.execute(ddl);
            }
        }
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='platform'"
                        + " AND table_name='ai_message'", String.class);
        assertThat(columns)
                .as("ai_message 形状必须来自冻结迁移（含历史读路径要的列）")
                .contains("id", "conversation_id", "user_id", "role", "content", "message_status", "deleted",
                        "tenant_id", "member_id", "create_time", "reply_to_message_id");
    }

    /** 从冻结迁移里抽某张表的真实 DDL（与既有 FrozenTableDdl 同一抽取口径）。 */
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

    /** 种子：epoch / ACL / 会话 / 发布版本 —— 全部是判据运行前必须存在的权威事实。 */
    private static void seed() {
        jdbc.update("INSERT INTO platform.ai_acl_epoch (tenant_id, version, update_time) VALUES (?,?,now())",
                T1, AV);
        // 负例租户也要有 epoch 行：否则授权判定会收敛成 503 UNKNOWN（"服务不可用"），
        // 而不是真正的"跨租户无权"404 —— 那会让负例失去语义。
        jdbc.update("INSERT INTO platform.ai_acl_epoch (tenant_id, version, update_time) VALUES (?,?,now())",
                T2, AV);
        jdbc.update("INSERT INTO platform.ai_resource (tenant_id, resource_type, resource_id, owner_member_id,"
                        + " owner_dept_id, parent_type, parent_id, status, resource_version, created_by_member,"
                        + " create_time, update_time) VALUES (?,'CONVERSATION',?,?,NULL,NULL,NULL,'ACTIVE',1,?,now(),now())",
                T1, CONV, M1, M1);
        jdbc.update("INSERT INTO platform.ai_resource (tenant_id, resource_type, resource_id, owner_member_id,"
                        + " owner_dept_id, parent_type, parent_id, status, resource_version, created_by_member,"
                        + " create_time, update_time) VALUES (?,'KB',?,?,NULL,NULL,NULL,'ACTIVE',1,?,now(),now())",
                T1, KB, M1, M1);
        jdbc.update("INSERT INTO platform.ai_resource_acl (id, tenant_id, resource_type, resource_id, subject_type,"
                        + " subject_id, action, expires_at, granted_by, create_time)"
                        + " VALUES (?,?, 'CONVERSATION', ?, 'MEMBER', ?, 'conversation.read', NULL, ?, now())",
                "acl-conv-1", T1, CONV, M1, M1);
        jdbc.update("INSERT INTO platform.ai_resource_acl (id, tenant_id, resource_type, resource_id, subject_type,"
                        + " subject_id, action, expires_at, granted_by, create_time)"
                        + " VALUES (?,?, 'KB', ?, 'MEMBER', ?, 'kb.read', NULL, ?, now())",
                "acl-kb-1", T1, KB, M1, M1);
        jdbc.update("INSERT INTO platform.ai_conversation (id, conversation_id, user_id, title, last_time,"
                        + " create_time, update_time, deleted, tenant_id, member_id)"
                        + " VALUES (?,?,?,?, now(), now(), now(), 0, ?, ?)",
                CONV_ROW_ID, CONV, U1, "F03 PG R7", T1, M1);
        // 发布权威（V15 + V24 的 dimension）：受理时绑定该版本，维度门要求 1536
        jdbc.update("INSERT INTO platform.ai_runtime_config_revision (tenant_id, revision_id, revision_no, state,"
                        + " provider_id, model_id, catalog_version, params_hash, params_json, credential_ref,"
                        + " operator_id, published_at, dimension, budget_units)"
                        + " VALUES (?,?,1,'PUBLISHED','synthetic-provider','synthetic-chat','cat-v1','hash-1',"
                        + " '{}'::jsonb, 'env:F03_R7_TEST_KEY', 't2r-runtime', now(), 1536, 1000)",
                T1, REVISION);
    }

    // ---------------------------------------------------------------- 容器

    private static void startContainer() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "ai.integration.enabled", "true",
                "ai.integration.transport", "local",
                "ai.integration.forward-timeout-millis", "4000",
                "ai.integration.security.enabled", "false",
                "p2.enabled", "true",
                "p2.worker.enabled", "true",
                "p2.chat.mode", "synthetic",
                "p2.embedding.mode", "synthetic",
                "p2.executor.mode", "real",
                "mineru.local.enabled", "false")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/f03-pg-r7-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/f03-pg-r7-docroot");
        docBase.mkdirs();
        Context ctx = tomcat.addContext("", docBase.getAbsolutePath());

        // 与生产同形的内层边界：直接访问 /internal/ai/v1/** 一律 404
        org.apache.tomcat.util.descriptor.web.FilterDef def =
                new org.apache.tomcat.util.descriptor.web.FilterDef();
        def.setFilterName("aiInternalAccessBoundaryFilter");
        def.setFilter(new AiInternalAccessBoundaryFilter());
        ctx.addFilterDef(def);
        org.apache.tomcat.util.descriptor.web.FilterMap map =
                new org.apache.tomcat.util.descriptor.web.FilterMap();
        map.setFilterName("aiInternalAccessBoundaryFilter");
        map.addURLPatternDecoded("/internal/ai/v1/*");
        map.setDispatcher("REQUEST");
        ctx.addFilterMap(map);

        StandardWrapper wrapper = (StandardWrapper) Tomcat.addServlet(ctx, "dispatcher",
                new org.springframework.web.servlet.DispatcherServlet(context));
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/", "dispatcher");

        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    // ---------------------------------------------------------------- 装配

    /**
     * 装配与生产内嵌形态同形：公开网关 + AI 授权/交付组 + runs 组 + worker/执行器组 +
     * feedback 组（会话历史端口）+ 运行权威组；持久层是本判据提供的真 PG DataSource。
     */
    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({
            AiGatewayController.class,
            AiGatewayStreamController.class,
            GatewayAuthorizer.class,
            AiWebEmbeddedConfiguration.class,
            AiEmbeddedRagConfiguration.class,
            AiEmbeddedRunConfiguration.class,
            AiEmbeddedFeedbackConfiguration.class,
            AiEmbeddedMapperConfiguration.class,
            AiEmbeddedWorkerConfiguration.LocalTransport.WorkerEnabled.class,
            RuntimeAuthorityConfiguration.class,
            P2Configuration.class,
            AiDeliveryPermitConfiguration.class
    })
    static class FixtureConfig {

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

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }

        /** MyBatis-Plus：真 Mapper → 真 PG；MetaObjectHandler/IdentifierGenerator 与 platform 侧同语义。 */
        @Bean
        MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setMetaObjectHandler(new MyMetaObjectHandler());
            globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
            factory.setGlobalConfig(globalConfig);
            return factory;
        }

        // ------------------------------------------------------------ platform 侧替身（后端不在本切片）

        @Bean
        AiIntegrationProperties aiIntegrationProperties() {
            AiIntegrationProperties properties = new AiIntegrationProperties();
            properties.setEnabled(true);
            properties.setTransport("local");
            properties.setForwardTimeoutMillis(4000);
            return properties;
        }

        @Bean
        CurrentPrincipalResolver currentPrincipalResolver() {
            return () -> Optional.of(new CurrentPrincipalResolver.CurrentMember(
                    ACTIVE.get().tenant(), ACTIVE.get().user(), ACTIVE.get().membership()));
        }

        @Bean
        PlatformIdentitySource platformIdentitySource() {
            return new PlatformIdentitySource() {
                @Override
                public TenantState tenantState(String tenantId) {
                    return TenantState.ENABLED;
                }

                @Override
                public PlatformIdentity membership(String tenantId, String subject, String membershipId) {
                    Member member = byMembership(membershipId);
                    if (member == null || !member.tenant().equals(tenantId)) {
                        return null;
                    }
                    return new PlatformIdentity(member.tenant(), member.user(), member.membership(),
                            true, member.permissions(), PV);
                }
            };
        }

        /**
         * platform 主体/组织事实端的替身（真实实现在 platform 后端）。
         *
         * <p>{@code currentSubjects} 用接口默认口径（{@code member:platform:<tenant>:<user>} +
         * {@code tenant_all:<tenant>}）；{@code withinDataScope} 必须显式给出，
         * 因为接口默认值是 {@code false}——那会让"ACL 已授权"仍被数据范围判成 DENY。
         * 替身口径：owner 成员等于当前 canonical 成员即通过；只有属主部门声明时不做部门子树判定。
         */
        @Bean
        OrganizationMatchController.SubjectMatchSource subjectMatchSource() {
            return new OrganizationMatchController.SubjectMatchSource() {
                @Override
                public boolean withinDataScope(String tenantId, String subject, String action,
                                               String ownerMember, String ownerDept) {
                    return ownerMember == null || ownerMember.equals("platform:" + tenantId + ":" + subject);
                }

                @Override
                public Optional<SubjectOrgFacts> orgFacts(String tenantId, String subject) {
                    return Optional.empty();
                }
            };
        }

        @Bean
        AiIdentityPort aiIdentityPort() {
            return () -> Optional.of(new AiExecutionFacts(ACTIVE.get().tenant(), ACTIVE.get().user(),
                    ACTIVE.get().membership(), PV, AV, ACTIVE.get().permissions()));
        }

        /**
         * platform 许可权威的替身（platform 后端不在本切片）：经**真实的**
         * {@code LocalPlatformPermits} 适配器进入 {@code DefaultRevocationGuard}，
         * 回执形状合法（permitId 为真 UUID 形状，policyVersion/operationId 与请求一致），
         * 因此"回执非法即拒绝"这一门仍然在判。
         */
        @Bean
        ProductionAuthorizationProvider productionAuthorizationProvider() {
            return new ProductionAuthorizationProvider() {
                @Override
                public PermitGrant acquire(PermitRequest request) {
                    return new PermitGrant(UUID.randomUUID().toString(), request.policyVersion(),
                            request.operationId());
                }

                @Override
                public void release(PermitRelease request) {
                    // platform 侧没有账本可释放；AI 侧 permit 行仍由真实 release 落库
                }
            };
        }

        /** platform "当前主体事实"端口的替身：只回答"启用 + 版本 7"。 */
        @Bean
        PlatformFactsClient platformFactsClient() {
            return new PlatformFactsClient("http://127.0.0.1:1", "f03-r7-stub-credential") {
                @Override
                public CurrentFacts currentFacts(String tenantId, String subject, String membershipId) {
                    return new CurrentFacts(true, PV);
                }
            };
        }

        /** 外部检索面替身：无 pgvector 环境下的空检索（执行器据此走真实的"证据不足"分支）。 */
        @Bean
        DocumentPort documentPort() {
            when(DOCUMENTS.matchesEmbeddingModel(anyString(), anyCollection(), anyString())).thenReturn(true);
            when(DOCUMENTS.searchPublished(anyString(), any(), any(), anyInt(), org.mockito.ArgumentMatchers.anyDouble()))
                    .thenReturn(List.of());
            when(DOCUMENTS.findDocument(anyString(), anyString())).thenReturn(Optional.empty());
            return DOCUMENTS;
        }

        @Bean
        PrivateObjectStore privateObjectStore() {
            return OBJECT_STORE;
        }
    }

    private static Member byMembership(String membershipId) {
        if (M1.equals(membershipId)) {
            return MEMBER_T1;
        }
        return M2.equals(membershipId) ? MEMBER_T2 : null;
    }

    /** 一次运行里"当前主体"的完整身份（租户/用户/成员 + 平台权限）。 */
    private record Member(String tenant, String user, String membership, Set<String> permissions) {
    }
}
