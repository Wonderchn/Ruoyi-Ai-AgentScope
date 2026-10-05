package org.ruoyi.ai.api.runtime;

import java.util.List;

/** Neutral Worker capability contract; implementations belong to the RAG/infra modules. */
public interface ChunkingPort {
    public static final String STRATEGY = "p2-md-v1";
    public static final String PAGE_STRATEGY = "p2-pages-v2";
    public static final int DEFAULT_MAX_CHARS = 900;

    public record ChunkDraft(int index, String chunkKey, String content, String contentHash, int charCount,
                             Integer pageFrom, Integer pageTo) {
    }
    List<ChunkDraft> chunk(String docId, String versionId, String markdown, int maxChars);
    List<ChunkDraft> chunkArtifact(String docId, String versionId, String artifact, int maxChars);
}
