package com.nageoffer.ai.ragent.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunEventAppender;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * publishVersionFenced 组件载具：真实 {@link DocumentDao} 事务代理（{@code @EnableTransactionManagement}＋
 * {@code DataSourceTransactionManager}）＋真 PostgreSQL（随机 schema＋冻结迁移抽出的四表 DDL）。
 * DDL 提取采用 FrozenTableDdl 同法在本测试内实现（不跨模块引用 runtime 测试源码）；
 * vector 扩展位于既有库的 extensions schema，故 search_path={@code <schema>,extensions}。
 *
 * <p>五判据（维护者 2026-10-14 定，R17 BD-2 补验收官窗）：
 * ①原子切换＋未提交不可见＋同版本幂等；②零 chunk 抛异常且事务代理真回滚＋外层事务全量回滚；
 * ③fence 拒绝（wrong fence／过期 lease／非 RUNNING，全字段不变）＋生产 guard
 * （RunExecutionGuard/withLiveFence 先锁 ai_run 再进 publish 的锁时序）；
 * ④tombstone 优先（不发布、不复活、不 SUPERSEDE）；⑤并发序号拒（高序号先持锁提交、
 * 低序号等待后 false；合法顺序低先高后两次都成功）。
 *
 * <p>每事务/观察者独立连接（PGSimpleDataSource 非池化、非 SingleConnectionDataSource）；
 * 每用例独立随机 schema，结束后 DROP CASCADE 自清。
 */
@Tag("dev")
@EnabledIfSystemProperty(named = "ragent.document.publish.test.jdbc-url", matches = ".+",
        disabledReason = "Requires private PostgreSQL via ragent.document.publish.test.jdbc-url")
class DocumentDaoPublishVersionPostgresTest {

    private static final String TENANT = "T-P";
    private static final String KB = "kb-pub";
    private static final String URL_PROP = "ragent.document.publish.test.jdbc-url";
    private static final String USER_PROP = "ragent.document.publish.test.jdbc-user";
    private static final String PASSWORD_PROP = "ragent.document.publish.test.jdbc-password";
    private static final String FENCE_OWNER = "w-pubtest";
    private static final long FENCE = 424242L;
    private static final List<String> TABLES =
            List.of("ai_run", "ai_document", "ai_document_version", "ai_document_chunk");

    private PGSimpleDataSource adminSource;
    private PGSimpleDataSource source;
    private String schema;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private DocumentDao dao;

    @BeforeEach
    void setUp() throws Exception {
        schema = "r17pub_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getProperty(URL_PROP);
        adminSource = newSource(url);
        try (Connection connection = adminSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        source = newSource(url, schema + ",extensions");
        context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("dataSource", source);
        context.register(Config.class);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        dao = context.getBean(DocumentDao.class);
        for (String table : TABLES) {
            for (String ddl : frozenDdl(table)) {
                try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
                    statement.execute(ddl);
                }
            }
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        if (context != null) {
            context.close();
        }
        if (schema != null && adminSource != null) {
            try (Connection connection = adminSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean DocumentDao documentDao(JdbcTemplate jdbcTemplate) { return new DocumentDao(jdbcTemplate); }
        @Bean RunLedgerDao runLedgerDao(JdbcTemplate jdbcTemplate) { return new RunLedgerDao(jdbcTemplate); }
        @Bean RunEventAppender runEventAppender(RunLedgerDao dao) { return new RunEventAppender(dao, new ObjectMapper()); }
        @Bean P2RuntimeProperties p2RuntimeProperties() { return new P2RuntimeProperties(); }
    }

    // ---------------------------------------------------------------- 判据①

    @Test
    void atomicSwitchUncommittedInvisibleCommitSwitchesAndReplayIdempotent() throws Exception {
        seedRun("run-a", FENCE, "RUNNING", liveLease());
        seedDocument("doc-a", "v1-a", 1L);
        seedPublishedVersion("doc-a", "v1-a");
        String v2 = newVersion("doc-a", "run-a", FENCE);
        seedChunk("doc-a", v2, "c1", "STAGING");

        AtomicReference<Object> outcome = new AtomicReference<>();
        try (Connection hold = source.getConnection()) {
            hold.setAutoCommit(false);
            try (var statement = hold.createStatement()) {
                statement.execute("SELECT chunk_key FROM ai_document_chunk WHERE tenant_id='" + TENANT
                        + "' AND version_id='" + v2 + "' FOR UPDATE");
            }
            Thread publisher = publisher(() -> dao.publishVersionFenced(TENANT, "doc-a", v2, "run-a", FENCE), outcome);
            publisher.start();
            publisher.join(500);
            assertThat(publisher.isAlive()).as("publish must pause on held chunk-row locks").isTrue();
            // 未提交：独立观察连接只见完整旧版本（指针与版本状态均未变）
            try (Connection observer = source.getConnection(); var statement = observer.createStatement()) {
                try (var rs = statement.executeQuery("SELECT published_version_id FROM ai_document "
                        + "WHERE tenant_id='" + TENANT + "' AND doc_id='doc-a'")) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("v1-a");
                }
                try (var rs = statement.executeQuery("SELECT state FROM ai_document_version "
                        + "WHERE tenant_id='" + TENANT + "' AND version_id='" + v2 + "'")) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("READY_TO_PUBLISH");
                }
            }
            hold.rollback();
            publisher.join(30_000);
            assertThat(publisher.isAlive()).isFalse();
        }
        assertThat(outcome.get()).isEqualTo(Boolean.TRUE);
        assertThat(pointerOf("doc-a")).isEqualTo(v2);
        assertThat(versionState(v2)).isEqualTo("PUBLISHED");
        assertThat(versionState("v1-a")).isEqualTo("SUPERSEDED");
        assertThat(chunkCount(v2, "PUBLISHED")).isEqualTo(1);
        assertThat(chunkCount(v2, "STAGING")).isZero();
        // 同版本幂等重放：早退 true，指针与 published_at 均无变更
        Timestamp publishedAt = jdbc.queryForObject(
                "SELECT published_at FROM ai_document_version WHERE tenant_id=? AND version_id=?",
                Timestamp.class, TENANT, v2);
        assertThat(dao.publishVersionFenced(TENANT, "doc-a", v2, "run-a", FENCE)).isTrue();
        assertThat(pointerOf("doc-a")).isEqualTo(v2);
        assertThat(jdbc.queryForObject("SELECT published_at FROM ai_document_version WHERE tenant_id=? AND version_id=?",
                Timestamp.class, TENANT, v2)).isEqualTo(publishedAt);
        assertThat(versionState("v1-a")).isEqualTo("SUPERSEDED");
    }

    // ---------------------------------------------------------------- 判据②

    @Test
    void zeroChunkPublishThrowsAndProxyRollsBack() {
        seedRun("run-b", FENCE, "RUNNING", liveLease());
        seedDocument("doc-b", "v1-b", 1L);
        seedPublishedVersion("doc-b", "v1-b");
        String v2 = newVersion("doc-b", "run-b", FENCE);
        // v2 READY 但零 STAGING chunk：version UPDATE 已发生，chunk UPDATE 0 行 → 异常 → 代理必须真回滚
        assertThatThrownBy(() -> dao.publishVersionFenced(TENANT, "doc-b", v2, "run-b", FENCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publish produced no visible chunks");
        assertThat(versionState(v2)).as("version UPDATE rolled back by the real transaction proxy")
                .isEqualTo("READY_TO_PUBLISH");
        assertThat(jdbc.queryForObject("SELECT published_at FROM ai_document_version WHERE tenant_id=? AND version_id=?",
                Timestamp.class, TENANT, v2)).isNull();
        assertThat(pointerOf("doc-b")).isEqualTo("v1-b");
        assertThat(versionState("v1-b")).isEqualTo("PUBLISHED");
    }

    @Test
    void outerTransactionFailureRollsBackEverything() {
        seedRun("run-c", FENCE, "RUNNING", liveLease());
        seedDocument("doc-c", "v1-c", 1L);
        seedPublishedVersion("doc-c", "v1-c");
        String v2 = newVersion("doc-c", "run-c", FENCE);
        seedChunk("doc-c", v2, "c1", "STAGING");
        String v3 = newVersion("doc-c", "run-c", FENCE);
        seedChunk("doc-c", v3, "c1", "STAGING");
        assertThat(dao.publishVersionFenced(TENANT, "doc-c", v2, "run-c", FENCE)).isTrue();
        // 外层事务于 DAO 成功返回后抛异常：版本/chunk/指针/SUPERSEDED 必须一起回滚
        TransactionTemplate outer = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        assertThatThrownBy(() -> outer.execute(status -> {
            assertThat(dao.publishVersionFenced(TENANT, "doc-c", v3, "run-c", FENCE)).isTrue();
            throw new RuntimeException("boom-after-publish");
        })).isInstanceOf(RuntimeException.class).hasMessage("boom-after-publish");
        assertThat(pointerOf("doc-c")).isEqualTo(v2);
        assertThat(versionState(v3)).isEqualTo("READY_TO_PUBLISH");
        assertThat(chunkCount(v3, "STAGING")).isEqualTo(1);
        assertThat(chunkCount(v3, "PUBLISHED")).isZero();
        assertThat(versionState(v2)).isEqualTo("PUBLISHED");
        assertThat(versionState("v1-c")).isEqualTo("SUPERSEDED");
    }

    // ---------------------------------------------------------------- 判据③

    @Test
    void fenceRejectionsLeaveEverythingUnchanged() {
        seedDocument("doc-f", "v1-f", 1L);
        seedPublishedVersion("doc-f", "v1-f");
        seedRun("run-f-wrong", FENCE, "RUNNING", liveLease());
        seedRun("run-f-expired", 777001L, "RUNNING", liveLease());
        seedRun("run-f-stopped", 777002L, "RUNNING", liveLease());
        String vWrong = newVersion("doc-f", "run-f-wrong", FENCE);
        seedChunk("doc-f", vWrong, "c1", "STAGING");
        String vExpired = newVersion("doc-f", "run-f-expired", 777001L);
        seedChunk("doc-f", vExpired, "c1", "STAGING");
        String vStopped = newVersion("doc-f", "run-f-stopped", 777002L);
        seedChunk("doc-f", vStopped, "c1", "STAGING");
        // 推 READY 后再破坏 lease/状态，保证三组都有真实目标行
        jdbc.update("UPDATE ai_run SET lease_until=? WHERE tenant_id=? AND run_id=?",
                expiredLease(), TENANT, "run-f-expired");
        jdbc.update("UPDATE ai_run SET status='RETRY_WAIT' WHERE tenant_id=? AND run_id=?", TENANT, "run-f-stopped");

        assertThat(dao.publishVersionFenced(TENANT, "doc-f", vWrong, "run-f-wrong", FENCE + 1)).isFalse();
        assertThat(dao.publishVersionFenced(TENANT, "doc-f", vExpired, "run-f-expired", 777001L)).isFalse();
        assertThat(dao.publishVersionFenced(TENANT, "doc-f", vStopped, "run-f-stopped", 777002L)).isFalse();

        assertThat(pointerOf("doc-f")).isEqualTo("v1-f");
        for (String versionId : List.of(vWrong, vExpired, vStopped)) {
            assertThat(versionState(versionId)).isEqualTo("READY_TO_PUBLISH");
            assertThat(jdbc.queryForObject("SELECT published_at FROM ai_document_version "
                    + "WHERE tenant_id=? AND version_id=?", Timestamp.class, TENANT, versionId)).isNull();
            assertThat(chunkCount(versionId, "STAGING")).isEqualTo(1);
        }
    }

    @Test
    void productionGuardGatesStaleFenceBeforePublishAndLiveFencePasses() {
        seedRun("run-g", FENCE, "RUNNING", liveLease());
        seedDocument("doc-g", "v1-g", 1L);
        seedPublishedVersion("doc-g", "v1-g");
        String v2 = newVersion("doc-g", "run-g", FENCE);
        seedChunk("doc-g", v2, "c1", "STAGING");
        // 活 fence：withLiveFence 先锁 ai_run 校验 owner/lease，publish 在同事务接 doc 锁（生产锁时序）
        RunExecutionGuard live = guardFor("run-g", FENCE);
        assertThat(live.commitAtomic(() -> dao.publishVersionFenced(TENANT, "doc-g", v2, "run-g", FENCE))).isTrue();
        assertThat(pointerOf("doc-g")).isEqualTo(v2);
        // 陈旧 fence：guard 在 publish 之前抛 VERSION_CONFLICT，目标行不变
        seedRun("run-g2", 888001L, "RUNNING", liveLease());
        seedDocument("doc-g2", "v1-g2", 1L);
        seedPublishedVersion("doc-g2", "v1-g2");
        String v2Stale = newVersion("doc-g2", "run-g2", 888001L);
        seedChunk("doc-g2", v2Stale, "c1", "STAGING");
        RunExecutionGuard stale = guardFor("run-g2", 888002L);
        assertThatThrownBy(() -> stale.commitAtomic(
                () -> dao.publishVersionFenced(TENANT, "doc-g2", v2Stale, "run-g2", 888002L)))
                .isInstanceOf(RunApiException.class);
        assertThat(pointerOf("doc-g2")).isEqualTo("v1-g2");
        assertThat(versionState(v2Stale)).isEqualTo("READY_TO_PUBLISH");
        assertThat(chunkCount(v2Stale, "STAGING")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 判据④

    @Test
    void tombstonedDocumentIsNotPublishedOrRevived() {
        seedRun("run-t", FENCE, "RUNNING", liveLease());
        seedDocument("doc-t", "v1-t", 1L);
        seedPublishedVersion("doc-t", "v1-t");
        String v2 = newVersion("doc-t", "run-t", FENCE);
        seedChunk("doc-t", v2, "c1", "STAGING");
        jdbc.update("UPDATE ai_document SET tombstoned_at=now(), updated_at=now() WHERE tenant_id=? AND doc_id=?",
                TENANT, "doc-t");
        assertThat(dao.publishVersionFenced(TENANT, "doc-t", v2, "run-t", FENCE)).isFalse();
        assertThat(pointerOf("doc-t")).isEqualTo("v1-t");
        assertThat(versionState(v2)).isEqualTo("READY_TO_PUBLISH");
        assertThat(chunkCount(v2, "STAGING")).isEqualTo(1);
        assertThat(versionState("v1-t")).isEqualTo("PUBLISHED");
    }

    // ---------------------------------------------------------------- 判据⑤

    @Test
    void concurrentPublishRejectsStaleLowerOrderAfterHigherCommits() throws Exception {
        seedRun("run-p", FENCE, "RUNNING", liveLease());
        seedDocument("doc-p", "v1-p", 1L);
        seedPublishedVersion("doc-p", "v1-p");
        String v2 = newVersion("doc-p", "run-p", FENCE);
        seedChunk("doc-p", v2, "c1", "STAGING");
        String v3 = newVersion("doc-p", "run-p", FENCE);
        seedChunk("doc-p", v3, "c1", "STAGING");

        AtomicReference<Object> high = new AtomicReference<>();
        AtomicReference<Object> low = new AtomicReference<>();
        try (Connection hold = source.getConnection()) {
            hold.setAutoCommit(false);
            // 控制连接锁住 v3 的 chunk 行：tHigh 停在 chunk 切换步（已持 doc 锁），保证"高序号先持锁"
            try (var statement = hold.createStatement()) {
                statement.execute("SELECT chunk_key FROM ai_document_chunk WHERE tenant_id='" + TENANT
                        + "' AND version_id='" + v3 + "' FOR UPDATE");
            }
            Thread tHigh = publisher(() -> dao.publishVersionFenced(TENANT, "doc-p", v3, "run-p", FENCE), high);
            tHigh.start();
            assertThat(docRowLockHeldByPublisher()).as("tHigh holds the ai_document row lock").isTrue();
            Thread tLow = publisher(() -> dao.publishVersionFenced(TENANT, "doc-p", v2, "run-p", FENCE), low);
            tLow.start();
            tLow.join(300);
            assertThat(tHigh.isAlive()).as("tHigh paused on chunk locks").isTrue();
            assertThat(tLow.isAlive()).as("tLow waits on the doc lock behind tHigh").isTrue();
            hold.rollback();
            tHigh.join(30_000);
            tLow.join(30_000);
            assertThat(tHigh.isAlive()).isFalse();
            assertThat(tLow.isAlive()).isFalse();
        }
        assertThat(high.get()).as("high order publishes first").isEqualTo(Boolean.TRUE);
        assertThat(low.get()).as("low order waits then is rejected").isEqualTo(Boolean.FALSE);
        assertThat(pointerOf("doc-p")).isEqualTo(v3);
        assertThat(versionState(v3)).isEqualTo("PUBLISHED");
        assertThat(versionState(v2)).isEqualTo("READY_TO_PUBLISH");
        assertThat(versionState("v1-p")).isEqualTo("SUPERSEDED");
    }

    @Test
    void legalOrderLowThenHighPublishesBothSucceed() {
        seedRun("run-l", FENCE, "RUNNING", liveLease());
        seedDocument("doc-l", "v1-l", 1L);
        seedPublishedVersion("doc-l", "v1-l");
        String v2 = newVersion("doc-l", "run-l", FENCE);
        seedChunk("doc-l", v2, "c1", "STAGING");
        String v3 = newVersion("doc-l", "run-l", FENCE);
        seedChunk("doc-l", v3, "c1", "STAGING");
        assertThat(dao.publishVersionFenced(TENANT, "doc-l", v2, "run-l", FENCE)).isTrue();
        assertThat(dao.publishVersionFenced(TENANT, "doc-l", v3, "run-l", FENCE)).isTrue();
        assertThat(pointerOf("doc-l")).isEqualTo(v3);
        assertThat(versionState(v3)).isEqualTo("PUBLISHED");
        assertThat(versionState(v2)).isEqualTo("SUPERSEDED");
        assertThat(versionState("v1-l")).isEqualTo("SUPERSEDED");
    }

    // ---------------------------------------------------------------- helpers

    private RunExecutionGuard guardFor(String runId, long fence) {
        return new RunExecutionGuard(context.getBean(RunLedgerDao.class), context.getBean(RunEventAppender.class),
                new P2RuntimeProperties(), TENANT, runId, 1, fence, FENCE_OWNER);
    }

    private Thread publisher(java.util.function.Supplier<Boolean> call, AtomicReference<Object> outcome) {
        return new Thread(() -> {
            try {
                outcome.set(call.get());
            } catch (RuntimeException e) {
                outcome.set(e);
            }
        });
    }

    /** 轮询确认 doc 行锁被 publisher 持有（NOWAIT 必须失败）。 */
    private boolean docRowLockHeldByPublisher() {
        for (int i = 0; i < 100; i++) {
            try (Connection probe = source.getConnection()) {
                probe.setAutoCommit(false);
                try (var statement = probe.createStatement()) {
                    statement.execute("SELECT published_version_id FROM ai_document WHERE tenant_id='" + TENANT
                            + "' AND doc_id='doc-p' FOR UPDATE NOWAIT");
                }
                probe.rollback();
            } catch (Exception lockHeldBySomeone) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void seedRun(String runId, long fence, String status, Timestamp leaseUntil) {
        jdbc.update("INSERT INTO ai_run (tenant_id, run_id, member_id, subject, action, status, policy_version, "
                + "acl_version, fence, lease_owner, lease_until) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                TENANT, runId, "m-pub", "document.ingest", "document.ingest", status, 1, 1, fence,
                FENCE_OWNER, leaseUntil);
    }

    private void seedDocument(String docId, String publishedVersionId, long versionCounter) {
        jdbc.update("INSERT INTO ai_document (tenant_id, doc_id, kb_id, name, member_id, published_version_id, "
                + "version_counter) VALUES (?,?,?,?,?,?,?)",
                TENANT, docId, KB, "pub-doc-" + docId, "m-pub", publishedVersionId, versionCounter);
    }

    private void seedPublishedVersion(String docId, String versionId) {
        jdbc.update("INSERT INTO ai_document_version (tenant_id, version_id, doc_id, upload_id, state, version_order) "
                + "VALUES (?,?,?,?,?,?)", TENANT, versionId, docId, "up-" + versionId, "PUBLISHED", 1L);
        seedChunk(docId, versionId, "c1", "PUBLISHED");
    }

    private void seedChunk(String docId, String versionId, String chunkKey, String state) {
        jdbc.update("INSERT INTO ai_document_chunk (tenant_id, version_id, chunk_key, chunk_index, doc_id, kb_id, "
                + "content, content_hash, char_count, state) VALUES (?,?,?,?,?,?,?,?,?,?)",
                TENANT, versionId, chunkKey, 0, docId, KB, "content-" + chunkKey, "hash-" + chunkKey, 10, state);
    }

    /** 真实 DAO 路径建新版本：insertVersion（STAGING）→ updateVersionStateFenced（READY_TO_PUBLISH）。 */
    private String newVersion(String docId, String runId, long fence) {
        String versionId = "v-" + UUID.randomUUID().toString().substring(0, 8);
        dao.insertVersion(TENANT, versionId, docId, "up-" + versionId, runId);
        assertThat(dao.updateVersionStateFenced(TENANT, versionId, runId, fence, "READY_TO_PUBLISH",
                null, null, null, null)).isTrue();
        return versionId;
    }

    private String pointerOf(String docId) {
        return jdbc.queryForObject("SELECT published_version_id FROM ai_document WHERE tenant_id=? AND doc_id=?",
                String.class, TENANT, docId);
    }

    private String versionState(String versionId) {
        return jdbc.queryForObject("SELECT state FROM ai_document_version WHERE tenant_id=? AND version_id=?",
                String.class, TENANT, versionId);
    }

    private long chunkCount(String versionId, String state) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM ai_document_chunk WHERE tenant_id=? AND version_id=? AND state=?",
                Long.class, TENANT, versionId, state);
        return count == null ? 0 : count;
    }

    private static Timestamp liveLease() {
        return Timestamp.from(Instant.now().plusSeconds(600));
    }

    private static Timestamp expiredLease() {
        return Timestamp.from(Instant.now().minusSeconds(600));
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
        String user = envOrProp("RAGENT_TEST_PG_USER", USER_PROP);
        if (user != null && !user.isBlank()) {
            dataSource.setUser(user);
        }
        String password = envOrProp("RAGENT_TEST_PG_PASSWORD", PASSWORD_PROP);
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
     * 冻结迁移 DDL 提取（FrozenTableDdl 同法，测试内 helper）：按版本序抽 CREATE TABLE platform.&lt;t&gt;
     * 与后续 ALTER TABLE，再把 platform. 前缀重写到本测试的随机 schema。
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
