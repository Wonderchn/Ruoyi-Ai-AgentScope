package org.ruoyi.system.controller.monitor;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.constant.HttpStatus;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.common.trace.domain.bo.TraceRunBo;
import org.ruoyi.common.trace.domain.vo.TraceDetailVo;
import org.ruoyi.common.trace.domain.vo.TraceNodeVo;
import org.ruoyi.common.trace.domain.vo.TraceRunVo;
import org.ruoyi.common.trace.service.TraceRecordService;
import org.ruoyi.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 链路追踪监控（F18 / RW-23）。
 *
 * <p><b>安全口径（RW-23 修复）</b>：本控制器此前把请求里的 {@link TraceRunBo} 原样下传，
 * 而 {@code TraceRecordServiceImpl} 只在"调用方自己填了 tenantId"时才过滤
 * （{@code .eq(StringUtils.isNotBlank(bo.getTenantId()), ...)}）—— 不传即跨租户全量；
 * 且 {@code run/nodes/detail} 三个按 traceId 的查询<b>完全没有租户校验</b>。现在：
 * <ul>
 *   <li>列表：租户范围只取<b>登录上下文</b>，请求里可伪造的 {@code tenantId} 一律被覆盖；</li>
 *   <li>traceId 查询：命中后校验该链路的租户归属，跨租户与"不存在"<b>同外显</b>
 *       （详情返回 {@code data=null}、节点返回空列表，不泄露该链路是否存在）；</li>
 *   <li>缺租户上下文：显式拒绝（403 语义），<b>不放宽为查全部</b>。</li>
 * </ul>
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/monitor/trace")
public class TraceController extends BaseController {

    private final TraceRecordService traceRecordService;

    /**
     * 获取链路追踪运行列表（仅当前租户）
     */
    @SaCheckPermission("monitor:trace:list")
    @GetMapping("/run/list")
    public TableDataInfo<TraceRunVo> list(TraceRunBo bo, PageQuery pageQuery) {
        // 覆盖请求里的 tenantId：租户范围只能来自登录上下文，不能来自可伪造的查询参数
        bo.setTenantId(currentTenantId());
        return traceRecordService.pageRuns(bo, pageQuery);
    }

    /**
     * 获取链路追踪运行详情（跨租户与不存在同外显：data=null）
     */
    @SaCheckPermission("monitor:trace:query")
    @GetMapping("/run/{traceId}")
    public R<TraceRunVo> run(@PathVariable String traceId) {
        TraceRunVo run = traceRecordService.getRun(traceId);
        return R.ok(visible(run) ? run : null);
    }

    /**
     * 获取链路追踪节点列表（父链路不在当前租户内 → 空列表）
     */
    @SaCheckPermission("monitor:trace:query")
    @GetMapping("/node/list/{traceId}")
    public R<List<TraceNodeVo>> nodes(@PathVariable String traceId) {
        if (!visible(traceRecordService.getRun(traceId))) {
            return R.ok(List.of());
        }
        return R.ok(traceRecordService.listNodes(traceId));
    }

    /**
     * 获取链路追踪完整详情（跨租户与不存在同外显：data=null）
     */
    @SaCheckPermission("monitor:trace:query")
    @GetMapping("/detail/{traceId}")
    public R<TraceDetailVo> detail(@PathVariable String traceId) {
        TraceDetailVo detail = traceRecordService.getDetail(traceId);
        if (detail == null || !visible(detail.getRun())) {
            return R.ok(null);
        }
        return R.ok(detail);
    }

    /**
     * 行是否落在当前租户内：链路租户列必须存在且与当前租户精确相等。
     */
    private boolean visible(TraceRunVo run) {
        return run != null
            && StringUtils.isNotBlank(run.getTenantId())
            && run.getTenantId().equals(currentTenantId());
    }

    /**
     * 当前有效租户（动态租户优先；租户功能关闭时取登录租户）。缺失即拒绝。
     */
    private String currentTenantId() {
        String tenantId = TenantHelper.isEnable() ? TenantHelper.getTenantId() : LoginHelper.getTenantId();
        if (StringUtils.isBlank(tenantId)) {
            throw new ServiceException("缺少租户上下文，拒绝查询链路追踪", HttpStatus.FORBIDDEN);
        }
        return tenantId;
    }
}
