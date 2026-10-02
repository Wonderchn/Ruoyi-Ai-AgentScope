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

import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.ragent.authorization.dao.AiAclEpochMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceAclMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper.AiResourceRow;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceAclMapper.AclRow;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * AI 授权域写服务（P1.3a）：KB 创建/删除（tombstone）与 ACL 管理的唯一写路径。
 *
 * <p>事务边界（05 §4.3）：每一类写都是<b>同一个数据库事务</b>里的多表联动，
 * 任一步失败整体回滚——
 * <ul>
 *   <li>创建：t_knowledge_base（tenant/owner 列来自 {@link PrincipalContext#require()}，
 *       <b>不接受 body 提供</b>）+ ai_resource registry 行（type=KB）+ owner ACL 行
 *       + epoch bump；</li>
 *   <li>删除：tombstone（status='TOMBSTONED' + resource_version+1）+ epoch bump，
 *       不物理删；</li>
 *   <li>ACL 管理：授权/撤权行 + 原子 epoch bump（撤权不 bump 就等于没撤）。</li>
 * </ul>
 * 事务用 {@link TransactionOperations} 而不是 {@code @Transactional}：
 * 编程式事务让"epoch 更新与 ACL 写在同一事务边界"可以用调用顺序直接断言
 * （{@code P1PersistentAclTest}），也避免注解在内部调用点静默失效。
 *
 * <p>错误外显（05 §4.2）：跨租户/无权/不存在一律 {@code RESOURCE_NOT_FOUND_OR_FORBIDDEN}(404)；
 * 缺主体 403；动作未授权 403；epoch 缺失/数据不一致 503 语义（{@link ServiceException}）。
 */
@Service
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiResourceWriteService {

    /** owner 授权行的动作字面量：owner-allow 覆盖全部动作（归属 + platform 匹配），行本身用于主体存活判定。 */
    public static final String OWNER_GRANT_ACTION = "kb.read";

    /** ACL 管理动作：操作者必须持有显式 kb.acl.manage 授权（或本人是 owner）。 */
    public static final String ACTION_ACL_MANAGE = "kb.acl.manage";

    private final NamedParameterJdbcTemplate jdbc;
    private final AiResourceMapper resourceMapper;
    private final AiResourceAclMapper aclMapper;
    private final AiAclEpochMapper epochMapper;
    private final TransactionOperations transactionOperations;
    private com.nageoffer.ai.ragent.framework.security.RevocationGuard revocations;
    private com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService authorization;
    private com.nageoffer.ai.ragent.framework.security.AuthorizationChecker platform;
    private boolean highRiskEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    public void setHighRiskEnabled(@org.springframework.beans.factory.annotation.Value("${ai.integration.high-risk.enabled:false}") boolean enabled) {
        highRiskEnabled = enabled;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void configureExecution(com.nageoffer.ai.ragent.framework.security.RevocationGuard guard,
            com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService resources,
            com.nageoffer.ai.ragent.framework.security.AuthorizationChecker checker) {
        revocations = guard;
        authorization = resources;
        platform = checker;
    }

    private <T> T write(ExecutionPrincipal principal, String action, String ref,
                       org.springframework.transaction.support.TransactionCallback<T> work) {
        if (!highRiskEnabled || revocations == null || authorization == null || platform == null) {
            throw new ServiceException("写入 permit/授权服务不可用");
        }
        if (!principal.hasScope(action)) { throw new P04AiException(P04AiErrorCode.FORBIDDEN); }
        platform.check(new com.nageoffer.ai.ragent.framework.security.DelegatedPrincipal(principal.issuer(),
                principal.tenantId(), principal.userId(), principal.membershipId(), principal.policyVersion(),
                principal.scopes(), principal.jti()), action, ref);
        if (!"kb.write".equals(action)) {
            var verdict = authorization.check(principal, action, ref);
            if (verdict == com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict.STALE) {
                throw new com.nageoffer.ai.ragent.framework.security.StaleVersionException("aclVersion changed");
            }
            if (verdict == null || verdict == com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict.UNKNOWN) {
                throw new ServiceException("资源授权事实未知");
            }
            if (verdict != com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict.GRANT) {
                throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            }
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ServiceException("资源 mutation 必须在外层业务事务之前 prepare/drain");
        }
        try (var operation = revocations.enter(principal, action, ref)) {
            Map<String, Object> parameters = Map.of("tenant", principal.tenantId(), "permit", operation.permitId(),
                    "barrier", operation.operationId());
            transactionOperations.executeWithoutResult(status -> {
                var versions = jdbc.query("SELECT version FROM ai_acl_epoch WHERE tenant_id=:tenant FOR UPDATE",
                        parameters, (rs, n) -> rs.getInt(1));
                if (versions.isEmpty() || versions.get(0) != principal.aclVersion()) {
                    throw new com.nageoffer.ai.ragent.framework.security.StaleVersionException("aclVersion changed");
                }
                int prepared=jdbc.update("INSERT INTO ai_tenant_barrier(tenant_id,status,barrier_id,reason,updated_at)"
                        + " VALUES(:tenant,'PENDING',:barrier,'resource mutation',now()) ON CONFLICT(tenant_id) DO UPDATE"
                        + " SET status='PENDING',barrier_id=EXCLUDED.barrier_id,updated_at=now()"
                        + " WHERE ai_tenant_barrier.status='OPEN' OR ai_tenant_barrier.barrier_id=EXCLUDED.barrier_id",parameters);
                if(prepared!=1){throw new ServiceException("另一资源屏障尚未闭合");}
            });
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while(true){
                Long active = jdbc.queryForObject("SELECT count(*) FROM ai_execution_permit WHERE tenant_id=:tenant"
                        + " AND status='ACTIVE' AND permit_id<>:permit", parameters, Long.class);
                if(active==null || active<0){throw new ServiceException("活跃集未知；屏障保持 PENDING");}
                if(active==0){break;}
                if(System.nanoTime()>=deadline){throw new ServiceException("资源 drain 超时；屏障保持 PENDING");}
                try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ServiceException("资源 drain 中断");}
            }
            T result=transactionOperations.execute(status -> {
                var versions=jdbc.query("SELECT version FROM ai_acl_epoch WHERE tenant_id=:tenant FOR UPDATE",parameters,(rs,n)->rs.getInt(1));
                if(versions.isEmpty() || versions.get(0)!=principal.aclVersion()){
                    throw new com.nageoffer.ai.ragent.framework.security.StaleVersionException("aclVersion changed");
                }
                Long active=jdbc.queryForObject("SELECT count(*) FROM ai_execution_permit WHERE tenant_id=:tenant"
                        + " AND status='ACTIVE' AND permit_id<>:permit",parameters,Long.class);
                if(active==null || active!=0){throw new ServiceException("资源活跃集未排空");}
                T value=work.doInTransaction(status);
                if(jdbc.update("UPDATE ai_tenant_barrier SET status='CLOSED',target_acl_version=(SELECT version FROM ai_acl_epoch WHERE tenant_id=:tenant),updated_at=now()"
                        + " WHERE tenant_id=:tenant AND barrier_id=:barrier AND status='PENDING'",parameters)!=1){throw new ServiceException("资源屏障提交未确认");}
                return value;
            });
            operation.close();
            revocations.setBarrierState(principal.tenantId(), com.nageoffer.ai.ragent.framework.security.RevocationGuard.BarrierState.OPEN,
                    operation.operationId(), null, "resource mutation committed and released");
            return result;
        }
    }

    public AiResourceWriteService(NamedParameterJdbcTemplate jdbc,
                                  AiResourceMapper resourceMapper,
                                  AiResourceAclMapper aclMapper,
                                  AiAclEpochMapper epochMapper,
                                  TransactionOperations transactionOperations) {
        this.jdbc = jdbc;
        this.resourceMapper = resourceMapper;
        this.aclMapper = aclMapper;
        this.epochMapper = epochMapper;
        this.transactionOperations = transactionOperations;
    }

    /** KB 创建草稿：归属字段刻意缺席——tenant/owner 只来自执行主体。 */
    public record KnowledgeBaseDraft(String name, String embeddingModel, String collectionName) {
    }

    /** ACL 授权请求：subjectType 用 framework 编码（member/department/role/tenant_all）。 */
    public record AclGrant(String subjectType, String subjectId, String action, Long expiresAtEpochSecond) {
    }

    /** KB 元数据视图（不含任何对象存储 key / 凭据）。 */
    public record KnowledgeBaseView(String kbId, String name, String embeddingModel, String collectionName,
                                    String ownerMemberId, String ownerDeptId) {
    }

    /** 文档元数据视图：刻意不含 file_url——那是内部对象 key，不出内联响应。 */
    public record DocumentView(String docId, String kbId, String docName, String status,
                               Integer enabled, Integer chunkCount) {
    }

    // ------------------------------------------------------------ 创建

    /**
     * 创建 KB：同一事务写 t_knowledge_base + registry + owner ACL + epoch bump。
     *
     * @return 生成的 kbId
     */
    public String createKnowledgeBase(KnowledgeBaseDraft draft) {
        ExecutionPrincipal principal = PrincipalContext.require();
        requireText(draft == null ? null : draft.name(), "name");
        requireText(draft.embeddingModel(), "embeddingModel");
        requireText(draft.collectionName(), "collectionName");

        String tenantId = principal.tenantId();
        String membershipId = principal.membershipId();
        String kbId = IdUtil.getSnowflakeNextIdStr();
        String ownerDept=authorization instanceof AiResourceAuthorizationService live?live.currentOwnerDept(principal,"kb.write"):null;

        return write(principal, "kb.write", "kb:" + kbId, status -> {
            // 元数据行：tenant_id / owner_member_id 只取自主体；owner_dept_id 等部门事实
            // 由 platform 组织匹配接线后补充（ExecutionPrincipal 当前不携带部门）。
            Map<String, Object> kb = new HashMap<>();
            kb.put("id", kbId);
            kb.put("name", draft.name());
            kb.put("embeddingModel", draft.embeddingModel());
            kb.put("collectionName", draft.collectionName());
            kb.put("createdBy", principal.userId());
            kb.put("updatedBy", principal.userId());
            kb.put("tenantId", tenantId);
            kb.put("ownerMemberId", membershipId);
            kb.put("ownerDeptId", ownerDept);
            jdbc.update("INSERT INTO t_knowledge_base (id, name, embedding_model, collection_name,"
                            + " created_by, updated_by, tenant_id, owner_member_id, owner_dept_id, deleted)"
                            + " VALUES (:id, :name, :embeddingModel, :collectionName, :createdBy, :updatedBy,"
                            + " :tenantId, :ownerMemberId, :ownerDeptId, 0)",
                    kb);

            // registry 行：归属与版本权威（type=KB，无父）
            resourceMapper.insert(new AiResourceRow(tenantId, AiResourceMapper.TYPE_KB, kbId,
                    membershipId, ownerDept, null, null, AiResourceMapper.STATUS_ACTIVE, 1L), membershipId);

            // owner ACL 行：让 owner 主体进入"主体存活"判定（owner-allow 仍要求 platform 认可，
            // owner 身份本身不豁免任何一侧）
            aclMapper.insert(new AclRow(IdUtil.getSnowflakeNextIdStr(), tenantId,
                    AiResourceMapper.TYPE_KB, kbId,
                    AiResourceAuthorizationService.DB_SUBJECT_MEMBER, membershipId,
                    OWNER_GRANT_ACTION, null, membershipId));

            bumpEpochOrRefuse(tenantId);
            return kbId;
        });
    }

    // ------------------------------------------------------------ 删除（tombstone）

    /**
     * 删除 KB：registry 行 tombstone + 版本递增 + epoch bump（同一事务），不物理删。
     *
     * <p>0 行受影响 = 资源不存在 / 不属于本租户 / 已删除——一律按"不存在"拒绝，
     * 不泄露存在性。动作级授权（kb.delete）由内部端点的判定层先行完成。
     */
    public void tombstoneKnowledgeBase(String kbId) {
        ExecutionPrincipal principal = PrincipalContext.require();
        String tenantId = principal.tenantId();
        write(principal, "kb.delete", "kb:" + kbId, status -> {
            int rows = resourceMapper.tombstone(tenantId, AiResourceMapper.TYPE_KB, kbId);
            if (rows == 0) {
                throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            }
            bumpEpochOrRefuse(tenantId);
            return null;
        });
    }

    // ------------------------------------------------------------ ACL 管理

    /** 新增一条授权：subject 同租户、操作者持 kb.acl.manage 且是 owner 或显式 grant，原子 bump。 */
    public void grantAclRule(String kbId, AclGrant grant) {
        ExecutionPrincipal principal = PrincipalContext.require();
        requireAclManageScope(principal);
        String tenantId = principal.tenantId();
        AclRow row = validatedRow(kbId, tenantId, principal, grant);
        if(authorization instanceof AiResourceAuthorizationService live){
            live.requireCurrentSubject(principal,AiResourceAuthorizationService.subjectRef(row.subjectType(),row.subjectId(),tenantId));
        }

        write(principal, ACTION_ACL_MANAGE, "kb:" + kbId, status -> {
            try {
                aclMapper.insert(row);
            } catch (DuplicateKeyException e) {
                // 同资源同主体同动作只有一行（uk_ai_resource_acl）：重复授予按请求错误拒绝
                throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "acl rule already exists");
            }
            bumpEpochOrRefuse(tenantId);
            return null;
        });
    }

    /** 撤销一条授权：撤权即删行 + 原子 bump（实际删到行才 bump，幂等撤销不制造版本噪声）。 */
    public void revokeAclRule(String kbId, AclGrant grant) {
        ExecutionPrincipal principal = PrincipalContext.require();
        requireAclManageScope(principal);
        String tenantId = principal.tenantId();
        AclRow row = validatedRow(kbId, tenantId, principal, grant);

        write(principal, ACTION_ACL_MANAGE, "kb:" + kbId, status -> {
            int rows = aclMapper.deleteRule(tenantId, AiResourceMapper.TYPE_KB, kbId,
                    row.subjectType(), row.subjectId(), row.action());
            if (rows > 0) {
                bumpEpochOrRefuse(tenantId);
            }
            return null;
        });
    }

    /** 校验动作权限、资源归属与主体租户，组装待写 ACL 行（不写库）。 */
    private AclRow validatedRow(String kbId, String tenantId, ExecutionPrincipal principal, AclGrant grant) {
        if (grant == null || grant.subjectType() == null || grant.action() == null
                || grant.action().isBlank()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST);
        }
        AiResourceAuthorizationService.ParsedSubject subject;
        try {
            subject = AiResourceAuthorizationService.parseSubjectRef(grant.subjectType() + ":"
                    + (grant.subjectId() == null ? "" : grant.subjectId()));
        } catch (IllegalArgumentException e) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "unsupported ACL subject type");
        }

        // 主体必须同租户：member 主体是 canonical membership（platform:<tenantId>:<userId>），
        // 租户段与当前主体不一致 = 试图把授权发给别的租户的成员，直接拒绝。
        // dept/role 是不透明 platform 引用，租户归属由 platform 匹配核实（本地只做非空校验）。
        if (AiResourceAuthorizationService.DB_SUBJECT_MEMBER.equals(subject.subjectType())) {
            requireSameTenantMember(subject.subjectId(), tenantId);
        } else if (AiResourceAuthorizationService.DB_SUBJECT_TENANT_ALL.equals(subject.subjectType())) {
            // TENANT_ALL 是显式租户级 grant：subject_id 必空（ck_ai_resource_acl_subject）。
            // 携带 subjectId 的请求必须拒绝而不是悄悄丢弃——那会把"授权给某人"
            // 反转成"授权给全租户"。
            if (grant.subjectId() != null && !grant.subjectId().isBlank()) {
                throw new P04AiException(P04AiErrorCode.BAD_REQUEST,
                        "tenant_all grant must not carry a subjectId");
            }
        } else if (subject.subjectId() == null || subject.subjectId().isBlank()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "subjectId is required");
        }

        // 资源必须存在且属于本租户（跨租户/不存在同外显 404）
        AiResourceRow resource = resourceMapper
                .findByPk(tenantId, AiResourceMapper.TYPE_KB, kbId)
                .orElseThrow(() -> new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));

        // 操作者必须是 owner 或持显式 kb.acl.manage 授权；两者都不满足与"不存在"同外显
        boolean owner = principal.membershipId().equals(resource.ownerMemberId());
        if (!owner && !hasLiveManageGrant(tenantId, kbId, principal.membershipId())) {
            throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }

        return new AclRow(IdUtil.getSnowflakeNextIdStr(), tenantId,
                AiResourceMapper.TYPE_KB, kbId,
                subject.subjectType(), subject.subjectId(), grant.action(),
                grant.expiresAtEpochSecond(), principal.membershipId());
    }

    /** member 主体必须是当前租户的 canonical membership。 */
    private static void requireSameTenantMember(String subjectId, String tenantId) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "subjectId is required");
        }
        String expectedPrefix = "platform:" + tenantId + ":";
        boolean canonical = subjectId.startsWith(expectedPrefix)
                && subjectId.length() > expectedPrefix.length()
                && subjectId.substring(expectedPrefix.length()).chars().allMatch(Character::isDigit);
        if (!canonical) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "acl subject does not belong to current tenant");
        }
    }

    /** 操作者是否在该资源上持有未过期的 kb.acl.manage 授权。 */
    private boolean hasLiveManageGrant(String tenantId, String kbId, String membershipId) {
        long now = System.currentTimeMillis() / 1000;
        return aclMapper.findByResource(tenantId, AiResourceMapper.TYPE_KB, kbId).stream()
                .anyMatch(rule -> AiResourceAuthorizationService.DB_SUBJECT_MEMBER.equals(rule.subjectType())
                        && membershipId.equals(rule.subjectId())
                        && ACTION_ACL_MANAGE.equals(rule.action())
                        && (rule.expiresAtEpochSecond() == null || rule.expiresAtEpochSecond() > now));
    }

    private static void requireAclManageScope(ExecutionPrincipal principal) {
        if (!principal.hasScope(ACTION_ACL_MANAGE)) {
            // 动作未授权：403（与"资源无权 404"区分——这里连管理动作本身都不在凭证范围内）
            throw new P04AiException(P04AiErrorCode.FORBIDDEN);
        }
    }

    /** bump 失败（无 epoch 行）即拒绝：整个事务回滚，绝不默认初始化版本。 */
    private void bumpEpochOrRefuse(String tenantId) {
        Optional<Integer> version = epochMapper.bump(tenantId);
        if (version.isEmpty()) {
            throw new ServiceException("acl epoch is missing for tenant " + tenantId
                    + "; refusing to default it");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, field + " is required");
        }
    }

    // ------------------------------------------------------------ 内部端点的租户内元数据读取

    /** 按租户读 KB 元数据（无租户条件不读；视图不含对象存储 key / 凭据）。 */
    public Optional<KnowledgeBaseView> findKnowledgeBase(String tenantId, String kbId) {
        String sql = "SELECT id, name, embedding_model, collection_name, owner_member_id, owner_dept_id"
                + " FROM t_knowledge_base"
                + " WHERE tenant_id = :tenantId AND id = :kbId AND deleted = 0";
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("kbId", kbId);
        return jdbc.query(sql, params, (rs, rowNum) -> new KnowledgeBaseView(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("embedding_model"),
                rs.getString("collection_name"),
                rs.getString("owner_member_id"),
                rs.getString("owner_dept_id"))).stream().findFirst();
    }

    /** 按租户读文档元数据；file_url（内部对象 key）刻意不进视图。 */
    public Optional<DocumentView> findDocument(String tenantId, String docId) {
        String sql = "SELECT id, kb_id, doc_name, status, enabled, chunk_count FROM t_knowledge_document"
                + " WHERE tenant_id = :tenantId AND id = :docId AND deleted = 0";
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("docId", docId);
        return jdbc.query(sql, params, (rs, rowNum) -> new DocumentView(
                rs.getString("id"),
                rs.getString("kb_id"),
                rs.getString("doc_name"),
                rs.getString("status"),
                rs.getObject("enabled") == null ? null : rs.getInt("enabled"),
                rs.getObject("chunk_count") == null ? null : rs.getInt("chunk_count")))
                .stream().findFirst();
    }
}
