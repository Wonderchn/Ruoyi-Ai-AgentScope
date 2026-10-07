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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.service.RuntimeCatalogConflictException;
import com.nageoffer.ai.ragent.rag.service.RuntimeCatalogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link RuntimeCatalogService} 的 JDBC 实现。
 *
 * <p><b>每条 SQL 都带 {@code tenant_id = ?}</b>：租户是方法参数而不是全局上下文，
 * 本类不读 {@code PrincipalContext}——这样"漏限定租户"在编译期就暴露成"没有参数可用"，
 * 而不是运行期静默跨租户读。
 *
 * <p><b>读失败不吞。</b>底层 {@code DataAccessException} 直接向上抛：协议层把它映射为
 * 503（依赖不可用），绝不返回空目录/空模型列表让调用方以为"这个租户就是没有模型"。
 * 唯一返回 {@code null} 的位置是 {@link #currentRevision} 的"该租户确实没有已发布行"，
 * 与读失败是两种不同事实。
 *
 * <p><b>档位 write-once。</b>{@link #attachTiers} 在事务里 INSERT ... ON CONFLICT DO NOTHING；
 * 冲突时复读既有行，内容一致按幂等回放、内容不同抛
 * {@link RuntimeCatalogConflictException}。因此"改档位"的唯一合法路径是发布新版本——
 * 与 V15 的不可变版本语义（以及 V24 对该表的注释）一致。
 */
public class JdbcRuntimeCatalogService implements RuntimeCatalogService {

    private static final String COLUMNS =
            "revision_id, revision_no, state, provider_id, model_id, catalog_version, params_hash, "
                    + "params_json, credential_ref, operator_id, published_at, dimension, budget_units";

    private static final String CURRENT_SQL =
            "SELECT " + COLUMNS + " FROM platform.ai_runtime_config_revision "
                    + "WHERE tenant_id = ? AND state = 'PUBLISHED' "
                    + "ORDER BY revision_no DESC LIMIT 1";

    private static final String REVISIONS_SQL =
            "SELECT " + COLUMNS + " FROM platform.ai_runtime_config_revision "
                    + "WHERE tenant_id = ? ORDER BY revision_no DESC LIMIT ?";

    private static final String TIERS_SQL =
            "SELECT revision_id, tier_code, candidate_ids, failure_threshold, open_duration_seconds "
                    + "FROM platform.ai_runtime_config_tier "
                    + "WHERE tenant_id = ? AND revision_id = ? ORDER BY tier_code";

    private static final String SETTINGS_SQL =
            "SELECT setting_key, setting_value FROM platform.ai_runtime_config_setting "
                    + "WHERE tenant_id = ? AND revision_id = ? ORDER BY setting_key";

    private static final String MODELS_SQL =
            "SELECT provider_id, model_id, catalog_version, params_hash, dimension, published_at "
                    + "FROM platform.ai_runtime_config_revision "
                    + "WHERE tenant_id = ? ORDER BY revision_no DESC LIMIT ?";

    private static final String INSERT_TIER_SQL =
            "INSERT INTO platform.ai_runtime_config_tier "
                    + "(tenant_id, revision_id, tier_code, candidate_ids, failure_threshold, open_duration_seconds) "
                    + "VALUES (?, ?, ?, ?::jsonb, ?, ?) "
                    + "ON CONFLICT (tenant_id, revision_id, tier_code) DO NOTHING";

    private static final String EXISTING_TIER_SQL =
            "SELECT candidate_ids, failure_threshold, open_duration_seconds "
                    + "FROM platform.ai_runtime_config_tier "
                    + "WHERE tenant_id = ? AND revision_id = ? AND tier_code = ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcRuntimeCatalogService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public RevisionRow currentRevision(String tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(CURRENT_SQL, tenantId);
        return rows.isEmpty() ? null : toRevision(tenantId, rows.get(0));
    }

    @Override
    public List<RevisionRow> revisions(String tenantId, int limit) {
        return jdbc.queryForList(REVISIONS_SQL, tenantId, limit).stream()
                .map(row -> toRevision(tenantId, row))
                .toList();
    }

    @Override
    public List<TierRow> tiers(String tenantId, String revisionId) {
        return jdbc.queryForList(TIERS_SQL, tenantId, revisionId).stream()
                .map(row -> new TierRow(
                        asString(row.get("revision_id")),
                        asString(row.get("tier_code")),
                        candidateIds(row.get("candidate_ids")),
                        asInt(row.get("failure_threshold")),
                        asInt(row.get("open_duration_seconds"))))
                .toList();
    }

    @Override
    public Map<String, Object> settings(String tenantId, String revisionId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(SETTINGS_SQL, tenantId, revisionId)) {
            out.put(asString(row.get("setting_key")), jsonValue(row.get("setting_value")));
        }
        return out;
    }

    @Override
    public List<ModelRow> knownModels(String tenantId, int limit) {
        Set<String> seen = new LinkedHashSet<>();
        List<ModelRow> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(MODELS_SQL, tenantId, limit)) {
            String providerId = asString(row.get("provider_id"));
            String modelId = asString(row.get("model_id"));
            if (providerId == null || modelId == null || !seen.add(providerId + "\u0000" + modelId)) {
                continue;
            }
            out.add(new ModelRow(providerId, modelId, asString(row.get("catalog_version")),
                    row.get("dimension") instanceof Number number ? number.intValue() : null,
                    asInstant(row.get("published_at")), asString(row.get("params_hash"))));
        }
        return out;
    }

    @Override
    public AttachResult attachTiers(String tenantId, String revisionId, List<TierSpec> tiers, String operatorId) {
        if (tiers == null || tiers.isEmpty()) {
            throw new RuntimeCatalogConflictException("no tiers to attach");
        }
        Boolean replayed = transactions.execute(status -> {
            boolean[] anyExisting = {true};
            for (TierSpec tier : tiers) {
                int inserted = jdbc.update(INSERT_TIER_SQL, tenantId, revisionId, tier.tierCode(),
                        writeJson(tier.candidateIds()), tier.failureThreshold(), tier.openDurationSeconds());
                if (inserted == 1) {
                    anyExisting[0] = false;
                    continue;
                }
                // 0 行 = 该 (tenant, revision, tier) 已存在：不可变版本不允许改档位。
                if (!sameAsExisting(tenantId, revisionId, tier)) {
                    throw new RuntimeCatalogConflictException(
                            "tier " + tier.tierCode() + " is already attached to immutable revision " + revisionId
                                    + " with different facts; publish a new revision instead");
                }
            }
            return anyExisting[0] && !tiers.isEmpty();
        });
        // 事务返回 null 只可能是 execute 被回滚（异常已抛出），这里显式 fail-closed。
        List<TierRow> rows = tiers(tenantId, revisionId);
        if (replayed == null) {
            throw new RuntimeCatalogConflictException("tier attachment did not commit");
        }
        return new AttachResult(revisionId, rows, replayed);
    }

    /** 复读既有行并逐字段比较；内容一致 ⇒ 幂等回放，不一致 ⇒ 拒绝。 */
    private boolean sameAsExisting(String tenantId, String revisionId, TierSpec tier) {
        List<Map<String, Object>> rows = jdbc.queryForList(EXISTING_TIER_SQL, tenantId, revisionId, tier.tierCode());
        if (rows.size() != 1) {
            return false;
        }
        Map<String, Object> row = rows.get(0);
        return candidateIds(row.get("candidate_ids")).equals(tier.candidateIds())
                && asInt(row.get("failure_threshold")) == tier.failureThreshold()
                && asInt(row.get("open_duration_seconds")) == tier.openDurationSeconds();
    }

    private RevisionRow toRevision(String tenantId, Map<String, Object> row) {
        return new RevisionRow(
                tenantId,
                asString(row.get("revision_id")),
                asLong(row.get("revision_no")),
                asString(row.get("state")),
                asString(row.get("provider_id")),
                asString(row.get("model_id")),
                asString(row.get("catalog_version")),
                asString(row.get("params_hash")),
                asString(row.get("params_json")),
                asString(row.get("credential_ref")),
                asString(row.get("operator_id")),
                asInstant(row.get("published_at")),
                row.get("dimension") instanceof Number number ? number.intValue() : null,
                row.get("budget_units") instanceof Number number ? number.longValue() : null);
    }

    private List<String> candidateIds(Object value) {
        Object parsed = jsonValue(value);
        if (!(parsed instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            out.add(item == null ? null : String.valueOf(item));
        }
        return out;
    }

    /** jsonb 列在 PGobject / String / 已解析结构三种形态下都还原成 Java 值。 */
    private Object jsonValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map || value instanceof List || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(text, new TypeReference<Object>() { });
        } catch (JsonProcessingException e) {
            return text;
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeCatalogConflictException("tier candidates are not serializable");
        }
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
