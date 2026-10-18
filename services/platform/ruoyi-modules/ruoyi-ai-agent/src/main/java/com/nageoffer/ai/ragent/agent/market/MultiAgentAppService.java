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

package com.nageoffer.ai.ragent.agent.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.ragent.agent.orchestration.SupervisorProtocol;
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * F14:2 多 Agent 应用市场（V43 {@code platform.ai_multi_agent_app}）的只读投影与受理复核。
 *
 * <p><b>市场是只读投影，不建安装表</b>（D1/D9）：选择=使用，客户端拿到的是
 * {@code uuid/name/description/mode/agents} 五个字段，不含所有者、租户或开关列。
 *
 * <p><b>可见性谓词只有一组</b>：{@code tenant_id=当前 AND is_public=1 AND is_enable=1
 * AND deleted=0}。搜索/详情/受理复核都复用它——私有、停用、软删、跨租户与"不存在"
 * 在服务层收敛为同一结果（{@code null}/empty），由控制器统一映射为 404 同一文案，
 * 不泄露存在性。可选关键词只能叠加过滤，永远不能放宽边界。
 *
 * <p><b>坏名册不投影</b>：名册形状由受理协议（{@code SupervisorProtocol.roster}）同一套
 * 约束校验；库内行若形状不合法，列表跳过该行、详情视为不可用。这样客户端不会拿到
 * 半截名册去发起一次必然失败的运行。
 */
public class MultiAgentAppService {

    public static final int DEFAULT_PAGE_SIZE = 12;
    public static final int MAX_PAGE_SIZE = 100;

    private static final String PUBLIC_PREDICATE =
            "tenant_id=? AND is_public=1 AND is_enable=1 AND deleted=0";
    private static final String PROJECTION = "uuid, name, description, mode, agents::text";
    private static final String SELECT_PUBLIC =
            "SELECT " + PROJECTION + " FROM platform.ai_multi_agent_app WHERE " + PUBLIC_PREDICATE;
    private static final String SEARCH_PUBLIC =
            SELECT_PUBLIC + " AND (? = '' OR name ILIKE ? OR COALESCE(description,'') ILIKE ?)"
                    + " ORDER BY update_time DESC, uuid ASC LIMIT ? OFFSET ?";
    private static final String COUNT_PUBLIC =
            "SELECT COUNT(*) FROM platform.ai_multi_agent_app WHERE " + PUBLIC_PREDICATE
                    + " AND (? = '' OR name ILIKE ? OR COALESCE(description,'') ILIKE ?)";
    private static final String FIND_PUBLIC = SELECT_PUBLIC + " AND uuid=?";

    private static final RowMapper<Row> MAPPER = (rs, index) -> new Row(
            rs.getString("uuid"), rs.getString("name"), rs.getString("description"),
            rs.getString("mode"), rs.getString("agents"));

    private final JdbcTemplate jdbc;

    public MultiAgentAppService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 公开列表（同租户、跨用户、只读）；关键词只叠加过滤。 */
    public AppPage search(String tenantId, String keyword, Integer current, Integer size) {
        String tenant = requireTenant(tenantId);
        String search = keyword == null ? "" : keyword.trim();
        int page = current == null || current < 1 ? 1 : current;
        int limit = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        long offset = (long) (page - 1) * limit;
        String pattern = "%" + search + "%";

        List<Row> rows = jdbc.query(SEARCH_PUBLIC, MAPPER, tenant, search, pattern, pattern, limit, offset);
        Long total = jdbc.queryForObject(COUNT_PUBLIC, Long.class, tenant, search, pattern, pattern);

        List<AppView> records = new ArrayList<>();
        for (Row row : rows) {
            AppView view = toView(row);
            if (view != null) {
                records.add(view);
            }
        }
        return new AppPage(List.copyOf(records), total == null ? 0L : total, page, limit);
    }

    /** 公开详情；不可见（不存在/跨租户/私有/停用/软删）或名册坏形状 → {@code null}。 */
    public AppView getPublic(String tenantId, String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return null;
        }
        List<Row> rows = jdbc.query(FIND_PUBLIC, MAPPER, requireTenant(tenantId), uuid.trim());
        return rows.isEmpty() ? null : toView(rows.get(0));
    }

    /**
     * 受理事务内的 appId 复核：与市场详情同一谓词，命中时返回 uuid 与库内名册原文。
     *
     * <p>这里不解析名册——名册一致性由受理契约用与输入校验相同的协议解析后比较，
     * 坏数据在该层收敛为 INTERNAL_ERROR，而不是伪装成"应用不存在"。
     */
    public Optional<VisibleApp> findVisible(String tenantId, String appId) {
        if (appId == null || appId.isBlank()) {
            return Optional.empty();
        }
        List<Row> rows = jdbc.query(FIND_PUBLIC, MAPPER, requireTenant(tenantId), appId.trim());
        return rows.stream().findFirst().map(row -> new VisibleApp(row.uuid(), row.agentsJson()));
    }

    private static AppView toView(Row row) {
        List<AppAgent> agents = parseAgents(row.agentsJson());
        if (agents == null) {
            return null;
        }
        return new AppView(row.uuid(), row.name(), row.description() == null ? "" : row.description(),
                row.mode(), agents);
    }

    /** 与受理同一套名册约束；形状不合法返回 null（市场不投影半截数据）。 */
    private static List<AppAgent> parseAgents(String json) {
        try {
            JsonNode node = CanonicalJson.strictMapper().readTree(json == null ? "" : json);
            SupervisorProtocol.Roster roster = SupervisorProtocol.roster(node);
            List<AppAgent> agents = new ArrayList<>(roster.agents().size());
            for (SupervisorProtocol.Agent agent : roster.agents()) {
                agents.add(new AppAgent(agent.name(), agent.instruction()));
            }
            return List.copyOf(agents);
        } catch (Exception invalid) {
            return null;
        }
    }

    private static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new RunApiException(RunErrorCode.TENANT_CONTEXT_MISSING);
        }
        return tenantId;
    }

    // ------------------------------------------------------------ 数据形状

    public record AppAgent(String name, String instruction) {
    }

    public record AppView(String uuid, String name, String description, String mode, List<AppAgent> agents) {
    }

    public record AppPage(List<AppView> records, long total, long current, long size) {
    }

    /** 受理复核结果：uuid + 库内名册原文（由契约解析并比对）。 */
    public record VisibleApp(String uuid, String agentsJson) {
    }

    /** JDBC 行（包内可见，供测试桩构造）。 */
    record Row(String uuid, String name, String description, String mode, String agentsJson) {
    }
}
