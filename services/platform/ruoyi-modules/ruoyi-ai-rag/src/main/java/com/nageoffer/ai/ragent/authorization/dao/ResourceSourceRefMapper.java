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

package com.nageoffer.ai.ragent.authorization.dao;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源源引用 DAO：KB → document → chunk 继承链在注册表里的<b>子资源方向</b>查询。
 *
 * <p>父方向（chunk → document → KB）由 {@link AiResourceMapper#findByPk} 沿
 * {@code parent_type/parent_id} 上溯；本 DAO 负责"给定父资源，列出 registry 里
 * 登记在它名下的子资源"——检索作用域投影（P1.3b 按 KB/DOC/chunk 分层）与
 * 父层 tombstone 影响面收窄都从这里取分层事实。
 *
 * <p>document / chunk 的 registry 行由写服务维护（本单元只建 KB 行）；
 * 没有子行就返回空列表，调用方不得把空当"全部"。
 */
@Repository
public class ResourceSourceRefMapper {

    private final NamedParameterJdbcTemplate jdbc;

    public ResourceSourceRefMapper(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一行子资源引用：类型 + id + 当前状态（父层 tombstone 判定需要状态，不只引用）。 */
    public record SourceRefRow(String resourceType,
                               String resourceId,
                               String status,
                               long resourceVersion) {
    }

    /** 按 (tenant_id, parent_type, parent_id) 列出子资源 registry 行。 */
    public List<SourceRefRow> findChildren(String tenantId, String parentType, String parentId) {
        String sql = "SELECT resource_type, resource_id, status, resource_version FROM ai_resource"
                + " WHERE tenant_id = :tenantId AND parent_type = :parentType AND parent_id = :parentId";
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("parentType", parentType);
        params.put("parentId", parentId);
        return jdbc.query(sql, params, (rs, rowNum) -> new SourceRefRow(
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("status"),
                rs.getLong("resource_version")));
    }
}
