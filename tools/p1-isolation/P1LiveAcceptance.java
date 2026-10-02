/* Licensed under the Apache License, Version 2.0. */
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.*;
import com.nageoffer.ai.ragent.agent.memory.*;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.authorization.*;
import com.nageoffer.ai.ragent.authorization.dao.*;
import com.nageoffer.ai.ragent.framework.context.*;
import com.nageoffer.ai.ragent.framework.security.*;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;

/** Standalone test harness. Loaded from the actual product jar dependencies, never a product HTTP endpoint. */
public class P1LiveAcceptance {
    public record Payload(String value) implements State {}
    static void require(boolean ok, String name) { if (!ok) throw new AssertionError(name); System.out.println("PASS " + name); }
    static ExecutionPrincipal principal(String tenant, int av) {
        return new ExecutionPrincipal(tenant,"900000000000000001","platform:"+tenant+":900000000000000001",2,av,
                Set.of("kb.read","document.download","conversation.export","kb.write"),UUID.randomUUID().toString(),"platform",0,Long.MAX_VALUE);
    }
    public static void main(String[] args) throws Exception {
        var ds = new DriverManagerDataSource(System.getenv("AI_DB_URL"),"ai_app",System.getenv("AI_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(ds);
        if (args.length>0 && args[0].equals("acl-race")) {
            try(var locked=ds.getConnection()) {
                locked.setAutoCommit(false);
                try(var stmt=locked.createStatement()) { stmt.executeQuery("SELECT version FROM ai_acl_epoch WHERE tenant_id='p1t1' FOR UPDATE").close(); }
                var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(System.getenv("P1C_AI_URL")+"/internal/ai/v1/knowledge-bases"))
                        .timeout(java.time.Duration.ofSeconds(15)).header("Authorization","Bearer "+System.getenv("P1C_RACE_JWT"))
                        .header("X-P04-Service-Credential",System.getenv("P1C_SERVICE")).header("Content-Type","application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"name\":\"race-must-rollback\",\"embeddingModel\":\"synthetic\",\"collectionName\":\"race\"}")).build();
                var pending=java.net.http.HttpClient.newHttpClient().sendAsync(request,java.net.http.HttpResponse.BodyHandlers.ofString());
                long end=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
                boolean waiting=false;
                while(System.nanoTime()<end && !pending.isDone()) {
                    waiting=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND wait_event_type='Lock' AND query LIKE '%ai_acl_epoch%' AND query LIKE '%FOR UPDATE%'",Long.class)>0;
                    if(waiting)break;
                    Thread.sleep(25);
                }
                require(waiting,"real HTTP request reached blocked epoch acquire");
                try(var stmt=locked.createStatement()) { stmt.executeUpdate("UPDATE ai_acl_epoch SET version=version+1 WHERE tenant_id='p1t1'"); }
                locked.commit();
                var response=pending.get(15,java.util.concurrent.TimeUnit.SECONDS);
                var root=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
                require(response.statusCode()==409 && root.path("code").intValue()==409,"C08-acl-stale-http actual in-flight snapshot rejected");
                require(jdbc.queryForObject("SELECT count(*) FROM t_knowledge_base WHERE name='race-must-rollback'",Long.class)==0,"old-av denial has zero business writes");
                System.out.println("HTTP 409 code=409 C08-acl-stale-http");
            }
            return;
        }
        var named = new NamedParameterJdbcTemplate(ds);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
        if (args.length>0 && (args[0].equals("hold") || args[0].equals("release"))) {
            var guard = new DefaultRevocationGuard(jdbc);
            guard.configurePlatform(System.getenv("P1C_PLATFORM_URL"),System.getenv("P1C_SERVICE"));
            if (args[0].equals("hold")) {
                int av=jdbc.queryForObject("SELECT version FROM ai_acl_epoch WHERE tenant_id='p1t1'",Integer.class);
                String ref="kb:kb-t1-private-a";
                String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(ref.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var grant=transactions.execute(status -> guard.acquire(new RevocationGuard.PermitRequest("p1t1",
                        "platform:p1t1:900000000000000001","kb.read",2,av,hash,"c10-live-operation",ref)));
                // Fault injection changes the synthetic lease only; ACTIVE is still a real service registration.
                jdbc.update("UPDATE ai_execution_permit SET expires_at=now()-interval '1 minute' WHERE permit_id=?",grant.permitId());
                require(guard.activePermitCount("p1t1")==1,"real held permit visible in shared database");
                System.out.println("PASS C10-held-permit");
            } else {
                String id=jdbc.queryForObject("SELECT permit_id FROM ai_execution_permit WHERE operation_id='c10-live-operation'",String.class);
                transactions.executeWithoutResult(status -> guard.release(id,"c10-live-operation"));
                require(guard.activePermitCount("p1t1")==0,"actual service releases held permit");
                System.out.println("PASS C10-held-release");
            }
            return;
        }
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setCacheEnabled(false);
        configuration.setEnvironment(new Environment("synthetic",new SpringManagedTransactionFactory(),ds));
        for (Class<?> mapper : List.of(AgentStateMapper.class,AgentMemoryMapper.class,AgentMemoryControlMapper.class,
                AgentMemoryExtractionMapper.class,AgentMessageMapper.class)) configuration.addMapper(mapper);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var state = new PgAgentStateStore(sessions.getMapper(AgentStateMapper.class));
        JsonUtils.resetToDefault();
        PrincipalContext.set(principal("p1t1",1));
        state.save("ignored","same-session","same-key",new Payload("t1"));
        PrincipalContext.set(principal("p1t2",1));
        state.save("ignored","same-session","same-key",new Payload("t2"));
        require(state.get("ignored","same-session","same-key",Payload.class).orElseThrow().value().equals("t2"),"state T2 reads own payload");
        PrincipalContext.set(principal("p1t1",1));
        require(state.get("ignored","same-session","same-key",Payload.class).orElseThrow().value().equals("t1"),"state T1 reads own payload");
        state.delete("ignored","same-session");
        PrincipalContext.set(principal("p1t2",1));
        require(state.exists("ignored","same-session"),"state delete does not cross tenant");
        var properties = new AgentMemoryProperties();
        var memory = new AgentMemoryRepository(sessions.getMapper(AgentMemoryMapper.class),sessions.getMapper(AgentMemoryExtractionMapper.class),
                sessions.getMapper(AgentMemoryControlMapper.class),sessions.getMapper(AgentMessageMapper.class),properties);
        for (String tenant : List.of("p1t1","p1t2")) {
            PrincipalContext.set(principal(tenant,1));
            memory.ensureControl("900000000000000001");
            sessions.getMapper(AgentMemoryMapper.class).insert(AgentMemoryDO.builder().id("mem-"+tenant).tenantId(tenant)
                    .memberId(principal(tenant,1).membershipId()).userId("900000000000000001").content(tenant).sourceType("EXTRACTION")
                    .createTime(new java.util.Date()).build());
            require(memory.listActiveItems("900000000000000001").size()==1 && memory.listActiveItems("900000000000000001").get(0).content().equals(tenant),"memory actual service scoped "+tenant);
        }
        PrincipalContext.clear();
        boolean refused=false;
        try { state.exists("ignored","same-session"); } catch (RuntimeException e) { refused=true; }
        require(refused,"state anonymous rejected");
        refused=false;
        try { memory.listActiveItems("900000000000000001"); } catch (RuntimeException e) { refused=true; }
        require(refused,"memory anonymous rejected");
        var guard = new DefaultRevocationGuard(jdbc);
        guard.configurePlatform(System.getenv("P1C_PLATFORM_URL"),System.getenv("P1C_SERVICE"));
        var resources = new AiResourceAuthorizationService(new AiResourceMapper(named),new AiResourceAclMapper(named),
                new AiAclEpochMapper(named),new ResourceSourceRefMapper(named),Clock.systemUTC());
        resources.configureSubjectMatch(System.getenv("P1C_PLATFORM_URL"),System.getenv("P1C_SERVICE"));
        var security = new P04SecurityProperties();
        security.getPlatform().setAuthorizationUrl(System.getenv("P1C_PLATFORM_URL")+"/internal/platform/v1/authorization/check");
        security.getPlatform().setServiceCredential(System.getenv("P1C_SERVICE"));
        resources.configurePlatformAuthorization(new PlatformAuthorizationClient(java.net.http.HttpClient.newHttpClient(),new com.fasterxml.jackson.databind.ObjectMapper(),security,true));
        var p=principal("p1t1",jdbc.queryForObject("SELECT version FROM ai_acl_epoch WHERE tenant_id='p1t1'",Integer.class));
        require(resources.check(p,"kb.read","kb:kb-t1-private-a")==ResourceAuthorizationService.Verdict.GRANT,"registry owner positive live");
        require(resources.check(p,"kb.read","kb:kb-t2-same-selector")==ResourceAuthorizationService.Verdict.DENY,"registry cross-tenant live");
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("kb:kb-t1-private-a".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        RevocationGuard.PermitGrant grant = transactions.execute(status -> guard.acquire(new RevocationGuard.PermitRequest(p.tenantId(),p.membershipId(),"kb.read",p.policyVersion(),p.aclVersion(),hash,"live-op","kb:kb-t1-private-a")));
        require(guard.activePermitCount("p1t1")==1,"platform plus AI permit acquire actual service");
        transactions.executeWithoutResult(status -> guard.release(grant.permitId(),"live-op"));
        transactions.executeWithoutResult(status -> guard.release(grant.permitId(),"live-op"));
        require(guard.activePermitCount("p1t1")==0,"actual release idempotent and drained");
        System.out.println("PASS C09-live-state-memory");
    }
}
