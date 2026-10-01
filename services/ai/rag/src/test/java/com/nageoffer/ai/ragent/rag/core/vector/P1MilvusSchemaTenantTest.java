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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1.3b Milvus shared-collection schema guard.
 *
 * <p>Why this class exists: the write and read paths both filter on {@code tenant_id}, but a Milvus
 * collection only accepts fields it <b>declares</b>. Without the field, every write carrying a tenant
 * is rejected by the server - which is the safe direction, but it means the tenant isolation this unit
 * added would be permanently unusable rather than merely untested. The read filter and the schema
 * therefore have to be asserted together; either alone is a half-built guarantee.
 *
 * <p>This backend has no runtime on this machine, so these assertions cover the request the code
 * builds, not server behaviour. That distinction is recorded as NOT_RUN in the closeout.
 */
class P1MilvusSchemaTenantTest {

    private static final String SHARED_COLLECTION = "rag_default_store";

    @Test
    @DisplayName("共享 collection 必须声明 tenant_id 字段，否则写行的租户会被服务端拒绝")
    void collectionDeclaresTenantField() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        when(milvusClient.hasCollection(any(HasCollectionReq.class))).thenReturn(Boolean.FALSE);
        MilvusVectorStoreAdmin admin = new MilvusVectorStoreAdmin(milvusClient, properties());

        admin.ensureVectorSpace(spec());

        ArgumentCaptor<CreateCollectionReq> reqCaptor = ArgumentCaptor.forClass(CreateCollectionReq.class);
        verify(milvusClient).createCollection(reqCaptor.capture());

        List<CreateCollectionReq.FieldSchema> fields = reqCaptor.getValue()
                .getCollectionSchema().getFieldSchemaList();
        List<String> names = fields.stream()
                .map(CreateCollectionReq.FieldSchema::getName)
                .collect(Collectors.toList());

        assertTrue(names.contains("tenant_id"),
                "collection 必须声明 tenant_id，实际字段=" + names);
        assertTrue(names.contains("collection_name") && names.contains("id") && names.contains("embedding"),
                "原有字段不得因为新增 tenant_id 而丢失，实际字段=" + names);

        CreateCollectionReq.FieldSchema tenantField = fields.stream()
                .filter(f -> "tenant_id".equals(f.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals(64, tenantField.getMaxLength(),
                "tenant_id 长度必须与 ExecutionPrincipal 的 1..64 契约一致");
    }

    @Test
    @DisplayName("tenant_id 必须有倒排索引：它出现在每次检索与删除的 filter 里")
    void collectionIndexesTenantField() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        when(milvusClient.hasCollection(any(HasCollectionReq.class))).thenReturn(Boolean.FALSE);
        MilvusVectorStoreAdmin admin = new MilvusVectorStoreAdmin(milvusClient, properties());

        admin.ensureVectorSpace(spec());

        ArgumentCaptor<CreateCollectionReq> reqCaptor = ArgumentCaptor.forClass(CreateCollectionReq.class);
        verify(milvusClient).createCollection(reqCaptor.capture());

        List<IndexParam> indexes = reqCaptor.getValue().getIndexParams();
        assertNotNull(indexes, "创建 collection 必须带索引参数");
        List<String> indexedFields = indexes.stream()
                .map(IndexParam::getFieldName)
                .collect(Collectors.toList());

        assertTrue(indexedFields.contains("tenant_id"),
                "tenant_id 必须有倒排索引，否则每次租户过滤都退化成全量标量扫描，实际索引字段=" + indexedFields);
        assertTrue(indexedFields.contains("collection_name"),
                "原有 collection_name 索引不得丢失，实际索引字段=" + indexedFields);
        assertTrue(indexedFields.contains("embedding"),
                "向量字段索引不得丢失，实际索引字段=" + indexedFields);
    }

    @Test
    @DisplayName("已存在的 collection 不会被重复创建（避免掩盖 schema 漂移）")
    void existingCollectionIsNotRecreated() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        when(milvusClient.hasCollection(any(HasCollectionReq.class))).thenReturn(Boolean.TRUE);
        MilvusVectorStoreAdmin admin = new MilvusVectorStoreAdmin(milvusClient, properties());

        admin.ensureVectorSpace(spec());

        verify(milvusClient, never()).createCollection(any(CreateCollectionReq.class));
    }

    @Test
    @DisplayName("dropVectorSpace 拒绝空白 collection_name：空 filter 会匹配到全部行")
    void dropRejectsBlankCollectionName() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        MilvusVectorStoreAdmin admin = new MilvusVectorStoreAdmin(milvusClient, properties());

        assertThrows(ClientException.class, () -> admin.dropVectorSpace(null));
        assertThrows(ClientException.class, () -> admin.dropVectorSpace(""));
        assertThrows(ClientException.class, () -> admin.dropVectorSpace("   "));

        // 拒绝必须发生在删除之前：发一条 filter 为空/恒假的删除语句再去抛错是不可接受的
        verify(milvusClient, never()).delete(any(DeleteReq.class));
    }

    @Test
    @DisplayName("dropVectorSpace 的 filter 会转义 collection_name，避免引号注入扩大删除范围")
    void dropEscapesCollectionName() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        DeleteResp resp = mock(DeleteResp.class);
        when(resp.getDeleteCnt()).thenReturn(0L);
        when(milvusClient.delete(any(DeleteReq.class))).thenReturn(resp);
        MilvusVectorStoreAdmin admin = new MilvusVectorStoreAdmin(milvusClient, properties());

        admin.dropVectorSpace("kb\" or collection_name != \"");

        ArgumentCaptor<DeleteReq> reqCaptor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(milvusClient).delete(reqCaptor.capture());
        String filter = reqCaptor.getValue().getFilter();
        assertNotNull(filter);
        assertTrue(filter.contains("\\\""),
                "注入用的引号必须被转义，否则 filter 的语义会被改写，实际=" + filter);
        assertEquals(1, filter.split("collection_name ==", -1).length - 1,
                "filter 里只应有一个 collection_name 相等条件，实际=" + filter);
    }

    private static RAGDefaultProperties properties() {
        RAGDefaultProperties properties = new RAGDefaultProperties();
        properties.setDimension(2);
        properties.setCollectionName(SHARED_COLLECTION);
        return properties;
    }

    /** 共享 collection 的落点描述：logicalName 在共享模型下不参与物理区分，只用于构造。 */
    private static VectorSpaceSpec spec() {
        return VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder().logicalName(SHARED_COLLECTION).build())
                .build();
    }
}
