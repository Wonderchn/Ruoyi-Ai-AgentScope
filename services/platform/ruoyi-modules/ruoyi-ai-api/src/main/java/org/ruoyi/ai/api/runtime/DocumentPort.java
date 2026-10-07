package org.ruoyi.ai.api.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Neutral Worker capability contract; implementations belong to the RAG/infra modules. */
public interface DocumentPort {
    public record DocumentRow(String docId, String kbId, String name, String memberId,
                              String publishedVersionId, Instant tombstonedAt, Instant createdAt) {
        public boolean tombstoned() {
            return tombstonedAt != null;
        }
    }

    public record UploadRow(String uploadId, String docId, String kbId, String memberId, String filename,
                            String mimeType, long sizeBytes, String sha256, String objectKey, String state,
                            Instant createdAt) {
    }

    public record VersionRow(String versionId, String docId, String uploadId, String runId, String state,
                             String parseRefJson, int chunkCount, String embeddingModel, Integer embeddingDimension,
                             Instant publishedAt) {
    }

    public record RetrievedChunk(String docId, String versionId, String chunkKey, int chunkIndex, String content,
                                 Integer pageFrom, Integer pageTo, double score) {
    }

    boolean matchesEmbeddingModel(String tenantId, java.util.Collection<String> kbIds, String model);
    void insertDocument(String tenantId, String docId, String kbId, String name, String memberId);
    Optional<DocumentRow> findDocument(String tenantId, String docId);
    List<DocumentRow> listDocuments(String tenantId, String kbId);
    int tombstoneDocument(String tenantId, String docId);
    int tombstoneVersions(String tenantId, String docId);
    int deleteChunksOfVersion(String tenantId, String versionId);
    void insertUpload(String tenantId, String uploadId, String docId, String kbId, String memberId,
                             String filename, String mimeType, long sizeBytes, String sha256, String objectKey);
    Optional<UploadRow> findUpload(String tenantId, String uploadId);
    void markUploadState(String tenantId, String uploadId, String state);
    void insertVersion(String tenantId, String versionId, String docId, String uploadId, String runId);
    Optional<VersionRow> findVersion(String tenantId, String versionId);
    Optional<VersionRow> findVersionByUpload(String tenantId, String docId, String uploadId);
    boolean updateVersionStateFenced(String tenantId, String versionId, String runId, long fence,
                                            String state, String parseRefJson, Integer chunkCount,
                                            String embeddingModel, Integer embeddingDimension);
    boolean publishVersionFenced(String tenantId, String docId, String versionId, String runId, long fence);
    long countChunks(String tenantId, String versionId, String state);
    void insertStagingChunk(String tenantId, String versionId, String chunkKey, int chunkIndex,
                                   String docId, String kbId, String content, String contentHash, int charCount,
                                   Integer pageFrom, Integer pageTo, List<Float> embedding, String embeddingModel);
    List<RetrievedChunk> searchPublished(String tenantId, List<String> kbIds, List<Float> queryVector,
                                                int topK, double minScore);
}
