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

import com.nageoffer.ai.ragent.framework.exception.ServiceException;

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
 *   <li>{@link SubjectMatchPort}：<b>本地 ACL 存活判定</b>——候选主体必须在
 *       当前租户仍有未过期的 ACL 行；组织候选（成员/部门/角色）的在线核实走
 *       {@code PlatformFactsPort}：内嵌装配注入本地实现（直接读 platform 身份源，
 *       不经 localhost HTTP），独立运行时由 HTTP 实现回调 platform 的
 *       {@code /authorization/subjects/match}（services/ai 冻结拷贝）。
 *       撤权即删行 + epoch bump，本端口立刻收窄。</li>
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
        ResourceAuthorizationService.SubjectMatchPort,
        com.nageoffer.ai.ragent.framework.security.AuthorizedRetrievalScopeResolver<com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope> {

    private final AiResourceMapper resourceMapper;
    private final AiResourceAclMapper aclMapper;
    private final AiAclEpochMapper epochMapper;
    private final ResourceSourceRefMapper sourceRefMapper;
    private final Clock clock;
    private final DefaultResourceAuthorizationService delegate;
    private com.nageoffer.ai.ragent.framework.security.AuthorizationChecker platformAuthorization;
    private com.nageoffer.ai.ragent.framework.security.PlatformFactsPort platformFacts;
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate projectionJdbc;

    @Autowired
    public void configureProjection(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        this.projectionJdbc = jdbc;
    }

    @Override
    public AuthorizedResourceScope resolve(ExecutionPrincipal principal, String action, Collection<String> requested) {
        return resolveScope(principal, action, requested);
    }

    @Override
    public com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope toRetrievalScope(AuthorizedResourceScope scope) {
        var principal = com.nageoffer.ai.ragent.framework.context.PrincipalContext.require();
        scope.requireStillValid(principal, principal.policyVersion(), currentAclVersion(principal.tenantId()));
        Set<String> kbs = new LinkedHashSet<>(), docs = new LinkedHashSet<>(), chunks = new LinkedHashSet<>(), collections = new LinkedHashSet<>();
        var kbRefs = scope.authorizedRefs().stream().filter(ref -> ref.startsWith("kb:")).toList();
        var kbVerdicts = checkBatch(principal, scope.action(), kbRefs);
        for (String ref : kbRefs) { if (kbVerdicts.get(ref) == Verdict.GRANT) { kbs.add(ref); } }
        if (kbs.isEmpty()) { return com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope.of(scope,kbs,docs,chunks,collections); }
        var docRefs = new LinkedHashSet<String>();
        if (kbs.size() == 1) { docRefs.addAll(childResourceRefs(principal.tenantId(), kbs.iterator().next())); }
        else {
            for (var row : sourceRefMapper.findChildrenByIds(principal.tenantId(), "KB",
                    kbs.stream().map(ref -> ref.substring(3)).toList())) {
                if ("DOCUMENT".equals(row.resourceType()) && "ACTIVE".equals(row.status())) {
                    docRefs.add(resourceRef(row.resourceType(), row.resourceId()));
                }
            }
        }
        docRefs.removeIf(ref -> !ref.startsWith("doc:"));
        var docVerdicts = checkBatch(principal, scope.action(), docRefs);
        for (String doc : docRefs) { if (docVerdicts.get(doc) == Verdict.GRANT) { docs.add(doc); } }
        if (!docs.isEmpty()) {
            if (projectionJdbc == null) { throw new ServiceException("retrieval projection unavailable"); }
            var parameters = Map.of("tenant",principal.tenantId(),"kbs",kbs.stream().map(ref->ref.substring(3)).toList(),
                    "docs",docs.stream().map(ref->ref.substring(4)).toList());
            projectionJdbc.query(P2PublishedChunkSql.projection(),parameters,(org.springframework.jdbc.core.RowCallbackHandler)rs->{
                        chunks.add("chunk:"+rs.getString("id"));collections.add(rs.getString("collection_name"));
                    });
            if (chunks.size()>10000) { throw new ServiceException("retrieval projection exceeds bounded scope"); }
        }
        scope.requireStillValid(principal,principal.policyVersion(),currentAclVersion(principal.tenantId()));
        return com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope.of(scope,kbs,docs,chunks,collections);
    }

    /** Derived data with absent, stale or unverifiable provenance is never returned to a model or a caller. */
    @Override
    public boolean sourcesCurrent(ExecutionPrincipal principal, String sourceRefs, int policyVersion, int aclVersion) {
        if (principal==null || sourceRefs==null || policyVersion!=principal.policyVersion() || aclVersion!=principal.aclVersion()) { return false; }
        if (currentAclVersion(principal.tenantId())!=aclVersion) { return false; }
        try {
            var refs=matchJson.readTree(sourceRefs);
            if (!refs.isArray() || refs.isEmpty() || refs.size()>200) { return false; }
            for(var item:refs){
                if(!item.isObject() || item.size()!=2 || !item.path("ref").isTextual() || !item.path("version").isIntegralNumber()
                        || !item.path("version").canConvertToInt() || item.path("version").intValue()<1){return false;}
                String ref=item.path("ref").textValue();
                var parsed=parseResourceRef(ref);
                var fact=resourceMapper.findByPk(principal.tenantId(),parsed.resourceType(),parsed.resourceId()).orElse(null);
                if(fact==null || !"ACTIVE".equals(fact.status()) || fact.resourceVersion()!=item.path("version").intValue()){return false;}
                String action=switch(parsed.resourceType()){case "KB"->"kb.read";case "DOCUMENT"->"document.read";case "CONVERSATION"->"conversation.read";default->null;};
                if(action==null){return false;}
                // Dependency checks use server facts and a fresh online function check for the source action.
                var sourcePrincipal=new ExecutionPrincipal(principal.tenantId(),principal.userId(),principal.membershipId(),
                        principal.policyVersion(),principal.aclVersion(),Set.of(action),principal.jti(),principal.issuer(),
                        principal.issuedAtEpochSecond(),principal.expiresAtEpochSecond());
                var verdict=check(sourcePrincipal,action,ref);
                if(verdict==Verdict.UNKNOWN){throw new ServiceException("source authorization unavailable");}
                if(verdict!=Verdict.GRANT){return false;}
            }
            return true;
        }catch(ServiceException e){throw e;}catch(Exception e){return false;}
    }
    private final com.fasterxml.jackson.databind.ObjectMapper matchJson = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * platform 主体/组织事实端口。内嵌装配提供本地实现（直接读 platform 身份源）；
     * 端口缺席时保持"仅本地 ACL 存活判定"的独立运行形态（与迁移前
     * {@code ai.integration.platform-base-url} 未配置时的行为一致）。
     */
    @Autowired(required = false)
    public void configurePlatformFacts(org.springframework.beans.factory.ObjectProvider<
            com.nageoffer.ai.ragent.framework.security.PlatformFactsPort> facts) {
        this.platformFacts = facts == null ? null : facts.getIfAvailable();
    }

    @Override
    public MatchResult match(ExecutionPrincipal principal, Collection<String> subjectRefs, int policyVersion, String action) {
        if (platformFacts == null) {
            // 无平台事实端口：独立运行的本地 ACL 存活判定（迁移前 platform-base-url 未配置的同一形态）
            return match(principal, subjectRefs, policyVersion);
        }
        var refs = List.copyOf(subjectRefs);
        var candidates = refs.stream().map(ref ->
                com.nageoffer.ai.ragent.framework.security.PlatformFactsPort.Candidate.subjectRefs(List.of(ref))).toList();
        var outcome = platformFacts.match(principal, candidates, action);
        requireMatchOutcome(outcome, refs.size(), policyVersion);
        Set<String> matched = new LinkedHashSet<>();
        for (int i = 0; i < refs.size(); i++) {
            if (Boolean.TRUE.equals(outcome.matches().get(i))) {
                matched.add(refs.get(i));
            }
        }
        return new MatchResult(matched, policyVersion);
    }

    /** 平台事实回执形状校验：版本与候选数必须精确对应，任一不符即拒绝（不放行）。 */
    private static void requireMatchOutcome(com.nageoffer.ai.ragent.framework.security.PlatformFactsPort.MatchOutcome outcome,
                                            int expected, int policyVersion) {
        if (outcome == null || outcome.policyVersion() != policyVersion
                || outcome.matches() == null || outcome.matches().size() != expected) {
            throw new ServiceException("platform facts response invalid");
        }
        for (Boolean value : outcome.matches()) {
            if (value == null) {
                throw new ServiceException("platform facts response invalid");
            }
        }
    }

    @Autowired
    public void configurePlatformAuthorization(com.nageoffer.ai.ragent.framework.security.AuthorizationChecker checker) {
        this.platformAuthorization = checker;
    }

    private void requirePlatform(ExecutionPrincipal principal, String action, String ref) {
        if (platformAuthorization != null) {
            if (!principal.hasScope(action)) {
                throw new com.nageoffer.ai.ragent.framework.security.P04AiException(
                        com.nageoffer.ai.ragent.framework.security.P04AiErrorCode.FORBIDDEN);
            }
            var result = platformAuthorization.check(new com.nageoffer.ai.ragent.framework.security.DelegatedPrincipal(
                    principal.issuer(), principal.tenantId(), principal.userId(), principal.membershipId(),
                    principal.policyVersion(), principal.scopes(), principal.jti()), action, ref);
            if (!result.allowed() || result.policyVersion() != principal.policyVersion()) {
                throw new ServiceException("platform authorization unavailable");
            }
        }
    }
    public void requireFunction(ExecutionPrincipal principal,String action,String ref){requirePlatform(principal,action,ref);}

    /**
     * 逐候选读取平台事实；端口缺席即事实不可得（ServiceException，绝不默认放行）。
     * 回执形状与版本精确校验，任一不符按 UNKNOWN 收敛。
     */
    private com.nageoffer.ai.ragent.framework.security.PlatformFactsPort.MatchOutcome queryFacts(
            ExecutionPrincipal principal, String action,
            List<com.nageoffer.ai.ragent.framework.security.PlatformFactsPort.Candidate> candidates) {
        if (platformFacts == null) { throw new ServiceException("platform candidate facts unavailable"); }
        var outcome = platformFacts.match(principal, candidates, action);
        requireMatchOutcome(outcome, candidates.size(), principal.policyVersion());
        return outcome;
    }

    public String currentOwnerDept(ExecutionPrincipal principal,String action) {
        var outcome=queryFacts(principal,action,List.of());
        var dept=outcome.principalDeptId();
        if(dept==null || dept.isBlank()){return null;}
        if(!dept.matches("[0-9]{1,19}")){throw new ServiceException("platform owner facts invalid");}
        return dept;
    }
    public void requireCurrentSubject(ExecutionPrincipal principal,String subjectRef){
        var outcome=queryFacts(principal,"kb.acl.manage",
                List.of(com.nageoffer.ai.ragent.framework.security.PlatformFactsPort.Candidate.validateOnly(subjectRef)));
        if(!Boolean.TRUE.equals(outcome.matches().get(0))){throw new com.nageoffer.ai.ragent.framework.security.P04AiException(
                com.nageoffer.ai.ragent.framework.security.P04AiErrorCode.BAD_REQUEST,"acl subject is not a current tenant member or organization");}
    }

    private boolean dataScopeAllows(ExecutionPrincipal principal,String action,String ref) {
        if(platformFacts==null){return true;}
        var candidates=new ArrayList<com.nageoffer.ai.ragent.framework.security.PlatformFactsPort.Candidate>();
        Set<String> visited=new LinkedHashSet<>();
        String current=ref;
        while(current!=null){
            if(!visited.add(current) || visited.size()>32){return false;}
            var parsed=parseResourceRef(current);
            var row=resourceMapper.findByPk(principal.tenantId(),parsed.resourceType(),parsed.resourceId()).orElse(null);
            if(row==null || !"ACTIVE".equals(row.status())){return false;}
            candidates.add(com.nageoffer.ai.ragent.framework.security.PlatformFactsPort.Candidate
                    .dataScope(row.ownerMemberId(),row.ownerDeptId()));
            current=row.parentType()==null?null:resourceRef(row.parentType(),row.parentId());
        }
        for(var matched:queryFacts(principal,action,candidates).matches()){
            if(!matched.booleanValue()){return false;}
        }
        return true;
    }

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
        requirePlatform(principal, action, requested == null || requested.isEmpty() ? "tenant:resources" : requested.iterator().next());
        var resolved=delegate.resolveScope(principal, action, requested);
        var refs=filterDataScope(principal, action, resolved.authorizedRefs());
        return AuthorizedResourceScope.granted(principal,action,refs,clock.millis());
    }

    @Override
    public Verdict check(ExecutionPrincipal principal, String action, String resourceRef) {
        requirePlatform(principal, action, resourceRef);
        var verdict=delegate.check(principal, action, resourceRef);
        return verdict==Verdict.GRANT && !dataScopeAllows(principal,action,resourceRef)?Verdict.DENY:verdict;
    }

    @Override
    public Map<String, Verdict> checkBatch(ExecutionPrincipal principal, String action,
                                           Collection<String> resourceRefs) {
        if (resourceRefs == null || resourceRefs.isEmpty()) { return Map.of(); }
        if (resourceRefs.size() == 1) {
            String ref = resourceRefs.iterator().next();
            return Map.of(ref, check(principal, action, ref));
        }
        requirePlatform(principal, action, "tenant:resources");
        Map<String,Verdict> result = new LinkedHashMap<>(delegate.checkBatch(principal, action, resourceRefs));
        var granted = result.entrySet().stream().filter(entry -> entry.getValue() == Verdict.GRANT)
                .map(Map.Entry::getKey).toList();
        var allowed = new LinkedHashSet<>(filterDataScope(principal, action, granted));
        for (String ref : granted) { if (!allowed.contains(ref)) { result.put(ref, Verdict.DENY); } }
        return result;
    }

    private List<String> filterDataScope(ExecutionPrincipal principal, String action, Collection<String> refs) {
        if (platformFacts == null || refs.isEmpty()) { return List.copyOf(refs); }
        if (refs.size() == 1) { return refs.stream().filter(ref -> dataScopeAllows(principal, action, ref)).toList(); }
        // Fresh facts per check: no allow decision is cached across calls or authorization epochs.
        var all = new LinkedHashMap<>(facts(principal.tenantId(), refs));
        var frontier = new LinkedHashSet<>(refs);
        for (int depth = 0; depth < 32 && !frontier.isEmpty(); depth++) {
            var parents = new LinkedHashSet<String>();
            for (String ref : frontier) {
                var fact = all.get(ref);
                if (fact != null && fact.parentRef() != null && !all.containsKey(fact.parentRef())) {
                    parents.add(fact.parentRef());
                }
            }
            if (parents.isEmpty()) { break; }
            all.putAll(facts(principal.tenantId(), parents));
            frontier = parents;
        }
        var candidates = new ArrayList<com.nageoffer.ai.ragent.framework.security.PlatformFactsPort.Candidate>();
        var owners = new ArrayList<String>();
        var allowed = new LinkedHashSet<>(refs);
        for (String ref : refs) {
            var visited = new LinkedHashSet<String>();
            String current = ref;
            while (current != null) {
                var fact = all.get(current);
                if (!visited.add(current) || visited.size() > 32 || fact == null || !"ACTIVE".equals(fact.status())) {
                    allowed.remove(ref); break;
                }
                candidates.add(com.nageoffer.ai.ragent.framework.security.PlatformFactsPort.Candidate
                        .dataScope(fact.ownerMemberId(), fact.ownerDeptId()));
                owners.add(ref);
                current = fact.parentRef();
            }
        }
        for (int offset = 0; offset < candidates.size(); offset += 200) {
            var batch = candidates.subList(offset, Math.min(offset + 200, candidates.size()));
            var matches = queryFacts(principal, action, batch).matches();
            for (int i = 0; i < matches.size(); i++) {
                if (!matches.get(i)) { allowed.remove(owners.get(offset + i)); }
            }
        }
        return refs.stream().filter(allowed::contains).toList();
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
        ExecutionPrincipal.requireTenantId(tenantId);
        Map<String, ResourceFact> result = new LinkedHashMap<>();
        List<AiResourceRow> rows = new ArrayList<>();
        if (resourceRefs == null || resourceRefs.isEmpty()) {
            // 空候选 = resolveScope 的"全部已授权资源"候选来源：本租户全部 ACTIVE 注册行。
            // 后续每行仍走完整判定（tombstone / ACL 交集），这里不是放行清单。
            rows.addAll(resourceMapper.listActive(tenantId));
        } else {
            var seen = resourceRefs.stream().filter(ref -> ref != null && !ref.isBlank()).distinct().toList();
            if (seen.size() == 1) {
                var parsed = parseResourceRef(seen.get(0));
                resourceMapper.findByPk(tenantId, parsed.resourceType(), parsed.resourceId()).ifPresent(rows::add);
            } else if (!seen.isEmpty()) {
                rows.addAll(resourceMapper.findByIds(tenantId, idsByType(seen)));
            }
        }
        rows.removeIf(row -> !Set.of(AiResourceMapper.TYPE_KB, AiResourceMapper.TYPE_DOCUMENT, "CONVERSATION", "RUN")
                .contains(row.resourceType()));
        Map<String, List<AclRow>> rulesByRef = new LinkedHashMap<>();
        if (rows.size() > 1) {
            var refs = rows.stream().map(row -> resourceRef(row.resourceType(), row.resourceId())).toList();
            for (var acl : aclMapper.findByIds(tenantId, idsByType(refs))) {
                rulesByRef.computeIfAbsent(resourceRef(acl.resourceType(), acl.resourceId()), ignored -> new ArrayList<>()).add(acl);
            }
        }
        for (AiResourceRow row : rows) {
            String ref = resourceRef(row.resourceType(), row.resourceId());
            var rules = rows.size() > 1 ? rulesByRef.getOrDefault(ref, List.of())
                    : aclMapper.findByResource(tenantId, row.resourceType(), row.resourceId());
            result.put(ref, toFact(ref, tenantId, row, rules));
        }
        return result;
    }

    /** 组装单条资源事实：规则行（含已过期，过期由判定本体按时钟处理）+ 父引用。 */
    private static Map<String, List<String>> idsByType(Collection<String> refs) {
        var ids = new LinkedHashMap<String, List<String>>();
        for (String ref : refs) {
            var parsed = parseResourceRef(ref);
            ids.computeIfAbsent(parsed.resourceType(), ignored -> new ArrayList<>()).add(parsed.resourceId());
        }
        return ids;
    }

    private ResourceFact toFact(String ref, String tenantId, AiResourceRow row, List<AclRow> aclRows) {
        List<AclRule> rules = new ArrayList<>();
        for (AclRow aclRow : aclRows) {
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
            // 本地 MEMBER/TENANT_ALL 必须匹配当前成员；组织候选需平台实时事实。
            if (ref != null && liveSubjects.contains(ref) && (platformAuthorization == null
                    || ref.equals("member:" + principal.membershipId())
                    || ref.equals("tenant_all:" + principal.tenantId()))) {
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
                if (!Set.of(AiResourceMapper.TYPE_KB, AiResourceMapper.TYPE_DOCUMENT, "CONVERSATION").contains(child.resourceType())) { continue; }
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
            case "CONVERSATION" -> "conv";
            case "RUN" -> "run";
            default -> throw new IllegalArgumentException("unsupported resourceType: " + resourceType);
        };
        return prefix + ":" + resourceId;
    }

    /** 编码后的资源类型（小写前缀）→ DB resource_type。 */
    public static String resourceTypeOfPrefix(String prefix) {
        return switch (prefix) {
            case "kb" -> AiResourceMapper.TYPE_KB;
            case "doc" -> AiResourceMapper.TYPE_DOCUMENT;
            case "conv" -> "CONVERSATION";
            case "run" -> "RUN";
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
