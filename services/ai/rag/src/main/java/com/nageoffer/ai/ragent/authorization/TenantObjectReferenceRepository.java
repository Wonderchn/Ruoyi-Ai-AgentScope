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

package com.nageoffer.ai.ragent.authorization;

import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper.AiResourceRow;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对象引用 ↔ 资源注册表绑定（P1.3c）：把"上传文档对应的存储对象"登记进
 * {@code ai_resource}，使下载路径能够执行<b>"未登记即 404"</b>的授权语义。
 *
 * <p>表契约（V3，DDL 是唯一权威）：没有专门的对象引用表，复用注册表——
 * {@code resource_type='OBJECT'}（DDL 对 type 无枚举约束，CHECK 只约束 status；
 * 类型取值按"后续单元按需追加"的约定扩出这一常量），复合主键
 * {@code (tenant_id, resource_type, resource_id)}。三列的取值约定：
 * <ul>
 *   <li>{@code resource_id} = 对象 key 的 uuid 段（最后一段去掉扩展名后的 32 位十六进制）。
 *       全 key 形如 {@code {tenantId}/{namespace}/{uuid}.{ext}}，超出 VARCHAR(64) 且
 *       带路径分隔符，不能整段入库；uuid 段定长且与 key 一一对应，是天然绑定标识；</li>
 *   <li>{@code parent_type}/{@code parent_id} = {@code DOCUMENT}/{docId}：对象挂在
 *       文档名下，授权走 {@code doc:<id>} 引用（tombstone 沿父链传播）；</li>
 *   <li>{@code owner_member_id} = 上传时主体的 canonical membership（归属只来自主体）。</li>
 * </ul>
 *
 * <p>写入复用 {@link AiResourceMapper}（insert/tombstone 都按复合主键自然携带租户）；
 * 本类只补一个方向查询：按 {@code (tenant, parent_type, parent_id)} 列出对象行
 * （复用 {@link com.nageoffer.ai.ragent.authorization.dao.ResourceSourceRefMapper}
 * 的查询模式，但固定过滤 {@code resource_type='OBJECT'}）。
 * 对象行不是授权目标（授权引用只覆盖 {@code kb:}/{@code doc:}），AuthorizationService
 * 的授权事实投影会跳过它；这里的状态过滤只服务"未登记/已下线即 404"。
 */
@Repository
public class TenantObjectReferenceRepository {

    /** 注册表资源类型：对象存储绑定行（非授权目标）。 */
    public static final String TYPE_OBJECT = "OBJECT";

    private final AiResourceMapper resourceMapper;
    private final NamedParameterJdbcTemplate jdbc;

    public TenantObjectReferenceRepository(AiResourceMapper resourceMapper,
                                           NamedParameterJdbcTemplate jdbc) {
        this.resourceMapper = resourceMapper;
        this.jdbc = jdbc;
    }

    /** 一行对象绑定事实。 */
    public record ObjectRow(String tenantId,
                            String resourceId,
                            String parentType,
                            String parentId,
                            String status,
                            long resourceVersion) {
    }

    /** 文档的存储绑定视图：file_url 是内部对象 key，只允许授权路径内部消费，不出任何对外视图。 */
    public record DocumentStorage(String fileUrl, String mimeType) {
    }

    /**
     * 登记一个已上传对象：type='OBJECT'、resource_id=uuid 段、parent 指向文档、ACTIVE。
     *
     * <p>调用方（{@code ObjectReferenceRegistrar}）保证文档行已写、key 属于该租户；
     * 主键冲突（重复登记）按 {@code DuplicateKeyException} 上抛，由调用方决定幂等策略。
     */
    public void register(String tenantId, String objectSegment, String parentType, String parentId,
                         String ownerMemberId, String createdByMember) {
        resourceMapper.insert(new AiResourceRow(tenantId, TYPE_OBJECT, objectSegment,
                ownerMemberId, null, parentType, parentId,
                AiResourceMapper.STATUS_ACTIVE, 1L), createdByMember);
    }

    /** 对象行下线（文档删除/对象替换时调用）：ACTIVE → TOMBSTONED，同一条语句版本递增。 */
    public int tombstone(String tenantId, String objectSegment) {
        return resourceMapper.tombstone(tenantId, TYPE_OBJECT, objectSegment);
    }

    /**
     * 按 (tenant, parent_type, parent_id) 列出<b>仍 ACTIVE</b> 的对象绑定行。
     *
     * <p>状态过滤放在 SQL 里：下载语义是"未登记即 404"，tombstone 行与不存在行同外显，
     * 不给调用方留"拿到行再自行放行"的分支。
     */
    public List<ObjectRow> findActiveByParent(String tenantId, String parentType, String parentId) {
        String sql = "SELECT tenant_id, resource_id, parent_type, parent_id, status, resource_version"
                + " FROM ai_resource"
                + " WHERE tenant_id = :tenantId AND parent_type = :parentType AND parent_id = :parentId"
                + " AND resource_type = 'OBJECT' AND status = 'ACTIVE'";
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("parentType", parentType);
        params.put("parentId", parentId);
        return jdbc.query(sql, params, (rs, rowNum) -> new ObjectRow(
                rs.getString("tenant_id"),
                rs.getString("resource_id"),
                rs.getString("parent_type"),
                rs.getString("parent_id"),
                rs.getString("status"),
                rs.getLong("resource_version")));
    }

    /**
     * 读文档行的存储绑定（file_url 对象 key 与 MIME）。租户条件恒在、只认未删除行；
     * 文档不存在/跨租户/已删除一律 empty，与"未登记"同外显。
     */
    public java.util.Optional<DocumentStorage> findDocumentStorage(String tenantId, String docId) {
        String sql = "SELECT file_url, mime_type FROM t_knowledge_document"
                + " WHERE tenant_id = :tenantId AND id = :docId AND deleted = 0";
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("docId", docId);
        return jdbc.query(sql, params, (rs, rowNum) -> new DocumentStorage(
                rs.getString("file_url"),
                rs.getString("mime_type"))).stream().findFirst();
    }

    /**
     * 从对象 key 提取 uuid 段（最后一个路径段去掉扩展名）。
     *
     * <p>这是登记与下载两侧共用的约定：key 不带路径分隔符的末段、截掉第一个点之后的部分。
     * 提取不出（空白/空段）即拒绝——登记侧拒绝落库，下载侧视为绑定缺失。
     */
    public static String objectSegmentOf(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("object key is required");
        }
        int slash = objectKey.lastIndexOf('/');
        String segment = slash < 0 ? objectKey : objectKey.substring(slash + 1);
        int dot = segment.indexOf('.');
        String base = dot < 0 ? segment : segment.substring(0, dot);
        if (base.isBlank() || base.length() > 64) {
            throw new IllegalArgumentException("object key has no usable uuid segment");
        }
        return base;
    }
}
