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

package com.nageoffer.ai.ragent.rag.core.graph;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.controller.vo.GraphViewVO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识图谱读路径（F08:0/1/3）。
 *
 * <p><b>只读主库投影</b>：本类不持有 {@link LightRagClient}，因此 read 路径零外呼；
 * 投影由 {@link GraphProjectionService} 显式重建，外部后端不可用时旧投影照常可读。
 *
 * <p><b>范围由 {@link GraphScopeResolver} 解析</b>：tenant 来自执行主体，KB 授权集合来自
 * 资源授权服务；SQL 只按 {@code tenant_id + kb_id IN + doc_id} 过滤，绝不再用
 * {@code collection + "_"} 子串判归属。
 *
 * <p>聚合口径：node_id 为节点键、{@code (source,target,label)} 为边键；sources 按
 * {@code (kb_id,doc_id)} 去重，docName/collectionName 由主库 join 给出。
 */
public class GraphQueryService {

    private static final int DEFAULT_DEPTH = 2;
    private static final int MAX_DEPTH = 3;
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;
    private static final int DEFAULT_LABELS = 50;
    private static final int MAX_LABELS = 200;

    private final GraphProjectionRepository repository;
    private final GraphScopeResolver scopeResolver;

    public GraphQueryService(GraphProjectionRepository repository, GraphScopeResolver scopeResolver) {
        this.repository = repository;
        this.scopeResolver = scopeResolver;
    }

    /**
     * 查询图谱子图。
     *
     * @param entity     起点实体名（精确优先，未命中回退大小写不敏感包含），空则全图
     * @param kbId       知识库 id；与 collection/doc 互斥，doc 最细
     * @param collection 知识库 collection 名（经主库唯一解析为 kbId）
     * @param doc        文档 id（追加 doc 级 grant）
     * @param depth      BFS 深度，clamp 1..3（非正取 2）
     * @param limit      节点上限，clamp 1..1000（非正取 200）
     */
    public GraphViewVO getGraph(String entity, String kbId, String collection, String doc,
                                int depth, int limit) {
        GraphScopeResolver.GraphScope scope = scopeResolver.resolve(kbId, collection, doc);
        if (scope.isEmpty()) {
            return GraphViewVO.builder().nodes(List.of()).edges(List.of())
                    .truncated(false).droppedUnattributed(0).readSource("projection").build();
        }
        List<GraphProjectionRepository.EntityRow> entityRows =
                repository.findEntities(scope.tenantId(), scope.kbIds(), scope.docId());
        List<GraphProjectionRepository.RelationRow> relationRows =
                repository.findRelations(scope.tenantId(), scope.kbIds(), scope.docId());

        Map<String, NodeAggregate> nodes = aggregateNodes(entityRows);
        Map<String, EdgeAggregate> edges = aggregateEdges(relationRows, nodes);

        Set<String> selected = selectNodes(entity, nodes, edges, depth);
        List<GraphViewVO.Node> ordered = nodes.values().stream()
                .filter(node -> selected.contains(node.id))
                .map(node -> GraphViewVO.Node.builder()
                        .id(node.id)
                        .name(node.displayName())
                        .type(node.type == null ? "" : node.type)
                        .description(String.join("\n", node.descriptions))
                        .degree(node.degree)
                        .sources(new ArrayList<>(node.sources.values()))
                        .build())
                .sorted(Comparator.comparingInt(GraphViewVO.Node::getDegree).reversed()
                        .thenComparing(GraphViewVO.Node::getId))
                .toList();

        int maxNodes = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        boolean truncated = ordered.size() > maxNodes;
        List<GraphViewVO.Node> keptNodes = truncated ? ordered.subList(0, maxNodes) : ordered;
        Set<String> kept = new LinkedHashSet<>();
        for (GraphViewVO.Node node : keptNodes) {
            kept.add(node.getId());
        }

        List<GraphViewVO.Edge> keptEdges = edges.values().stream()
                .filter(edge -> kept.contains(edge.source) && kept.contains(edge.target))
                .map(edge -> GraphViewVO.Edge.builder()
                        .id(edge.id())
                        .source(edge.source)
                        .target(edge.target)
                        .label(edge.label == null ? "" : edge.label)
                        .description(String.join("\n", edge.descriptions))
                        .sourcesDerived(edge.derived)
                        .sources(new ArrayList<>(edge.sources.values()))
                        .build())
                .sorted(Comparator.comparing(GraphViewVO.Edge::getSource)
                        .thenComparing(GraphViewVO.Edge::getTarget)
                        .thenComparing(GraphViewVO.Edge::getLabel))
                .toList();

        int dropped = repository.countUnattributed(scope.tenantId(), scope.kbIds(), scope.docId());
        return GraphViewVO.builder().nodes(List.copyOf(keptNodes)).edges(keptEdges)
                .truncated(truncated).droppedUnattributed(dropped).readSource("projection").build();
    }

    /**
     * 检索实体标签，供可视化搜索框；keyword 为空取出现次数最多的标签。
     */
    public List<String> searchEntities(String keyword, int limit) {
        GraphScopeResolver.GraphScope scope = scopeResolver.resolve(null, null, null);
        if (scope.isEmpty()) {
            return List.of();
        }
        int max = limit <= 0 ? DEFAULT_LABELS : Math.min(limit, MAX_LABELS);
        return repository.findLabels(scope.tenantId(), scope.kbIds(), null,
                StrUtil.isBlank(keyword) ? null : keyword.trim(), max);
    }

    // ------------------------------------------------------------------ 聚合

    private static Map<String, NodeAggregate> aggregateNodes(
            List<GraphProjectionRepository.EntityRow> rows) {
        Map<String, NodeAggregate> nodes = new LinkedHashMap<>();
        for (GraphProjectionRepository.EntityRow row : rows) {
            if (StrUtil.isBlank(row.nodeId())) {
                continue;
            }
            NodeAggregate node = nodes.computeIfAbsent(row.nodeId(), NodeAggregate::new);
            if (StrUtil.isBlank(node.entityKey) && StrUtil.isNotBlank(row.entityKey())) {
                node.entityKey = row.entityKey();
            }
            if (StrUtil.isBlank(node.name) && StrUtil.isNotBlank(row.name())) {
                node.name = row.name();
            }
            if (StrUtil.isBlank(node.type) && StrUtil.isNotBlank(row.entityType())) {
                node.type = row.entityType();
            }
            String description = StrUtil.trimToEmpty(row.description());
            if (StrUtil.isNotBlank(description)) {
                node.descriptions.add(description);
            }
            node.sources.putIfAbsent(sourceKey(row.kbId(), row.docId()),
                    source(row.kbId(), row.docId(), row.docName(), row.collectionName()));
        }
        return nodes;
    }

    private static Map<String, EdgeAggregate> aggregateEdges(
            List<GraphProjectionRepository.RelationRow> rows,
            Map<String, NodeAggregate> nodes) {
        Map<String, EdgeAggregate> edges = new LinkedHashMap<>();
        for (GraphProjectionRepository.RelationRow row : rows) {
            if (StrUtil.isBlank(row.sourceNodeId()) || StrUtil.isBlank(row.targetNodeId())) {
                continue;
            }
            // 端点必须来自实体投影行；否则该边是悬空/无主数据，读侧丢弃。
            if (!nodes.containsKey(row.sourceNodeId()) || !nodes.containsKey(row.targetNodeId())) {
                continue;
            }
            String label = StrUtil.nullToEmpty(row.relationLabel());
            String key = row.sourceNodeId() + '\u0000' + row.targetNodeId() + '\u0000' + label;
            EdgeAggregate edge = edges.computeIfAbsent(key,
                    ignored -> new EdgeAggregate(row.sourceNodeId(), row.targetNodeId(), label));
            String description = StrUtil.trimToEmpty(row.description());
            if (StrUtil.isNotBlank(description)) {
                edge.descriptions.add(description);
            }
            if (StrUtil.isBlank(row.sourceFilePath())) {
                // 派生来源：边自身无 file_path，继承两端节点来源
                edge.sources.putIfAbsent(sourceKey(row.kbId(), row.docId()),
                        source(row.kbId(), row.docId(), row.docName(), row.collectionName()));
                inheritEndpointSources(edge, row.sourceNodeId(), nodes);
                inheritEndpointSources(edge, row.targetNodeId(), nodes);
            } else {
                edge.derived = false;
                edge.sources.putIfAbsent(sourceKey(row.kbId(), row.docId()),
                        source(row.kbId(), row.docId(), row.docName(), row.collectionName()));
            }
            nodes.get(row.sourceNodeId()).degree++;
            nodes.get(row.targetNodeId()).degree++;
        }
        return edges;
    }

    private static void inheritEndpointSources(EdgeAggregate edge, String nodeId,
                                               Map<String, NodeAggregate> nodes) {
        NodeAggregate node = nodes.get(nodeId);
        if (node != null) {
            node.sources.forEach(edge.sources::putIfAbsent);
        }
    }

    private static GraphViewVO.Source source(String kbId, String docId, String docName, String collectionName) {
        return GraphViewVO.Source.builder().kbId(kbId).docId(docId).docName(docName)
                .collectionName(collectionName).build();
    }

    private static String sourceKey(String kbId, String docId) {
        return kbId + "\u0000" + docId;
    }

    // ------------------------------------------------------------------ 节点选择

    private static Set<String> selectNodes(String entity, Map<String, NodeAggregate> nodes,
                                           Map<String, EdgeAggregate> edges, int depth) {
        String needle = StrUtil.trimToEmpty(entity);
        if (StrUtil.isBlank(needle)) {
            return new LinkedHashSet<>(nodes.keySet());
        }
        Set<String> starts = new LinkedHashSet<>();
        for (NodeAggregate node : nodes.values()) {
            if (needle.equals(node.entityKey) || needle.equals(node.name)) {
                starts.add(node.id);
            }
        }
        if (starts.isEmpty()) {
            String lowered = needle.toLowerCase();
            for (NodeAggregate node : nodes.values()) {
                if (node.displayName().toLowerCase().contains(lowered)) {
                    starts.add(node.id);
                }
            }
        }
        if (starts.isEmpty()) {
            return Set.of();
        }
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (EdgeAggregate edge : edges.values()) {
            adjacency.computeIfAbsent(edge.source, ignored -> new LinkedHashSet<>()).add(edge.target);
            adjacency.computeIfAbsent(edge.target, ignored -> new LinkedHashSet<>()).add(edge.source);
        }
        int maxDepth = depth <= 0 ? DEFAULT_DEPTH : Math.min(depth, MAX_DEPTH);
        Set<String> visited = new LinkedHashSet<>(starts);
        Set<String> frontier = new LinkedHashSet<>(starts);
        for (int step = 0; step < maxDepth && !frontier.isEmpty(); step++) {
            Set<String> next = new LinkedHashSet<>();
            for (String nodeId : frontier) {
                for (String neighbour : adjacency.getOrDefault(nodeId, Set.of())) {
                    if (visited.add(neighbour)) {
                        next.add(neighbour);
                    }
                }
            }
            frontier = next;
        }
        return visited;
    }

    // ------------------------------------------------------------------ 内部记录

    private static final class NodeAggregate {
        private final String id;
        private String entityKey;
        private String name;
        private String type;
        private final Set<String> descriptions = new LinkedHashSet<>();
        private final Map<String, GraphViewVO.Source> sources = new LinkedHashMap<>();
        private int degree;

        private NodeAggregate(String id) {
            this.id = id;
        }

        private String displayName() {
            if (StrUtil.isNotBlank(name)) {
                return name;
            }
            return StrUtil.isNotBlank(entityKey) ? entityKey : id;
        }
    }

    private static final class EdgeAggregate {
        private final String source;
        private final String target;
        private final String label;
        private final Set<String> descriptions = new LinkedHashSet<>();
        private final Map<String, GraphViewVO.Source> sources = new LinkedHashMap<>();
        private boolean derived = true;

        private EdgeAggregate(String source, String target, String label) {
            this.source = source;
            this.target = target;
            this.label = label;
        }

        private String id() {
            return source + "->" + target + (label.isEmpty() ? "" : "[" + label + "]");
        }
    }
}
