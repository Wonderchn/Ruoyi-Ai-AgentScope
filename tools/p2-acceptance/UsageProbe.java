import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;

/** Native synthetic lifecycle acceptance; no provider calls, reset or row deletion. */
public final class UsageProbe {
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static RunLedgerDao runs;
    static UsageLedgerService usage;
    static final String HASH="a".repeat(64);
    static String required(String name) {
        var value=System.getenv(name);
        if(value==null || value.isBlank()) throw new IllegalArgumentException("missing controlled variable "+name);
        return value;
    }
    static void check(boolean ok,String name) {
        System.out.println((ok?"PASS ":"FAIL ")+name);
        if(!ok) throw new AssertionError(name);
    }
    static void transaction(Runnable action) {tx.executeWithoutResult(s->action.run());}
    static String fixture(String tenant) {
        String run="usage-"+UUID.randomUUID().toString().replace("-","");
        transaction(()-> {
            runs.insertRun(tenant,run,"probe-member","probe-subject","chat.submit",null,HASH,"{}","{}","p2-v1",1,1,"[]",null);
            runs.insertReservation(tenant,"reserve-"+UUID.randomUUID().toString().replace("-",""),run,"probe-member",20);
        });
        return run;
    }
    static String call(String tenant,String run) {
        return usage.startCall(tenant,run,1,"native-probe",UsageLedgerService.KIND_CHAT,"owned-probe","no-provider-io",HASH);
    }
    static void reservation(String tenant,String run,String state,long units) {
        var rows=jdbc.queryForList("SELECT state,units FROM ai_budget_reservation WHERE tenant_id=? AND run_id=?",tenant,run);
        check(rows.size()==1 && state.equals(rows.get(0).get("state")) && ((Number)rows.get(0).get("units")).longValue()==units,"reservation "+run+" "+state+" units="+units+" once");
    }
    static void repeat(Runnable action) throws Exception {
        var pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<20;i++) futures.add(pool.submit(action));
            for(var future:futures) future.get(30,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
    }
    public static void main(String[] args) throws Exception {
        var ds=new DriverManagerDataSource(required("P2_USAGE_URL"),"p2p3_ai_app",required("P2_USAGE_PASSWORD"));
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        runs=new RunLedgerDao(jdbc);usage=new UsageLedgerService(jdbc);
        String tenant="usage-probe-"+UUID.randomUUID().toString().replace("-","");
        String known=fixture(tenant), first=call(tenant,known);
        var receipt=Map.<String,Object>of("total_tokens",1500);
        repeat(()->transaction(()->usage.settle(tenant,first,"owned-receipt-1",receipt)));
        check(usage.usageOf(tenant,known).settledCalls()==1 && usage.usageOf(tenant,known).pendingReconciliation()==0,"20 concurrent settlements keep one durable known call");
        repeat(()->transaction(()->usage.finalizeReservation(tenant,known)));
        repeat(()->transaction(()->usage.releaseReservation(tenant,known)));
        reservation(tenant,known,"SETTLED",2);
        String empty=fixture(tenant);
        repeat(()->transaction(()->usage.finalizeReservation(tenant,empty)));
        repeat(()->transaction(()->usage.releaseReservation(tenant,empty)));
        reservation(tenant,empty,"RELEASED",20);
        String unknown=fixture(tenant), missingId=call(tenant,unknown),missingUsage=call(tenant,unknown);
        transaction(()->usage.settle(tenant,missingId,null,receipt));
        transaction(()->usage.settle(tenant,missingUsage,"owned-unknown",null));
        repeat(()->transaction(()->usage.finalizeReservation(tenant,unknown)));
        check(usage.usageOf(tenant,unknown).pendingReconciliation()==2 && usage.usageOf(tenant,unknown).settledCalls()==0,"missing ID and usage are both pending, never zero settlement");
        reservation(tenant,unknown,"RESERVED",20);
        String collision=fixture(tenant), left=call(tenant,collision),right=call(tenant,collision);
        transaction(()->usage.settle(tenant,left,"owned-collision",receipt));
        transaction(()->usage.settle(tenant,right,"owned-collision",receipt));
        repeat(()->transaction(()->usage.finalizeReservation(tenant,collision)));
        check("PROVIDER_ID_COLLISION".equals(jdbc.queryForObject("SELECT error_code FROM ai_model_call WHERE tenant_id=? AND call_id=?",String.class,tenant,right)),"distinct internal call with repeated external receipt remains collision-pending");
        check(usage.usageOf(tenant,collision).settledCalls()==1 && usage.usageOf(tenant,collision).pendingReconciliation()==1,"collision does not silently duplicate usage");
        reservation(tenant,collision,"RESERVED",20);
        String otherTenant=tenant+"-b", other=fixture(otherTenant), otherCall=call(otherTenant,other);
        transaction(()->usage.settle(otherTenant,otherCall,"owned-receipt-1",receipt));
        transaction(()->usage.finalizeReservation(otherTenant,other));
        reservation(otherTenant,other,"SETTLED",2);
        check(jdbc.queryForObject("SELECT count(*) FROM ai_run WHERE tenant_id IN (?,?) AND idempotency_key IS NOT NULL",Integer.class,tenant,otherTenant)==0,"native fixture rows remain excluded from product Worker admission");
        System.out.println("RETAINED_TENANTS="+tenant+","+otherTenant);
        System.out.println("NATIVE_USAGE_ACCEPTANCE=PASS; provider IO=0; real envelope unchanged");
    }
}
