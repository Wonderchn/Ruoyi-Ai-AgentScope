package com.nageoffer.ai.ragent.ingest;

import com.nageoffer.ai.ragent.runtime.RunApiException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ai.api.runtime.ChunkingPort;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class D1ChunkingPortTest {
    @Test
    void parserArtifactThroughNeutralPortPreservesPhysicalPageNumbersAndStableKeys() {
        ChunkingPort port = new MarkdownChunker();
        String artifact = """
                {"is_full_document":true,"metadata":{"document":{"page_count":2}},"pages":[
                  {"page_idx":0,"blocks":[{"content":"first page"}]},
                  {"page_idx":1,"blocks":[{"content":"second page"}]}]}
                """;
        var chunks = port.chunkArtifact("doc1", "version1", artifact, 900);
        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).pageFrom());
        assertEquals(2, chunks.get(1).pageTo());
        assertEquals(chunks, port.chunkArtifact("doc1", "version1", artifact, 900));
        assertNotEquals(chunks.get(0).chunkKey(), chunks.get(1).chunkKey());
    }

    @Test
    void incompleteParserProvenanceCannotBecomePublishedPageCitations() {
        ChunkingPort port = new MarkdownChunker();
        assertThrows(RunApiException.class, () -> port.chunkArtifact("doc1", "version1",
                "{\"is_full_document\":false,\"pages\":[]}", 900));
    }
}
