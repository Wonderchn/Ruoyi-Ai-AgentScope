package org.ruoyi.common.tenant.handle;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.tenant.exception.TenantException;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.common.tenant.properties.TenantProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义租户处理器
 *
 * <p><b>R-2（租户空上下文 fail-closed 收口）</b>：本类此前在拿不到租户号时
 * {@code ignoreTable} 直接 {@code return true}，即<b>对任何表都不注入租户谓词</b>。
 * 于是"让租户上下文变空"就等于"读到全平台数据"——缺上下文不是报错，而是静默放行。
 * 现在按<b>三类表</b>分别处置，只有租户业务表会走到"必须有可信租户"这一步：
 *
 * <ol>
 *   <li><b>全局共享表（第一类）</b>：冻结 DDL 里<b>没有</b> {@code tenant_id} 列
 *       （纯关联表、平台注册表、遗留统一表），根本没有可过滤的列，永远不过滤。
 *       权威清单 = {@link #PERMITTED_SHARED_TABLES}（代码）+ 代码生成器表。实测：
 *       {@code sys_menu}、{@code sys_role_menu}、{@code sys_user_role}、{@code sys_role_dept}、
 *       {@code sys_user_post}、{@code sys_tenant_package}、{@code sys_client}、
 *       {@code flow_spel}、{@code ai_legacy_user}、{@code ai_provider_envelope}、
 *       {@code ai_provider_spend} 共 11 张。</li>
 *   <li><b>显式允许的系统表（第二类）</b>：{@link #PLATFORM_LEVEL_TABLES}，<b>确有</b>
 *       {@code tenant_id} 列，但按架构属平台级、不做行级过滤。写死在代码里。</li>
 *   <li><b>租户业务表（第三类）</b>：其余全部。<b>租户功能启用且拿不到可信租户时，
 *       在数据库操作之前抛 {@link TenantException} 拒绝</b>，不再放行。</li>
 * </ol>
 *
 * <p><b>R-2-R2（F2）——"配置不能放宽跳过面"在运行时成立</b>：{@code tenant.excludes}
 * 被<b>保留</b>为共享表的声明，但它<b>不再参与分类</b>（{@link #classify} 只读代码清单），
 * 且在构造时被校验为 {@link #PERMITTED_SHARED_TABLES} 的子集：越界即抛
 * {@link TenantException}（{@code tenant.context.excludes.widened}）让上下文<b>启动失败</b>。
 * 于是"把 {@code sys_user} 写进 excludes 就能让它不过滤"这条路在运行时不成立，
 * 而不只是在静态 YAML 测试里不成立。
 *
 * <p><b>R-2-R2（F3）——已登录但主体无租户不再被当作可信</b>：见
 * {@link #requireTrustedPrincipalTenant}；"缺少可信 tenant"包括"已登录却没有主体租户"。
 *
 * <p><b>为什么"在数据库操作前"成立</b>：MyBatis-Plus 的
 * {@code TenantLineInnerInterceptor} 在 {@code beforeQuery}/{@code beforePrepare} 里、
 * 也就是<b>语句下发之前</b>逐表调用本类的 {@code ignoreTable}；抛异常则 SQL 永不下发。
 * 读与写（SELECT/INSERT/UPDATE/DELETE）走同一条判定。
 *
 * <p><b>明确、可审计的跨租户执行上下文</b>（第二类之外的合法例外）：
 * <ul>
 *   <li>{@code TenantHelper.ignore(...)}：经 {@code InterceptorIgnoreHelper} 短路，
 *       <b>根本不进入本类</b>，调用点即审计点（可 grep）；</li>
 *   <li>{@code TenantHelper.dynamic(tenantId, ...)} 与超管租户切换端点
 *       {@code SysTenantController#dynamicTenant}（{@code @SaCheckRole(superadmin)}）——
 *       本类对"已登录但动态租户 ≠ 自身租户"的情形做超管校验（见
 *       {@link #isTrustedForCurrentPrincipal}），非超管的跨租户切换一律拒绝。</li>
 * </ul>
 *
 * <p><b>不做的事</b>：不以任何默认租户补齐缺失上下文（{@code 000000} 也不行）——缺就是缺，
 * 只能拒绝。也不把 {@code ignoreTable} 对所有表统一改成 {@code false}：全局共享表与
 * 显式允许的系统表必须继续免过滤，否则会把共享目录读成空、并对没有该列的表演化出
 * 不存在的列谓词。
 *
 * @author Lion Li
 */
@Slf4j
public class PlusTenantLineHandler implements TenantLineHandler {

    /**
     * 代码生成器表：冻结 DDL 无租户列，平台级，永远不过滤。
     */
    private static final List<String> CODE_GEN_TABLES = List.of(
            "gen_table",
            "gen_table_column"
    );

    /**
     * <b>第二类：显式允许的系统表</b>——确有 {@code tenant_id} 列，但按架构属平台级，
     * 不做行级过滤。写死在此处是<b>刻意</b>的：放宽过滤必须经过代码评审，不能靠改配置。
     *
     * <ul>
     *   <li>{@code sys_tenant}：租户注册表本身。平台超管需要列出/管理全部租户；
     *       按当前租户过滤会把租户管理页读成只剩自己一行。</li>
     *   <li>{@code sys_oss_config}：全局文件存储配置。各租户共用同一份 OSS 接入配置，
     *       RuoYi 既有语义即如此（改动会打断所有租户的文件上传）。</li>
     *   <li>{@code ai_flow_trace_run} / {@code ai_flow_trace_node}：<b>平台运维链路追踪</b>。
     *       不是租户业务数据，依据见下。</li>
     * </ul>
     *
     * <p><b>{@code ai_flow_trace_*} 为什么不属于租户业务数据（依据，不是默认）</b>：
     * <ol>
     *   <li>写入方<b>没有任何代码</b>给这两个实体赋 {@code tenantId}
     *       （{@code TraceRecordServiceImpl.startRun/startNode} 直接 {@code insert}，
     *       {@code TraceRun}/{@code TraceNode} 从无 {@code setTenantId} 调用点）；
     *       MyBatis-Plus 默认不下发 null 字段，落库值只可能来自 DDL 默认
     *       {@code '000000'}。也就是说这些行今天<b>本就没有被租户归属</b>。</li>
     *   <li>写入发生在流式/异步链路，租户上下文不传播；若强行纳入行拦截器，
     *       注入的会是 NULL（该列可空），等于<b>把平台遥测错误地标成某个租户</b>，
     *       比不过滤更危险。</li>
     *   <li>读面已单独收口：{@code TraceController}（RW-23 / {@code b90f4585}）用
     *       登录租户覆盖客户端传入的 {@code tenantId}，并在无租户上下文时 403，
     *       不依赖本行拦截器。</li>
     * </ol>
     * ⇒ 保留其 excludes 项是<b>基于以上事实的决定</b>；若将来要把 trace 做成租户可见数据，
     * 必须先用新迁移补归属并在写入侧建立可信上下文，而不是从本清单移除。
     */
    private static final List<String> PLATFORM_LEVEL_TABLES = List.of(
            "sys_tenant",
            "sys_oss_config",
            "ai_flow_trace_run",
            "ai_flow_trace_node"
    );

    /**
     * <b>第一类的权威清单</b>：允许"不过滤"的共享表全集。
     *
     * <p><b>R-2-R2（F2）</b>：本清单是<b>代码</b>事实，不是配置事实。此前
     * {@code sharedTables()} 直接采用 {@code TenantProperties.excludes}，
     * 于是"配置不能放宽过滤"只被仓内 YAML 的静态测试守住，<b>运行时不成立</b>——
     * 把 {@code sys_user} 写进 excludes 就能让它缺上下文时照样跳过。
     * 现在改为：
     * <ul>
     *   <li>{@link #classify} 只认这份代码清单（配置对分类<b>没有</b>影响力）；</li>
     *   <li>构造时校验 {@code tenant.excludes} ⊆ 本清单，越界即<b>响亮失败</b>
     *       （见 {@link #PlusTenantLineHandler(TenantProperties)}）。</li>
     * </ul>
     * 因此"放宽跳过面"只有一条路：改这份清单并过代码评审。
     *
     * <p>配置<b>收窄</b>也不生效（分类不读配置）：这是刻意的——这些表在冻结 DDL 里
     * 没有 {@code tenant_id} 列，一旦被当成租户业务表就会生成不存在的列谓词。
     */
    private static final List<String> PERMITTED_SHARED_TABLES = List.of(
            // ── 纯关联表 / 平台注册表（冻结 DDL 无 tenant_id 列）──
            "sys_menu",
            "sys_tenant_package",
            "sys_role_dept",
            "sys_role_menu",
            "sys_user_post",
            "sys_user_role",
            "sys_client",
            // ── 平台管理的共享表达式目录（见报告 §flow_spel）──
            "flow_spel",
            // ── 冻结形状无 tenant_id 列的遗留统一表 ──
            "ai_legacy_user",
            "ai_provider_envelope",
            "ai_provider_spend",
            // ── 第二类：确有 tenant_id 列但按架构属平台级 ──
            "sys_tenant",
            "sys_oss_config",
            "ai_flow_trace_run",
            "ai_flow_trace_node"
    );

    /**
     * 表的三分类。判定次序与处置见类注释。
     */
    public enum TableScope {
        /** 第一类：全局共享表，无 tenant_id 列，永远不过滤。 */
        GLOBAL_SHARED,
        /** 第二类：显式允许的系统表，有 tenant_id 列但平台级，不过滤。 */
        PLATFORM_LEVEL,
        /** 第三类：租户业务表，必须限域；缺可信租户即拒绝。 */
        TENANT_BUSINESS
    }

    private final TenantProperties tenantProperties;

    public PlusTenantLineHandler(TenantProperties tenantProperties) {
        this.tenantProperties = tenantProperties;
        // 早可见性：配置放宽在这里先留下一条 ERROR 日志；真正的拒绝在首次使用时发生
        // （见 ignoreTable 的 requireNoWidenedExcludes），因为"首次使用"才是语句即将下发的时刻。
        List<String> widened = widenedExcludes(tenantProperties);
        if (!widened.isEmpty()) {
            log.error("tenant.excludes 包含不在代码允许清单内的表：{}；"
                    + "任何租户业务数据操作都会被拒绝，请改 PlusTenantLineHandler.PERMITTED_SHARED_TABLES "
                    + "并经过代码评审", widened);
        }
    }

    /**
     * 配置里越界的 excludes 项（空列表表示配置合法）。
     *
     * <p>大小写不敏感比较，与 {@link #classify} 的匹配口径一致。
     */
    private static List<String> widenedExcludes(TenantProperties tenantProperties) {
        List<String> widened = new ArrayList<>();
        if (tenantProperties == null || tenantProperties.getExcludes() == null) {
            return widened;
        }
        for (String configured : tenantProperties.getExcludes()) {
            if (configured == null || configured.isBlank()) {
                continue;
            }
            if (!StringUtils.equalsAnyIgnoreCase(configured.trim(),
                    PERMITTED_SHARED_TABLES.toArray(new String[0]))) {
                widened.add(configured.trim());
            }
        }
        return widened;
    }

    /**
     * <b>F2 的运行期收口</b>：配置一旦放宽了不过滤的表集合，就在<b>首次使用</b>时响亮失败，
     * 而不是静默按配置跳过。
     *
     * <p>为什么放在"首次使用"而不是"构造"：语句即将下发的时刻才是收口点
     * （构造只是装配）；而且这样"直接构造 handler"的调用方与 Spring 装配走<b>同一条</b>判定，
     * 不存在"绕过装配校验"的形态。
     */
    private void requireNoWidenedExcludes() {
        List<String> widened = widenedExcludes(tenantProperties);
        if (!widened.isEmpty()) {
            log.error("tenant.excludes 试图放宽不过滤的表集合，已拒绝本次数据库操作：越界项={}；"
                    + "允许的共享表清单由 PlusTenantLineHandler.PERMITTED_SHARED_TABLES 定义，"
                    + "变更必须经过代码评审", widened);
            throw new TenantException("tenant.context.excludes.widened", String.join(",", widened));
        }
    }

    /**
     * 把表名归入三类之一。公开出来是为了让分类本身可以被判据直接钉住
     * （新增/移除一张平台级表必须是一次显式决定，见 R-2 判据）。
     *
     * <p>只认代码清单：配置<b>不能</b>把一张表加进共享集（{@link #requireNoWidenedExcludes}
     * 会拒绝），也<b>不能</b>把它移出（本方法不读配置）。
     */
    public TableScope classify(String tableName) {
        if (StringUtils.equalsAnyIgnoreCase(tableName,
                PLATFORM_LEVEL_TABLES.toArray(new String[0]))) {
            return TableScope.PLATFORM_LEVEL;
        }
        if (StringUtils.equalsAnyIgnoreCase(tableName,
                PERMITTED_SHARED_TABLES.toArray(new String[0]))
                || StringUtils.equalsAnyIgnoreCase(tableName,
                CODE_GEN_TABLES.toArray(new String[0]))) {
            return TableScope.GLOBAL_SHARED;
        }
        return TableScope.TENANT_BUSINESS;
    }

    @Override
    public Expression getTenantId() {
        String tenantId = TenantHelper.getTenantId();
        if (StringUtils.isBlank(tenantId)) {
            // 防御深度：本分支在正常拦截器流程中已由 ignoreTable 提前拒绝而不可达，
            // 保留是为了任何直接调用也不会退化成 "tenant_id = NULL" 的静默空结果。
            throw new TenantException("tenant.context.missing");
        }
        return new StringValue(tenantId);
    }

    @Override
    public boolean ignoreTable(String tableName) {
        // 0) 租户功能未启用：本就不存在租户过滤（与既有语义一致）。
        if (!TenantHelper.isEnable()) {
            return true;
        }

        // 0.5) F2：配置放宽了不过滤的表集合 ⇒ 首次使用即响亮失败（早于任何分类/谓词）。
        requireNoWidenedExcludes();

        TableScope scope = classify(tableName);
        // 1) 第一类 / 第二类：不是租户业务数据，不过滤。
        if (scope != TableScope.TENANT_BUSINESS) {
            return true;
        }

        // 2) 第三类：租户业务表，必须有可信租户。
        String tenantId = TenantHelper.getTenantId();
        if (StringUtils.isBlank(tenantId)) {
            log.error("租户业务表 {} 缺少可信租户上下文（tenant.enable=true 且无登录主体/无动态租户），"
                    + "已在数据库操作前拒绝", tableName);
            throw new TenantException("tenant.context.missing");
        }
        requireTrustedPrincipalTenant(tableName, tenantId);
        return false;
    }

    /**
     * 要求"当前生效租户号"对该调用方可信，否则在数据库操作前抛异常。
     *
     * <p><b>R-2-R2（F3）</b>：此前"已登录但 {@code LoginHelper.getTenantId()} 为空"被当作可信
     * （{@code return true}），于是只要塞进任意动态租户就能限域查询——<b>已登录却没有主体租户
     * 正是"缺少可信 tenant"</b>，必须拒绝，而不是放行。现在按三种情形分别处置：
     *
     * <ol>
     *   <li><b>未登录</b>：目标租户由认证/注册流程在核验主体后显式设置（{@code PasswordAuthStrategy}
     *       等的 {@code TenantHelper.dynamic}），放行；</li>
     *   <li><b>已登录但主体无租户</b> ⇒ {@code tenant.context.missing}，<b>拒绝</b>（F3）；</li>
     *   <li><b>已登录且主体有租户</b>：租户号等于自身 ⇒ 放行；不同 ⇒ 仅平台超管放行
     *       （{@code SysTenantController#dynamicTenant} 是 {@code @SaCheckRole("superadmin")}），
     *       其余按 {@code tenant.context.forged} 拒绝。</li>
     * </ol>
     */
    private void requireTrustedPrincipalTenant(String tableName, String tenantId) {
        if (!LoginHelper.isLogin()) {
            return;
        }
        String loginTenant = LoginHelper.getTenantId();
        if (StringUtils.isBlank(loginTenant)) {
            log.error("租户业务表 {} 的调用方已登录但主体没有租户（缺可信 tenant），"
                    + "不得凭动态租户 {} 放行，已在数据库操作前拒绝", tableName, tenantId);
            throw new TenantException("tenant.context.missing");
        }
        if (StringUtils.equals(tenantId, loginTenant)) {
            return;
        }
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        log.error("租户业务表 {} 的租户上下文 {} 与登录主体租户 {} 不一致且调用方不是平台超管，"
                + "已按租户伪造拒绝", tableName, tenantId, loginTenant);
        throw new TenantException("tenant.context.forged");
    }

}
