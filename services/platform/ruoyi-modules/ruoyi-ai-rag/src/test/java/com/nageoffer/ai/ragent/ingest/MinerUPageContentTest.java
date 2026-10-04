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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class MinerUPageContentTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String ACTUAL_SHAPE = """
            {"pages":[{"page_idx":0,"blocks":[{"type":"text","content":"P2-MARKER-XYZZY"}]},
            {"page_idx":1,"blocks":[{"type":"text","content":"Marker-Beta-2026"}]}],
            "metadata":{"document":{"page_count":2}},"is_full_document":true}
            """;

    @Test void actualTwoPageProvenanceKeepsPhysicalPagesAndStableIsolatedChunks() {
        var pages = MinerUPageContent.decode(ACTUAL_SHAPE, mapper);
        assertEquals(1, pages.get(0).number());
        assertEquals(2, pages.get(1).number());
        var chunker = new MarkdownChunker();
        var chunks = chunker.chunkPages("doc", "version", pages, 900);
        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).pageFrom());
        assertEquals(2, chunks.get(1).pageFrom());
        assertEquals(2, chunks.get(1).pageTo());
        assertFalse(chunks.get(0).content().contains("Beta"));
        assertEquals(chunks, chunker.chunkPages("doc", "version", pages, 900));
        assertNotEquals(chunks.get(0).chunkKey(), chunker.chunk("doc", "version", "P2-MARKER-XYZZY", 900).get(0).chunkKey());
    }

    @Test void malformedOrPartialProvenanceFailsInsteadOfGuessingPages() {
        for (String invalid : new String[]{
                ACTUAL_SHAPE.replace("\"page_idx\":1", "\"page_idx\":0"),
                ACTUAL_SHAPE.replace("\"page_idx\":0", "\"page_idx\":-1"),
                ACTUAL_SHAPE.replace("\"page_idx\":0", "\"page_idx\":0.5"),
                ACTUAL_SHAPE.replace("\"page_count\":2", "\"page_count\":3"),
                ACTUAL_SHAPE.replace("\"is_full_document\":true", "\"is_full_document\":false"),
                ACTUAL_SHAPE.replace("\"content\":\"P2-MARKER-XYZZY\"", "\"content\":{}"),
                "{}", "null", "not-json"}) {
            assertThrows(RunApiException.class, () -> MinerUPageContent.decode(invalid, mapper));
        }
    }

    @Test void visualCaptionsAndFootnotesRetainTheSamePage() {
        String artifact = """
                {"pages":[{"page_idx":0,"blocks":[{"type":"table","content":"table body",
                "captions":[{"content":"caption"}],"footnotes":[{"content":"footnote"}]}]}],
                "metadata":{"document":{"page_count":1}},"is_full_document":true}
                """;
        assertEquals("table body\n\ncaption\n\nfootnote", MinerUPageContent.decode(artifact, mapper).get(0).markdown());
    }
}
