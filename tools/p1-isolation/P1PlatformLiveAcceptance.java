/* Licensed under the Apache License, Version 2.0. */
import org.ruoyi.aiidentity.*;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.system.aiidentity.AiPolicyRevisionServiceImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.ruoyi.system.aiidentity.mapper.SysAiPolicyRevisionMapper;

/** Real coordinator + JDBC + unavailable HTTP node. No test HTTP endpoints are shipped. */
public class P1PlatformLiveAcceptance {
    public static void main(String[] args) {
        var ds = new DriverManagerDataSource(System.getenv("P1C_PLATFORM_DB_URL"),"platform_app",System.getenv("PLATFORM_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(ds);
        int before=jdbc.queryForObject("SELECT version FROM sys_ai_policy_revision WHERE tenant_id='p1t1'",Integer.class);
        var properties = new AiIntegrationProperties();
        boolean blocked=args.length>0 && args[0].equals("blocked");
        boolean drained=args.length>0 && args[0].equals("drained");
        properties.setAiBaseUrl(System.getenv(blocked || drained ? "P1C_AI_URL" : "P1C_STOPPED_AI_URL"));
        properties.setServiceCredential(System.getenv("P1C_SERVICE"));
        var configuration=new MybatisConfiguration();
        configuration.setEnvironment(new Environment("synthetic",new SpringManagedTransactionFactory(),ds));
        configuration.addMapper(SysAiPolicyRevisionMapper.class);
        var sessions=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var coordinator = new RevocationBarrierCoordinator(jdbc,new AiPolicyRevisionServiceImpl(sessions.getMapper(SysAiPolicyRevisionMapper.class)),new HttpAiBarrierClient(properties),
                new TransactionTemplate(new DataSourceTransactionManager(ds)));
        var result = coordinator.drainAndBump("p1t1",blocked || drained ? "c10" : "c10-node-down","synthetic live drill");
        int after=jdbc.queryForObject("SELECT version FROM sys_ai_policy_revision WHERE tenant_id='p1t1'",Integer.class);
        String state=jdbc.queryForObject("SELECT status FROM sys_ai_tenant_barrier WHERE tenant_id='p1t1'",String.class);
        if (blocked) {
            if(result.closed() || before!=after || !"PENDING".equals(state) || result.remaining()!=1) throw new AssertionError("active permit incorrectly drained");
            System.out.println("PASS C10-coordinator-timeout pv unchanged, ACTIVE retains PENDING");
            return;
        }
        if (drained) {
            if(!result.closed() || after!=before+1 || result.newVersion()!=after || !"OPEN".equals(state) || result.remaining()!=0) throw new AssertionError("released permit did not drain and bump");
            if(jdbc.queryForObject("SELECT count(*) FROM sys_ai_execution_permit WHERE tenant_id='p1t1' AND status='ACTIVE'",Long.class)!=0) throw new AssertionError("platform permits not released");
            System.out.println("PASS C10-release-drain-bump pv advanced exactly once, platform OPEN");
            return;
        }
        if(result.closed() || before!=after || !"PENDING".equals(state) || result.remaining()!=-1) throw new AssertionError("node fault incorrectly acknowledged");
        System.out.println("PASS C10-node-unreachable pv unchanged, committed platform PENDING");
    }
}
