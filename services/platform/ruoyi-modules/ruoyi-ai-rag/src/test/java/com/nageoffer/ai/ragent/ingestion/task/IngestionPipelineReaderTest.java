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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F06:1/2-A Stage C：管线快照结构校验与节点行 id 派生的**纯函数**判据（无 DB、无 Mock）。
 *
 * <p>结构校验是受理期硬判据（契约 validate 调用），错误码必须稳定可归因。
 */
@Tag("dev")
class IngestionPipelineReaderTest {

    private static IngestionPipelineReader.PipelineSnapshot snapshot(IngestionPipelineReader.NodeSnapshot... nodes) {
        return new IngestionPipelineReader.PipelineSnapshot("pipe-1", "p", List.of(nodes), "h");
    }

    private static IngestionPipelineReader.NodeSnapshot node(String id, String type, boolean enabled, String next) {
        return new IngestionPipelineReader.NodeSnapshot(id, type, enabled, next, null, null);
    }

    @Test
    void validChainIsAccepted() {
        IngestionPipelineReader reader = new IngestionPipelineReader(null, null);
        assertNull(reader.structureError(snapshot(
                node("n1", "parser", true, "n2"),
                node("n2", "chunker", true, "n3"),
                node("n3", "indexer", true, null))));
    }

    @Test
    void disabledNodeIsSkippedInEnabledChain() {
        IngestionPipelineReader reader = new IngestionPipelineReader(null, null);
        IngestionPipelineReader.PipelineSnapshot snap = snapshot(
                node("n1", "parser", true, "n2"),
                node("n2", "enhancer", false, "n3"),
                node("n3", "chunker", true, "n4"),
                node("n4", "indexer", true, null));
        assertEquals(3, snap.enabledChain().size());
        assertNull(reader.structureError(snap));
    }

    @Test
    void missingRequiredNodeIsRejected() {
        IngestionPipelineReader reader = new IngestionPipelineReader(null, null);
        assertEquals("INGEST_NODE_REQUIRED_MISSING", reader.structureError(snapshot(
                node("n1", "parser", true, "n2"),
                node("n2", "chunker", true, null))));
    }

    @Test
    void outOfOrderNodeIsRejected() {
        IngestionPipelineReader reader = new IngestionPipelineReader(null, null);
        assertEquals("INGEST_NODE_ORDER_INVALID", reader.structureError(snapshot(
                node("n1", "chunker", true, "n2"),
                node("n2", "parser", true, "n3"),
                node("n3", "indexer", true, null))));
    }

    @Test
    void unknownNodeTypeIsRejected() {
        IngestionPipelineReader reader = new IngestionPipelineReader(null, null);
        assertEquals("INGEST_NODE_TYPE_UNKNOWN", reader.structureError(snapshot(
                node("n1", "parser", true, "n2"),
                node("n2", "vectorize", true, "n3"),
                node("n3", "indexer", true, null))));
    }

    @Test
    void emptyEnabledChainIsRejected() {
        IngestionPipelineReader reader = new IngestionPipelineReader(null, null);
        assertEquals("INGEST_PIPELINE_EMPTY", reader.structureError(snapshot(
                node("n1", "parser", false, "n2"),
                node("n2", "indexer", false, null))));
    }

    @Test
    void nodeRowIdIsDeterministicAndBounded() {
        String first = IngestionTaskRepository.nodeRowId("r-" + "a".repeat(32), "n2");
        String second = IngestionTaskRepository.nodeRowId("r-" + "a".repeat(32), "n2");
        assertEquals(first, second, "node row id must be deterministic");
        assertEquals(20, first.length(), "node row id must fit VARCHAR(20)");
        assertTrue(first.startsWith("n"));
        assertTrue(!first.equals(IngestionTaskRepository.nodeRowId("r-" + "a".repeat(32), "n3")),
                "different nodes must have different row ids");
    }
}
