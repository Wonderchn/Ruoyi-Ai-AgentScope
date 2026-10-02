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

import com.nageoffer.ai.ragent.authorization.dao.AiAclEpochMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceAclMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper;
import com.nageoffer.ai.ragent.authorization.dao.ResourceSourceRefMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper.AiResourceRow;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceAclMapper.AclRow;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.DefaultResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.AuthorizedResourceScope;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 授权域组装点（P1.3a）：把 {@code ai_resource / ai_resource_acl / ai_acl_epoch}
 * 三个 DAO 组装成 framework 的 {@link ResourceAuthorizationService}。
 *
 * <p>本类同时是两个端口的 rag 侧唯一实现：
 * <ul>
 *   <li>{@link FactPort}：从 {@link AiResourceMapper} 读资源事实、从
 *       {@link AiResourceAclMapper} 读规则行，父链 tombstone 传播所需的 parent
 *       状态随事实一起给出；</li>
 *   <li>{@link SubjectMatchPort}：本单元为<b>本地 ACL 存活判定</b>——候选主体必须
 *       在当前租户仍有未过期的 ACL 行；platform 组织候选匹配（成员/部门/角色的
 *       在线核实）在后续单元接线。撤权即删行 + epoch bump，本端口立刻收窄。</li>
 * </ul>
 * 判定本体在 {@link DefaultResourceAuthorizationService}（交集/tombstone/expiry/epoch
 * 已实现并通过单测），这里只做事实装配：<code>new DefaultResourceAuthorizationService(this, this, clock)</code>。
 *
 * <p>装配默认关闭（{@code ai.integration.enabled=false}）：授权链未接线时，
 * 检索层按"没有授权"拒绝，而不是把未接线的服务注册成半个授权。
 *
 * <p>资源引用编码（registry 生成，framework 只消费）：{@code kb:<id>} / {@code doc:<id>}；
 * 主体引用编码沿用 framework 约定：{@code member:platform:T1:2101}、{@code department:1102}、
 * {@code role:9101}、{@code tenant_all:<tenantId>}（DB 里 TENANT_ALL 的 subject_id 必空，
 * 编码层补租户仅为可读）。
 */
@Component
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiResourceAuthorizationService
        implements ResourceAuthorizationService, ResourceAuthorizationService.FactPort,
        ResourceAuthorizationService.SubjectMatchPort {

    private final AiResourceMapper resourceMapper;
    private final AiResourceAclMapper aclMapper;
    private final AiAclEpochMapper epochMapper;
    private final ResourceSourceRefMapper sourceRefMapper;
    private final Clock clock;
    private final DefaultResourceAuthorizationService delegate;

    @Autowired
    public AiResourceAuthorizationService(AiResourceMapper resourceMapper,
                                          AiResourceAclMapper aclMapper,
                                          AiAclEpochMapper epochMapper,
                                          ResourceSourceRefMapper sourceRefMapper,
                                          ObjectProvider<Clock> clockProvider) {
        this(resourceMapper, aclMapper, epochMapper, sourceRefMapper,
                clockProvider == null ? Clock.systemUTC() : clockProvider.getIfAvailable(Clock::systemUTC));
    }

    /**
     * 显式装配入口：测试与集成装配用固定时钟直接构造，不经过 Spring。
     */
    public AiResourceAuthorizationService(AiResourceMapper resourceMapper,
                                          AiResourceAclMapper aclMapper,
                                          AiAclEpochMapper epochMapper,
                                          ResourceSourceRefMapper sourceRefMapper,
                                          Clock clock) {
        this.resourceMapper = resourceMapper;
        this.aclMapper = aclMapper;
        this.epochMapper = epochMapper;
        this.sourceRefMapper = sourceRefMapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.delegate = new DefaultResourceAuthorizationService(this, this, this.clock);
    }

    // ------------------------------------------------------------ 对外判定（委托判定本体）

    @Override
    public AuthorizedResourceScope resolveScope(ExecutionPrincipal principal, String action,
                                                Collection<String> requested) {
        return delegate.resolveScope(principal, action, requested);
    }

    @Override
    public Verdict check(ExecutionPrincipal principal, String action, String resourceRef) {
        return delegate.check(principal, action, resourceRef);
    }

    @Override
    public Map<String, Verdict> checkBatch(ExecutionPrincipal principal, String action,
                                           Collection<String> resourceRefs) {
        return delegate.checkBatch(principal, action, resourceRefs);
    }

    @Override
    public int currentAclVersion(String tenantId) {
        return delegate.currentAclVersion(tenantId);
    }

    // ------------------------------------------------------------ FactPort

    @Override
    public java.util.Optional<Integer> aclVersion(String tenantId) {
        // 行不存在返回 empty：framework 侧会拒绝并抛 ServiceException，绝不默认 1
        return epochMapper.findVersion(tenantId);
    }

    @Override
    public Map<String, ResourceFact> facts(String tenantId, Collection<String> resourceRefs) {
        Map<String, ResourceFact> result = new LinkedHashMap<>();
        List<AiResourceRow> rows = new ArrayList<>();
        if (resourceRefs == null || resourceRefs.isEmpty()) {
            // 空候选 = resolveScope 的"全部已授权资源"候选来源：本租户全部 ACTIVE 注册行。
            // 后续每行仍走完整判定（tombstone / ACL 交集），这里不是放行清单。
            rows.addAll(resourceMapper.listActive(tenantId));
        } else {
            Set<String> seen = new LinkedHashSet<>();
            for (String ref : resourceRefs) {
                if (ref == null || ref.isBlank() || !seen.add(ref)) {
                    continue;
                }
                ParsedRef parsed = parseResourceRef(ref);
                resourceMapper.findByPk(tenantId, parsed.resourceType(), parsed.resourceId())
                        .ifPresent(rows::add);
            }
        }
        for (AiResourceRow row : rows) {
            String ref = resourceRef(row.resourceType(), row.resourceId());
            result.put(ref, toFact(ref, tenantId, row));
        }
        return result;
    }

    /** 组装单条资源事实：规则行（含已过期，过期由判定本体按时钟处理）+ 父引用。 */
    private ResourceFact toFact(String ref, String tenantId, AiResourceRow row) {
        List<AclRule> rules = new ArrayList<>();
        for (AclRow aclRow : aclMapper.findByResource(tenantId, row.resourceType(), row.resourceId())) {
            rules.add(new AclRule(ref,
                    subjectTypeCode(aclRow.subjectType()),
                    aclRow.subjectType().equals(DB_SUBJECT_TENANT_ALL) ? tenantId : aclRow.subjectId(),
                    aclRow.action(),
                    aclRow.expiresAtEpochSecond()));
        }
        String parentRef = row.parentType() == null || row.parentId() == null
                ? null
                : resourceRef(row.parentType(), row.parentId());
        return new ResourceFact(ref,
                row.resourceType(),
                parentRef,
                row.ownerMemberId(),
                row.ownerDeptId(),
                row.status(),
                (int) Math.min(row.resourceVersion(), Integer.MAX_VALUE),
                rules);
    }

    // ------------------------------------------------------------ SubjectMatchPort

    @Override
    public MatchResult match(ExecutionPrincipal principal, Collection<String> subjectRefs, int policyVersion) {
        long now = clock.instant().getEpochSecond();
        Set<String> liveSubjects = new LinkedHashSet<>();
        for (AclRow row : aclMapper.listTenantRows(principal.tenantId())) {
            if (row.expiresAtEpochSecond() != null && row.expiresAtEpochSecond() <= now) {
                // 过期是时间判定：这条行不再让它的主体"存活"，不等定时清理
                continue;
            }
            liveSubjects.add(encodeSubjectRef(row.subjectType(), row.subjectId(), row.tenantId()));
        }
        Set<String> matched = new LinkedHashSet<>();
        for (String ref : subjectRefs) {
            if (ref != null && liveSubjects.contains(ref)) {
                matched.add(ref);
            }
        }
        // 本地实现无法核实 platform 的 pv：按"同一版本"回执，pv 的在线核实在
        // platform 集成单元接线；在此之前版本校验由 epoch（av）承担。
        return new MatchResult(matched, policyVersion);
    }

    // ------------------------------------------------------------ 继承链子资源（P1.3b 投影依赖）

    /**
     * 某父资源（如 KB）名下当前登记的子资源引用（只含 ACTIVE 行）。
     *
     * <p>父层 tombstone 时调用方不应再使用本方法结果——整体不可见优先于子行状态。
     */
    public List<String> childResourceRefs(String tenantId, String parentRef) {
        ParsedRef parsed = parseResourceRef(parentRef);
        List<String> refs = new ArrayList<>();
        for (ResourceSourceRefMapper.SourceRefRow child
                : sourceRefMapper.findChildren(tenantId, parsed.resourceType(), parsed.resourceId())) {
            if (AiResourceMapper.STATUS_ACTIVE.equals(child.status())) {
                refs.add(resourceRef(child.resourceType(), child.resourceId()));
            }
        }
        return refs;
    }

    // ------------------------------------------------------------ 引用编码（registry 单一来源）

    /** DB 侧主体类型字面量。 */
    public static final String DB_SUBJECT_MEMBER = "MEMBER";
    public static final String DB_SUBJECT_DEPT = "DEPT";
    public static final String DB_SUBJECT_ROLE = "ROLE";
    public static final String DB_SUBJECT_TENANT_ALL = "TENANT_ALL";

    /** 编码资源引用：{@code kb:<id>} / {@code doc:<id>}。 */
    public static String resourceRef(String resourceType, String resourceId) {
        String prefix = switch (resourceType) {
            case AiResourceMapper.TYPE_KB -> "kb";
            case AiResourceMapper.TYPE_DOCUMENT -> "doc";
            default -> throw new IllegalArgumentException("unsupported resourceType: " + resourceType);
        };
        return prefix + ":" + resourceId;
    }

    /** 编码后的资源类型（小写前缀）→ DB resource_type。 */
    public static String resourceTypeOfPrefix(String prefix) {
        return switch (prefix) {
            case "kb" -> AiResourceMapper.TYPE_KB;
            case "doc" -> AiResourceMapper.TYPE_DOCUMENT;
            default -> throw new IllegalArgumentException("unsupported resource ref: " + prefix);
        };
    }

    /** 编码主体引用：{@code member:<membershipId>} / {@code department:<id>} 等。 */
    public static String subjectRef(String dbSubjectType, String subjectId, String tenantId) {
        return encodeSubjectRef(dbSubjectType, subjectId, tenantId);
    }

    /** 解析资源引用为 (resourceType, resourceId)；解析不出即拒绝（RuntimeException → UNKNOWN）。 */
    public static ParsedRef parseResourceRef(String ref) {
        int idx = ref == null ? -1 : ref.indexOf(':');
        if (idx <= 0 || idx == ref.length() - 1) {
            throw new IllegalArgumentException("malformed resource ref: " + ref);
        }
        return new ParsedRef(resourceTypeOfPrefix(ref.substring(0, idx)), ref.substring(idx + 1));
    }

    /** 解析主体引用为 (dbSubjectType, subjectId 或 null)；TENANT_ALL 的 subject_id 在 DB 必空。 */
    public static ParsedSubject parseSubjectRef(String ref) {
        int idx = ref == null ? -1 : ref.indexOf(':');
        if (idx <= 0) {
            throw new IllegalArgumentException("malformed subject ref: " + ref);
        }
        String code = ref.substring(0, idx);
        String id = idx == ref.length() - 1 ? null : ref.substring(idx + 1);
        return new ParsedSubject(dbSubjectTypeOfCode(code),
                DB_SUBJECT_TENANT_ALL.equals(dbSubjectTypeOfCode(code)) ? null : id);
    }

    private static String subjectTypeCode(String dbSubjectType) {
        return switch (dbSubjectType) {
            case DB_SUBJECT_MEMBER -> "member";
            case DB_SUBJECT_DEPT -> "department";
            case DB_SUBJECT_ROLE -> "role";
            case DB_SUBJECT_TENANT_ALL -> "tenant_all";
            default -> throw new IllegalArgumentException("unsupported subjectType: " + dbSubjectType);
        };
    }

    /** DB 主体行 → framework 主体引用编码（TENANT_ALL 的 DB subject_id 为空，编码层补租户）。 */
    private static String encodeSubjectRef(String dbSubjectType, String subjectId, String tenantId) {
        return subjectTypeCode(dbSubjectType) + ":"
                + (DB_SUBJECT_TENANT_ALL.equals(dbSubjectType) ? tenantId : subjectId);
    }

    private static String dbSubjectTypeOfCode(String code) {
        return switch (code) {
            case "member" -> DB_SUBJECT_MEMBER;
            case "department" -> DB_SUBJECT_DEPT;
            case "role" -> DB_SUBJECT_ROLE;
            case "tenant_all" -> DB_SUBJECT_TENANT_ALL;
            default -> throw new IllegalArgumentException("unsupported subject ref: " + code);
        };
    }

    /** 解析后的资源引用。 */
    public record ParsedRef(String resourceType, String resourceId) {
    }

    /** 解析后的主体引用（TENANT_ALL 的 subjectId 为 null）。 */
    public record ParsedSubject(String subjectType, String subjectId) {
    }
}
