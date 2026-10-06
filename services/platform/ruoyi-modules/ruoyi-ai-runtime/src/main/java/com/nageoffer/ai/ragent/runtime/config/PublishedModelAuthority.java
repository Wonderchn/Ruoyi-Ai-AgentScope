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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 数据库发布版本作为唯一运行权威的实现（C1.1/C1.2/C1.3）。
 *
 * <p><b>为什么是"取每个租户 revision_no 最大的 PUBLISHED 行"，而不是"一张单行表"。</b>
 * C1.2 要求同一个租户下"新受理的 run 绑新版本"与"已受理/运行/恢复的 run 固定原版本"
 * <b>同时成立</b>。单行表无法表达这件事：改那唯一一行就等于同时改掉所有在途 run 的解释
 * （run 上的 revision_id 还指着同一个值，但那份配置已经变了）。所以事实必须是**版本序列**，
 * "当前版本"是序列上的一个查询（V15 的 {@code idx_ai_runtime_config_revision_published}），
 * 而 run 记录的是**不可变的 revision_id**。
 *
 * <p><b>失败即拒绝（C1.1/C1.5-3）。</b>三种情况一律抛
 * {@link ConfigAuthorityUnavailable}，绝不返回 YAML 值、绝不返回旧值、绝不返回空串：
 * <ol>
 *   <li>没有执行主体 —— 无租户就无从谈"哪个租户的权威"，让调用方猜等于跨租户读配置；</li>
 *   <li>该租户没有 PUBLISHED 版本 —— 没有权威就是没有，不能拿"默认模型"顶替；</li>
 *   <li>数据库读取失败 —— 这正是 C1.5 判据 3 要打的"断 DB 时不得返回 YAML 成功路径"。</li>
 * </ol>
 *
 * <p><b>这里刻意没有缓存。</b>撤权必须"立即生效"（C1.3），而进程内缓存正是让撤权延迟生效的
 * 常见成因。多一次按主键/索引的点查是这个契约的合理代价；需要吞吐时应当加**带失效广播**的
 * 缓存，而不是在这里加一个没有失效通道的 map。
 */
public class PublishedModelAuthority implements EngineModelAuthority, PublishedModelFactsPort {

    /**
     * 取当前权威版本：租户内 revision_no 最大的 PUBLISHED 行。
     *
     * <p>用 {@code LIMIT 1} + 唯一索引 {@code uk_ai_runtime_config_revision_no} 保证单值：
     * 该索引使"同租户同 revision_no 两行"不可能存在，因此这里不存在并列歧义。
     */
    private static final String CURRENT_PUBLISHED_SQL =
            "SELECT revision_id, revision_no, provider_id, model_id, catalog_version, params_hash, "
                    + "credential_ref, operator_id, published_at "
                    + "FROM platform.ai_runtime_config_revision "
                    + "WHERE tenant_id = ? AND state = 'PUBLISHED' "
                    + "ORDER BY revision_no DESC LIMIT 1";

    /**
     * WP-040 A2 维度门的读端：同源同表同行，只多取 {@code dimension} 列
     * （V24 补的管理面事实列）。刻意**不**改 {@link #CURRENT_PUBLISHED_SQL}：
     * 受理主路径的列清单是 V15 冻结契约的镜像，多取一列就多一处漂移点。
     */
    private static final String CURRENT_PUBLISHED_FACTS_SQL =
            "SELECT revision_id, revision_no, provider_id, model_id, catalog_version, params_hash, "
                    + "credential_ref, operator_id, published_at, dimension "
                    + "FROM platform.ai_runtime_config_revision "
                    + "WHERE tenant_id = ? AND state = 'PUBLISHED' "
                    + "ORDER BY revision_no DESC LIMIT 1";

    private final JdbcTemplate jdbc;

    public PublishedModelAuthority(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PublishedModel requirePublished(String action) {
        ExecutionPrincipal principal = PrincipalContext.get();
        if (principal == null) {
            throw new ConfigAuthorityUnavailable("no execution principal for action=" + action);
        }
        String tenantId = principal.tenantId();

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(CURRENT_PUBLISHED_SQL, tenantId);
        } catch (DataAccessException failure) {
            // 刻意不把异常消息透出（可能含连接串/库名）；原因分类保留在异常里供日志使用。
            throw new ConfigAuthorityUnavailable("config authority read failed");
        }
        if (rows.isEmpty()) {
            throw new ConfigAuthorityUnavailable("no published config revision");
        }

        Map<String, Object> row = rows.get(0);
        return new PublishedModel(
                tenantId,
                asString(row.get("revision_id")),
                asLong(row.get("revision_no")),
                asString(row.get("provider_id")),
                asString(row.get("model_id")),
                asString(row.get("catalog_version")),
                asString(row.get("params_hash")),
                asString(row.get("credential_ref")),
                asString(row.get("operator_id")),
                asInstant(row.get("published_at")));
    }

    /**
     * WP-040 A2 的读端。**刻意不抛拒绝异常**而返回 {@code null}：维度门的拒绝归
     * 受理侧（它先经 {@link #requirePublished} 做过拒绝判定，本端口的 null 只是
     * "门自动跳过/防御"分支），两个端口的失败语义不能互相吞并。
     */
    @Override
    public ConfigRevisionFacts currentPublishedFacts() {
        ExecutionPrincipal principal = PrincipalContext.get();
        if (principal == null) {
            return null;
        }
        String tenantId = principal.tenantId();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(CURRENT_PUBLISHED_FACTS_SQL, tenantId);
        } catch (DataAccessException failure) {
            return null;
        }
        if (rows.isEmpty()) {
            return null;
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
                row.get("dimension") instanceof Number number ? number.intValue() : 0);
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
