package org.ruoyi.system.service.impl;

import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.constant.TenantConstants;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.core.utils.ip.AddressUtils;
import org.ruoyi.common.log.event.OperLogEvent;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.tenant.audit.PlatformAuditAccess;
import org.ruoyi.common.tenant.audit.PlatformAuditAttribution;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.system.domain.SysOperLog;
import org.ruoyi.system.domain.bo.SysOperLogBo;
import org.ruoyi.system.domain.vo.SysOperLogVo;
import org.ruoyi.system.mapper.SysOperLogMapper;
import org.ruoyi.system.service.ISysOperLogService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 操作日志 服务层处理
 *
 * @author Lion Li
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class SysOperLogServiceImpl implements ISysOperLogService {

    private final SysOperLogMapper baseMapper;

    /**
     * 操作日志记录
     *
     * <p><b>R-2-R4</b>：本方法在 {@code @Async} 线程执行，租户上下文<b>不传播</b>
     * （本项目无 {@code TaskDecorator}），因此不能读"当前租户"。租户由
     * {@code LogAspect.handleLog} 在<b>入队前</b>从<b>服务端会话</b>捕获
     * （{@code operLog.setTenantId(LoginHelper.getTenantId())}），归属判定与显式写入
     * 见 {@link #insertOperlog}。</p>
     *
     * @param operLogEvent 操作日志事件
     */
    @Async
    @EventListener
    public void recordOper(OperLogEvent operLogEvent) {
        SysOperLogBo operLog = MapstructUtils.convert(operLogEvent, SysOperLogBo.class);
        // 远程查询操作地点
        operLog.setOperLocation(AddressUtils.getRealAddressByIP(operLog.getOperIp()));
        insertOperlog(operLog);
    }

    /**
     * 分页查询操作日志列表
     *
     * @param operLog   查询条件
     * @param pageQuery 分页参数
     * @return 操作日志分页列表
     */
    @Override
    public TableDataInfo<SysOperLogVo> selectPageOperLogList(SysOperLogBo operLog, PageQuery pageQuery) {
        LambdaQueryWrapper<SysOperLog> lqw = buildQueryWrapper(operLog);
        if (StringUtils.isBlank(pageQuery.getOrderByColumn())) {
            lqw.orderByDesc(SysOperLog::getOperId);
        }
        Page<SysOperLogVo> page = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(page);
    }

    private LambdaQueryWrapper<SysOperLog> buildQueryWrapper(SysOperLogBo operLog) {
        Map<String, Object> params = operLog.getParams();
        return new LambdaQueryWrapper<SysOperLog>()
            .like(StringUtils.isNotBlank(operLog.getOperIp()), SysOperLog::getOperIp, operLog.getOperIp())
            .like(StringUtils.isNotBlank(operLog.getTitle()), SysOperLog::getTitle, operLog.getTitle())
            .eq(operLog.getBusinessType() != null && operLog.getBusinessType() > 0,
                SysOperLog::getBusinessType, operLog.getBusinessType())
            .func(f -> {
                if (ArrayUtil.isNotEmpty(operLog.getBusinessTypes())) {
                    f.in(SysOperLog::getBusinessType, Arrays.asList(operLog.getBusinessTypes()));
                }
            })
            .eq(operLog.getStatus() != null,
                SysOperLog::getStatus, operLog.getStatus())
            .like(StringUtils.isNotBlank(operLog.getOperName()), SysOperLog::getOperName, operLog.getOperName())
            .between(params.get("beginTime") != null && params.get("endTime") != null,
                SysOperLog::getOperTime, params.get("beginTime"), params.get("endTime"));
    }

    /**
     * 新增操作日志
     *
     * <p><b>R-2-R4（裁决 §7.1）</b>：审计行的归属在这里<b>显式</b>定下并写进实体，
     * 不依赖任何环境上下文、也不依赖 DDL 默认值：
     * <ol>
     *   <li>归属已确认（{@code LogAspect} 从服务端会话捕获到非空租户）⇒ 写该真实租户，
     *       并在<b>真实租户作用域内</b>执行 INSERT；</li>
     *   <li>归属不可确认 ⇒ 写保留值
     *       {@link TenantConstants#PLATFORM_AUDIT_TENANT_ID} 并记录原因；
     *       INSERT 走 {@code SysOperLogMapper#insert} 上<b>逐方法</b>的租户行拦截例外
     *       （例外只覆盖这一条语句，见该 mapper 的 javadoc）。</li>
     * </ol>
     *
     * @param bo 操作日志对象
     */
    @Override
    public void insertOperlog(SysOperLogBo bo) {
        SysOperLog operLog = MapstructUtils.convert(bo, SysOperLog.class);
        operLog.setOperTime(new Date());
        PlatformAuditAttribution.Attribution attribution =
                PlatformAuditAttribution.resolveWithReason(bo.getTenantId(), StringUtils.isNotBlank(bo.getTenantId()));
        operLog.setTenantId(attribution.tenantId());
        if (attribution.unattributed()) {
            log.warn("操作日志无归属：reason={}，记入平台审计域 tenant_id={}",
                    attribution.reason(), attribution.tenantId());
            insertAuditRow(operLog);
            return;
        }
        TenantHelper.dynamic(attribution.tenantId(), () -> insertAuditRow(operLog));
    }

    /**
     * 审计行落库的唯一入口。
     *
     * <p>显式拒绝"归属为空"的行：{@code sys_oper_log.tenant_id} 有 DDL 默认 {@code '000000'}，
     * 一旦让空值落下去就会变成"用默认租户冒充归属"——裁决明令禁止。这里在数据库操作前拦住。
     */
    private void insertAuditRow(SysOperLog operLog) {
        if (StringUtils.isBlank(operLog.getTenantId())) {
            throw new ServiceException("审计行必须显式携带归属（真实租户或平台审计标记），不得依赖 DDL 默认值");
        }
        baseMapper.insert(operLog);
    }

    /**
     * <b>平台审计域读取</b>（裁决 §7.1 执行范围第 4 条）：无归属审计行只对平台超管开放，
     * 且必须持有 {@link PlatformAuditAccess}（类型系统强制过门）。租户作用域下的任何普通查询
     * 都读不到这些行——普通查询走继承的 {@code selectXxx}，仍按当前租户过滤。
     */
    @Override
    public List<SysOperLogVo> selectPlatformAudit(PlatformAuditAccess access) {
        if (access == null) {
            throw new ServiceException("平台审计域访问必须持有 PlatformAuditAccess");
        }
        return baseMapper.selectPlatformAudit().stream()
                .map(row -> MapstructUtils.convert(row, SysOperLogVo.class))
                .toList();
    }

    /**
     * 查询系统操作日志集合
     *
     * @param operLog 操作日志对象
     * @return 操作日志集合
     */
    @Override
    public List<SysOperLogVo> selectOperLogList(SysOperLogBo operLog) {
        LambdaQueryWrapper<SysOperLog> lqw = buildQueryWrapper(operLog);
        return baseMapper.selectVoList(lqw.orderByDesc(SysOperLog::getOperId));
    }

    /**
     * 批量删除系统操作日志
     *
     * @param operIds 需要删除的操作日志ID
     * @return 结果
     */
    @Override
    public int deleteOperLogByIds(Long[] operIds) {
        return baseMapper.deleteByIds(Arrays.asList(operIds));
    }

    /**
     * 查询操作日志详细
     *
     * @param operId 操作ID
     * @return 操作日志对象
     */
    @Override
    public SysOperLogVo selectOperLogById(Long operId) {
        return baseMapper.selectVoById(operId);
    }

    /**
     * 清空操作日志
     */
    @Override
    public void cleanOperLog() {
        baseMapper.delete(new LambdaQueryWrapper<>());
    }
}
