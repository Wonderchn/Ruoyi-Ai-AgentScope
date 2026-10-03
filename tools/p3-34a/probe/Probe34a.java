/*
 * P3 U00 / 34A 探针（专属测试，不属于产品路径）。
 *
 * 观察目标：agentscope-core 2.0.2 的 ReActAgent 何时调用 AgentStateStore 的
 * save/get（时机与内容），工具成功到检查点提交的两个窗口内强杀 JVM 后，
 * 实际加载入口（get/getList）能否恢复、外部副作用（假工具调用）是否重复。
 *
 * 证据全部落库：probe_log（store 调用流水）、probe_tools（假工具外部副作用）、
 * probe_state（状态负载）。窗口 W1/W2 通过 System.exit 在精确点终止本 JVM，
 * 由外部脚本记录 PID/退出码后重启（--window=resume）。
 */
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.State;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class Probe34a {

    static String jdbcUrl;
    static String dbUser;
    static String dbPass;
    static String sessionId;
    static String window;
    static int crashAfterSaves;

    static synchronized void log(Connection c, String event, String key, String detail) {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO probe34a.probe_log(event, session_id, state_key, detail) VALUES (?,?,?,?)")) {
            ps.setString(1, event);
            ps.setString(2, sessionId);
            ps.setString(3, key);
            ps.setString(4, detail);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[probe] log failed: " + e);
        }
        System.out.println("[probe:" + event + "] " + key + " " + detail);
    }

    /** 最小 AgentStateStore：与 t_agent_state 同键形（tenant/member/session/state_key），每次调用留痕。 */
    static class JdbcStateStore implements AgentStateStore {
        final Connection conn;
        int saves = 0;
        int gets = 0;

        JdbcStateStore(Connection conn) {
            this.conn = conn;
        }

        @Override
        public void save(String userId, String session, String key, State value) {
            save(userId, session, key, List.of(value));
        }

        @Override
        public void save(String userId, String session, String key, List<? extends State> values) {
            try {
                String payload = io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(values);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO probe34a.probe_state(tenant_id, member_id, session_id, state_key, payload, update_time) "
                                + "VALUES ('probe-tenant','probe-member',?,?,?::jsonb,now()) "
                                + "ON CONFLICT (tenant_id, member_id, session_id, state_key) DO UPDATE SET payload=EXCLUDED.payload, update_time=now()")) {
                    ps.setString(1, session);
                    ps.setString(2, key);
                    ps.setString(3, payload);
                    ps.executeUpdate();
                }
                saves++;
                log(conn, "save", key, "count=" + saves + " bytes=" + payload.length());
                if (window.equals("W2") && saves >= crashAfterSaves) {
                    System.out.println("[probe] W2: halting after save #" + saves);
                    Runtime.getRuntime().halt(98);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public <T extends State> Optional<T> get(String userId, String session, String key, Class<T> type) {
            gets++;
            String payload = null;
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT payload FROM probe34a.probe_state WHERE tenant_id='probe-tenant' AND member_id='probe-member' AND session_id=? AND state_key=?")) {
                    ps.setString(1, session);
                    ps.setString(2, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            payload = rs.getString(1);
                        }
                    }
                }
                if (payload == null) {
                    log(conn, "getMissing", key, "count=" + gets + " type=" + type.getSimpleName());
                    return Optional.empty();
                }
                List<?> raw = io.agentscope.core.util.JsonUtils.getJsonCodec().fromJson(payload, List.class);
                T value = raw.isEmpty() ? null : io.agentscope.core.util.JsonUtils.getJsonCodec().convertValue(raw.get(0), type);
                log(conn, "getLoaded", key, "count=" + gets + " type=" + type.getSimpleName() + " present=" + (value != null));
                return Optional.ofNullable(value);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public <T extends State> List<T> getList(String userId, String session, String key, Class<T> type) {
            gets++;
            log(conn, "getList", key, "count=" + gets + " type=" + type.getSimpleName());
            List<T> result = new ArrayList<>();
            try {
                String payload = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT payload FROM probe34a.probe_state WHERE tenant_id='probe-tenant' AND member_id='probe-member' AND session_id=? AND state_key=?")) {
                    ps.setString(1, session);
                    ps.setString(2, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            payload = rs.getString(1);
                        }
                    }
                }
                if (payload != null) {
                    List<?> raw = io.agentscope.core.util.JsonUtils.getJsonCodec().fromJson(payload, List.class);
                    for (Object item : raw) {
                        result.add(io.agentscope.core.util.JsonUtils.getJsonCodec().convertValue(item, type));
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            log(conn, "getListLoaded", key, "items=" + result.size());
            return result;
        }

        @Override
        public boolean exists(String userId, String session) {
            log(conn, "exists", "-", "session=" + session);
            return false;
        }

        @Override
        public void delete(String userId, String session) {
            log(conn, "delete", "-", "session=" + session);
        }

        @Override
        public Set<String> listSessionIds(String userId) {
            return Set.of();
        }
    }

    /** 脚本假模型：第 1 次调用发出 probe_tool 工具调用，第 2 次返回文本。 */
    static class ScriptedModel implements Model {
        int calls = 0;

        @Override
        public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            calls++;
            int call = calls;
            if (call == 1) {
                return Flux.just(new ChatResponse("resp-1",
                        List.of(new ToolUseBlock("call-probe-1", "probe_tool", Map.of("arg", "value-1"))),
                        new ChatUsage(10, 5, 15), Map.of(), "tool_calls"));
            }
            return Flux.just(new ChatResponse("resp-2",
                    List.of(TextBlock.builder().text("probe answered after tool").build()),
                    new ChatUsage(10, 5, 15), Map.of(), "stop"));
        }

        @Override
        public String getModelName() {
            return "probe-scripted";
        }

        @Override
        public int getContextWindowSize() {
            return 8192;
        }
    }

    /** 假工具：先落外部副作用账（probe_tools），W1 窗口在返回前强杀本 JVM。 */
    static class ProbeTool implements AgentTool {
        final Connection conn;

        ProbeTool(Connection conn) {
            this.conn = conn;
        }

        @Override
        public String getName() {
            return "probe_tool";
        }

        @Override
        public String getDescription() {
            return "probe tool that records an external side effect";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of("arg", Map.of("type", "string")));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO probe34a.probe_tools(session_id, tool, args) VALUES (?,?,?)")) {
                ps.setString(1, sessionId);
                ps.setString(2, "probe_tool");
                ps.setString(3, String.valueOf(param.getInput()));
                ps.executeUpdate();
            } catch (Exception e) {
                return Mono.just(ToolResultBlock.error("probe tool ledger failed: " + e));
            }
            if (window.equals("W1")) {
                System.out.println("[probe] W1: side effect recorded, halting before result commit");
                Runtime.getRuntime().halt(97);
            }
            ToolUseBlock use = param.getToolUseBlock();
            return Mono.just(ToolResultBlock.of(use.getId(), use.getName(),
                    TextBlock.builder().text("probe tool ok").build()));
        }
    }

    public static void main(String[] args) throws Exception {
        window = "A1";
        sessionId = "s-" + System.currentTimeMillis();
        String action = "run";
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            int eq = arg.indexOf('=');
            String key = eq > 0 ? arg.substring(0, eq) : arg;
            String value = eq > 0 ? arg.substring(eq + 1) : args[++i];
            switch (key) {
                case "--window" -> window = value;
                case "--session" -> sessionId = value;
                case "--action" -> action = value;
                case "--jdbc" -> jdbcUrl = value;
                case "--db-user" -> dbUser = value;
                case "--db-pass" -> dbPass = value;
                case "--crash-save-count" -> crashAfterSaves = Integer.parseInt(value);
                default -> System.err.println("unknown arg " + arg);
            }
        }
        Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
        log(conn, "boot", "-", "window=" + window + " session=" + sessionId + " action=" + action
                + " pid=" + ProcessHandle.current().pid());

        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new ProbeTool(conn));

        ReActAgent agent = ReActAgent.builder()
                .name("Probe34a")
                .sysPrompt("You are a probe. When asked, call the probe_tool once, then answer.")
                .model(new ScriptedModel())
                .toolkit(toolkit)
                .maxIters(5)
                .maxRetries(2)
                .stateStore(new JdbcStateStore(conn))
                .build();

        RuntimeContext ctx = RuntimeContext.builder()
                .userId("probe-user")
                .sessionId(sessionId)
                .build();

        Msg reply = agent.call(action, ctx).block();
        log(conn, "done", "-", "reply=" + (reply == null ? "null" : reply.getContent().get(0).toString()));
        System.out.println("[probe] complete");
        System.exit(0);
    }
}
