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

package com.nageoffer.ai.ragent.ingestion.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineNodeRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionPipelineDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionPipelineNodeDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineNodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionPipelineServiceImplTest {

    @Test
    void enabledSurvivesWriteReadAndDefinitionAndNullDefaultsToTrue() {
        var pipeline = IngestionPipelineDO.builder().id("p1").name("enabled").build();
        when(pipelineMapper.selectById("p1")).thenReturn(pipeline);
        var disabled = IngestionPipelineNodeDO.builder().nodeId("disabled").nodeType("fetcher").enabled(false).build();
        var legacy = IngestionPipelineNodeDO.builder().nodeId("legacy").nodeType("parser").enabled(null).build();
        when(nodeMapper.selectList(any())).thenReturn(List.of(disabled, legacy));
        org.assertj.core.api.Assertions.assertThat(service.get("p1").getNodes()).extracting("enabled").containsExactly(false, true);
        org.assertj.core.api.Assertions.assertThat(service.getDefinition("p1").getNodes()).extracting("enabled").containsExactly(false, true);
        var off = new IngestionPipelineNodeRequest();
        off.setNodeId("disabled"); off.setNodeType("fetcher"); off.setEnabled(false);
        var omitted = new IngestionPipelineNodeRequest();
        omitted.setNodeId("legacy"); omitted.setNodeType("parser"); omitted.setEnabled(null);
        var update = new IngestionPipelineUpdateRequest();
        update.setNodes(List.of(off, omitted));
        service.update("p1", update);
        var saved = org.mockito.ArgumentCaptor.forClass(IngestionPipelineNodeDO.class);
        org.mockito.Mockito.verify(nodeMapper, org.mockito.Mockito.times(2)).insert(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getAllValues()).extracting("enabled").containsExactly(false, true);
    }

    @Mock
    private IngestionPipelineMapper pipelineMapper;

    @Mock
    private IngestionPipelineNodeMapper nodeMapper;

    @Mock
    private BizChangeLogContext bizChangeLogContext;

    private IngestionPipelineServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IngestionPipelineServiceImpl(
                pipelineMapper,
                nodeMapper,
                new ObjectMapper(),
                bizChangeLogContext
        );
    }

    @Test
    void updatePhysicallyDeletesExistingNodesBeforeReinsertingThem() {
        String pipelineId = "pipeline-1";
        IngestionPipelineDO pipeline = IngestionPipelineDO.builder()
                .id(pipelineId)
                .name("default")
                .build();
        when(pipelineMapper.selectById(pipelineId)).thenReturn(pipeline);
        when(nodeMapper.selectList(any())).thenReturn(List.of());

        IngestionPipelineNodeRequest node = new IngestionPipelineNodeRequest();
        node.setNodeId("fetcher-1");
        node.setNodeType("fetcher");
        IngestionPipelineUpdateRequest request = new IngestionPipelineUpdateRequest();
        request.setNodes(List.of(node));

        service.update(pipelineId, request);

        InOrder order = inOrder(nodeMapper);
        order.verify(nodeMapper).physicalDeleteByPipelineId(pipelineId);
        order.verify(nodeMapper).insert(argThat((IngestionPipelineNodeDO inserted) ->
                pipelineId.equals(inserted.getPipelineId())
                        && "fetcher-1".equals(inserted.getNodeId())
        ));
    }
}
