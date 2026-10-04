import com.nageoffer.ai.ragent.runtime.*;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.sql.*;
import java.util.concurrent.TimeUnit;

/** Dedicated persistent provider + two JVM lease/fence gate. No business schemas. */
public class Probe34b {
    static final String TENANT="p34-tenant", RUN="p34-run", MEMBER="platform:p34-tenant:1001";
    static JdbcTemplate jdbc;
    static RunLedgerDao dao;
    static RunEventAppender events;
    @Configuration @EnableTransactionManagement(proxyTargetClass=true)
    static class Config {
        @Bean DriverManagerDataSource dataSource(){return new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"),"p2app",System.getenv("AI_DB_PASSWORD"));}
        @Bean JdbcTemplate jdbcTemplate(DriverManagerDataSource source){return new JdbcTemplate(source);}
        @Bean PlatformTransactionManager transactionManager(DriverManagerDataSource source){return new DataSourceTransactionManager(source);}
        @Bean RunLedgerDao ledger(JdbcTemplate jdbc){return new RunLedgerDao(jdbc);}
        @Bean RunEventAppender appender(RunLedgerDao ledger){return new RunEventAppender(ledger,new ObjectMapper());}
    }
    static void check(boolean pass,String id){System.out.println((pass?"PASS ":"FAIL ")+id);if(!pass)throw new AssertionError(id);}
    static void waitFor(String stage,int seconds)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<end){if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM probe_control WHERE stage=?)",Boolean.class,stage)))return;Thread.sleep(100);}
        throw new AssertionError("missing stage "+stage);
    }
    static void stage(String value){jdbc.update("INSERT INTO probe_control(stage)VALUES(?) ON CONFLICT DO NOTHING",value);}
    static Process child(String mode)throws Exception{
        var p=new ProcessBuilder("java","-cp",System.getProperty("java.class.path"),"Probe34b",mode).inheritIO().start();
        System.out.println("OWNED_PID mode="+mode+" pid="+p.pid());return p;
    }
    static boolean rejected(Runnable operation){try{operation.run();return false;}catch(RunApiException expected){return expected.errorCode()==RunErrorCode.VERSION_CONFLICT;}}
    static Connection provider()throws Exception{return DriverManager.getConnection(System.getenv("SANDBOX_JDBC_URL"),System.getenv("SANDBOX_DB_USER"),System.getenv("SANDBOX_DB_PASSWORD"));}
    static String post(String key,String hash)throws Exception{
        try(var c=provider()){
            try(var p=c.prepareStatement("INSERT INTO requests(operation_key,args_hash)VALUES(?,?)")){p.setString(1,key);p.setString(2,hash);p.executeUpdate();}
            try(var p=c.prepareStatement("INSERT INTO tickets(operation_key,args_hash,external_id)VALUES(?,?,?) ON CONFLICT(operation_key) DO NOTHING")){p.setString(1,key);p.setString(2,hash);p.setString(3,"ticket-"+key);p.executeUpdate();}
            try(var p=c.prepareStatement("SELECT args_hash,external_id FROM tickets WHERE operation_key=?")){p.setString(1,key);try(var r=p.executeQuery()){if(!r.next()||!hash.equals(r.getString(1)))throw new IllegalArgumentException("provider args mismatch");return r.getString(2);}}
        }
    }
    static String lookup(String key,String hash)throws Exception{
        try(var c=provider();var p=c.prepareStatement("SELECT args_hash,external_id FROM tickets WHERE operation_key=?")){
            p.setString(1,key);try(var r=p.executeQuery()){if(!r.next())return null;if(!hash.equals(r.getString(1)))throw new IllegalArgumentException("provider args mismatch");return r.getString(2);}
        }
    }
    static boolean compatibleState(String tenant,String member,String run,String engine,int version) {
        return jdbc.queryForObject("SELECT count(*) FROM probe_state WHERE state_key='bound-state' AND tenant_id=? AND member_id=? AND run_id=? AND engine_version=? AND checkpoint_version=?",
                Integer.class,tenant,member,run,engine,version)==1;
    }
    public static void main(String[] args)throws Exception {
        try(var context=new AnnotationConfigApplicationContext(Config.class)){
            jdbc=context.getBean(JdbcTemplate.class);dao=context.getBean(RunLedgerDao.class);events=context.getBean(RunEventAppender.class);
            if(args.length>0 && args[0].startsWith("race-")) {
                stage(args[0]+"-ready");waitFor("race-go",10);
                while(true) {
                    var maybe=dao.claimNext(args[0],20);if(maybe.isEmpty())break;var claim=maybe.get();
                    dao.withLiveFence(TENANT,claim.run().runId(),args[0],claim.newFence(),()->{
                        jdbc.update("INSERT INTO probe_claim(run_id,worker)VALUES(?,?)",claim.run().runId(),args[0]);return null;});
                    events.terminal(TENANT,claim.run().runId(),args[0],claim.newFence(),"SUCCEEDED",Map.of("race",true),null);
                    Thread.sleep(100);
                } return;
            }
            if(args.length>0 && args[0].endsWith("-crash")) {
                var claim=dao.claimNext(args[0],3).orElseThrow();String run=claim.run().runId(),key=run+"-operation";
                dao.withLiveFence(TENANT,run,args[0],claim.newFence(),()->{
                    jdbc.update("INSERT INTO probe_tool(operation_key,args_hash,state,fence)VALUES(?,'args-v1','STARTED',?)",key,claim.newFence());return null;});
                String result=post(key,"args-v1");
                if("checkpoint-crash".equals(args[0])) dao.commitStep(TENANT,run,"tool-write",claim.newAttempt(),"tool-write","{\"externalId\":\""+result+"\"}",null,null,claim.newFence(),args[0]);
                Runtime.getRuntime().halt("checkpoint-crash".equals(args[0])?86:87);
            }
            if(args.length>0 && "worker-a".equals(args[0])){
                var claim=dao.claimNext("worker-a",3).orElseThrow();check(RUN.equals(claim.run().runId()),"A-claims-formal-only");
                dao.withLiveFence(TENANT,RUN,"worker-a",claim.newFence(),()->{jdbc.update("INSERT INTO probe_tool(operation_key,args_hash,state,fence)VALUES('same-operation','args-v1','STARTED',?)",claim.newFence());return null;});
                post("same-operation","args-v1");stage("provider-written-response-lost");waitFor("release-stale-owner",40);
                check(rejected(()->dao.commitStep(TENANT,RUN,"stale",claim.newAttempt(),"stale","{}",null,null,claim.newFence(),"worker-a")),"G04-old-step-rejected");
                check(rejected(()->events.appendFenced(TENANT,RUN,"worker-a",claim.newFence(),"run.output_delta",Map.of("text","stale"))),"G04-old-event-rejected");
                check(rejected(()->dao.withLiveFence(TENANT,RUN,"worker-a",claim.newFence(),()->jdbc.update("INSERT INTO probe_state(state_key,payload)VALUES('stale','{}')"))),"G04-old-state-rejected");
                check(rejected(()->dao.withLiveFence(TENANT,RUN,"worker-a",claim.newFence(),()->jdbc.update("UPDATE probe_tool SET state='STALE_RESULT' WHERE operation_key='same-operation'"))),"G04-old-tool-result-rejected");
                stage("stale-owner-rejected");return;
            }
            if(args.length>0 && "worker-b".equals(args[0])){
                waitFor("provider-written-response-lost",15);Thread.sleep(3500);dao.sweepExpiredLeases();
                var claim=dao.claimNext("worker-b",20).orElseThrow();check(claim.newFence()>1,"G04-new-fence");
                check(!dao.renewLease(TENANT,RUN,"worker-a",1,30),"G04-stale-renew-rejected");
                dao.withLiveFence(TENANT,RUN,"worker-b",claim.newFence(),()->{jdbc.update("UPDATE probe_tool SET state='UNKNOWN' WHERE operation_key='same-operation'");return null;});
                check(lookup("temporarily-absent","args-v1")==null,"G03-absent-is-not-final");
                check("UNKNOWN".equals(jdbc.queryForObject("SELECT state FROM probe_tool WHERE operation_key='same-operation'",String.class)),"G03-unknown-preserved");
                String result=lookup("same-operation","args-v1");check(result!=null,"G03-found-existing-write");
                dao.withLiveFence(TENANT,RUN,"worker-b",claim.newFence(),()->{
                    jdbc.update("UPDATE probe_tool SET state='SUCCEEDED',result=?,fence=? WHERE operation_key='same-operation'",result,claim.newFence());
                    dao.commitStep(TENANT,RUN,"tool-write",claim.newAttempt(),"tool-write","{\"externalId\":\""+result+"\"}",null,null,claim.newFence(),"worker-b");
                    jdbc.update("INSERT INTO probe_state(state_key,payload,tenant_id,member_id,run_id,engine_version,checkpoint_version)VALUES('bound-state','{}',?,?,?,'agentscope-2.0.2',1)",TENANT,MEMBER,RUN);return null;
                });
                stage("checkpoint-committed");stage("release-stale-owner");waitFor("stale-owner-rejected",15);
                check(dao.latestCompletedStep(TENANT,RUN,"tool-write").isPresent(),"G03-completed-step-reused");
                check(compatibleState(TENANT,MEMBER,RUN,"agentscope-2.0.2",1),"G02-bound-state-load");
                check(!compatibleState(TENANT,"wrong-member",RUN,"agentscope-2.0.2",1),"G02-wrong-member-rejected");
                check(!compatibleState(TENANT,MEMBER,RUN,"different-engine",1),"G02-engine-mismatch-rejected");
                check(!compatibleState(TENANT,MEMBER,RUN,"agentscope-2.0.2",2),"G02-checkpoint-version-rejected");
                check(rejected(()->dao.withLiveFence("wrong-tenant",RUN,"worker-b",claim.newFence(),()->1)),"G04-wrong-tenant-rejected");
                check(rejected(()->dao.withLiveFence(TENANT,"wrong-run","worker-b",claim.newFence(),()->1)),"G04-wrong-run-rejected");
                events.terminal(TENANT,RUN,"worker-b",claim.newFence(),"SUCCEEDED",Map.of("reconciled",true),null);return;
            }
            dao.insertRun(TENANT,"legacy-row",MEMBER,"1001","rag.chat",null,null,null,null,"p1-read-only",1,1,"[]",null);
            dao.insertRun(TENANT,RUN,MEMBER,"1001","agent.run","probe-key","hash","{}","{}","p34-v1",1,1,"[]",null);
            Process a=child("worker-a"),b=child("worker-b");
            try {check(a.waitFor(50,TimeUnit.SECONDS)&&a.exitValue()==0,"worker-a-exit");check(b.waitFor(50,TimeUnit.SECONDS)&&b.exitValue()==0,"worker-b-exit");}
            finally {if(a.isAlive())a.destroyForcibly();if(b.isAlive())b.destroyForcibly();}
            try(var c=provider();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM tickets WHERE operation_key='same-operation'")){r.next();check(r.getInt(1)==1,"G03-actual-external-write-once");}
            check("QUEUED".equals(dao.findRun(TENANT,"legacy-row").orElseThrow().status()),"P1-readonly-row-not-claimed");
            check(dao.findRun(TENANT,RUN).orElseThrow().isTerminal(),"G03-terminal-after-reconcile");
            check(jdbc.queryForObject("SELECT count(*) FROM probe_state WHERE state_key='stale'",Integer.class)==0,"G04-no-stale-state");
            check("ticket-same-operation".equals(post("same-operation","args-v1")),"G03-idempotent-original-key");
            boolean altered=false;try{post("same-operation","changed-args");}catch(IllegalArgumentException e){altered=true;}check(altered,"G03-key-args-conflict");
            for(String mode:List.of("tool-crash","checkpoint-crash")) {
                String run=mode+"-run",key=run+"-operation";
                dao.insertRun(TENANT,run,MEMBER,"1001","agent.run",run,"hash","{}","{}","p34-v1",1,1,"[]",null);
                Process crashed=child(mode);try {check(crashed.waitFor(10,TimeUnit.SECONDS)&&crashed.exitValue()==(mode.equals("tool-crash")?87:86),mode+"-actual-halt");}
                finally {if(crashed.isAlive())crashed.destroyForcibly();}
                Thread.sleep(3500);dao.sweepExpiredLeases();var claim=dao.claimNext("recovery",20).orElseThrow();
                check(run.equals(claim.run().runId()),mode+"-reclaimed");
                boolean saved=dao.latestCompletedStep(TENANT,run,"tool-write").isPresent();
                check(saved==mode.equals("checkpoint-crash"),mode+"-checkpoint-window");
                String found=lookup(key,"args-v1");check(found!=null,mode+"-reconcile-without-post");
                if(!saved) dao.commitStep(TENANT,run,"tool-write",claim.newAttempt(),"tool-write","{}",null,null,claim.newFence(),"recovery");
                events.terminal(TENANT,run,"recovery",claim.newFence(),"SUCCEEDED",Map.of("reconciled",true),null);
                try(var c=provider();var p=c.prepareStatement("SELECT count(*) FROM requests WHERE operation_key=?")) {
                    p.setString(1,key);try(var r=p.executeQuery()){r.next();check(r.getInt(1)==1,mode+"-one-post");}
                }
            }
            for(int i=0;i<10;i++) dao.insertRun(TENANT,"race-run-"+i,MEMBER,"1001","agent.run","race-key-"+i,"hash","{}","{}","p34-v1",1,1,"[]",null);
            Process raceA=child("race-a"),raceB=child("race-b");
            try {waitFor("race-a-ready",10);waitFor("race-b-ready",10);stage("race-go");
                check(raceA.waitFor(15,TimeUnit.SECONDS)&&raceA.exitValue()==0,"race-a-exit");
                check(raceB.waitFor(15,TimeUnit.SECONDS)&&raceB.exitValue()==0,"race-b-exit");
            } finally {if(raceA.isAlive())raceA.destroyForcibly();if(raceB.isAlive())raceB.destroyForcibly();}
            check(jdbc.queryForObject("SELECT count(*) FROM probe_claim",Integer.class)==10,"G04-ten-competing-claims-once");
            check(jdbc.queryForObject("SELECT count(DISTINCT worker) FROM probe_claim",Integer.class)==2,"G04-two-actual-claimers");
            check(jdbc.queryForObject("SELECT count(*) FROM ai_run WHERE run_id LIKE 'race-run-%' AND attempt=1 AND status='SUCCEEDED'",Integer.class)==10,"G04-all-races-fence-one");
            System.out.println("PASS 34B-production-substrate");
        }
    }
}
