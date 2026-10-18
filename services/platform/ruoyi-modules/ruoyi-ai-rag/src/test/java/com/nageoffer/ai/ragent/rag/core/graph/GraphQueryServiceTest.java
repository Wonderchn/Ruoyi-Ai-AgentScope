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

import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.AuthorizedResourceScope;
import com.nageoffer.ai.ragent.rag.controller.vo.GraphViewVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class GraphQueryServiceTest {

    private FakeGraphProjectionRepository repository;
    private GraphQueryService service;
    private ExecutionPrincipal principal;

    @BeforeEach
    void setUp() {
        AiResourceAuthorizationService authorization = mock(AiResourceAuthorizationService.class);
        repository = new FakeGraphProjectionRepository();
        GraphScopeResolver resolver = new GraphScopeResolver(repository, authorization);
        service = new GraphQueryService(repository, resolver);
        principal = new ExecutionPrincipal("T1", "2101", "platform:T1:2101", 1, 1,
                Set.of("kb.read"), "jti-1", "platform", 1, 9_999_999_999L);
        PrincipalContext.set(principal);
        when(authorization.resolveScope(any(), eq("kb.read"), any())).thenAnswer(call -> {
            Collection<String> requested = call.getArgument(2);
            Set<String> all = new LinkedHashSet<>(List.of("kb:kb", "kb:kb_hr"));
            Set<String> refs = requested == null || requested.isEmpty()
                    ? all : all.stream().filter(requested::contains).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            return AuthorizedResourceScope.granted(principal, "kb.read", refs, 1L);
        });
        repository.kbs.put("kb", new GraphProjectionRepository.KbRow("kb", "KB", "kb"));
        repository.kbs.put("kb_hr", new GraphProjectionRepository.KbRow("kb_hr", "KB HR", "kb_hr"));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    private static GraphProjectionRepository.EntityRow entity(String node, String name, String kb,
                                                              String doc, String description) {
        return new GraphProjectionRepository.EntityRow(node, name, name, "PERSON", description,
                doc, kb, "文档-" + doc, kb);
    }

    private static GraphProjectionRepository.RelationRow relation(String source, String target, String label,
                                                                  String kb, String doc, String path) {
        return new GraphProjectionRepository.RelationRow(source, target, source, target, label,
                "关系-" + label, path, doc, kb, "文档-" + doc, kb);
    }

    @Test
    @DisplayName("R1 同名/前缀库隔离：kb 查询绝不带入 kb_hr 行")
    void prefixOverlappingKbIsNotMixed() {
        repository.entities.add(entity("n1", "Alice", "kb", "d1", "本库"));
        repository.entities.add(entity("h1", "Alice", "kb_hr", "d2", "别库"));

        GraphViewVO view = service.getGraph(null, "kb", null, null, 2, 200);

        assertEquals(List.of("n1"), view.getNodes().stream().map(GraphViewVO.Node::getId).toList());
        assertTrue(repository.calls.stream().noneMatch(call -> call.startsWith("entities:T1:kb_hr")));
        assertEquals("projection", view.getReadSource());
    }

    @Test
    @DisplayName("R4 实体精确匹配后按 depth 做邻域 BFS")
    void entityFilterUsesBoundedBfs() {
        repository.entities.add(entity("n1", "Alice", "kb", "d1", ""));
        repository.entities.add(entity("n2", "Bob", "kb", "d1", ""));
        repository.entities.add(entity("n3", "Carol", "kb", "d1", ""));
        repository.entities.add(entity("n4", "Dave", "kb", "d1", ""));
        repository.relations.add(relation("n1", "n2", "knows", "kb", "d1", "kb_d1"));
        repository.relations.add(relation("n2", "n3", "knows", "kb", "d1", "kb_d1"));
        repository.relations.add(relation("n3", "n4", "knows", "kb", "d1", "kb_d1"));

        GraphViewVO depth1 = service.getGraph("Alice", "kb", null, null, 1, 200);
        assertEquals(Set.of("n1", "n2"),
                depth1.getNodes().stream().map(GraphViewVO.Node::getId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, depth1.getEdges().size());

        GraphViewVO depth2 = service.getGraph("Alice", "kb", null, null, 2, 200);
        assertEquals(Set.of("n1", "n2", "n3"),
                depth2.getNodes().stream().map(GraphViewVO.Node::getId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, depth2.getEdges().size());
    }

    @Test
    @DisplayName("精确未命中时回退 ILIKE，关键字大小写不敏感")
    void entityFilterFallsBackToCaseInsensitiveContains() {
        repository.entities.add(entity("n1", "Alice", "kb", "d1", ""));
        repository.entities.add(entity("n2", "Bob", "kb", "d1", ""));

        GraphViewVO view = service.getGraph("ali", "kb", null, null, 2, 200);

        assertEquals(List.of("n1"), view.getNodes().stream().map(GraphViewVO.Node::getId).toList());
    }

    @Test
    @DisplayName("limit 截断：按度数降序、id 升序，置 truncated；边只留两端可见")
    void limitTruncatesDeterministically() {
        repository.entities.add(entity("n1", "A", "kb", "d1", ""));
        repository.entities.add(entity("n2", "B", "kb", "d1", ""));
        repository.entities.add(entity("n3", "C", "kb", "d1", ""));
        repository.relations.add(relation("n1", "n2", "r", "kb", "d1", "kb_d1"));
        repository.relations.add(relation("n2", "n3", "r", "kb", "d1", "kb_d1"));

        GraphViewVO view = service.getGraph(null, "kb", null, null, 2, 2);

        assertTrue(view.isTruncated());
        assertEquals(List.of("n2", "n1"), view.getNodes().stream().map(GraphViewVO.Node::getId).toList());
        assertEquals(1, view.getEdges().size());
        assertEquals(2, view.getNodes().get(0).getDegree());
    }

    @Test
    @DisplayName("R9 sources 聚合：同实体跨文档合并来源、描述去重换行")
    void sourcesAggregateAcrossDocuments() {
        repository.entities.add(entity("n1", "Alice", "kb", "d1", "第一段"));
        repository.entities.add(entity("n1", "Alice", "kb", "d2", "第二段"));
        repository.entities.add(entity("n2", "Bob", "kb", "d1", ""));
        repository.relations.add(relation("n1", "n2", "knows", "kb", "d1", "kb_d1"));
        repository.relations.add(relation("n1", "n2", "knows", "kb", "d2", ""));

        GraphViewVO view = service.getGraph(null, "kb", null, null, 2, 200);

        GraphViewVO.Node alice = view.getNodes().stream()
                .filter(node -> "n1".equals(node.getId())).findFirst().orElseThrow();
        assertEquals(2, alice.getSources().size());
        assertTrue(alice.getDescription().contains("第一段") && alice.getDescription().contains("第二段"));
        GraphViewVO.Edge edge = view.getEdges().get(0);
        assertEquals(2, edge.getSources().size());
        assertFalse(edge.isSourcesDerived(), "任一关系行带自身 file_path 时不算派生");
    }

    @Test
    @DisplayName("R9 边无 file_path 时继承两端来源并标记 sourcesDerived")
    void edgeWithoutPathInheritsEndpointSources() {
        repository.entities.add(entity("n1", "Alice", "kb", "d1", ""));
        repository.entities.add(entity("n2", "Bob", "kb", "d2", ""));
        repository.relations.add(relation("n1", "n2", "knows", "kb", "d1", ""));

        GraphViewVO view = service.getGraph(null, "kb", null, null, 2, 200);

        GraphViewVO.Edge edge = view.getEdges().get(0);
        assertTrue(edge.isSourcesDerived());
        assertEquals(Set.of("d1", "d2"),
                edge.getSources().stream().map(GraphViewVO.Source::getDocId).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    @DisplayName("R5 labels：limit clamp 1..200，空关键字取热门")
    void labelsClampAndDelegateToProjection() {
        repository.entities.add(entity("n1", "Alice", "kb", "d1", ""));

        assertEquals(List.of("Alice"), service.searchEntities("ali", 50));
        service.searchEntities(null, 999);
        assertTrue(repository.calls.stream().anyMatch(call ->
                call.startsWith("labels:T1:") && call.endsWith(":null:null:200")));
        service.searchEntities(null, 0);
        assertTrue(repository.calls.stream().anyMatch(call ->
                call.startsWith("labels:T1:") && call.endsWith(":null:null:50")));
    }

    @Test
    @DisplayName("R6 读路径不依赖 LightRagClient（零外呼的结构证据）")
    void readPathHasNoLightRagDependency() {
        for (Field field : GraphQueryService.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().contains("LightRag"),
                    "读路径不得持有 LightRag 客户端: " + field.getName());
        }
    }

    @Test
    @DisplayName("droppedUnattributed 由仓储诊断计数进入 VO")
    void unattributedRowsSurfaceInView() {
        repository.unattributed = 3;

        GraphViewVO view = service.getGraph(null, "kb", null, null, 2, 200);

        assertEquals(3, view.getDroppedUnattributed());
    }
}
