#!/usr/bin/env python3
"""Compile production queries and emit rollback-only PostgreSQL behavior checks."""
import argparse
from pathlib import Path
import subprocess
import tempfile


def production_queries(repo):
    source = repo / ("services/platform/ruoyi-modules/ruoyi-ai-runtime/src/main/java/"
                     "com/nageoffer/ai/ragent/authorization/P2PublishedChunkSql.java")
    printer = """
import com.nageoffer.ai.ragent.authorization.P2PublishedChunkSql;
public class PrintP2Queries {
    public static void main(String[] args) {
        var encoder = java.util.Base64.getEncoder();
        for (String query : new String[]{P2PublishedChunkSql.projection(),
                P2PublishedChunkSql.retrieval("?, ?", "?, ?", "?", "?")}) {
            System.out.println(encoder.encodeToString(query.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
}
"""
    with tempfile.TemporaryDirectory(prefix="p2-query-check-") as directory:
        temp = Path(directory)
        (temp / "PrintP2Queries.java").write_text(printer, encoding="utf-8")
        subprocess.run(["javac", "--release", "17", "-d", str(temp), str(source),
                        str(temp / "PrintP2Queries.java")], check=True, capture_output=True, text=True)
        output = subprocess.run(["java", "-cp", str(temp), "PrintP2Queries"], check=True,
                                capture_output=True, text=True).stdout.splitlines()
    import base64
    return [base64.b64decode(line).decode("utf-8") for line in output]


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def bind_jdbc(query, parameters):
    assert query.count("?") == len(parameters), "production query binding drift"
    pieces = query.split("?")
    return pieces[0] + "".join(str(value) + tail for value, tail in zip(parameters, pieces[1:]))


def check(query, column, expected, label):
    wanted = "ARRAY[" + ",".join(literal(value) for value in sorted(expected)) + "]::text[]"
    return f"""
DO $check$
DECLARE observed text[];
BEGIN
    SELECT coalesce(array_agg({column} ORDER BY {column}), ARRAY[]::text[]) INTO observed
      FROM ({query}) result;
    IF observed IS DISTINCT FROM {wanted} THEN
        RAISE EXCEPTION 'P2 retrieval: {label} expected %, got %', {wanted}, observed;
    END IF;
END $check$;
"""


def build_sql(projection, retrieval):
    vector = literal("[" + ",".join(["1"] + ["0"] * 1535) + "]")
    kb_ids = "'p2ci-a','p2ci-b'"
    project = (projection.replace(":tenant", "'P2-CI-A'").replace(":kbs", kb_ids)
               .replace(":docs", "'p2ci-shared'"))
    retrieve = bind_jdbc(retrieval, [vector, "'P2-CI-A'", "'p2ci-ca'", "'p2ci-cb'",
                                   "'p2ci-a'", "'p2ci-b'", "'p2ci-shared'",
                                   "'p2ci-current:same-key'", vector, "10"])
    sql = f"""
BEGIN;
SET LOCAL search_path=platform,extensions;
INSERT INTO ai_knowledge_base
 (id,name,embedding_model,collection_name,created_by,tenant_id,owner_member_id)
 VALUES ('p2ci-a','Synthetic P2 A','ci-model','p2ci-ca','1','P2-CI-A','platform:P2-CI-A:1'),
        ('p2ci-b','Synthetic P2 B','ci-model','p2ci-cb','1','P2-CI-B','platform:P2-CI-B:1');
INSERT INTO ai_resource (tenant_id,resource_type,resource_id,owner_member_id,parent_type,parent_id)
 VALUES ('P2-CI-A','KB','p2ci-a','platform:P2-CI-A:1',NULL,NULL),
        ('P2-CI-B','KB','p2ci-b','platform:P2-CI-B:1',NULL,NULL),
        ('P2-CI-A','DOCUMENT','p2ci-shared','platform:P2-CI-A:1','KB','p2ci-a'),
        ('P2-CI-B','DOCUMENT','p2ci-shared','platform:P2-CI-B:1','KB','p2ci-b'),
        ('P2-CI-A','DOCUMENT','p2ci-denied','platform:P2-CI-A:2','KB','p2ci-a');
INSERT INTO ai_document (tenant_id,doc_id,kb_id,name,member_id,published_version_id)
 VALUES ('P2-CI-A','p2ci-shared','p2ci-a','Synthetic A','platform:P2-CI-A:1','p2ci-current'),
        ('P2-CI-B','p2ci-shared','p2ci-b','Synthetic B','platform:P2-CI-B:1','p2ci-current'),
        ('P2-CI-A','p2ci-denied','p2ci-a','Denied document','platform:P2-CI-A:2','p2ci-denied-v');
INSERT INTO ai_document_version (tenant_id,version_id,doc_id,upload_id,state)
 VALUES ('P2-CI-A','p2ci-current','p2ci-shared','p2ci-upload-a','PUBLISHED'),
        ('P2-CI-B','p2ci-current','p2ci-shared','p2ci-upload-b','PUBLISHED'),
        ('P2-CI-A','p2ci-other','p2ci-shared','p2ci-upload-old','PUBLISHED'),
        ('P2-CI-A','p2ci-denied-v','p2ci-denied','p2ci-upload-denied','PUBLISHED');
INSERT INTO ai_document_chunk
 (tenant_id,version_id,chunk_key,chunk_index,doc_id,kb_id,content,content_hash,char_count,state,embedding)
 VALUES ('P2-CI-A','p2ci-current','same-key',0,'p2ci-shared','p2ci-a','P2-NEW-CONTENT','ci',14,'PUBLISHED',{vector}::vector),
        ('P2-CI-B','p2ci-current','same-key',0,'p2ci-shared','p2ci-b','OTHER-TENANT','ci',12,'PUBLISHED',{vector}::vector),
        ('P2-CI-A','p2ci-other','same-key',0,'p2ci-shared','p2ci-a','OTHER-VERSION','ci',13,'PUBLISHED',{vector}::vector),
        ('P2-CI-A','p2ci-denied-v','same-key',0,'p2ci-denied','p2ci-a','DENIED-DOCUMENT','ci',15,'PUBLISHED',{vector}::vector);
"""
    sql += check(project, "id", ["p2ci-current:same-key"], "current P2 projection and tenant joins")
    sql += check(retrieve, "content", ["P2-NEW-CONTENT"], "HTTP vector read matches published P2 content")
    sql += check(project.replace("'P2-CI-A'", "'P2-CI-B'"), "id",
                 ["p2ci-current:same-key"], "other tenant positive control")
    for selection, label in [(":kbs", "empty authorized KBs"), (":docs", "empty authorized documents")]:
        empty = (projection.replace(":tenant", "'P2-CI-A'").replace(":kbs", "NULL" if selection == ":kbs" else kb_ids)
                 .replace(":docs", "NULL" if selection == ":docs" else "'p2ci-shared'"))
        sql += check(empty, "id", [], label)
    mutations = [
        ("UPDATE ai_document SET tombstoned_at=now() WHERE tenant_id='P2-CI-A'", "tombstoned document"),
        ("UPDATE ai_document_chunk SET state='STAGING' WHERE tenant_id='P2-CI-A'", "unpublished chunks"),
        ("UPDATE ai_document_version SET state='SUPERSEDED' WHERE tenant_id='P2-CI-A'", "superseded version"),
        ("UPDATE ai_document SET published_version_id=NULL WHERE tenant_id='P2-CI-A'", "absent publication pointer"),
        ("UPDATE ai_resource SET status='TOMBSTONED' WHERE tenant_id='P2-CI-A' AND resource_type='KB'", "closed KB registry"),
        ("UPDATE ai_resource SET status='TOMBSTONED' WHERE tenant_id='P2-CI-A' AND resource_type='DOCUMENT'", "closed document registry"),
        ("UPDATE ai_resource SET parent_id='wrong-parent' WHERE tenant_id='P2-CI-A' AND resource_type='DOCUMENT'", "wrong registry parent"),
        ("UPDATE ai_document_chunk SET kb_id='p2ci-b' WHERE tenant_id='P2-CI-A'", "chunk/document KB mismatch"),
        ("UPDATE ai_document_chunk SET embedding=NULL WHERE tenant_id='P2-CI-A'", "missing embedding"),
    ]
    for statement, label in mutations:
        sql += "SAVEPOINT mutation;\n" + statement + ";\n"
        sql += check(project, "id", [], label + " projection")
        sql += check(retrieve, "content", [], label + " retrieval")
        sql += "ROLLBACK TO mutation; RELEASE mutation;\n"
    sql += "UPDATE ai_document SET published_version_id='p2ci-other' WHERE tenant_id='P2-CI-A' AND doc_id='p2ci-shared';\n"
    sql += check(project, "id", ["p2ci-other:same-key"], "publication pointer moves without stale chunks")
    sql += check(retrieve, "content", [], "previous projected chunk cannot survive a publication switch")
    return sql + "ROLLBACK;\nSELECT 'P2 PUBLISHED RETRIEVAL CHECK PASSED';\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    queries = production_queries(args.repo)
    args.output.write_text(build_sql(*queries), encoding="utf-8")
    print("Generated real-query P2 published retrieval checks (rollback-only synthetic fixtures)")
