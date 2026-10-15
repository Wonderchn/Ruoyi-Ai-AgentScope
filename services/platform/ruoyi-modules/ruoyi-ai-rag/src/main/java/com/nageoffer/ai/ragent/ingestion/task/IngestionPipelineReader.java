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

package com.nageoffer.ai.ragent.ingestion.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F06:1/2 canonical 摄取运行面的**只读**管线快照读取器（租户参数化，不依赖 PrincipalContext）。
 *
 * <p>为什么不用 {@code IngestionPipelineServiceImpl.getDefinition}：该方法经
 * {@code PrincipalContext.require()} 取租户，只活在前端请求线程；worker 线程执行
 * {@code ingestion.run} 时没有 PrincipalContext，无法据以重建定义。本读取器以
 * {@code (tenantId, pipelineId)} 显式入参读同一对表，供受理与执行两侧共用一份事实。
 *
 * <p>只读；不写任何表；不修改既有 DO/服务。
 */
@Component
public class IngestionPipelineReader {

    private static final java.util.Set<String> KNOWN_TYPES =
            java.util.Set.of("fetcher", "parser", "enhancer", "chunker", "enricher", "indexer");
    /** 链必须含的最小组（否则无法产出分块并发布）。 */
    private static final java.util.List<String> REQUIRED_ORDER =
            java.util.List.of("fetcher", "parser", "enhancer", "chunker", "enricher", "indexer");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IngestionPipelineReader(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record NodeSnapshot(String nodeId, String nodeType, boolean enabled, String nextNodeId,
                               String settingsJson, String conditionJson) {
    }

    /** 解析后的管线快照。{@code chain} 为按 nextNodeId 走通后的有序节点（含 disabled）。 */
    public record PipelineSnapshot(String pipelineId, String name, List<NodeSnapshot> chain, String hash) {

        public List<NodeSnapshot> enabledChain() {
            return chain.stream().filter(NodeSnapshot::enabled).toList();
        }
    }

    public Optional<PipelineSnapshot> load(String tenantId, String pipelineId) {
        if (tenantId == null || pipelineId == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> heads = jdbc.query(
                "SELECT id, name FROM ai_ingestion_pipeline WHERE tenant_id=? AND id=? AND deleted=0",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("name", rs.getString("name"));
                    return row;
                }, tenantId, pipelineId);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        String name = String.valueOf(heads.get(0).get("name"));

        List<NodeSnapshot> raw = jdbc.query(
                "SELECT node_id, node_type, enabled, next_node_id, settings_json, condition_json "
                        + "FROM ai_ingestion_pipeline_node WHERE tenant_id=? AND pipeline_id=? AND deleted=0",
                (rs, rowNum) -> new NodeSnapshot(
                        rs.getString("node_id"),
                        normalizeType(rs.getString("node_type")),
                        rs.getObject("enabled") == null || rs.getBoolean("enabled"),
                        rs.getString("next_node_id"),
                        rs.getString("settings_json"),
                        rs.getString("condition_json")),
                tenantId, pipelineId);
        if (raw.isEmpty()) {
            throw new IllegalStateException("pipeline has no nodes");
        }

        Map<String, NodeSnapshot> byId = new LinkedHashMap<>();
        for (NodeSnapshot node : raw) {
            if (node.nodeId() == null || node.nodeId().isBlank() || byId.put(node.nodeId(), node) != null) {
                throw new IllegalStateException("pipeline node ids must be unique and non-blank");
            }
        }
        java.util.Set<String> referenced = new java.util.HashSet<>();
        for (NodeSnapshot node : raw) {
            if (node.nextNodeId() != null && !node.nextNodeId().isBlank()) {
                referenced.add(node.nextNodeId());
            }
        }
        List<String> headsList = raw.stream().map(NodeSnapshot::nodeId).filter(id -> !referenced.contains(id)).toList();
        if (headsList.size() != 1) {
            throw new IllegalStateException("pipeline must have exactly one head");
        }
        List<NodeSnapshot> chain = new ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        String cursor = headsList.get(0);
        while (cursor != null) {
            NodeSnapshot node = byId.get(cursor);
            if (node == null || !visited.add(cursor)) {
                throw new IllegalStateException("pipeline chain is broken or cyclic");
            }
            chain.add(node);
            cursor = node.nextNodeId() == null || node.nextNodeId().isBlank() ? null : node.nextNodeId();
        }
        if (chain.size() != raw.size()) {
            throw new IllegalStateException("pipeline contains nodes outside the single chain");
        }

        return Optional.of(new PipelineSnapshot(pipelineId, name, chain, hashOf(chain)));
    }

    /**
     * 结构合法性（受理期硬判）。返回 {@code null} 表示合法，否则返回稳定错误码。
     */
    public String structureError(PipelineSnapshot snapshot) {
        List<NodeSnapshot> enabled = snapshot.enabledChain();
        if (enabled.isEmpty()) {
            return "INGEST_PIPELINE_EMPTY";
        }
        int cursor = -1;
        boolean hasParser = false;
        boolean hasChunker = false;
        boolean hasIndexer = false;
        for (NodeSnapshot node : enabled) {
            if (!KNOWN_TYPES.contains(node.nodeType())) {
                return "INGEST_NODE_TYPE_UNKNOWN";
            }
            int position = REQUIRED_ORDER.indexOf(node.nodeType());
            if (position < cursor) {
                return "INGEST_NODE_ORDER_INVALID";
            }
            cursor = position;
            hasParser |= "parser".equals(node.nodeType());
            hasChunker |= "chunker".equals(node.nodeType());
            hasIndexer |= "indexer".equals(node.nodeType());
        }
        if (!hasParser || !hasChunker || !hasIndexer) {
            return "INGEST_NODE_REQUIRED_MISSING";
        }
        return null;
    }

    /** 读取节点 settings 的 JsonNode（调用方判空/白名单）。 */
    public JsonNode settings(NodeSnapshot node) {
        return parse(node.settingsJson());
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("invalid pipeline json", e);
        }
    }

    private static String normalizeType(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toLowerCase().replace('-', '_');
    }

    private static String hashOf(List<NodeSnapshot> chain) {
        StringBuilder canonical = new StringBuilder();
        for (NodeSnapshot node : chain) {
            canonical.append(node.nodeId()).append('|')
                    .append(node.nodeType()).append('|')
                    .append(node.enabled() ? "1" : "0").append('|')
                    .append(node.nextNodeId() == null ? "" : node.nextNodeId()).append('|')
                    .append(node.settingsJson() == null ? "" : node.settingsJson()).append('|')
                    .append(node.conditionJson() == null ? "" : node.conditionJson()).append('\n');
        }
        return CanonicalJson.sha256(canonical.toString());
    }
}
