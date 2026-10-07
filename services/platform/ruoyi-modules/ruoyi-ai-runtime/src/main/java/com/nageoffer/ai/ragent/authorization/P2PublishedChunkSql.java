package com.nageoffer.ai.ragent.authorization;

/** The same published document data used by P2 ingestion and run retrieval. */
public final class P2PublishedChunkSql {
    private P2PublishedChunkSql() {
    }

    // Every join carries tenant identity; callers additionally bind a tenant and
    // their server-authorized KB/document/chunk sets before executing this view.
    public static final String CURRENT_ROWS = """
            SELECT c.tenant_id, c.doc_id, c.kb_id, c.version_id,
                   c.version_id || ':' || c.chunk_key AS id,
                   c.content, c.embedding, kb.collection_name
            FROM ai_document_chunk c
            JOIN ai_document d ON d.tenant_id=c.tenant_id AND d.doc_id=c.doc_id AND d.kb_id=c.kb_id
            JOIN ai_document_version pv ON pv.tenant_id=c.tenant_id AND pv.version_id=c.version_id AND pv.doc_id=c.doc_id
            JOIN platform.ai_knowledge_base kb ON kb.tenant_id=d.tenant_id AND kb.id=d.kb_id
            JOIN ai_resource dr ON dr.tenant_id=d.tenant_id AND dr.resource_type='DOCUMENT' AND dr.resource_id=d.doc_id
            JOIN ai_resource kr ON kr.tenant_id=d.tenant_id AND kr.resource_type='KB' AND kr.resource_id=d.kb_id
            WHERE c.state='PUBLISHED' AND pv.state='PUBLISHED' AND c.version_id=d.published_version_id
              AND d.tombstoned_at IS NULL AND kb.deleted = 0 AND c.embedding IS NOT NULL
              AND dr.status='ACTIVE' AND kr.status='ACTIVE' AND dr.parent_type='KB' AND dr.parent_id=d.kb_id
            """;

    public static String projection() {
        return "SELECT id, collection_name FROM (" + CURRENT_ROWS + ") published "
                + "WHERE tenant_id=:tenant AND kb_id IN (:kbs) AND doc_id IN (:docs) LIMIT 10001";
    }

    public static String retrieval(String collectionSlots, String kbSlots, String docSlots, String chunkSlots) {
        return "SELECT id, content, collection_name, 1 - (embedding <=> ?::vector) AS score FROM ("
                + CURRENT_ROWS + ") published WHERE tenant_id = ? AND collection_name IN (" + collectionSlots
                + ") AND kb_id IN (" + kbSlots + ") AND doc_id IN (" + docSlots + ") AND id IN (" + chunkSlots
                + ") ORDER BY embedding <=> ?::vector LIMIT ?";
    }
}
