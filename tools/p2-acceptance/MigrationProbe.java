import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Native migration acceptance against an explicitly owned synthetic database. */
public final class MigrationProbe {
    static final String URL = required("P2_MIGRATION_URL");
    static final String PASS = required("P2_MIGRATION_PASSWORD");
    static final Path ROOT = Path.of(required("P2_MIGRATION_REPO"));
    static final Path WORK = Path.of(required("P2_MIGRATION_WORK"));
    static String required(String name) {
        String value=System.getenv(name);
        if(value==null || value.isBlank()) throw new IllegalArgumentException("missing controlled variable "+name);
        return value;
    }
    static Flyway migration(String schema,String user,Path directory) {
        return Flyway.configure().dataSource(URL,user,PASS).defaultSchema(schema).schemas(schema)
            .createSchemas(false).cleanDisabled(true).initSql("SET search_path TO "+schema+",extensions")
            .locations("filesystem:"+directory.toAbsolutePath().toString().replace('\\','/')).load();
    }
    static void check(boolean ok,String name) {
        System.out.println((ok?"PASS ":"FAIL ")+name);
        if(!ok) throw new AssertionError(name);
    }
    static void denied(String user,String sql,String name) throws Exception {
        try(Connection connection=DriverManager.getConnection(URL,user,PASS);Statement statement=connection.createStatement()) {
            try {statement.execute(sql);throw new AssertionError(name+" unexpectedly permitted");}
            catch(SQLException exception) {check("42501".equals(exception.getSQLState()),name+" SQLSTATE="+exception.getSQLState());}
        }
    }
    public static void main(String[] args) throws Exception {
        Path platform=ROOT.resolve("services/platform/docs/script/sql/postgres");
        Path ai=ROOT.resolve("services/ai/resources/database/postgres/migrations");
        var platformFlyway=migration("platform","p2p3_platform_mig",platform);
        var aiFlyway=migration("ai","p2p3_ai_mig",ai);
        check(platformFlyway.migrate().migrationsExecuted==6,"platform actual migrations=6");
        check(aiFlyway.migrate().migrationsExecuted==12,"ai actual migrations=12");
        platformFlyway.validate();aiFlyway.validate();
        check(platformFlyway.migrate().migrationsExecuted==0 && aiFlyway.migrate().migrationsExecuted==0,"repeat migration executes zero");
        platformFlyway.validate();aiFlyway.validate();
        check(Arrays.stream(platformFlyway.info().applied()).allMatch(i->i.getState().isApplied()),"platform history applied");
        check(Arrays.stream(aiFlyway.info().applied()).allMatch(i->i.getState().isApplied()),"ai history applied");
        Path drift=WORK.resolve("copied-ai-drift");Files.createDirectories(drift);
        try(var files=Files.list(ai)) {for(Path file:files.filter(p->p.getFileName().toString().matches("V[0-9]+__.*\\.sql")).toList()) Files.copy(file,drift.resolve(file.getFileName()));}
        Files.writeString(drift.resolve("V12__agent_action_ledgers.sql"),"\nSELECT 1;\n",StandardOpenOption.APPEND);
        try {migration("ai","p2p3_ai_mig",drift).validate();throw new AssertionError("checksum drift accepted");}
        catch(FlywayValidateException expected) {check(expected.getMessage().contains("checksum mismatch"),"private copied checksum drift rejected");}
        aiFlyway.validate();
        try {migration("platform","p2p3_ai_mig",ai).migrate();throw new AssertionError("wrong schema accepted");}
        catch(org.flywaydb.core.api.FlywayException expected) {check(expected.getMessage().toLowerCase(Locale.ROOT).contains("permission denied"),"wrong schema migration role denied");}
        denied("p2p3_ai_app","CREATE TABLE ai.forbidden_ddl(id int)","AI runtime DDL denied");
        denied("p2p3_platform_app","CREATE TABLE platform.forbidden_ddl(id int)","platform runtime DDL denied");
        denied("p2p3_ai_app","SELECT count(*) FROM platform.sys_user","AI cross-schema read denied");
        denied("p2p3_platform_app","SELECT count(*) FROM ai.ai_run","platform cross-schema read denied");
        try(Connection c=DriverManager.getConnection(URL,"p2p3_ai_mig",PASS);Statement s=c.createStatement()) {
            try(ResultSet r=s.executeQuery("SELECT count(*) FROM ai.flyway_schema_history WHERE success")) {r.next();check(r.getInt(1)==12,"durable ai history=12");}
            for(String table:List.of("ai_run","ai_run_event","outbox_event","t_agent_state","t_agent_memory","ai_tool_call","ai_action_approval","ai_action_reconciliation","ai_action_inheritance","ai_agent_checkpoint")) {
                try(ResultSet r=s.executeQuery("SELECT to_regclass('ai."+table+"') IS NOT NULL")) {r.next();check(r.getBoolean(1),"retained table "+table);}
            }
            var original=new ObjectMapper().readTree(ROOT.resolve("services/ai/rag/src/test/resources/p1/table-attribution.json").toFile()).path("tables");
            check(original.size()>0,"original attribution ledger nonempty");
            for(var table:original) {
                String name=table.path("name").asText();
                if(!name.matches("[a-z0-9_]+")) throw new IllegalArgumentException("invalid attribution name");
                try(ResultSet r=s.executeQuery("SELECT to_regclass('ai."+name+"') IS NOT NULL")) {r.next();check(r.getBoolean(1),"P1 attribution "+name);}
            }
            System.out.println("P1_ATTRIBUTION_COUNT="+original.size());
        }
        System.out.println("MIGRATION_PROBE_COMPLETE");
    }
}
