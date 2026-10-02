/* Licensed under the Apache License, Version 2.0. */
import com.fasterxml.jackson.databind.*;
import com.baomidou.mybatisplus.core.*;
import com.nageoffer.ai.ragent.agent.dao.mapper.*;
import com.nageoffer.ai.ragent.agent.memory.*;
import com.nageoffer.ai.ragent.agent.state.*;
import com.nageoffer.ai.ragent.authorization.*;
import com.nageoffer.ai.ragent.authorization.dao.*;
import com.nageoffer.ai.ragent.framework.context.*;
import com.nageoffer.ai.ragent.framework.security.*;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationSummaryMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.core.memory.JdbcConversationMemorySummaryService;
import com.nageoffer.ai.ragent.rag.service.impl.ConversationGroupServiceImpl;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.*;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.datasource.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.sync.RequestBody;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Real production HTTP, JDBC and product source guards. No synthetic authorization provider. */
public class P1ProductionAcceptance {
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String T="p1t1", U="900000000000000001", M="platform:"+T+":"+U;
    static JdbcTemplate ai, platform;
    public record Payload(String value) implements State {}
    static void ok(boolean value,String name){if(!value)throw new AssertionError(name);System.out.println("PASS "+name);}
    static int pv(){return platform.queryForObject("SELECT version FROM sys_ai_policy_revision WHERE tenant_id=?",Integer.class,T);}
    static int av(){return ai.queryForObject("SELECT version FROM ai_acl_epoch WHERE tenant_id=?",Integer.class,T);}
    static ExecutionPrincipal principal(){return new ExecutionPrincipal(T,U,M,pv(),av(),Set.of("kb.read","memory.read","conversation.read","document.download","kb.acl.manage","kb.retrieve"),UUID.randomUUID().toString(),"platform",0,Long.MAX_VALUE);}
    static String signed(String subject,String action,int version) throws Exception {
        return signedTenant(T,subject,action,version);
    }
    static String signedTenant(String tenant,String subject,String action,int version) throws Exception {
        long now=Instant.now().getEpochSecond();
        var claims=new LinkedHashMap<String,Object>();claims.put("iss","platform");claims.put("aud",List.of("ai"));claims.put("sub",subject);claims.put("tid",tenant);claims.put("mid","platform:"+tenant+":"+subject);claims.put("pv",version);claims.put("scope",List.of(action));claims.put("jti",UUID.randomUUID().toString());claims.put("iat",now);claims.put("nbf",now);claims.put("exp",now+60);
        var b=Base64.getUrlEncoder().withoutPadding();String input=b.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"platform-prod-k1\"}".getBytes(StandardCharsets.UTF_8))+"."+b.encodeToString(JSON.writeValueAsBytes(claims));
        String pem=Files.readString(Path.of(System.getenv("P1C_PRIVATE_KEY"))).replaceAll("-----[^-]+-----","").replaceAll("\\s","");
        var key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        var signer=Signature.getInstance("SHA256withRSA");signer.initSign(key);signer.update(input.getBytes(StandardCharsets.US_ASCII));return input+"."+b.encodeToString(signer.sign());
    }
    static HttpRequest request(boolean gateway,String method,String path,String body,String action) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(System.getenv(gateway?"P1C_PLATFORM_URL":"P1C_AI_URL")+(gateway?"/api/ai/v1":"/internal/ai/v1")+path)).timeout(Duration.ofSeconds(65));
        b.header("Authorization","Bearer "+(gateway?System.getenv("P1C_BROWSER_TOKEN"):signed(U,action,pv())));
        if(gateway)b.header("clientid","p1b-client");else b.header("X-P04-Service-Credential",System.getenv("P1C_SERVICE"));
        if(body!=null)b.header("Content-Type","application/json");
        return b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
    }
    static HttpResponse<byte[]> send(boolean gateway,String method,String path,String body,String action) throws Exception{return HTTP.send(request(gateway,method,path,body,action),HttpResponse.BodyHandlers.ofByteArray());}
    static JsonNode data(HttpResponse<byte[]> r) throws Exception{return JSON.readTree(r.body()).path("data");}
    static void release(HttpResponse<?> r) throws Exception {
        release(T,M,r);
    }
    static void release(String tenant,String member,HttpResponse<?> r) throws Exception {
        String id=r.headers().firstValue("X-AI-Delivery-Permit").orElseThrow();String op=r.headers().firstValue("X-AI-Delivery-Operation").orElseThrow();
        var b=HttpRequest.newBuilder(URI.create(System.getenv("P1C_AI_URL")+"/internal/ai/v1/authorization/deliveries/release")).header("X-P04-Service-Credential",System.getenv("P1C_SERVICE")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("tenantId",tenant,"memberId",member,"permitId",id,"operationId",op))));
        var reply=HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());ok(reply.statusCode()==204 && reply.body().isEmpty(),"delivery service acknowledgement");
    }
    static long active(){return ai.queryForObject("SELECT count(*) FROM ai_execution_permit WHERE tenant_id=? AND status='ACTIVE'",Long.class,T);}
    static void drained() throws Exception {for(int n=0;n<100 && active()!=0;n++)Thread.sleep(20);ok(active()==0,"final delivery leaves no active lease");}
    static void fixtureObjects(){
        try(var s3=S3Client.builder().endpointOverride(URI.create(System.getenv("P1C_S3_URL"))).region(Region.US_EAST_1).forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(System.getenv("P1B_S3_ACCESS_KEY"),System.getenv("P1B_S3_SECRET_KEY")))).build()){
            try{s3.createBucket(CreateBucketRequest.builder().bucket("ragent-sources").build());}catch(S3Exception e){if(e.statusCode()!=409)throw e;}
            s3.putObject(PutObjectRequest.builder().bucket("ragent-sources").key("p1t1/shared/11111111-1111-4111-8111-111111111111.txt").build(),RequestBody.fromString("P1 synthetic private document\n"));
        }
    }
    static void roleScope(String scope,Long... depts) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(System.getenv("P1C_PLATFORM_URL")+"/system/role/dataScope")).timeout(Duration.ofSeconds(65)).header("Authorization","Bearer "+System.getenv("P1C_BROWSER_TOKEN")).header("clientid","p1b-client").header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("roleId",900000000000000021L,"dataScope",scope,"deptIds",depts))));
        int before=pv();var reply=HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());ok(reply.statusCode()==200 && JSON.readTree(reply.body()).path("code").intValue()==200 && pv()==before+1,"real role mutation dataScope="+scope+" atomically bumps pv");
        ok("OPEN".equals(platform.queryForObject("SELECT status FROM sys_ai_tenant_barrier WHERE tenant_id=?",String.class,T)),"automatic policy barrier reopened after commit");
    }
    public static void main(String[] args) throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("AI_DB_URL"),"ai_app",System.getenv("AI_DB_PASSWORD"));ai=new JdbcTemplate(ds);
        platform=new JdbcTemplate(new DriverManagerDataSource(System.getenv("P1C_PLATFORM_DB_URL"),"platform_app",System.getenv("PLATFORM_DB_PASSWORD")));
        fixtureObjects();
        var r=send(true,"GET","/documents/doc-t1-a/content",null,"document.download");ok(r.statusCode()==200 && new String(r.body(),StandardCharsets.UTF_8).equals("P1 synthetic private document\n") && r.headers().firstValue("X-AI-Delivery-Permit").isEmpty(),"C08-real-private-S3-download status="+r.statusCode()+" body="+new String(r.body(),StandardCharsets.UTF_8));drained();
        var range=request(true,"GET","/documents/doc-t1-a/content",null,"document.download");
        var rb=HttpRequest.newBuilder(range.uri()).timeout(range.timeout().orElseThrow());range.headers().map().forEach((k,v)->v.forEach(x->rb.header(k,x)));rb.header("Range","bytes=0-3");
        r=HTTP.send(rb.GET().build(),HttpResponse.BodyHandlers.ofByteArray());ok(r.statusCode()==206 && new String(r.body(),StandardCharsets.UTF_8).equals("P1 s") && r.headers().firstValue("Content-Range").orElse("").equals("bytes 0-3/30"),"C08-real-Range-bytes status="+r.statusCode()+" body="+new String(r.body(),StandardCharsets.UTF_8));drained();
        for(String path:List.of("/documents/doc-t1-b/content","/documents/doc-t2-a/content","/documents/missing/content","/conversations/conv-t1-b","/conversations/conv-t2-a","/runs/run-t2-a","/runs/run-t2-a/event-records")){
            r=send(true,"GET",path,null,"kb.read");ok(r.statusCode()==404 && !new String(r.body(),StandardCharsets.UTF_8).contains("PRIVATE"),"private/cross/missing 404 "+path);
        }
        for(String path:List.of("/conversations","/conversations/conv-t1-a","/conversations/conv-t1-a/messages","/runs/run-t1-a","/runs/run-t1-a/event-records","/memories")){
            ai.update("UPDATE ai_run SET policy_version=?,acl_version=? WHERE tenant_id=?",pv(),av(),T);
            r=send(true,"GET",path,null,"conversation.read");ok(r.statusCode()==200 && !new String(r.body(),StandardCharsets.UTF_8).contains("PRIVATE T2"),"formal read 200 "+path);drained();
            if(path.endsWith("/event-records"))ok(data(r).get(0).path("seq").intValue()==1 && data(r).get(1).path("seq").intValue()==2,"event original sequence preserved");
        }
        r=send(true,"GET","/conversations/conv-t1-a/export",null,"conversation.export");ok(r.statusCode()==200 && new String(r.body(),StandardCharsets.UTF_8).contains("T1 private conversation"),"C08-real-export-NDJSON");drained();
        int count=Integer.parseInt(HTTP.send(HttpRequest.newBuilder(URI.create(System.getenv("P1C_EMBEDDING_URL")+"/count")).build(),HttpResponse.BodyHandlers.ofString()).body());
        r=send(true,"POST","/knowledge-bases/retrievals","{\"query\":\"test\",\"requestedKbIds\":[\"kb-t1-private-a\"],\"topK\":10}","kb.retrieve");
        ok(r.statusCode()==200 && data(r).size()==1 && data(r).get(0).path("text").asText().equals("allowed content"),"C07-real-PG-embedding-shared-collection-filter status="+r.statusCode()+" body="+new String(r.body(),StandardCharsets.UTF_8));drained();
        int calls=Integer.parseInt(HTTP.send(HttpRequest.newBuilder(URI.create(System.getenv("P1C_EMBEDDING_URL")+"/count")).build(),HttpResponse.BodyHandlers.ofString()).body());ok(calls==count+1,"authorized embedding occurred exactly once");
        var empty=HttpRequest.newBuilder(URI.create(System.getenv("P1C_AI_URL")+"/internal/ai/v1/knowledge-bases/retrievals")).header("Authorization","Bearer "+signedTenant("p1t2","900000000000000006","kb.retrieve",1)).header("X-P04-Service-Credential",System.getenv("P1C_SERVICE")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"never embed\",\"topK\":10}"));
        var er=HTTP.send(empty.build(),HttpResponse.BodyHandlers.ofByteArray());ok(er.statusCode()==200 && data(er).isArray() && data(er).isEmpty(),"empty authorization returns empty status="+er.statusCode()+" body="+new String(er.body(),StandardCharsets.UTF_8));
        // Empty member delivery is acknowledged with its real member binding.
        String eid=er.headers().firstValue("X-AI-Delivery-Permit").orElseThrow(),eop=er.headers().firstValue("X-AI-Delivery-Operation").orElseThrow();
        var ack=HttpRequest.newBuilder(URI.create(System.getenv("P1C_AI_URL")+"/internal/ai/v1/authorization/deliveries/release")).header("X-P04-Service-Credential",System.getenv("P1C_SERVICE")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("tenantId","p1t2","memberId","platform:p1t2:900000000000000006","permitId",eid,"operationId",eop))));ok(HTTP.send(ack.build(),HttpResponse.BodyHandlers.discarding()).statusCode()==204,"empty delivery release");
        r=send(true,"POST","/knowledge-bases/retrievals","{\"query\":\"never embed\",\"requestedKbIds\":[\"kb-t1-private-b\"],\"topK\":10}","kb.retrieve");ok(r.statusCode()==404,"explicit unauthorized selector 404");
        int endCalls=Integer.parseInt(HTTP.send(HttpRequest.newBuilder(URI.create(System.getenv("P1C_EMBEDDING_URL")+"/count")).build(),HttpResponse.BodyHandlers.ofString()).body());ok(calls==endCalls,"C07-empty-and-denied-zero-embedding-IO");
        var allRequest=HttpRequest.newBuilder(URI.create(System.getenv("P1C_AI_URL")+"/internal/ai/v1/knowledge-bases/kb-t1-tenant-all")).header("Authorization","Bearer "+signed("900000000000000005","kb.read",pv())).header("X-P04-Service-Credential",System.getenv("P1C_SERVICE")).GET().build();
        var allReply=HTTP.send(allRequest,HttpResponse.BodyHandlers.ofByteArray());ok(allReply.statusCode()==200,"C05-explicit-tenant-all-grant-positive");release(T,"platform:p1t1:900000000000000005",allReply);drained();
        for(String kb:List.of("kb-t1-dept","kb-t1-role")){r=send(true,"GET","/knowledge-bases/"+kb,null,"kb.read");ok(r.statusCode()==200,"C05-live-organization-grant "+kb+" status="+r.statusCode()+" body="+new String(r.body(),StandardCharsets.UTF_8));drained();}
        r=send(true,"GET","/knowledge-bases/kb-t1-expired",null,"kb.read");ok(r.statusCode()==404,"C05-expired-grant-denied");
        var once=request(false,"GET","/knowledge-bases/kb-t1-private-a",null,"kb.read");var positive=HTTP.send(once,HttpResponse.BodyHandlers.ofByteArray());ok(positive.statusCode()==200,"delegation positive control");release(positive);
        ok(HTTP.send(once,HttpResponse.BodyHandlers.discarding()).statusCode()==401,"C06-persistent-jti-replay-401");
        var protectedUri=once.uri();
        ok(HTTP.send(HttpRequest.newBuilder(protectedUri).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==401,"no service identity or delegation 401");
        ok(HTTP.send(HttpRequest.newBuilder(protectedUri).header("X-P04-Service-Credential",System.getenv("P1C_SERVICE")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==401,"missing delegation 401");
        ok(HTTP.send(HttpRequest.newBuilder(protectedUri).header("X-P04-Service-Credential",System.getenv("P1C_SERVICE")).header("Authorization","Bearer malformed.jwt.value").GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==401,"invalid delegation 401");
        ok(HTTP.send(HttpRequest.newBuilder(protectedUri).header("X-P04-Service-Credential","wrong-owned-test-service").header("Authorization","Bearer "+signed(U,"kb.read",pv())).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==401,"wrong service identity 401");
        ok(HTTP.send(HttpRequest.newBuilder(protectedUri).header("X-P04-Service-Credential",System.getenv("P1C_SERVICE")).header("Authorization","Bearer "+signed("900000000000000002","kb.read",pv())).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==403,"foreign member cannot become T1 subject");
        ok(HTTP.send(HttpRequest.newBuilder(protectedUri).header("X-P04-Service-Credential",System.getenv("P1C_SERVICE")).header("Authorization","Bearer "+signed(U,"unknown.action",pv())).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==403,"unknown canonical action denied");
        var future=HttpRequest.newBuilder(once.uri()).header("Authorization","Bearer "+signed(U,"kb.read",pv()+1)).header("X-P04-Service-Credential",System.getenv("P1C_SERVICE")).GET();ok(HTTP.send(future.build(),HttpResponse.BodyHandlers.discarding()).statusCode()==409,"future pv rejected");
        r=send(false,"POST","/knowledge-bases","{\"name\":\"forged\",\"tenantId\":\"p1t2\"}","kb.write");ok(r.statusCode()==403,"C06-body-identity-forgery-403");
        int beforeInvalid=av();r=send(false,"PUT","/knowledge-bases/kb-t1-shared-src/acl","{\"subjectType\":\"MEMBER\",\"subjectId\":\""+M+"\",\"action\":\"kb.read\"}","kb.acl.manage");ok(r.statusCode()==400 && av()==beforeInvalid && active()==0,"malformed ACL type 400 with no epoch or permit side effect");
        sourceChecks(ds);
        for(String scope:List.of("1","2","3","4","5","6")){
            if(scope.equals("2"))roleScope(scope,5102L);else roleScope(scope);
            r=send(true,"GET","/knowledge-bases/kb-t1-shared-src",null,"kb.read");
            boolean allow=Set.of("1","2","4","6").contains(scope);ok(r.statusCode()==(allow?200:404),"C04-action-role-dataScope-"+scope+" unrelated ALL cannot broaden");if(allow)drained();
            r=send(true,"GET","/knowledge-bases/kb-t1-private-a",null,"kb.read");boolean self=!scope.equals("2");ok(r.statusCode()==(self?200:404),"scope self binding "+scope);if(self)drained();
        }
        roleScope("1");
        for(String sql:List.of("UPDATE sys_tenant SET status='1' WHERE tenant_id='p1t1'","UPDATE sys_tenant_package SET status='1' WHERE package_id=900000000000000091","UPDATE sys_role SET status='1' WHERE role_id=900000000000000021","UPDATE sys_menu SET status='1' WHERE menu_id=7102","UPDATE sys_user SET status='1' WHERE user_id=900000000000000001")){
            platform.update(sql);r=send(false,"GET","/knowledge-bases/kb-t1-private-a",null,"kb.read");ok(r.statusCode()==403 || r.statusCode()==404,"current disabled fact fails closed");platform.update(sql.replace("status='1'","status='0'"));
        }
        System.out.println("PASS C11-production-extended");
    }
    static void sourceChecks(DriverManagerDataSource ds) throws Exception {
        var named=new NamedParameterJdbcTemplate(ds);var auth=new AiResourceAuthorizationService(new AiResourceMapper(named),new AiResourceAclMapper(named),new AiAclEpochMapper(named),new ResourceSourceRefMapper(named),Clock.systemUTC());
        auth.configureSubjectMatch(System.getenv("P1C_PLATFORM_URL"),System.getenv("P1C_SERVICE"));var security=new P04SecurityProperties();security.getPlatform().setAuthorizationUrl(System.getenv("P1C_PLATFORM_URL")+"/internal/platform/v1/authorization/check");security.getPlatform().setServiceCredential(System.getenv("P1C_SERVICE"));auth.configurePlatformAuthorization(new PlatformAuthorizationClient(HTTP,JSON,security,true));
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("sources",auth);var provider=beans.getBeanProvider(ResourceAuthorizationService.class);
        var cfg=new MybatisConfiguration();cfg.setMapUnderscoreToCamelCase(true);cfg.setCacheEnabled(false);cfg.setEnvironment(new Environment("synthetic",new SpringManagedTransactionFactory(),ds));
        for(Class<?> type:List.of(AgentStateMapper.class,AgentMemoryMapper.class,AgentMemoryControlMapper.class,AgentMemoryExtractionMapper.class,AgentMessageMapper.class,ConversationSummaryMapper.class,ConversationMessageMapper.class,ConversationMapper.class))cfg.addMapper(type);
        var sessions=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(cfg));var state=new PgAgentStateStore(sessions.getMapper(AgentStateMapper.class));state.configureSources(true,provider);JsonUtils.resetToDefault();
        var memory=new AgentMemoryRepository(sessions.getMapper(AgentMemoryMapper.class),sessions.getMapper(AgentMemoryExtractionMapper.class),sessions.getMapper(AgentMemoryControlMapper.class),sessions.getMapper(AgentMessageMapper.class),new AgentMemoryProperties());memory.configureSources(true,provider);
        var group=new ConversationGroupServiceImpl(sessions.getMapper(ConversationMessageMapper.class),sessions.getMapper(ConversationSummaryMapper.class),sessions.getMapper(ConversationMapper.class));group.configureScope(true);
        var summary=new JdbcConversationMemorySummaryService(group,null,new MemoryProperties(),null,null,null,null,Runnable::run);summary.configureSources(true,provider);
        String proof="[{\"ref\":\"kb:kb-t1-shared-src\",\"version\":1}]";PrincipalContext.set(principal());
        auth.configureProjection(named);
        var guardTarget=new DefaultRevocationGuard(ai);guardTarget.configurePlatform(System.getenv("P1C_PLATFORM_URL"),System.getenv("P1C_SERVICE"));
        var proxyFactory=new org.springframework.aop.framework.ProxyFactory(guardTarget);proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new DataSourceTransactionManager(ds),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var guard=(DefaultRevocationGuard)proxyFactory.getProxy();beans.registerSingleton("guard",guard);guardTarget.configureSelf(beans.getBeanProvider(DefaultRevocationGuard.class));
        var embeddingCalls=new java.util.concurrent.atomic.AtomicInteger();
        var embedding=(com.nageoffer.ai.ragent.infra.embedding.EmbeddingService)java.lang.reflect.Proxy.newProxyInstance(P1ProductionAcceptance.class.getClassLoader(),new Class[]{com.nageoffer.ai.ragent.infra.embedding.EmbeddingService.class},(object,method,arguments)->{embeddingCalls.incrementAndGet();throw new AssertionError("unexpected embedding IO");});
        var retriever=new com.nageoffer.ai.ragent.rag.core.vector.PgVectorRetrieverService(ai,embedding);
        retriever.configureExecution(true,true,provider,beans.getBeanProvider(RevocationGuard.class));
        var scope=auth.toRetrievalScope(auth.resolve(principal(),"kb.retrieve",List.of("kb:kb-t1-private-a")));
        float[] vector=new float[1536];vector[0]=1;
        var query=com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest.builder().query("never embed stale").topK(10).build();
        var chunks=retriever.retrieveByVector(scope,vector,query);ok(chunks.size()==1 && chunks.get(0).getText().equals("allowed content"),"direct actual PG vector service shared collection isolation");
        state.save(U,"proof-session","proof-key",new Payload("protected state"));ok(state.get(U,"proof-session","proof-key",Payload.class).isEmpty(),"unknown state provenance suppressed");
        ai.update("UPDATE t_agent_state SET source_refs=?::jsonb,source_policy_version=?,source_acl_version=? WHERE tenant_id=? AND session_id='proof-session'",proof,pv(),av(),T);
        ai.update("INSERT INTO t_agent_memory(id,tenant_id,member_id,user_id,content,source_type,source_refs,source_policy_version,source_acl_version,create_time) VALUES('proof-memory',?,?,?,'protected memory','EXTRACTION',?::jsonb,?,?,now())",T,M,U,proof,pv(),av());
        ai.update("INSERT INTO t_conversation_summary(id,tenant_id,member_id,user_id,conversation_id,content,last_message_id,source_refs,source_policy_version,source_acl_version) VALUES('proof-summary',?,?,?,'conv-t1-a','protected summary','msg-c-t1-a',?::jsonb,?,?)",T,M,U,proof,pv(),av());
        ok(state.get(U,"proof-session","proof-key",Payload.class).orElseThrow().value().equals("protected state") && memory.listActiveItems(U).size()==1 && summary.loadLatestSummary("conv-t1-a",U)!=null,"C09-live-known-source-state-memory-summary-positive");
        var delivery=send(false,"GET","/documents/doc-t1-a/content",null,"document.download");ok(delivery.statusCode()==200 && active()==1,"direct delivery stays ACTIVE after upstream read");
        int before=av();var revoke=request(false,"DELETE","/knowledge-bases/kb-t1-shared-src/acl?subjectType=member&subjectId="+URLEncoder.encode(M,StandardCharsets.UTF_8)+"&action=kb.read",null,"kb.acl.manage");
        var pending=HTTP.sendAsync(revoke,HttpResponse.BodyHandlers.ofByteArray());boolean closed=false;
        for(int i=0;i<400 && !pending.isDone();i++){if("PENDING".equals(ai.queryForObject("SELECT status FROM ai_tenant_barrier WHERE tenant_id=?",String.class,T))){closed=true;break;}Thread.sleep(25);}
        ok(closed && !pending.isDone() && av()==before,"C10-automatic-ACL-revoke-waits-for-final-delivery"+(pending.isDone()?" status="+pending.get().statusCode()+" body="+new String(pending.get().body(),StandardCharsets.UTF_8):""));release(delivery);
        var result=pending.get(45,TimeUnit.SECONDS);ok(result.statusCode()==200 && av()==before+1 && active()==0,"C10-ACL-facts-commit-after-release");
        PrincipalContext.set(principal());
        for(int method=0;method<3;method++){
            boolean rejected=false;
            try{if(method==0)retriever.retrieve(scope,query);else if(method==1)retriever.retrieveByVector(scope,vector,query);else retriever.embedAndNormalize(scope,"never embed stale");}
            catch(StaleVersionException expected){rejected=true;}
            ok(rejected && embeddingCalls.get()==0,"C07-direct-stale-scope-zero-IO method="+method);
        }
        for(String table:List.of("t_agent_state","t_agent_memory","t_conversation_summary"))ai.update("UPDATE "+table+" SET source_policy_version=?,source_acl_version=? WHERE tenant_id=?",pv(),av(),T);
        ok(state.get(U,"proof-session","proof-key",Payload.class).isEmpty() && memory.listActiveItems(U).isEmpty() && summary.loadLatestSummary("conv-t1-a",U)==null,"C09-revoked-source-no-state-memory-summary-content-even-current-proof");
        // Restore only the explicit grant through the same actual strong write service for the scope matrix.
        var restored=send(false,"PUT","/knowledge-bases/kb-t1-shared-src/acl","{\"subjectType\":\"member\",\"subjectId\":\""+M+"\",\"action\":\"kb.read\"}","kb.acl.manage");ok(restored.statusCode()==200,"actual ACL grant restoration");PrincipalContext.clear();
    }
}
