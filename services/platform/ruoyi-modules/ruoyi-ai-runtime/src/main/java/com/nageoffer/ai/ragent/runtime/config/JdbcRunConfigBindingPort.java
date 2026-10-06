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

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link RunConfigBindingPort} 的 JDBC 实现（C1.2/C1.3）。
 *
 * <p><b>一次查询取齐"run 上记录的绑定"与"版本行的事实"，并在应用层比对两者。</b>
 * 分开查两次会打开一个窗口：两次读之间版本行被撤权，或者 run 行的绑定被改写，
 * 结果就是"用旧事实配新版本"。单条 JOIN 查询 + 显式一致性比对把窗口收掉。
 *
 * <p><b>为什么 LEFT JOIN 而不是 INNER JOIN。</b>INNER JOIN 会把"run 未绑定版本"与
 * "绑定版本缺失"两种成因都折叠成"查不到行"，从而无法给出可诊断的拒绝原因，
 * 也更容易被误实现成"查不到就取当前版本"。LEFT JOIN 之后这两者可以分别判定并分别拒绝。
 *
 * <p><b>刻意不缓存。</b>撤权必须立即生效（C1.3）；缓存正是让"撤权后旧版本仍在用"发生的东西。
 * 需要吞吐时应加**带失效广播**的缓存，而不是这里一个没有失效通道的 map。
 */
public class JdbcRunConfigBindingPort implements RunConfigBindingPort {

    private static final String BOUND_REVISION_SQL =
            "SELECT r.action, r.config_revision_id, "
                    + "       r.catalog_version AS run_catalog_version, "
                    + "       r.params_hash     AS run_params_hash, "
                    + "       r.provider_id     AS run_provider_id, "
                    + "       r.model_id        AS run_model_id, "
                    + "       r.config_operator, r.config_published_at, "
                    + "       c.revision_no, c.state AS revision_state, "
                    + "       c.provider_id     AS revision_provider_id, "
                    + "       c.model_id        AS revision_model_id, "
                    + "       c.catalog_version AS revision_catalog_version, "
                    + "       c.params_hash     AS revision_params_hash, "
                    + "       c.credential_ref, c.operator_id AS revision_operator, "
                    + "       c.published_at    AS revision_published_at "
                    + "FROM platform.ai_run r "
                    + "LEFT JOIN platform.ai_runtime_config_revision c "
                    + "  ON c.tenant_id = r.tenant_id AND c.revision_id = r.config_revision_id "
                    + "WHERE r.tenant_id = ? AND r.run_id = ?";

    private final JdbcTemplate jdbc;

    public JdbcRunConfigBindingPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RunConfigBinding requireBoundRevision(String tenantId, String runId) {
        if (tenantId == null || tenantId.isBlank() || runId == null || runId.isBlank()) {
            throw new ConfigAuthorityUnavailable("tenantId and runId are required");
        }

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(BOUND_REVISION_SQL, tenantId, runId);
        } catch (DataAccessException failure) {
            // 不透出连接细节；原因分类保留在异常里供日志使用
            throw new ConfigAuthorityUnavailable("run binding read failed");
        }
        if (rows.isEmpty()) {
            throw new ConfigAuthorityUnavailable("run not found");
        }

        Map<String, Object> row = rows.get(0);
        String revisionId = asString(row.get("config_revision_id"));
        if (revisionId == null || revisionId.isBlank()) {
            // C1.2 第 3 行：**不复用当前版本顶替**。历史 run 就是"不可追溯"，
            // 明确拒绝比编一个版本更有价值 —— 编出来的版本会让账实不符且无法回溯。
            throw new ConfigAuthorityUnavailable("run has no bound config revision");
        }
        if (row.get("revision_no") == null) {
            throw new ConfigAuthorityUnavailable("bound config revision missing");
        }
        String state = asString(row.get("revision_state"));
        if (!"PUBLISHED".equals(state)) {
            // C1.3：撤权立即生效，不受 run 固定版本保护
            throw new ConfigAuthorityUnavailable("bound config revision is " + state);
        }

        // run 上记录的事实必须与版本行自洽：不一致说明存在篡改或写路径缺陷。
        // 此时**不能挑一个信**：挑 run 上的会掩盖版本被改，挑版本行的会掩盖 run 被改。
        requireAgrees("catalog_version", row.get("run_catalog_version"), row.get("revision_catalog_version"));
        requireAgrees("params_hash", row.get("run_params_hash"), row.get("revision_params_hash"));
        requireAgrees("provider_id", row.get("run_provider_id"), row.get("revision_provider_id"));
        requireAgrees("model_id", row.get("run_model_id"), row.get("revision_model_id"));

        return new RunConfigBinding(
                tenantId,
                runId,
                asString(row.get("action")),
                revisionId,
                asLong(row.get("revision_no")),
                asString(row.get("revision_provider_id")),
                asString(row.get("revision_model_id")),
                asString(row.get("revision_catalog_version")),
                asString(row.get("revision_params_hash")),
                asString(row.get("credential_ref")),
                asString(row.get("revision_operator")),
                asInstant(row.get("revision_published_at")));
    }

    private static void requireAgrees(String field, Object runValue, Object revisionValue) {
        if (!Objects.equals(asString(runValue), asString(revisionValue))) {
            throw new ConfigAuthorityUnavailable("run binding diverges on " + field);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
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
