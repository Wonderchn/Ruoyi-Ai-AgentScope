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

package com.nageoffer.ai.ragent.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分块：稳定逻辑键（同版本重跑不重复）、内容 hash、上限约束。 */
class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker();

    private static final String MARKDOWN = """
            # P2 MinerU Local Integration Probe

            Marker-Alpha-2026: tenant synthetic document one.

            Question anchor: what is the marker code for project phase two?

            Answer anchor: the marker code is P2-MARKER-XYZZY.

            ## Page two

            Marker-Beta-2026: second page anchor text.
            """;

    @Test
    void sameVersionProducesStableKeysAndHashes() {
        List<MarkdownChunker.ChunkDraft> first = chunker.chunk("doc-1", "ver-1", MARKDOWN, 900);
        List<MarkdownChunker.ChunkDraft> second = chunker.chunk("doc-1", "ver-1", MARKDOWN, 900);
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).chunkKey(), second.get(i).chunkKey());
            assertEquals(first.get(i).contentHash(), second.get(i).contentHash());
        }
    }

    @Test
    void newVersionProducesDifferentKeys() {
        List<MarkdownChunker.ChunkDraft> first = chunker.chunk("doc-1", "ver-1", MARKDOWN, 900);
        List<MarkdownChunker.ChunkDraft> second = chunker.chunk("doc-1", "ver-2", MARKDOWN, 900);
        assertNotEquals(first.get(0).chunkKey(), second.get(0).chunkKey());
    }

    @Test
    void chunkSizeRespectsLimitAndContentIsPreserved() {
        int limit = 60;
        List<MarkdownChunker.ChunkDraft> chunks = chunker.chunk("doc-1", "ver-1", MARKDOWN, limit);
        assertTrue(chunks.size() > 1);
        for (MarkdownChunker.ChunkDraft chunk : chunks) {
            assertTrue(chunk.charCount() <= limit, "chunk exceeds limit: " + chunk.charCount());
            assertTrue(chunk.content().length() <= limit);
        }
        StringBuilder joined = new StringBuilder();
        for (MarkdownChunker.ChunkDraft chunk : chunks) {
            joined.append(chunk.content());
        }
        assertTrue(joined.toString().contains("P2-MARKER-XYZZY"));
        assertTrue(joined.toString().contains("Marker-Beta-2026"));
    }

    @Test
    void emptyMarkdownProducesNoChunks() {
        assertTrue(chunker.chunk("doc-1", "ver-1", "", 900).isEmpty());
        assertTrue(chunker.chunk("doc-1", "ver-1", "   \n\n  ", 900).isEmpty());
    }
}
