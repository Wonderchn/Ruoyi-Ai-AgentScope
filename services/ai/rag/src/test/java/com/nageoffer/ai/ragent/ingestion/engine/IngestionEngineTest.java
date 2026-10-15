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

package com.nageoffer.ai.ragent.ingestion.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.node.IngestionNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;



class IngestionEngineTest {

    @Test
    void multipleStartNodesFailBeforeAnyNodeExecutes() {
        List<String> executedNodeIds = new ArrayList<>();
        IngestionEngine engine = engine(executedNodeIds);
        PipelineDefinition pipeline = PipelineDefinition.builder()
                .nodes(List.of(
                        node("z-root", "z-leaf"),
                        node("a-leaf", null),
                        node("z-leaf", null),
                        node("a-root", "a-leaf")))
                .build();

        ClientException exception = assertThrows(
                ClientException.class,
                () -> engine.execute(pipeline, IngestionContext.builder().build()));

        assertEquals("流水线存在多个起始节点: a-root, z-root", exception.getMessage());
        assertEquals(List.of(), executedNodeIds);
    }

    @Test
    void nullStartNodeIdAlongsideNormalRootFailsWithoutNpe() {
        List<String> executedNodeIds = new ArrayList<>();
        IngestionEngine engine = engine(executedNodeIds);
        PipelineDefinition pipeline = PipelineDefinition.builder()
                .nodes(List.of(
                        node("a-root", null),
                        node(null, null)))
                .build();

        ClientException exception = assertThrows(
                ClientException.class,
                () -> engine.execute(pipeline, IngestionContext.builder().build()));

        assertEquals("流水线存在多个起始节点: null, a-root", exception.getMessage());
        assertEquals(List.of(), executedNodeIds);
    }

    @Test
    void blankStartNodeIdIsRejectedBeforeExecution() {
        List<String> executedNodeIds = new ArrayList<>();
        IngestionEngine engine = engine(executedNodeIds);
        PipelineDefinition pipeline = PipelineDefinition.builder()
                .nodes(List.of(node(" ", null)))
                .build();

        ClientException exception = assertThrows(
                ClientException.class,
                () -> engine.execute(pipeline, IngestionContext.builder().build()));

        assertEquals("流水线未找到起始节点", exception.getMessage());
        assertEquals(List.of(), executedNodeIds);
    }

    @Test
    void singleChainStillExecutesEveryNodeInOrder() {
        List<String> executedNodeIds = new ArrayList<>();
        IngestionEngine engine = engine(executedNodeIds);
        PipelineDefinition pipeline = PipelineDefinition.builder()
                .nodes(List.of(
                        node("middle", "leaf"),
                        node("leaf", null),
                        node("root", "middle")))
                .build();
        IngestionContext context = IngestionContext.builder().build();

        engine.execute(pipeline, context);

        assertEquals(List.of("root", "middle", "leaf"), executedNodeIds);
        assertEquals(IngestionStatus.COMPLETED, context.getStatus());
    }

    private IngestionEngine engine(List<String> executedNodeIds) {
        IngestionNode node = new IngestionNode() {
            @Override
            public String getNodeType() {
                return "test";
            }

            @Override
            public NodeResult execute(IngestionContext context, NodeConfig config) {
                executedNodeIds.add(config.getNodeId());
                return NodeResult.ok();
            }
        };
        return new IngestionEngine(
                List.of(node),
                new ConditionEvaluator(new ObjectMapper()),
                new NodeOutputExtractor());
    }

    @Test
    void disabledNodeSkipsWithoutEvaluatingConditionAndContinuesTheChain() throws Exception {
        List<String> executed = new ArrayList<>();
        NodeConfig disabled = new ObjectMapper().readerFor(NodeConfig.class)
                .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue("{\"nodeId\":\"disabled\",\"nodeType\":\"test\",\"enabled\":false,\"condition\":false,\"nextNodeId\":\"tail\"}");
        IngestionContext context = IngestionContext.builder().build();
        engine(executed).execute(PipelineDefinition.builder()
                .nodes(List.of(disabled, node("tail", null))).build(), context);
        assertEquals(List.of("tail"), executed);
        assertEquals(IngestionStatus.COMPLETED, context.getStatus());
        assertEquals("Skipped: 节点已禁用", context.getLogs().get(0).getMessage());
        assertEquals(0, context.getLogs().get(0).getDurationMs());
    }

    @Test
    void conditionFalseSkipsAndContinuesToEnabledSuccessor() throws Exception {
        List<String> executed = new ArrayList<>();
        NodeConfig guarded = node("guarded", "tail");
        guarded.setCondition(new ObjectMapper().readTree("false"));
        IngestionContext context = IngestionContext.builder().build();
        engine(executed).execute(PipelineDefinition.builder()
                .nodes(List.of(guarded, node("tail", null))).build(), context);
        assertEquals(List.of("tail"), executed);
        assertEquals("Skipped: 条件未满足", context.getLogs().get(0).getMessage());
    }

    @Test
    void omittedAndNullEnabledRemainCompatibleAndAllDisabledCompletes() {
        List<String> executed = new ArrayList<>();
        NodeConfig root = node("root", "middle");
        NodeConfig middle = node("middle", "tail");
        middle.setEnabled(null);
        NodeConfig tail = node("tail", null);
        tail.setEnabled(false);
        var pipeline = PipelineDefinition.builder().nodes(List.of(root, middle, tail)).build();
        engine(executed).execute(pipeline, IngestionContext.builder().build());
        assertEquals(Boolean.TRUE, root.getEnabled());
        assertEquals(List.of("root", "middle"), executed);
        root.setEnabled(false);
        middle.setEnabled(false);
        executed.clear();
        IngestionContext context = IngestionContext.builder().build();
        engine(executed).execute(pipeline, context);
        assertEquals(List.of(), executed);
        assertEquals(3, context.getLogs().size());
        assertEquals(IngestionStatus.COMPLETED, context.getStatus());
    }

    @Test
    void disabledNodeCannotBypassUnknownTypeOrTopologyValidation() {
        var unknown = node("unknown", null);
        unknown.setNodeType("unregistered");
        unknown.setEnabled(false);
        var context = IngestionContext.builder().build();
        engine(new ArrayList<>()).execute(PipelineDefinition.builder().nodes(List.of(unknown)).build(), context);
        assertEquals(IngestionStatus.FAILED, context.getStatus());
        unknown.setNextNodeId("missing");
        assertThrows(ClientException.class, () -> engine(new ArrayList<>()).execute(
                PipelineDefinition.builder().nodes(List.of(unknown)).build(), IngestionContext.builder().build()));
    }

    private NodeConfig node(String nodeId, String nextNodeId) {
        return NodeConfig.builder()
                .nodeId(nodeId)
                .nodeType("test")
                .nextNodeId(nextNodeId)
                .build();
    }
}
