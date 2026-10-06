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
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;
import com.nageoffer.ai.ragent.framework.security.StaleVersionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 授权域内部端点（P1.3a）：{@code /internal/ai/v1} 下的 KB 元数据与 ACL 管理。
 *
 * <p><b>默认不装配</b>（{@code ai.integration.enabled=false}）：默认配置下本控制器
 * 不存在，404 由既有边界保证，不新增任何可达面。
 *
 * <p>错误外显（05 §4.2，HTTP status == body.code）：
 * <ul>
 *   <li>跨租户 / 无权 / 不存在：一律 404（body code=404），不泄露存在性；</li>
 *   <li>缺主体 / 动作未授权：403；</li>
 *   <li>版本陈旧（pv/av）：409；</li>
 *   <li>授权事实源不可用：503，不放行。</li>
 * </ul>
 * 判定统一走 {@link ResourceAuthorizationService}（DENY/STALE/UNKNOWN 收敛为
 * 404/409/503）；响应<b>不含</b>内部对象 key / 凭据。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiResourceController {

    /** 只读动作：KB 元数据与列表。 */
    public static final String ACTION_KB_READ = "kb.read";
    /** 删除动作（tombstone）。 */
    public static final String ACTION_KB_DELETE = "kb.delete";
    /** 文档元数据只读动作。 */
    public static final String ACTION_DOC_READ = "document.read";

    private final AiResourceAuthorizationService authorization;
    private final AiResourceWriteService writeService;

    public AiResourceController(AiResourceAuthorizationService authorization,
                                AiResourceWriteService writeService) {
        this.authorization = authorization;
        this.writeService = writeService;
    }

    private AuthorizedDownloadService downloads;
    private AuthorizedExportService exports;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private TenantConversationReadRepository conversations;
    private TenantRunReadRepository runs;
    private TenantEventReadRepository events;
    private com.nageoffer.ai.ragent.framework.security.RevocationGuard revocations;
    /**
     * (3) 交付 permit 泄漏修复：成功路径的 permit 交给请求级持有者，由
     * {@link AiDeliveryPermitConfiguration.DeliveryPermitInterceptor} 在 afterCompletion 单点释放。
     * 用 {@code @Autowired(required=false)} setter 注入：不改公共构造签名，切片里没有 holder 也不炸。
     */
    private AiDeliveryPermitConfiguration.DeliveryPermitHolder deliveryPermits;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureDeliveryPermits(AiDeliveryPermitConfiguration.DeliveryPermitHolder deliveryPermits){
        this.deliveryPermits=deliveryPermits;
    }
    private org.springframework.beans.factory.ObjectProvider<org.ruoyi.ai.api.runtime.AuthorizedRetrievalPort<com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope, com.nageoffer.ai.ragent.framework.convention.RetrievedChunk>> retrievers;
    private org.springframework.beans.factory.ObjectProvider<com.nageoffer.ai.ragent.ingest.EmbeddingGateway> p2Embeddings;
    @org.springframework.beans.factory.annotation.Autowired
    public void configureP2(org.springframework.beans.factory.ObjectProvider<com.nageoffer.ai.ragent.ingest.EmbeddingGateway> embeddings) {this.p2Embeddings=embeddings;}

    @org.springframework.beans.factory.annotation.Autowired
    public void configureExecution(com.nageoffer.ai.ragent.framework.security.RevocationGuard revocations,
            org.springframework.beans.factory.ObjectProvider<org.ruoyi.ai.api.runtime.AuthorizedRetrievalPort<com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope, com.nageoffer.ai.ragent.framework.convention.RetrievedChunk>> retrievers){
        this.revocations=revocations;this.retrievers=retrievers;
    }

    private <T> ResponseEntity<ApiEnvelope<T>> reply(String action,String ref,T data) {
        if(revocations==null){throw new ServiceException("delivery permit unavailable");}
        var operation=revocations.enter(PrincipalContext.require(),action,ref);
        // (3) 交付 permit 泄漏修复：登记给请求级持有者，成功路径不再需要人工释放；
        // 失败路径仍走下面的 operation.close()（两侧都幂等，见 AiDeliveryPermitConfiguration 注释）。
        if(deliveryPermits!=null){deliveryPermits.register(operation,operation.permitId(),operation.operationId());}
        try {
            return ResponseEntity.ok().header("Cache-Control","no-store")
                    .header("X-AI-Delivery-Permit",operation.permitId()).header("X-AI-Delivery-Operation",operation.operationId())
                    .body(ApiEnvelope.ok(data));
        }catch(RuntimeException e){operation.close();throw e;}
    }

    @GetMapping("/conversations")
    public ResponseEntity<ApiEnvelope<List<TenantConversationReadRepository.ConversationRow>>> listConversations(
            @RequestParam(defaultValue="0") long offset,@RequestParam(defaultValue="100") int limit){
        var principal=PrincipalContext.require();authorization.requireFunction(principal,"conversation.read","tenant:conversations");
        var rows=conversations.listConversations(principal.tenantId(),principal.membershipId(),offset,limit).stream()
                .filter(row->authorization.check(principal,"conversation.read","conv:"+row.conversationId())==Verdict.GRANT).toList();
        return reply("conversation.read","tenant:conversations",rows);
    }

    /**
     * 新建会话请求体（G-52）。
     *
     * <p>刻意只有标题一个字段：归属（{@code tenant_id}/{@code member_id}/{@code user_id}）
     * **只来自执行主体**，请求体不提供、也无法覆盖。缺标题由写服务的
     * {@code requireText} 拒绝为 {@code BAD_REQUEST}，不在这里另造一套校验。
     */
    public record CreateConversationRequest(String title) { }

    /**
     * 新建会话（F03 / G-52）。
     *
     * <p><b>授权顺序与读取、改名路径一致，但 ref 层级**有意**不同。</b>
     * 创建时**还没有 conversationId**，所以外层只能问"能不能在这个租户下建会话"：
     * {@code requireFunction(principal, "conversation.rename", "tenant:conversations")} ——
     * 与 {@code GET /conversations} 同一个 tenant 级 ref，动作换成写动作
     * {@code conversation.rename}。**内层**由写服务在拿到新 id 之后问"这个新资源归谁"：
     * {@code "conv:" + newId}（见 {@code AiResourceWriteService#createConversation}）。
     * **两层 ref 不同是刻意的**，不要为了"看起来一致"把它们改成同一个。
     *
     * <p><b>为什么这里没有 {@code requireGrant}。</b>资源级判定要求"资源已存在且已授予主体"，
     * 而创建时资源尚不存在；新资源的 owner ACL 由写服务在同一事务里落盘。写服务走
     * {@code write(...)} —— G-40 {@code ai.integration.high-risk.enabled} 守卫的**唯一**经过点，
     * 因此**未开启 high-risk 的实例上本接口 fail-closed 返回 503 是正确行为**，不是缺陷。
     */
    @PostMapping("/conversations")
    public ResponseEntity<ApiEnvelope<Map<String,Object>>> createConversation(
            @RequestBody(required=false) CreateConversationRequest request){
        var principal=PrincipalContext.require();
        authorization.requireFunction(principal,"conversation.rename","tenant:conversations");
        String conversationId = writeService.createConversation(
                new AiResourceWriteService.ConversationDraft(request==null?null:request.title()));
        // 与 createKnowledgeBase（:402）同形：**不调 reply(...)**。
        //
        // 理由（G-52d 实测，行级证据见报告 §7.4）：reply(...) 会在控制器层再登记一次交付 permit
        // （:106 的 revocations.enter），而创建在写服务内**已经 bump 过 ai_acl_epoch**（业务行 + registry
        // + owner ACL 已提交）⇒ 第二次登记拿同一主体的**旧 aclVersion** 去比较 ⇒ 409
        // 「aclVersion 已变化（N -> N+1），拒绝登记」⇒ **提交成功、响应失败**，客户端重试即造出重复会话。
        // 网关对 POST/PUT 走 JSON 转发路径、**不要求 X-AI-Delivery-* 回执头**（对照组：createKnowledgeBase
        // 同样不登记，实测 200）⇒ 创建不需要那次登记；**读路径的 reply(...) 一字未动**。
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("conversationId",conversationId,"created",true)));
    }

    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<ApiEnvelope<TenantConversationReadRepository.ConversationRow>> getConversation(@PathVariable String conversationId){
        var principal=PrincipalContext.require();requireGrant(principal,"conversation.read","conv:"+conversationId);
        var row=conversations.findConversation(principal.tenantId(),principal.membershipId(),conversationId)
                .orElseThrow(()->new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        return reply("conversation.read","conv:"+conversationId,row);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<ApiEnvelope<List<TenantConversationReadRepository.MessageRow>>> getMessages(@PathVariable String conversationId,
            @RequestParam(defaultValue="0") long offset,@RequestParam(defaultValue="100") int limit){
        var principal=PrincipalContext.require();requireGrant(principal,"conversation.read","conv:"+conversationId);
        if(conversations.findConversation(principal.tenantId(),principal.membershipId(),conversationId).isEmpty()){
            throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);}
        return reply("conversation.read","conv:"+conversationId,conversations.listMessages(principal.tenantId(),principal.membershipId(),conversationId,offset,limit));
    }

    /**
     * F03 重命名请求体（D10）。
     *
     * <p>只接受标题与期望版本两个字段，避免把 DTO 变成可越权改归属的入口。
     *
     * <p>{@code expectedVersion} 是 {@code Long} 而非 {@code long}：**必须能表达 null**，
     * 因为"缺失/null = 显式兼容模式"是 C9.2 明文规定的语义。用基本类型会把缺失静默变成 0，
     * 从而把每一次不带该字段的旧客户端改名判成 409——那是静默的行为破坏，不是兼容。
     */
    public record RenameConversationRequest(String title, Long expectedVersion) { }

    /**
     * 重命名会话（F03）＋ D10 乐观锁。
     *
     * <p>授权顺序与读取路径完全一致：先 {@code requireFunction}（功能级）再 {@code requireGrant}
     * （资源级）——两者都通过才进写服务；写服务内部还会再取一次 {@code PrincipalContext.require()}，
     * 所以主体不可能被调用方替换。0 行受影响且未携带 {@code expectedVersion} 时按"不存在"拒绝
     * （不泄露存在性），携带 {@code expectedVersion} 时由写服务区分 409/404。
     *
     * <p>响应携带**新的** {@code version}，使客户端可自证并用于下一次改名（C9.2/C9.5）。
     */
    @PutMapping("/conversations/{conversationId}")
    public ResponseEntity<ApiEnvelope<Map<String,Object>>> renameConversation(@PathVariable String conversationId,
            @RequestBody RenameConversationRequest request){
        var principal=PrincipalContext.require();
        authorization.requireFunction(principal,"conversation.rename","conv:"+conversationId);
        requireGrant(principal,"conversation.rename","conv:"+conversationId);
        Long expectedVersion = request==null?null:request.expectedVersion();
        long version = writeService.renameConversation(conversationId, request==null?null:request.title(), expectedVersion);
        return reply("conversation.rename","conv:"+conversationId,
                Map.of("conversationId",conversationId,"renamed",true,"version",version));
    }

    /** 删除会话（F03）：软删；授权顺序同上。 */
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<ApiEnvelope<Map<String,Object>>> deleteConversation(@PathVariable String conversationId){
        var principal=PrincipalContext.require();
        authorization.requireFunction(principal,"conversation.delete","conv:"+conversationId);
        requireGrant(principal,"conversation.delete","conv:"+conversationId);
        writeService.deleteConversation(conversationId);
        return reply("conversation.delete","conv:"+conversationId,Map.of("conversationId",conversationId,"deleted",true));
    }

    @GetMapping("/memories")
    public ResponseEntity<ApiEnvelope<List<Map<String,Object>>>> getMemories(@RequestParam(defaultValue="0") long offset,
            @RequestParam(defaultValue="100") int limit){
        var principal=PrincipalContext.require();authorization.requireFunction(principal,"memory.read","member:memories");
        if(offset<0 || limit<1 || limit>200){throw new P04AiException(P04AiErrorCode.BAD_REQUEST);}
        List<Map<String,Object>> rows=jdbc.query("SELECT id,content,source_refs,source_policy_version,source_acl_version FROM platform.ai_agent_memory"
                +" WHERE tenant_id=? AND member_id=? AND invalid_at IS NULL ORDER BY create_time,id LIMIT ? OFFSET ?",(rs,n)->{
                    if(!authorization.sourcesCurrent(principal,rs.getString("source_refs"),rs.getInt("source_policy_version"),rs.getInt("source_acl_version"))){return null;}
                    return Map.<String,Object>of("id",rs.getString("id"),"content",rs.getString("content"));
                },principal.tenantId(),principal.membershipId(),limit,offset).stream().filter(java.util.Objects::nonNull).toList();
        return reply("memory.read","member:memories",rows);
    }

    private TenantRunReadRepository.RunRow visibleRun(ExecutionPrincipal principal,String runId,String action){
        authorization.requireFunction(principal,action,"run:"+runId);
        requireGrant(principal,action,"run:"+runId);
        var row=runs.findRun(principal.tenantId(),runId).orElseThrow(()->new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        var p2=runSnapshots.getIfAvailable();
        if(p2!=null && p2.isManagedRun(principal.tenantId(),runId)) {p2.get(principal,runId); return row;}
        if(!authorization.sourcesCurrent(principal,row.resourceRefs(),row.policyVersion(),row.aclVersion())){
            throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);}
        return row;
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<ApiEnvelope<Map<String,Object>>> getRun(@PathVariable String runId){
        var principal=PrincipalContext.require();
        var p2=runSnapshots.getIfAvailable();
        if(p2!=null && p2.isManagedRun(principal.tenantId(),runId)){
            // P2 正式执行账本：当前授权 + 扩展快照（状态/阶段/版本/允许动作/终态结果）
            requireGrant(principal,"run.get","run:"+runId);
            return reply("run.get","run:"+runId,p2.snapshot(principal,runId));
        }
        var row=visibleRun(principal,runId,"run.get");
        return reply("run.get","run:"+runId,Map.of("runId",row.runId(),"action",row.action(),"status",row.status(),"createdAt",row.createdAt()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void configureP2Snapshots(org.springframework.beans.factory.ObjectProvider<
            com.nageoffer.ai.ragent.runtime.RunLifecycleService> snapshots) {
        this.runSnapshots = snapshots;
    }

    private org.springframework.beans.factory.ObjectProvider<
            com.nageoffer.ai.ragent.runtime.RunLifecycleService> runSnapshots;


    @GetMapping("/runs/{runId}/event-records")
    public ResponseEntity<ApiEnvelope<List<TenantEventReadRepository.EventRow>>> getEvents(@PathVariable String runId,
            @RequestParam(defaultValue="0") long afterSeq,@RequestParam(defaultValue="100") int limit){
        var principal=PrincipalContext.require();visibleRun(principal,runId,"run.events");
        return reply("run.events","run:"+runId,events.listEvents(principal.tenantId(),runId,afterSeq,limit));
    }

    public record RetrievalRequest(String query,List<String> requestedKbIds,int topK) { }

    @PostMapping("/knowledge-bases/retrievals")
    public ResponseEntity<ApiEnvelope<List<com.nageoffer.ai.ragent.framework.convention.RetrievedChunk>>> retrieve(@RequestBody RetrievalRequest request){
        if(request==null || request.query()==null || request.query().isBlank() || request.query().length()>4096
                || request.topK()<1 || request.topK()>100 || request.requestedKbIds()!=null && request.requestedKbIds().size()>200){throw new P04AiException(P04AiErrorCode.BAD_REQUEST);}
        var principal=PrincipalContext.require();
        var requested=request.requestedKbIds()==null?List.<String>of():request.requestedKbIds().stream().map(id->"kb:"+id).toList();
        var scope=authorization.toRetrievalScope(authorization.resolve(principal,"kb.retrieve",requested));
        if(scope.isEmpty() && !requested.isEmpty()){throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);}
        if(scope.isEmpty() || scope.publishedChunkRefs().isEmpty()){return reply("kb.retrieve","tenant:retrieval",List.of());}
        var retriever=retrievers.getIfAvailable();if(retriever==null){throw new ServiceException("authorized PG retrieval unavailable");}
        var operation=revocations.enter(principal,"kb.retrieve","tenant:retrieval");
        try{
            var data=retriever.retrieve(scope,request.query(),request.topK());
            // Transfer ownership of the same active lease to final delivery without an unprotected gap.
            return ResponseEntity.ok().header("Cache-Control","no-store").header("X-AI-Delivery-Permit",operation.permitId())
                    .header("X-AI-Delivery-Operation",operation.operationId()).body(ApiEnvelope.ok(data));
        }catch(RuntimeException e){operation.close();throw e;}
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void configureReadPaths(AuthorizedDownloadService downloads,AuthorizedExportService exports,
            org.springframework.jdbc.core.JdbcTemplate jdbc,TenantConversationReadRepository conversations,
            TenantRunReadRepository runs,TenantEventReadRepository events) {
        this.downloads=downloads;this.exports=exports;this.jdbc=jdbc;this.conversations=conversations;this.runs=runs;this.events=events;
    }

    private ResponseEntity<byte[]> delivery(byte[] bytes,String contentType,
            com.nageoffer.ai.ragent.framework.security.RevocationGuard.Operation operation,String range) {
        try {
            int from=0,to=bytes.length-1,status=200;
            if(range!=null){
                if(!range.matches("bytes=([0-9]+-[0-9]*|-[0-9]+)")){throw new P04AiException(P04AiErrorCode.BAD_REQUEST);}
                String[] parts=range.substring(6).split("-",-1);
                long start=parts[0].isEmpty()?Math.max(0,bytes.length-Long.parseLong(parts[1])):Long.parseLong(parts[0]);
                long end=parts[1].isEmpty()||parts[0].isEmpty()?bytes.length-1:Long.parseLong(parts[1]);
                if(start<0 || start>=bytes.length || end<start){throw new P04AiException(P04AiErrorCode.BAD_REQUEST);}
                from=(int)start;to=(int)Math.min(end,bytes.length-1);status=206;
            }
            var builder=ResponseEntity.status(status).contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                    .header("Cache-Control","no-store").header("Accept-Ranges","bytes")
                    .header("X-AI-Delivery-Permit",operation.permitId()).header("X-AI-Delivery-Operation",operation.operationId());
            if(status==206){builder.header("Content-Range","bytes "+from+"-"+to+"/"+bytes.length);}
            return builder.body(java.util.Arrays.copyOfRange(bytes,from,to+1));
        } catch(RuntimeException e){operation.close();throw e;}
    }

    @GetMapping("/documents/{docId}/content")
    public ResponseEntity<byte[]> download(@PathVariable String docId,
            @org.springframework.web.bind.annotation.RequestHeader(value="Range",required=false) String range) {
        var document=downloads.openLeasedDocument(docId);
        byte[] bytes;
        try(var source=document.source()){
            bytes=source.readNBytes(4*1024*1024+1);
            if(bytes.length>4*1024*1024){throw new ServiceException("文档超出单次交付上限");}
        }catch(Exception e){
            try{document.source().close();document.operation().close();}catch(Exception close){/* keep ACTIVE if source stop/release unconfirmed */}
            throw new ServiceException("文档读取未完成");
        }
        return delivery(bytes,document.mimeType()==null?"application/octet-stream":document.mimeType(),document.operation(),range);
    }

    @GetMapping("/conversations/{conversationId}/export")
    public ResponseEntity<byte[]> export(@PathVariable String conversationId) {
        var export=exports.exportLeased(conversationId);
        return delivery(export.bytes(),"application/x-ndjson",export.operation(),null);
    }

    // ------------------------------------------------------------ DTO（嵌套 record，无归属字段可由 body 提供）

    /** KB 创建请求：刻意没有 tenant/owner 字段——归属只来自执行主体。 */
    public record CreateKnowledgeBaseRequest(String name, String embeddingModel, String collectionName) {
    }

    /** ACL 授权/撤销请求：subjectType 取 framework 编码（member/department/role/tenant_all）。 */
    public record AclRuleRequest(String subjectType, String subjectId, String action, Long expiresAtEpochSecond) {
    }

    // ------------------------------------------------------------ KB 查询

    /** 当前主体可读的 KB 列表（空授权 = 空列表，不回落全库）。 */
    @GetMapping("/knowledge-bases")
    public ResponseEntity<ApiEnvelope<List<Map<String, Object>>>> listKnowledgeBases() {
        ExecutionPrincipal principal = PrincipalContext.require();
        List<Map<String, Object>> data = new ArrayList<>();
        for (String ref : authorization.resolveScope(principal, "kb.list", List.of()).authorizedRefs()) {
            if (!ref.startsWith("kb:")) { continue; }
            String kbId = AiResourceAuthorizationService.parseResourceRef(ref).resourceId();
            writeService.findKnowledgeBase(principal.tenantId(), kbId)
                    .ifPresent(view -> data.add(kbView(view)));
        }
        return reply("kb.list","tenant:resources",data);
    }

    /** 单个 KB：跨租户/无权/不存在一律 404。 */
    @GetMapping("/knowledge-bases/{kbId}")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> getKnowledgeBase(@PathVariable String kbId) {
        ExecutionPrincipal principal = PrincipalContext.require();
        String ref = AiResourceAuthorizationService.resourceRef(AiResourceMapper.TYPE_KB, kbId);
        requireGrant(principal, ACTION_KB_READ, ref);
        Map<String, Object> data = writeService.findKnowledgeBase(principal.tenantId(), kbId)
                .map(this::kbView)
                .orElseThrow(() -> new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        data.put("documentRefs", authorization.childResourceRefs(principal.tenantId(), ref));
        return reply("kb.read",ref,data);
    }

    // ------------------------------------------------------------ KB 写入

    /** 创建 KB：归属来自主体；同一事务写元数据 + registry + owner ACL + epoch bump。 */
    @PostMapping("/knowledge-bases")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> createKnowledgeBase(
            @RequestBody CreateKnowledgeBaseRequest request) {
        PrincipalContext.require();
        String model=request==null ? null : request.embeddingModel();
        String collection=request==null ? null : request.collectionName();
        var embedding=p2Embeddings==null ? null : p2Embeddings.getIfAvailable();
        if(embedding!=null) {
            if(model==null || model.isBlank()) model=embedding.model();
            if(!embedding.model().equals(model)) throw new com.nageoffer.ai.ragent.runtime.RunApiException(com.nageoffer.ai.ragent.runtime.RunErrorCode.BAD_REQUEST,"configured embedding model required");
            if(collection==null || collection.isBlank()) collection="p2-"+java.util.UUID.randomUUID();
        }
        String kbId = writeService.createKnowledgeBase(
                new AiResourceWriteService.KnowledgeBaseDraft(
                        request == null ? null : request.name(),
                        model,collection));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kbId", kbId);
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }

    /** 删除 KB（tombstone）：判定层 kb.delete 通过后同事务 tombstone + epoch bump。 */
    @DeleteMapping("/knowledge-bases/{kbId}")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> deleteKnowledgeBase(@PathVariable String kbId) {
        ExecutionPrincipal principal = PrincipalContext.require();
        requireGrant(principal, ACTION_KB_DELETE,
                AiResourceAuthorizationService.resourceRef(AiResourceMapper.TYPE_KB, kbId));
        writeService.tombstoneKnowledgeBase(kbId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kbId", kbId);
        data.put("status", "TOMBSTONED");
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }

    // ------------------------------------------------------------ ACL 管理

    /** 新增授权：subject 同租户、操作者持 kb.acl.manage 且是 owner 或显式 grant，原子 bump。 */
    @PutMapping("/knowledge-bases/{kbId}/acl")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> grantAcl(
            @PathVariable String kbId,
            @RequestBody AclRuleRequest request) {
        PrincipalContext.require();
        writeService.grantAclRule(kbId, toGrant(request));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kbId", kbId);
        data.put("granted", true);
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }

    /** 撤销授权：撤权即删行 + 原子 bump；其它主体下一次判定立刻收窄。 */
    @DeleteMapping("/knowledge-bases/{kbId}/acl")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> revokeAcl(
            @PathVariable String kbId,
            @RequestParam String subjectType,
            @RequestParam(required = false) String subjectId,
            @RequestParam String action) {
        PrincipalContext.require();
        writeService.revokeAclRule(kbId, new AiResourceWriteService.AclGrant(
                subjectType, subjectId, action, null));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kbId", kbId);
        data.put("granted", false);
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }

    // ------------------------------------------------------------ 文档查询

    /** 单个文档：判定走 doc.read；响应不含 file_url（内部对象 key）。 */
    @GetMapping("/documents/{docId}")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> getDocument(@PathVariable String docId) {
        ExecutionPrincipal principal = PrincipalContext.require();
        String ref = AiResourceAuthorizationService.resourceRef(AiResourceMapper.TYPE_DOCUMENT, docId);
        requireGrant(principal, ACTION_DOC_READ, ref);
        Map<String, Object> data = writeService.findDocument(principal.tenantId(), docId)
                .map(doc -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("docId", doc.docId());
                    view.put("kbId", doc.kbId());
                    view.put("docName", doc.docName());
                    view.put("status", doc.status());
                    view.put("enabled", doc.enabled());
                    view.put("chunkCount", doc.chunkCount());
                    return view;
                })
                .orElseThrow(() -> new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        return reply("document.read",ref,data);
    }

    // ------------------------------------------------------------ 判定收敛

    /** GRANT 放行；DENY→404、STALE→409、UNKNOWN→503（对外状态与语义一一对应）。 */
    private void requireGrant(ExecutionPrincipal principal, String action, String ref) {
        Verdict verdict = authorization.check(principal, action, ref);
        switch (verdict) {
            case GRANT -> {
                // 已授权
            }
            case DENY -> throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            case STALE -> throw new StaleVersionException(
                    "aclVersion changed; refetch current versions and retry");
            case UNKNOWN -> throw new ServiceException(
                    "resource authorization is unavailable; refusing to serve");
        }
    }

    private Map<String, Object> kbView(AiResourceWriteService.KnowledgeBaseView view) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kbId", view.kbId());
        data.put("name", view.name());
        data.put("embeddingModel", view.embeddingModel());
        data.put("collectionName", view.collectionName());
        data.put("ownerDeptId", view.ownerDeptId());
        return data;
    }

    private static AiResourceWriteService.AclGrant toGrant(AclRuleRequest request) {
        if (request == null) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST);
        }
        return new AiResourceWriteService.AclGrant(request.subjectType(), request.subjectId(),
                request.action(), request.expiresAtEpochSecond());
    }

    // ------------------------------------------------------------ 错误映射（HTTP status == body.code）

    @ExceptionHandler(P04AiException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleP04(P04AiException ex) {
        P04AiErrorCode code = ex.errorCode();
        return ResponseEntity.status(code.httpStatus())
                .body(ApiEnvelope.error(code.httpStatus(), ex.getMessage(), code.name()));
    }

    @ExceptionHandler(com.nageoffer.ai.ragent.runtime.RunApiException.class)
    public ResponseEntity<?> handleRun(com.nageoffer.ai.ragent.runtime.RunApiException ex) {
        return com.nageoffer.ai.ragent.runtime.web.RunApiResponses.fail(ex.errorCode(),ex.getMessage(),com.nageoffer.ai.ragent.framework.security.AiRequestIdFilter.currentOrEmpty());
    }

    @ExceptionHandler(ClientException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleClient(ClientException ex) {
        // 缺主体等主体上下文问题：403（不区分"没带"与"带了不完整"，避免给探测者反馈）
        return ResponseEntity.status(P04AiErrorCode.TENANT_CONTEXT_MISSING.httpStatus())
                .body(ApiEnvelope.error(P04AiErrorCode.TENANT_CONTEXT_MISSING.httpStatus(),
                        ex.getMessage(), P04AiErrorCode.TENANT_CONTEXT_MISSING.name()));
    }

    @ExceptionHandler(StaleVersionException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleStale(StaleVersionException ex) {
        return ResponseEntity.status(409)
                .body(ApiEnvelope.error(409, ex.getMessage(), P04AiErrorCode.POLICY_VERSION_STALE.name()));
    }

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleService(ServiceException ex) {
        // 授权事实源不可用：503，不放行
        return ResponseEntity.status(503)
                .body(ApiEnvelope.error(503, ex.getMessage(), P04AiErrorCode.AUTHORIZATION_UNAVAILABLE.name()));
    }
}
