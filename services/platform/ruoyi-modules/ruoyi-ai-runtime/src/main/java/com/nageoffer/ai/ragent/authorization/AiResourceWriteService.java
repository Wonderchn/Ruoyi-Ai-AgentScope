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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * AI 授权域写服务（P1.3a）：KB 创建/删除（tombstone）与 ACL 管理的唯一写路径。
 *
 * <p>事务边界（05 §4.3）：每一类写都是<b>同一个数据库事务</b>里的多表联动，
 * 任一步失败整体回滚——
 * <ul>
 *   <li>创建：platform.ai_knowledge_base（tenant/owner 列来自 {@link PrincipalContext#require()}，
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

    /**
     * **KB 域** owner 授权行的动作字面量：owner-allow 覆盖全部动作（归属 + platform 匹配），
     * 行本身用于主体存活判定。
     *
     * <p><b>域不同、动作不同 —— 不要跨域复用。</b>ACL 行的 {@code action} 必须与 {@code resource_type}
     * 成对出现：KB 的 owner 行是 {@code kb.read}，会话的 owner 行是 {@code conversation.read}。
     * 这里刻意**按域拆成两个常量**（而不是一个听起来通用、实际只属于 KB 的 {@code OWNER_GRANT_ACTION}）：
     * 2026-10-06 的 G-52e 实测正是"会话创建复用了 KB 域常量"⇒ owner ACL 落成 {@code kb.read}
     * ⇒ 列表侧（{@code AiResourceController:118-119} 的 {@code conversation.read} 行级判定）判不出来
     * ⇒ **创建者看不到自己刚建的会话**（③ 判据 FAIL）。
     * <b>复用前先问：这个动作属于哪个域？</b>
     */
    public static final String KB_OWNER_GRANT_ACTION = "kb.read";

    /** **会话域** owner 授权行的动作字面量（域说明见 {@link #KB_OWNER_GRANT_ACTION}）。 */
    public static final String CONVERSATION_OWNER_GRANT_ACTION = "conversation.read";

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

    private static final Logger log = LoggerFactory.getLogger(AiResourceWriteService.class);

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

    /**
     * 把 {@link #write} 拒绝的**四种成因**编码成一个可 grep 的稳定 token 串。
     *
     * <p>刻意不把这些细节放进客户端文案：文案外泄装配状态本身就是信息泄露面。
     * 服务端日志才是归属地（§6.1 第 11 条）。
     */
    /**
     * 屏障对账组件（G-55c）。{@code required=false} + 使用点**响亮失败**：即便它缺席，
     * finally 也必须把"PENDING 未被收回"这件事记进日志（不得静默），但绝不因此再抛异常
     * （finally 里抛异常会把"业务失败的原因"覆盖成"对账失败"）。
     */
    private TenantBarrierReconciler barrierReconciler;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureBarrierReconciler(TenantBarrierReconciler barrierReconciler) {
        this.barrierReconciler = barrierReconciler;
    }

    private String refusalReason() {
        StringBuilder reason = new StringBuilder();
        if (!highRiskEnabled) { reason.append("high_risk_disabled"); }
        if (revocations == null) { append(reason, "revocation_guard_missing"); }
        if (authorization == null) { append(reason, "resource_authorization_missing"); }
        if (platform == null) { append(reason, "platform_checker_missing"); }
        return reason.isEmpty() ? "unknown" : reason.toString();
    }

    private static void append(StringBuilder reason, String token) {
        if (!reason.isEmpty()) { reason.append(','); }
        reason.append(token);
    }

    /**
     * 严格入口：**资源必须已存在且已授权**（改名 / 删除 / ACL 授予与撤销等既有路径都走这里）。
     *
     * <p>这条路径的资源级判定（{@code authorization.check}）是"改名类"操作的授权核心：
     * 它要求被操作资源在 {@code ai_resource} 里是 ACTIVE 且主体已获授权，否则一律 404
     * （不泄露存在性）。
     */
    private <T> T write(ExecutionPrincipal principal, String action, String ref,
                       org.springframework.transaction.support.TransactionCallback<T> work) {
        return writeInternal(principal, action, ref, true, work);
    }

    /**
     * 创建入口：**只跳过"资源必须已授权"这一步**，其余守卫与 {@link #write} 完全相同。
     *
     * <p><b>为什么创建必须跳过资源级授权。</b>被创建的资源**此刻还不存在**：
     * {@code authorization.check(principal, action, "conv:"+newId)} 对一个尚未落 registry 的引用
     * 只能返回"未授权"，于是 {@link #write} 会在写事务之前抛 404 —— **创建路径就永远不可达**。
     * 这与 KB 创建在 {@link #writeInternal} 里被 {@code kb.write} 豁免是**同一个原因**
     * （KB 也是"先有创建，才有资源可授权"），只是 KB 走的是既有的动作名豁免，
     * 而会话创建复用的是改名动作，不能用动作名区分，所以这里用**独立的创建入口**表达同一语义。
     *
     * <p><b>跳过的是什么、保留的是什么（必须读清）。</b>
     * <ul>
     *   <li><b>跳过</b>：仅"资源已存在且已授权"这一条（{@code authorization.check}）；</li>
     *   <li><b>保留</b>：装配完整性四条件（未开 high-risk / 守卫或授权服务未装配 ⇒ 503）、
     *       {@code principal.hasScope(action)}（功能级 ⇒ 403）、
     *       {@code platform.check(...)}（平台侧功能/租户级判定）、
     *       {@code ai_acl_epoch} 版本复核、{@code ai_tenant_barrier} 屏障与
     *       {@code ai_execution_permit} 排空、以及"业务行 + registry + owner ACL + epoch bump 同事务"。</li>
     * </ul>
     * 换句话说：**创建不是"多了一条可以绕过的动作"，而是"创建语义下资源级判定不适用"** ——
     * 新资源的归属由 {@code platform.check} 与执行主体决定，owner ACL 由本入口的写回调落盘。
     * <b>不要**为了省事把其它动作也接到本入口上。</b>
     */
    private <T> T writeCreating(ExecutionPrincipal principal, String action, String ref,
                       org.springframework.transaction.support.TransactionCallback<T> work) {
        return writeInternal(principal, action, ref, false, work);
    }

    /**
     * 两条入口的共同实现。
     *
     * @param requireExistingGrant {@code true} = 严格路径（资源必须已授权）；
     *                             {@code false} = 创建路径（被创建的资源尚不存在，见 {@link #writeCreating}）。
     */
    private <T> T writeInternal(ExecutionPrincipal principal, String action, String ref,
                       boolean requireExistingGrant,
                       org.springframework.transaction.support.TransactionCallback<T> work) {
        if (!highRiskEnabled || revocations == null || authorization == null || platform == null) {
            // 这个 503 有**四种互不相同的成因**，而客户端看到的文案**完全相同**：
            //   ① 高危写开关未开（`ai.integration.high-risk.enabled`，缺省 false）；
            //   ② RevocationGuard 未装配；③ ResourceAuthorizationService 未装配；
            //   ④ AuthorizationChecker（平台判定）未装配。
            // 只凭客户端文案无法区分它们 —— 2026-10-06 的 F 面验收实测正是踩在这里：
            // 「写入 permit/授权服务不可用」被反复归因为「平台侧写入 permit 失败」，
            // 而平台侧 permit 登记（DefaultRevocationGuard）其实**在读路径上是成功的**。
            // 纪律 §6.1 第 11 条：**失败原因必须进服务端日志**，客户端文案保持不变。
            // 日志里四个判定位逐项落值，使一次请求即可定案，不必再靠排除法。
            log.warn("write refused reason={} highRiskEnabled={} revocationGuardBound={} resourceAuthorizationBound={}"
                            + " platformCheckerBound={} action={} tenant={}",
                    refusalReason(), highRiskEnabled, revocations != null, authorization != null,
                    platform != null, action, principal == null ? null : principal.tenantId());
            throw new ServiceException("写入 permit/授权服务不可用");
        }
        if (!principal.hasScope(action)) { throw new P04AiException(P04AiErrorCode.FORBIDDEN); }
        platform.check(new com.nageoffer.ai.ragent.framework.security.DelegatedPrincipal(principal.issuer(),
                principal.tenantId(), principal.userId(), principal.membershipId(), principal.policyVersion(),
                principal.scopes(), principal.jti()), action, ref);
        // 资源级判定：严格路径必须过；创建路径不适用（被创建的资源尚不存在，见 writeCreating 的说明）。
        // kb.write 的既有豁免保持不变（KB 创建也属于"资源尚不存在"）。
        if (requireExistingGrant && !"kb.write".equals(action)) {
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
        // 屏障/许可标识提到 try 之外：finally 里要用它们做对账，而 `revocations.enter(...)` 自身
        // 可能抛异常 —— 那时本操作**从未准备过**屏障（PENDING 还没写），也就无需对账。
        String barrierId = null;
        String permitId = null;
        try (var operation = revocations.enter(principal, action, ref)) {
            barrierId = operation.operationId();
            permitId = operation.permitId();
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
                        + " AND status='ACTIVE' AND permit_id<>:permit"
                        + " AND expires_at > CURRENT_TIMESTAMP", parameters, Long.class);
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
                        + " AND status='ACTIVE' AND permit_id<>:permit"
                        + " AND expires_at > CURRENT_TIMESTAMP",parameters,Long.class);
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
        } finally {
            // ------------------------------------------------------------------ G-55c：必然执行的对账
            // 为什么必须有它：上面 `SET status='PENDING'` 在**独立事务**（executeWithoutResult）里已提交，
            // 而 `SET status='CLOSED'` 在**业务写事务内**。业务写一旦回滚 —— 例如一次完全合法的
            // 409（StaleVersionException，乐观锁冲突）或任何业务异常 —— CLOSED 被一起回滚、PENDING 留在库里，
            // 且下面那句 setBarrierState(OPEN) 也不会执行（它在 try 的成功路径尾部）。
            // 结果：DefaultRevocationGuard 之后对该租户**每一次**写都拒（"租户屏障 PENDING，拒绝新 permit"），
            // 而触发它的是一次**合法失败** ⇒ "失败"变成 DoS 原语（T1 实测：一次正确 409 之后 barrier_pending=1）。
            //
            // 三条论证在本实现里如何成立：
            //  ① finally 与业务事务无关 —— Java 语义保证异常必然穿过它；`try(...)` 的资源关闭发生在本块之后。
            //  ② 对账走 REQUIRES_NEW（TenantBarrierReconciler 内建 TransactionTemplate 的传播行为），
            //     外层回滚**回滚不掉**内层已提交的事务。
            //  ③ 对账**只写 OPEN、绝不写 CLOSED** —— Reconciler 的 API 里根本没有写 CLOSED 的方法；
            //     CLOSED 仍只由上面业务事务内那条 UPDATE 写出（回滚的业务写不得被记成成功）。
            // 另：`operation.close()` 不在这里重复调用 —— 它由 try-with-resources 在本块之后自动执行。
            try {
                if (barrierId == null) {
                    // enter 失败：本操作没有准备过屏障，无对账对象（不是"对账失败"）。
                    log.warn("屏障对账跳过：本操作未登记 permit（enter 阶段即失败）tenant={}", principal.tenantId());
                } else if (barrierReconciler == null) {
                    log.error("屏障对账组件未装配：PENDING 未被收回 tenant={} barrier={}",
                            principal.tenantId(), barrierId);
                } else {
                    barrierReconciler.reclaimIfNoActivePermit(principal.tenantId(), barrierId, permitId);
                }
            } catch (RuntimeException reconcileFailure) {
                // finally 内**绝不再抛**：此刻事务/响应已在收尾，抛出会盖掉真正的业务失败原因。
                // 但也不静默 —— 对账失败本身就是需要人看的事实。
                log.error("屏障对账失败（数据库保持现状，屏障可能仍为 PENDING）tenant={} barrier={} cause={}",
                        principal.tenantId(), barrierId, reconcileFailure.getMessage());
            }
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
     * 创建 KB：同一事务写 platform.ai_knowledge_base + registry + owner ACL + epoch bump。
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
            jdbc.update("INSERT INTO platform.ai_knowledge_base (id, name, embedding_model, collection_name,"
                            + " created_by, updated_by, tenant_id, owner_member_id, owner_dept_id, deleted)"
                            + " VALUES (:id, :name, :embeddingModel, :collectionName, :createdBy, :updatedBy,"
                            + " :tenantId, :ownerMemberId, :ownerDeptId, 0)",
                    kb);

            // registry 行：归属与版本权威（type=KB，无父）
            resourceMapper.insert(new AiResourceRow(tenantId, AiResourceMapper.TYPE_KB, kbId,
                    membershipId, ownerDept, null, null, AiResourceMapper.STATUS_ACTIVE, 1L), membershipId);

            // owner ACL 行：让 owner 主体进入"主体存活"判定（owner-allow 仍要求 platform 认可，
            // owner 身份本身不豁免任何一侧）——**KB 域 ⇒ 用 KB_OWNER_GRANT_ACTION**。
            aclMapper.insert(new AclRow(IdUtil.getSnowflakeNextIdStr(), tenantId,
                    AiResourceMapper.TYPE_KB, kbId,
                    AiResourceAuthorizationService.DB_SUBJECT_MEMBER, membershipId,
                    KB_OWNER_GRANT_ACTION, null, membershipId));

            bumpEpochOrRefuse(tenantId);
            return kbId;
        });
    }

    // ------------------------------------------------------------ 会话创建（G-52）

    /** 会话创建草稿：归属字段刻意缺席——tenant/owner 只来自执行主体。 */
    public record ConversationDraft(String title) {
    }

    /**
     * 创建会话：同一事务写 {@code platform.ai_conversation} + registry + owner ACL + epoch bump。
     *
     * <p><b>为什么必须与 {@link #createKnowledgeBase} 同形、而不是照 {@code touchConversation}。</b>
     * 判据"创建后能被 {@code GET /conversations} 列出"的目标面是
     * <b>枚举 {@code ai_resource} 的 ACTIVE 注册行</b>
     * （{@code AiResourceAuthorizationService:346} 的"全部已授权资源"候选来源、
     * {@code :361} 的类型白名单、{@code :449-463} 的 {@code "conv" ↔ "CONVERSATION"} 映射）。
     * 而 {@code touchConversation} 只写 {@code ai_conversation} 一行、**从不写 registry/ACL/epoch**
     * ⇒ 只插业务行的话，创建出来的会话在列表里**看不见**。因此这里逐行照 KB 创建：
     * 业务行 → registry 行 → owner ACL 行 → {@code bumpEpochOrRefuse}。
     *
     * <p><b>为什么不另开一条授权写路径。</b>本方法是 {@code write(...)} 的公开入口 ——
     * 即 G-40 那道 {@code ai.integration.high-risk.enabled} 守卫的**唯一**经过点。
     * 会话创建按分类就是高危授权写（它产生新的 owner ACL 与 epoch），
     * 绕开守卫等于给会话开一条越过屏障/许可的写入路径，与 D04 与屏障语义相悖。
     * <b>推论</b>：在未开启 high-risk 的实例上，创建会话返回 503 是 **fail-closed 的正确行为**。
     *
     * <p><b>⚠️ {@code id} 与 {@code conversation_id} 是两个都要给的非空列。</b>
     * {@code platform.ai_conversation} 的 NOT NULL 且无默认值的列实测为
     * {@code id / conversation_id / user_id / title / tenant_id / member_id}
     * （V7 原文里该表**没有** {@code tenant_id}/{@code member_id}/{@code version}，它们是后续迁移加的；
     * {@code version} 虽有 {@code DEFAULT 0}，但 C9 要它作为后续 PUT 乐观锁的初值，
     * 故**显式写 0** 让 rename/delete 的 CAS 从它开始）。
     * 只给 {@code conversation_id} 而漏 {@code id} **不会在编译期报错，只在运行期炸** ——
     * 这就是下一个读这段代码的人一定会问的那件事。
     *
     * @return 生成的 conversationId
     */
    public String createConversation(ConversationDraft draft) {
        ExecutionPrincipal principal = PrincipalContext.require();
        requireText(draft == null ? null : draft.title(), "title");

        String tenantId = principal.tenantId();
        String membershipId = principal.membershipId();
        String conversationId = IdUtil.getSnowflakeNextIdStr();
        String ownerDept = authorization instanceof AiResourceAuthorizationService live
                ? live.currentOwnerDept(principal, "conversation.rename") : null;

        // 创建走 writeCreating（**只**跳过"资源已存在且已授权"那一步，其余守卫全保留）：
        // 新会话此刻还没有 registry 行，走严格入口必然在写事务之前 404（G-52c 实测）。
        // 注意与下面的改名路径（严格 write）的区别：改名要求"该资源已授权"，创建不适用。
        return writeCreating(principal, "conversation.rename", "conv:" + conversationId, status -> {
            // 业务行：tenant_id / member_id / user_id 只取自主体（不接受 body 提供）。
            Map<String, Object> conversation = new HashMap<>();
            conversation.put("id", conversationId);
            conversation.put("conversationId", conversationId);
            conversation.put("userId", principal.userId());
            conversation.put("title", draft.title());
            conversation.put("tenantId", tenantId);
            conversation.put("memberId", membershipId);
            conversation.put("version", 0);
            jdbc.update("INSERT INTO platform.ai_conversation (id, conversation_id, user_id, title,"
                            + " tenant_id, member_id, version, deleted)"
                            + " VALUES (:id, :conversationId, :userId, :title,"
                            + " :tenantId, :memberId, :version, 0)",
                    conversation);

            // registry 行：归属与版本权威。类型用**字面量** "CONVERSATION"——
            // AiResourceMapper 只声明了 TYPE_KB / TYPE_DOCUMENT，而授权侧白名单
            // （AiResourceAuthorizationService:361/:431）与 resourceRef 映射（:449-463）
            // 用的也是这个字面量，两侧必须逐字一致。
            resourceMapper.insert(new AiResourceRow(tenantId, "CONVERSATION", conversationId,
                    membershipId, ownerDept, null, null, AiResourceMapper.STATUS_ACTIVE, 1L), membershipId);

            // owner ACL 行：让 owner 主体进入"主体存活"判定（owner-allow 仍要求 platform 认可，
            // owner 身份本身不豁免任何一侧）——与 KB 创建逐行同形，**但域不同 ⇒ 动作用会话域的
            // CONVERSATION_OWNER_GRANT_ACTION**（G-52e：这里曾复用 KB 的 "kb.read"，导致
            // 列表侧 conversation.read 行级判定失败、创建者看不到自己的会话）。
            aclMapper.insert(new AclRow(IdUtil.getSnowflakeNextIdStr(), tenantId,
                    "CONVERSATION", conversationId,
                    AiResourceAuthorizationService.DB_SUBJECT_MEMBER, membershipId,
                    CONVERSATION_OWNER_GRANT_ACTION, null, membershipId));

            bumpEpochOrRefuse(tenantId);
            return conversationId;
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

    // ------------------------------------------------------------ 会话写入（F03）

    /**
     * 会话重命名 SQL（D10 乐观锁）。列名与读路径（{@code TenantConversationReadRepository}）严格一致：
     * 都用 {@code conversation_id}（公开 id）而**不是**主键 {@code id}，
     * 都按 {@code tenant_id + member_id} 限定，都只看 {@code deleted = 0}。
     *
     * <p><b>{@code title} 与 {@code version} 在同一条 UPDATE 语句内</b>（C9.1）：
     * 语句级原子，天然同事务——不会出现"标题写了但版本没涨"或反之的中间态，
     * 也不存在"先从应用读到 N、再写回 N+1"的竞态窗口。
     *
     * <p><b>兼容模式为什么是 {@code (:expectedVersion IS NULL OR version = :expectedVersion)}</b>
     * 而不是"两个 SQL 二选一"：一条语句只表达一种授权条件，
     * 若按是否有 expectedVersion 分流成两条 SQL，真库测试就只会覆盖到其中一条
     * （C9.5 要求兼容模式有测试固定其行为），且两条 SQL 的条件容易漂移。
     * 显式兼容模式仍会 {@code version = version + 1}，因此客户端总能自证版本前进。
     *
     * <p><b>为什么不写 {@code session_title}</b>：{@code title} 是统一读路径与两个写入口的
     * 唯一写目标（C9.1 / WP-038A），{@code session_title} 是历史保留列，只存不改。
     *
     * <p>包级可见是为了让真库测试执行**同一份** SQL 文本，而不是测试里再抄一遍——
     * 抄一遍就会出现"测试通过、生产 SQL 不同"的分叉。
     */
    static final String SQL_RENAME_CONVERSATION =
            "UPDATE platform.ai_conversation SET title = :title, version = version + 1, update_time = now()"
                    + " WHERE tenant_id = :tenant AND member_id = :member AND conversation_id = :conversation"
                    + " AND deleted = 0"
                    // G-52f / C9 阻塞修复：`IS NULL` 一侧的裸参数**没有类型语境**。
                    // NamedParameterJdbcTemplate 会把 `:expectedVersion` 展开成 `?`，
                    // 于是 PG 收到 `($5 IS NULL OR version = $6)` —— **$5 无从推断类型**，
                    // 只要该参数绑定为 NULL（请求没带 expectedVersion）就必然
                    // `ERROR: could not determine data type of parameter $5` ⇒ BadSqlGrammarException
                    // ⇒ 网关把它洗成 503「授权服务不可用」。
                    // 显式转型给 $5 一个类型语境；第二个（`version = ?`）本来就有列类型语境，不需要动。
                    // 用 bigint 而非 integer：入参是 Java `Long`（renameConversation 的 expectedVersion），
                    // 且 bigint 与 integer 列比较时 PG 会隐式加宽，两种列型都安全。
                    + " AND (CAST(:expectedVersion AS bigint) IS NULL OR version = :expectedVersion)";

    /**
     * 乐观锁冲突探测：会话是否存在/可见，以及它当前的 {@code version}。
     *
     * <p>只用于把"0 行"区分为 409（版本冲突）与 404（不存在/无权/已删除）。
     * 读到的版本**不**参与写入判定——写入仍由 {@link #SQL_RENAME_CONVERSATION} 的
     * {@code version = :expectedVersion} 条件裁决，所以这里不存在 TOCTOU：
     * 探测与写入之间的并发改名只会让 UPDATE 影响 0 行，进而在下一次探测里变成 409。
     */
    static final String SQL_CONVERSATION_VERSION =
            "SELECT version FROM platform.ai_conversation"
                    + " WHERE tenant_id = :tenant AND member_id = :member AND conversation_id = :conversation"
                    + " AND deleted = 0";

    /** 会话删除 SQL：软删（{@code deleted = 1}），不物理删，保留消息与审计链。 */
    static final String SQL_SOFT_DELETE_CONVERSATION =
            "UPDATE platform.ai_conversation SET deleted = 1, update_time = now()"
                    + " WHERE tenant_id = :tenant AND member_id = :member AND conversation_id = :conversation"
                    + " AND deleted = 0";

    /** 会话标题上限，与冻结形状 {@code ai_conversation.title VARCHAR(128)} 一致。 */
    static final int CONVERSATION_TITLE_MAX = 128;

    /**
     * 重命名会话（F03）。0 行受影响 = 不存在 / 不属于本租户本成员 / 已删除——一律按"不存在"拒绝，
     * 不泄露存在性（与 {@link #tombstoneKnowledgeBase} 同一语义）。
     *
     * <p><b>为什么不 bump ACL epoch。</b>{@code bumpEpochOrRefuse} 是给**授权**变更用的
     * （KB 建/删/ACL 授予/撤销）：它会把租户的 {@code ai_acl_epoch} 递增，从而使所有在途 permit 的
     * {@code aclVersion} 立即过期。会话改名是**内容**变更，不是授权变更；为一次改名把全租户在途
     * run 判成 {@code StaleVersionException} 是过度的副作用。删除同理——被删会话在读取路径上
     * 因 {@code deleted = 0} 过滤而立即不可见，引用它的产物按 fail-closed 404，
     * 这正是"撤权后不再可见"的既有语义，不需要靠 epoch 实现。
     */
    public void renameConversation(String conversationId, String title) {
        renameConversation(conversationId, title, null);
    }

    /**
     * 重命名会话（F03）＋ D10 乐观锁。
     *
     * <p>协议（C9.2）：
     * <ul>
     *   <li>{@code expectedVersion} 提供且匹配 → 更新，{@code version = version + 1}，返回新版本；</li>
     *   <li>{@code expectedVersion} 提供且不匹配 → {@code RESOURCE_VERSION_CONFLICT}(409)，<b>不写入</b>；</li>
     *   <li>{@code expectedVersion} 缺失/null → 显式兼容模式：无条件更新，仍自增版本并返回新版本。</li>
     * </ul>
     *
     * <p>0 行且未携带 {@code expectedVersion} → 保持既有 404 语义（不存在 / 不属于本租户本成员 / 已删除）。
     * 0 行且携带了 {@code expectedVersion} → 再探一次：行可见就是版本冲突 409，行不可见仍是 404。
     * 这样"并发改名失败方"拿到的是 409 而不是被误报成"资源不存在"。
     *
     * @param expectedVersion 期望版本；null = 显式兼容模式（受约束的过渡路径，见 C9.5）
     * @return 更新后的新版本
     */
    public long renameConversation(String conversationId, String title, Long expectedVersion) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "conversation id required");
        }
        if (title == null || title.isBlank()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "title required");
        }
        if (title.length() > CONVERSATION_TITLE_MAX) {
            // 不截断：超长标题必须显式拒绝，否则会出现"存进去的和用户看到的不是一回事"
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "title too long");
        }
        if (expectedVersion != null && expectedVersion < 0) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "expectedVersion must not be negative");
        }
        ExecutionPrincipal principal = PrincipalContext.require();
        String tenantId = principal.tenantId();
        String memberId = principal.membershipId();
        return write(principal, "conversation.rename", "conv:" + conversationId, status -> {
            int rows = jdbc.update(SQL_RENAME_CONVERSATION, conversationParams(title, tenantId, memberId,
                    conversationId, expectedVersion));
            if (rows > 0) {
                return currentVersion(tenantId, memberId, conversationId);
            }
            if (expectedVersion == null) {
                throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            }
            Long live = currentVersionOrNull(tenantId, memberId, conversationId);
            if (live == null) {
                // 会话不可见：即使带了 expectedVersion 也只是"不存在"，不制造 409 噪音
                throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            }
            throw new P04AiException(P04AiErrorCode.RESOURCE_VERSION_CONFLICT,
                    "conversation version conflict: expected " + expectedVersion + " but current is " + live);
        });
    }

    /** 读取可见会话的当前 {@code version}；不可见时返回 null。 */
    public Long currentVersionOrNull(String tenantId, String memberId, String conversationId) {
        // 参数顺序必须是 (sql, params, rowMapper)：NamedParameterJdbcTemplate 没有
        // (sql, rowMapper, params) 这个重载，写成那个顺序会以"找不到合适的方法"整模块编译失败
        // （T2 实证：2026-10-06 01:23 的全模块 test-compile 在此处中断）。
        var rows = jdbc.query(SQL_CONVERSATION_VERSION,
                Map.of("tenant", tenantId, "member", memberId, "conversation", conversationId),
                (rs, rowNum) -> rs.getLong(1));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long currentVersion(String tenantId, String memberId, String conversationId) {
        Long version = currentVersionOrNull(tenantId, memberId, conversationId);
        if (version == null) {
            // 刚更新过却读不到：并发软删。按不存在处理，不返回凭空的版本号。
            throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        return version;
    }

    private static Map<String, Object> conversationParams(String title, String tenantId, String memberId,
                                                          String conversationId, Long expectedVersion) {
        // HashMap 而非 Map.of：expectedVersion 为 null 时 Map.of 会抛 NPE，
        // 而"缺失/null = 兼容模式"正是必须走通的路径。
        Map<String, Object> params = new HashMap<>();
        params.put("title", title);
        params.put("tenant", tenantId);
        params.put("member", memberId);
        params.put("conversation", conversationId);
        params.put("expectedVersion", expectedVersion);
        return params;
    }

    /** 删除会话（F03）：软删，0 行受影响按"不存在"拒绝。语义与 {@link #renameConversation} 一致。 */
    public void deleteConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "conversation id required");
        }
        ExecutionPrincipal principal = PrincipalContext.require();
        String tenantId = principal.tenantId();
        String memberId = principal.membershipId();
        write(principal, "conversation.delete", "conv:" + conversationId, status -> {
            int rows = jdbc.update(SQL_SOFT_DELETE_CONVERSATION, Map.of(
                    "tenant", tenantId, "member", memberId, "conversation", conversationId));
            if (rows == 0) {
                throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            }
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
                + " FROM platform.ai_knowledge_base"
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
        String sql = "SELECT id, kb_id, doc_name, status, enabled, chunk_count FROM platform.ai_knowledge_document"
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
