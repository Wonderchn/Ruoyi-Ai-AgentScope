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

package com.nageoffer.ai.ragent.runtime.config;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link ConfigRevisionPublisher} 的 JDBC 实现：写的就是运行期读的那张表
 * （{@code platform.ai_runtime_config_revision}，V15），因此**构不成第二权威**。
 *
 * <p><b>为什么写侧也在本包（而不是管理后端模块）。</b>发布是"把事实固定成不可变版本"
 * 的事务：INSERT revision 行 + INSERT audit 行必须同事务（V15 冻结设计：审计只追加）。
 * 把 SQL 拆到另一个模块会把这段事务语义复制成第二份。管理面模块后续只依赖本端口。
 *
 * <p><b>审计列 {@code diff_json}。</b>PUBLISH 的 diff 是「相对上一 PUBLISHED 版本」的
 * 事实差异（provider/model/catalog/params_hash/dimension）；REVOKE 记录的是被撤的
 * revision_id。**不写密钥**：{@code credential_ref} 只以"引用是否变化"的布尔形式入 diff。
 *
 * <p><b>维度（A2）的两道门。</b>第一道在这里：维度 != 1536 时**在任何写入之前**抛出
 * （响应亮失败且 DB 行数零增量）。第二道是迁移 V24 的 CHECK 约束（数据库兜底，
 * 防任何旁路写路径）。
 */
public class JdbcConfigRevisionPublisher implements ConfigRevisionPublisher {

    private static final String INSERT_REVISION_SQL =
            "INSERT INTO platform.ai_runtime_config_revision "
                    + "(tenant_id, revision_id, revision_no, state, provider_id, model_id, catalog_version, "
                    + " params_hash, params_json, credential_ref, operator_id, published_at, dimension) "
                    + "VALUES (?, ?, ?, 'PUBLISHED', ?, ?, ?, ?, ?::jsonb, ?, ?, now(), ?)";

    private static final String INSERT_AUDIT_SQL =
            "INSERT INTO platform.ai_runtime_config_revision_audit "
                    + "(tenant_id, audit_id, revision_id, operator_id, action, diff_json) "
                    + "VALUES (?, ?, ?, ?, 'PUBLISH', ?::jsonb)";

    private static final String REVOKE_SQL =
            "UPDATE platform.ai_runtime_config_revision SET state = 'REVOKED', revoked_at = now() "
                    + "WHERE tenant_id = ? AND revision_id = ? AND state = 'PUBLISHED'";

    private static final String INSERT_REVOKE_AUDIT_SQL =
            "INSERT INTO platform.ai_runtime_config_revision_audit "
                    + "(tenant_id, audit_id, revision_id, operator_id, action, diff_json) "
                    + "VALUES (?, ?, ?, ?, 'REVOKE', ?::jsonb)";

    private static final String PREVIOUS_SQL =
            "SELECT provider_id, model_id, catalog_version, params_hash, dimension, credential_ref "
                    + "FROM platform.ai_runtime_config_revision "
                    + "WHERE tenant_id = ? AND state = 'PUBLISHED' ORDER BY revision_no DESC LIMIT 1";

    private static final String REQUIRE_SQL =
            "SELECT revision_id, revision_no, provider_id, model_id, catalog_version, params_hash, "
                    + "credential_ref, operator_id, published_at, dimension "
                    + "FROM platform.ai_runtime_config_revision "
                    + "WHERE tenant_id = ? AND revision_id = ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcConfigRevisionPublisher(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public ConfigRevisionFacts publish(ConfigRevisionCommand raw) {
        ExecutionPrincipal principal = PrincipalContext.get();
        if (principal == null) {
            throw new ConfigAuthorityUnavailable("no execution principal for config publish");
        }
        String tenantId = principal.tenantId();
        ConfigRevisionCommand command = raw == null ? null : raw.normalized();
        requireFact(tenantId, command);

        // A2 第一道门：维度错误在任何写入之前响亮拒绝 ⇒ DB 行数零增量。
        if (command.dimension() != REQUIRED_DIMENSION) {
            throw new ConfigAuthorityUnavailable(
                    "embedding dimension " + command.dimension() + " != required " + REQUIRED_DIMENSION
                            + "; refusing to publish");
        }

        // 审计 diff 需要上一版本；读失败会让整个发布失败（发布必须可审计，不允许"发了但没人知道上一版"）。
        // 首发布（租户还没有 PUBLISHED 行）是合法状态：diff 按"无上一版本"处理，不得与读失败混同。
        Map<String, Object> previous;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(PREVIOUS_SQL, tenantId);
            previous = rows.isEmpty() ? null : rows.get(0);
        } catch (DataAccessException failure) {
            throw new ConfigAuthorityUnavailable("config authority read failed");
        }

        String revisionId = "rev-" + UUID.randomUUID().toString().replace("-", "");
        String diff = diffJson(command, previous);
        try {
            return transactions.execute(status -> {
                jdbc.update(INSERT_REVISION_SQL,
                        tenantId, revisionId, nextRevisionNo(tenantId), command.providerId(), command.modelId(),
                        command.catalogVersion(), command.paramsHash(), command.paramsJson(),
                        command.credentialRef(), principal.userId(), command.dimension());
                jdbc.update(INSERT_AUDIT_SQL, tenantId,
                        "aud-" + UUID.randomUUID().toString().replace("-", ""),
                        revisionId, principal.userId(), diff);
                return require(tenantId, revisionId);
            });
        } catch (DataAccessException failure) {
            // 刻意不透出连接细节；原因分类保留在异常里供日志使用。
            throw new ConfigAuthorityUnavailable("config revision publish failed");
        }
    }

    @Override
    public void revoke(String revisionId, String operatorId) {
        ExecutionPrincipal principal = PrincipalContext.get();
        if (principal == null) {
            throw new ConfigAuthorityUnavailable("no execution principal for config revoke");
        }
        String tenantId = principal.tenantId();
        if (revisionId == null || revisionId.isBlank()) {
            throw new ConfigAuthorityUnavailable("revisionId is required to revoke");
        }
        String trimmed = revisionId.trim();
        try {
            // UPDATE + 审计必须同事务：审计写失败时撤权一起回滚（否则"撤了但没人知道"）。
            Boolean revoked = transactions.execute(status -> {
                int updated = jdbc.update(REVOKE_SQL, tenantId, trimmed);
                if (updated != 1) {
                    // 0 行 = 版本不存在或已撤权；V15 触发器保证不存在第三种状态，这里不复读。
                    throw new ConfigAuthorityUnavailable(
                            "config revision " + trimmed + " is missing or already revoked");
                }
                jdbc.update(INSERT_REVOKE_AUDIT_SQL, tenantId,
                        "aud-" + UUID.randomUUID().toString().replace("-", ""),
                        trimmed,
                        operatorId == null || operatorId.isBlank() ? principal.userId() : operatorId.trim(),
                        "{\"action\":\"REVOKE\",\"credentialRefChanged\":false}");
                return Boolean.TRUE;
            });
            if (revoked == null) {
                throw new ConfigAuthorityUnavailable("config revision revoke failed");
            }
        } catch (DataAccessException failure) {
            throw new ConfigAuthorityUnavailable("config revision revoke failed");
        }
    }

    @Override
    public ConfigRevisionFacts require(String revisionId) {
        ExecutionPrincipal principal = PrincipalContext.get();
        if (principal == null) {
            throw new ConfigAuthorityUnavailable("no execution principal for config read");
        }
        if (revisionId == null || revisionId.isBlank()) {
            throw new ConfigAuthorityUnavailable("revisionId is required");
        }
        return require(principal.tenantId(), revisionId.trim());
    }

    private ConfigRevisionFacts require(String tenantId, String revisionId) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(REQUIRE_SQL, tenantId, revisionId);
        } catch (DataAccessException failure) {
            throw new ConfigAuthorityUnavailable("config authority read failed");
        }
        if (rows.isEmpty()) {
            throw new ConfigAuthorityUnavailable("config revision " + revisionId + " not found");
        }
        Map<String, Object> row = rows.get(0);
        return new ConfigRevisionFacts(
                tenantId,
                asString(row.get("revision_id")),
                asLong(row.get("revision_no")),
                asString(row.get("provider_id")),
                asString(row.get("model_id")),
                asString(row.get("catalog_version")),
                asString(row.get("params_hash")),
                asString(row.get("credential_ref")),
                asString(row.get("operator_id")),
                asInstant(row.get("published_at")),
                asInt(row.get("dimension")));
    }

    private void requireFact(String tenantId, ConfigRevisionCommand command) {
        if (command == null) {
            throw new ConfigAuthorityUnavailable("publish command is required");
        }
        if (command.providerId() == null || command.providerId().isBlank()
                || command.modelId() == null || command.modelId().isBlank()
                || command.catalogVersion() == null || command.catalogVersion().isBlank()
                || command.paramsHash() == null || command.paramsHash().isBlank()) {
            throw new ConfigAuthorityUnavailable(
                    "providerId/modelId/catalogVersion/paramsHash are required facts (tenant=" + tenantId + ")");
        }
    }

    private long nextRevisionNo(String tenantId) {
        Long no = jdbc.queryForObject(
                "SELECT coalesce(max(revision_no), 0) + 1 FROM platform.ai_runtime_config_revision "
                        + "WHERE tenant_id = ?",
                Long.class, tenantId);
        return no == null ? 1L : no;
    }

    /**
     * 发布 diff：只含事实字段与"凭据引用是否变化"的布尔；**不含密钥**，也不含引用值本身
     * （引用可能被当作半敏感信息，diff 是审计面，按最小披露原则只记录变化与否）。
     * 首发布（{@code previous == null}）时一切视为"相对空版本"（changed=true、previousDimension=null）。
     */
    private String diffJson(ConfigRevisionCommand command, Map<String, Object> previous) {
        boolean firstPublish = previous == null;
        String previousProvider = firstPublish ? null : asString(previous.get("provider_id"));
        String previousModel = firstPublish ? null : asString(previous.get("model_id"));
        String previousCatalog = firstPublish ? null : asString(previous.get("catalog_version"));
        String previousHash = firstPublish ? null : asString(previous.get("params_hash"));
        Object previousDimension = firstPublish ? null : previous.get("dimension");
        String previousRef = firstPublish ? null : asString(previous.get("credential_ref"));
        boolean credentialRefChanged = previousRef == null
                ? command.credentialRef() != null
                : !previousRef.equals(command.credentialRef());
        return "{\"providerChanged\":" + !command.providerId().equals(previousProvider)
                + ",\"modelChanged\":" + !command.modelId().equals(previousModel)
                + ",\"catalogVersionChanged\":" + !command.catalogVersion().equals(previousCatalog)
                + ",\"paramsHashChanged\":" + !command.paramsHash().equals(previousHash)
                + ",\"dimension\":" + command.dimension()
                + ",\"previousDimension\":" + (previousDimension == null ? "null" : asInt(previousDimension))
                + ",\"credentialRefChanged\":" + credentialRefChanged
                + "}";
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Instant asInstant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }
}
