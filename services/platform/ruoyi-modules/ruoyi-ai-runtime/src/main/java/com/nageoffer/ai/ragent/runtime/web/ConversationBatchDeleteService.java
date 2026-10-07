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

package com.nageoffer.ai.ragent.runtime.web;

import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.authorization.TenantConversationReadRepository;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.framework.security.StaleVersionException;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * F03 普通会话（{@code platform.ai_conversation}）的受控批量删除服务端契约（D05）。
 *
 * <p><b>这是"一次请求删掉一个集合"的服务端契约，不是"前端多选后循环调单删"。</b>
 * 单资源删除逐条授权、逐条提交，满足的只是"每条各自合法"；批量删除的风险在
 * <b>集合</b>上，本类把三件事分别钉死（D05 原文：<i>同租户内的有界资源集合整体授权、
 * 整体事务，初始上限 100；重复 ID 和空集合拒绝；全部资源先校验，任一不可访问则整体失败，
 * 无部分成功。使用覆盖全部资源的批量 permit 或按确定顺序取得逐资源 permit，并复核 epoch。
 * 不得复用单资源 permit 授权 N 个对象；先完成服务端契约和负例再开放批量 UI</i>）：
 *
 * <ol>
 *   <li><b>集合形状</b>：{@code null} / 空 / 任一空白 ID / 重复 ID / 超过
 *       {@value #MAX_BATCH} 条 —— 一律 <b>整体拒绝</b>（400），<b>不截断、不去重、不静默跳过</b>。
 *       截断会让客户端以为"删完了"，去重会把"实际动作集合 ≠ 请求集合"藏起来；</li>
 *   <li><b>整体授权</b>：集合维度先做功能级判定（租户级 ref），再对<b>全部</b>资源做资源级
 *       判定（{@link AiResourceAuthorizationService#checkBatch} 一次批量读，不做 N 次逐条往返），
 *       全部通过才继续；</li>
 *   <li><b>无部分成功</b>：可见性预检（tenant + member + deleted=0）在<b>取得任何 permit 之前</b>
 *       对全部资源做完，任一不可见即整体失败 —— 该路径上数据库一行都没动，也没有 permit 需要回收；</li>
 *   <li><b>覆盖全部资源的批量 permit</b>（D05 明文允许的两种方案之一）：<b>一个</b> permit，
 *       其 {@code resourceRefsHash} 覆盖<b>全部</b>资源引用。刻意<b>不</b>复用单资源 permit
 *       去授权 N 个对象 —— 那会让"授权覆盖范围"与"实际动作范围"不一致；</li>
 *   <li><b>epoch 复核</b>：取得 permit 之后、写入之前再读一次当前主体，要求
 *       {@code policyVersion}/{@code aclVersion} 与进入时一致；不一致 ⇒ 整体放弃、释放 permit；</li>
 *   <li><b>整体事务</b>：N 行软删在<b>同一个数据库事务</b>内完成；实际影响行数不等于集合大小时
 *       （并发软删/预检后被撤权）抛错回滚，<b>整批一行都不留</b>。</li>
 * </ol>
 *
 * <p><b>与单资源删除（{@code AiResourceWriteService#deleteConversation}）的关系与差异。</b>
 * 两者是<b>同一张表、同一语义（{@code deleted = 1} 软删、visibility 由 {@code deleted = 0} 决定）</b>，
 * 删除 SQL 逐字一致（由 {@code ConversationBatchDeleteServiceTest} 的反射判据钉住，禁止漂移）。
 * 差异只有一处，<b>必须显式登记而不是含糊过去</b>：单资源路径走
 * {@code AiResourceWriteService#write(...)} 的"租户屏障 PENDING + drain + 单事务 + CLOSE"协议，
 * 而那个协议<b>在语义上无法承载批量</b>：它的 drain 判据是"租户内本 permit 之外的活跃 permit 数 = 0"，
 * 若批量先取 N 个 permit 再走该路径，N-1 个自己的 permit 会把 drain 永久挡住（30s 后 503）；
 * 若只取一个 permit 去覆盖 N 个资源，正是 D05 明令禁止的形态。
 * 因此本类按 D05 采用"覆盖全部资源的批量 permit + 单事务"，<b>不</b>复制/改写屏障协议；
 * 把批量入口并入 {@code AiResourceWriteService} 需要 T0 在授权域内新增集合级写入口
 * （见 RW-01 报告的"共享文件待集成补丁"）。
 */
public class ConversationBatchDeleteService {

    /** D05：初始上限 100。超限 <b>拒绝</b>，不截断。 */
    public static final int MAX_BATCH = 100;

    /** 删除动作：与网关 {@code conversation.delete} 路由动作、与 {@code ai:conversation:delete} 逐字一致。 */
    public static final String DELETE_ACTION = "conversation.delete";

    /** 集合维度的功能级判定 ref（与读列表 {@code AiResourceController#listConversations} 同一租户级 ref）。 */
    public static final String TENANT_REF = "tenant:conversations";

    /** 资源引用前缀（{@code AiResourceAuthorizationService.resourceRef("CONVERSATION", id)} 的产出形状）。 */
    private static final String CONVERSATION_REF_PREFIX = "conv:";

    /**
     * 会话软删 SQL。语义必须与 {@code AiResourceWriteService.SQL_SOFT_DELETE_CONVERSATION} 一致：
     * 只软删（{@code deleted = 1}），不物理删，保留消息与审计链；谓词恒带
     * {@code tenant_id + member_id + deleted = 0}（拿到 conversationId 不等于拿到写权）。
     * 逐字一致性由测试用反射读那一份常量比对，不允许悄悄漂移。
     */
    public static final String SQL_SOFT_DELETE_CONVERSATIONS =
            "UPDATE platform.ai_conversation SET deleted = 1, update_time = now()"
                    + " WHERE tenant_id = :tenant AND member_id = :member"
                    + " AND conversation_id IN (:conversations) AND deleted = 0";

    private final AiResourceAuthorizationService authorization;
    private final TenantConversationReadRepository conversations;
    private final ObjectProvider<RevocationGuard> revocations;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public ConversationBatchDeleteService(AiResourceAuthorizationService authorization,
                                          TenantConversationReadRepository conversations,
                                          ObjectProvider<RevocationGuard> revocations,
                                          NamedParameterJdbcTemplate jdbc,
                                          TransactionOperations transactions) {
        this.authorization = authorization;
        this.conversations = conversations;
        this.revocations = revocations;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /**
     * 一次受控批量删除的结果。
     *
     * @param deletedCount 实际软删的会话数（恒等于去重校验通过后的请求集合大小；重复已被拒绝）
     * @param permitCount  本次取得并释放的 permit 数。D05 的"覆盖全部资源的批量 permit"口径下恒为 1，
     *                     该字段存在的意义是<b>可断言</b>："没有按资源数取 permit，也没有复用一个单资源 permit"
     * @param permitId     本次批量 permit 的标识（响应不返回；仅供调用方/测试自证）
     */
    public record Outcome(int deletedCount, int permitCount, String permitId) {
    }

    /**
     * 执行一次受控批量删除。主体只取自 {@link PrincipalContext}（请求体不提供、也无法覆盖归属）。
     *
     * @throws RunApiException 400（空 / 空白 ID / 重复 / 超限）、401（缺主体）、403（功能级拒绝）、
     *                         404（任一资源不可见或未授权）、409（epoch 复核不一致）、
     *                         503（permit 基础设施或授权事实源不可用）
     */
    public Outcome deleteAll(List<String> conversationIds) {
        ExecutionPrincipal principal = PrincipalContext.get();
        if (principal == null) {
            // 缺主体不得退化成"无用户限定"（那正是跨租户读写的直接成因）
            throw new RunApiException(RunErrorCode.AUTH_REQUIRED);
        }

        // ① 集合形状：整体拒绝，不截断、不去重
        List<String> ordered = requireWellFormedSet(conversationIds);
        List<String> refs = ordered.stream().map(id -> CONVERSATION_REF_PREFIX + id).toList();

        // ② 功能级（集合维度）：租户级 ref
        translateVoid(() -> authorization.requireFunction(principal, DELETE_ACTION, TENANT_REF));

        // ③ 资源级：一次批量判定覆盖全部资源；任一非 GRANT 即整体失败（此时 0 permit、0 写入）
        //    判定结果缺席（null）按"未授权"处理 —— 事实源返回空不等于"放行全部"。
        Map<String, ResourceAuthorizationService.Verdict> verdicts = translate(
                () -> authorization.checkBatch(principal, DELETE_ACTION, refs));
        verdicts = verdicts == null ? Map.of() : verdicts;
        for (String ref : refs) {
            ResourceAuthorizationService.Verdict verdict = verdicts.get(ref);
            if (verdict == null || verdict == ResourceAuthorizationService.Verdict.DENY) {
                throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            }
            if (verdict == ResourceAuthorizationService.Verdict.STALE) {
                throw new RunApiException(RunErrorCode.VERSION_CONFLICT,
                        "aclVersion changed; refetch current versions and retry");
            }
            if (verdict == ResourceAuthorizationService.Verdict.UNKNOWN) {
                throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,
                        "resource authorization is unavailable; refusing to delete");
            }
        }

        // ④ 可见性预检：全部资源先校验，任一不可见整体失败 —— 此路径上没有任何 permit 被取得、没有任何行被写
        String tenantId = principal.tenantId();
        String memberId = principal.membershipId();
        for (String conversationId : ordered) {
            if (translate(() -> conversations.findConversation(tenantId, memberId, conversationId)).isEmpty()) {
                throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            }
        }

        // ⑤ 覆盖全部资源的批量 permit（D05：不得复用单资源 permit 授权 N 个对象）
        RevocationGuard guard = revocations.getIfAvailable();
        if (guard == null) {
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,
                    "delivery/revocation guard unavailable; refusing bulk mutation");
        }
        int policyVersion = principal.policyVersion();
        int aclVersion = principal.aclVersion();
        String operationId = UUID.randomUUID().toString();
        RevocationGuard.PermitGrant grant = translate(() -> guard.acquire(new RevocationGuard.PermitRequest(
                tenantId, memberId, DELETE_ACTION, policyVersion, aclVersion,
                batchRefsHash(refs), operationId, String.join(",", refs))));
        RevocationGuard.Operation operation = new RevocationGuard.Operation(guard, grant.permitId(), operationId);
        try {
            // ⑥ epoch 复核：写入前再读一次当前主体，版本变了就整体放弃
            ExecutionPrincipal current = PrincipalContext.get();
            if (current == null
                    || current.policyVersion() != policyVersion
                    || current.aclVersion() != aclVersion) {
                throw new RunApiException(RunErrorCode.VERSION_CONFLICT,
                        "policy/acl version changed during batch delete; nothing was deleted");
            }

            // ⑦ 整体事务：整批要么都删掉，要么一行都不动
            Integer deleted = transactions.execute(status -> {
                int rows = jdbc.update(SQL_SOFT_DELETE_CONVERSATIONS,
                        Map.of("tenant", tenantId, "member", memberId, "conversations", ordered));
                if (rows != ordered.size()) {
                    // 并发软删 / 预检后被撤权 / 谓词不匹配：不允许"部分成功"
                    throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN,
                            "batch delete affected " + rows + " of " + ordered.size()
                                    + " conversations; rolled back");
                }
                return rows;
            });
            return new Outcome(deleted == null ? 0 : deleted, 1, grant.permitId());
        } finally {
            operation.close();
        }
    }

    /**
     * 集合形状校验（D05 前三条）。返回<b>排序后</b>的列表：删除顺序确定是可复核性的前提
     * （同一集合的两次请求不会产生两种顺序），同时让 {@link #batchRefsHash} 的结果稳定。
     */
    static List<String> requireWellFormedSet(List<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "conversationIds must not be empty");
        }
        if (conversationIds.size() > MAX_BATCH) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST,
                    "conversationIds must not exceed " + MAX_BATCH + " entries");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String conversationId : conversationIds) {
            if (conversationId == null || conversationId.isBlank()) {
                throw new RunApiException(RunErrorCode.BAD_REQUEST, "conversationIds must not contain blank ids");
            }
            if (!seen.add(conversationId)) {
                throw new RunApiException(RunErrorCode.BAD_REQUEST, "conversationIds must not contain duplicates");
            }
        }
        // TreeSet 提供确定顺序；重复已在上面被拒绝，所以这里不会静默吞掉任何元素。
        return List.copyOf(new TreeSet<>(seen));
    }

    /**
     * 批量 permit 的 {@code resourceRefsHash}：<b>覆盖全部资源</b>的规范化摘要。
     *
     * <p>用换行连接（换行不是合法 ref 字符）而不是逗号拼接的"字符串拼接后 hash"，
     * 是为了避免 {refs=[a,b]} 与 {refs=[a+b]} 这类前缀歧义；集合本身已排序，
     * 因此同一集合恒得同一摘要，不同集合不会碰撞出同一摘要。
     */
    static String batchRefsHash(List<String> refs) {
        try {
            String canonical = String.join("\n", new TreeSet<>(refs));
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 把授权域/框架域抛出的已知失败翻译成运行面错误码，使本端点的错误契约<b>自包含且稳定</b>：
     * 不依赖调用方注册了哪个 {@code @ControllerAdvice}，也不会把 403/409 静默收敛成 500。
     *
     * <p>刻意<b>不</b>放行任何一类失败：翻译只改变错误的表达，不改变"是否拒绝"。
     */
    private static <T> T translate(java.util.concurrent.Callable<T> action) {
        try {
            return action.call();
        } catch (RunApiException e) {
            throw e;
        } catch (P04AiException e) {
            P04AiErrorCode code = e.errorCode();
            throw switch (code) {
                case FORBIDDEN -> new RunApiException(RunErrorCode.FORBIDDEN, e.getMessage());
                case RESOURCE_NOT_FOUND_OR_FORBIDDEN ->
                        new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, e.getMessage());
                case AUTH_REQUIRED, DELEGATION_INVALID -> new RunApiException(RunErrorCode.AUTH_REQUIRED, e.getMessage());
                case BAD_REQUEST -> new RunApiException(RunErrorCode.BAD_REQUEST, e.getMessage());
                case POLICY_VERSION_STALE, RESOURCE_VERSION_CONFLICT, RESOURCE_ID_CONFLICT ->
                        new RunApiException(RunErrorCode.VERSION_CONFLICT, e.getMessage());
                default -> new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE, e.getMessage());
            };
        } catch (StaleVersionException e) {
            throw new RunApiException(RunErrorCode.VERSION_CONFLICT,
                    "aclVersion changed; refetch current versions and retry");
        } catch (ClientException e) {
            // 缺主体/租户上下文：拒绝，不退化成"无用户限定"
            throw new RunApiException(RunErrorCode.TENANT_CONTEXT_MISSING);
        } catch (ServiceException e) {
            // 授权事实源不可用：503，不放行
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,
                    "resource authorization is unavailable; refusing to delete");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RunApiException(RunErrorCode.INTERNAL_ERROR);
        }
    }

    private static void translateVoid(Runnable action) {
        translate(() -> {
            action.run();
            return null;
        });
    }
}
