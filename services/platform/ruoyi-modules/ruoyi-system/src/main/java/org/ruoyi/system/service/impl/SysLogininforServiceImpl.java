package org.ruoyi.system.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.constant.Constants;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.core.utils.ip.AddressUtils;
import org.ruoyi.common.log.event.LoginClientFacts;
import org.ruoyi.common.log.event.LogininforEvent;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.tenant.audit.PlatformAuditAccess;
import org.ruoyi.common.tenant.audit.PlatformAuditAttribution;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.system.domain.SysLogininfor;
import org.ruoyi.system.domain.bo.SysLogininforBo;
import org.ruoyi.system.domain.vo.SysClientVo;
import org.ruoyi.system.domain.vo.SysLogininforVo;
import org.ruoyi.system.mapper.SysLogininforMapper;
import org.ruoyi.system.service.ISysClientService;
import org.ruoyi.system.service.ISysLogininforService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 系统访问日志情况信息 服务层处理
 *
 * @author Lion Li
 */
@RequiredArgsConstructor
@Slf4j
@Service
public class SysLogininforServiceImpl implements ISysLogininforService {

    private final SysLogininforMapper baseMapper;

    private final ISysClientService clientService;

    /**
     * 记录登录信息
     *
     * <p>G-53（RW-14）：客户端事实全部来自事件入队前捕获的 {@link LoginClientFacts} 不可变快照。
     * 本方法在 {@code @Async} 线程执行，<b>绝不读取 {@link jakarta.servlet.http.HttpServletRequest}</b>：
     * 请求结束后容器回收/复位请求对象，旧实现（读 UA/IP/client 三处）会拿到错值或 NPE 并静默丢审计。</p>
     *
     * @param logininforEvent 登录事件
     */
    @Async
    @EventListener
    public void recordLogininfor(LogininforEvent logininforEvent) {
        LoginClientFacts facts = ObjectUtil.defaultIfNull(logininforEvent.getClientFacts(), LoginClientFacts.unknown());
        try {
            // 客户端信息：仅用快照里的 clientId 解析（缓存查询，不再触碰请求）
            SysClientVo client = null;
            if (StringUtils.isNotBlank(facts.clientId())) {
                client = clientService.queryByClientId(facts.clientId());
            }
            String address = AddressUtils.getRealAddressByIP(facts.ip());
            // 打印信息到日志（脱敏 IP + 控制字符清洗，防日志伪造）
            log.info("{}", auditLogLine(facts, address, logininforEvent));
            // 封装对象
            SysLogininforBo logininfor = new SysLogininforBo();
            logininfor.setTenantId(logininforEvent.getTenantId());
            logininfor.setTenantVerified(logininforEvent.isTenantVerified());
            logininfor.setUserName(logininforEvent.getUsername());
            if (ObjectUtil.isNotNull(client)) {
                logininfor.setClientKey(client.getClientKey());
                logininfor.setDeviceType(client.getDeviceType());
            }
            logininfor.setIpaddr(facts.ip());
            logininfor.setLoginLocation(address);
            logininfor.setBrowser(facts.browser());
            logininfor.setOs(facts.os());
            logininfor.setMsg(logininforEvent.getMessage());
            // 日志状态
            if (StringUtils.equalsAny(logininforEvent.getStatus(), Constants.LOGIN_SUCCESS, Constants.LOGOUT, Constants.REGISTER)) {
                logininfor.setStatus(Constants.SUCCESS);
            } else if (Constants.LOGIN_FAIL.equals(logininforEvent.getStatus())) {
                logininfor.setStatus(Constants.FAIL);
            }
            // 插入数据
            // R-2-R4：本方法在 @Async 线程执行，租户上下文不传播（本项目无 TaskDecorator），
            // 不能读"当前租户"。归属由事件在入队前捕获（含"服务端是否核验过该租户"这一事实），
            // 由 insertLogininfor 显式判定并写进实体——不再有任何 ignore 作用域，
            // 也不会被下面的 catch 吞成一条 WARN 而静默丢失。
            insertLogininfor(logininfor);
        } catch (RuntimeException e) {
            // 审计是旁路：失败不得冒泡到登录流程，但必须留下可检索痕迹（旧实现在缺 UA 时 NPE 静默丢行）
            log.warn("login_audit_failed tenantId={} status={} errorType={}",
                logininforEvent.getTenantId(), logininforEvent.getStatus(), e.getClass().getName(), e);
        }
    }

    /**
     * 审计日志行：沿用 [ip][地点][账号][状态][消息] 形状，但 IP 脱敏、各字段清洗控制字符。
     */
    static String auditLogLine(LoginClientFacts facts, String address, LogininforEvent event) {
        StringBuilder line = new StringBuilder();
        line.append(getBlock(facts.maskedIp()));
        line.append(sanitizeLogValue(address));
        line.append(getBlock(sanitizeLogValue(event.getUsername())));
        line.append(getBlock(sanitizeLogValue(event.getStatus())));
        line.append(getBlock(sanitizeLogValue(event.getMessage())));
        Object[] args = event.getArgs();
        if (args != null && args.length > 0) {
            line.append(getBlock(sanitizeLogValue(Arrays.deepToString(args))));
        }
        return line.toString();
    }

    /**
     * 去掉控制字符（CR/LF/TAB 等），防止账号/消息里的换行伪造日志行。
     */
    static String sanitizeLogValue(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch < 0x20 || ch == 0x7f) {
                continue;
            }
            builder.append(ch);
        }
        return builder.toString();
    }

    private static String getBlock(Object msg) {
        if (msg == null) {
            msg = "";
        }
        return "[" + msg + "]";
    }

    /**
     * 分页查询登录日志列表
     *
     * @param logininfor 查询条件
     * @param pageQuery  分页参数
     * @return 登录日志分页列表
     */
    @Override
    public TableDataInfo<SysLogininforVo> selectPageLogininforList(SysLogininforBo logininfor, PageQuery pageQuery) {
        Map<String, Object> params = logininfor.getParams();
        LambdaQueryWrapper<SysLogininfor> lqw = new LambdaQueryWrapper<SysLogininfor>()
            .like(StringUtils.isNotBlank(logininfor.getIpaddr()), SysLogininfor::getIpaddr, logininfor.getIpaddr())
            .eq(StringUtils.isNotBlank(logininfor.getStatus()), SysLogininfor::getStatus, logininfor.getStatus())
            .like(StringUtils.isNotBlank(logininfor.getUserName()), SysLogininfor::getUserName, logininfor.getUserName())
            .between(params.get("beginTime") != null && params.get("endTime") != null,
                SysLogininfor::getLoginTime, params.get("beginTime"), params.get("endTime"));
        if (StringUtils.isBlank(pageQuery.getOrderByColumn())) {
            lqw.orderByDesc(SysLogininfor::getInfoId);
        }
        Page<SysLogininforVo> page = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(page);
    }

    /**
     * 新增系统登录日志
     *
     * @param bo 访问日志对象
     */
    @Override
    public void insertLogininfor(SysLogininforBo bo) {
        SysLogininfor logininfor = MapstructUtils.convert(bo, SysLogininfor.class);
        logininfor.setLoginTime(new Date());
        // R-2-R4（裁决 §7.1）：归属在这里显式定下并写进实体，不依赖环境上下文、也不依赖 DDL 默认值。
        // 请求自行声明的租户只有在服务端核验过（事件里的 tenantVerified）时才算可信归属。
        PlatformAuditAttribution.Attribution attribution =
                PlatformAuditAttribution.resolveWithReason(bo.getTenantId(), bo.isTenantVerified());
        logininfor.setTenantId(attribution.tenantId());
        if (attribution.unattributed()) {
            log.warn("登录审计无归属：reason={}，记入平台审计域 tenant_id={}",
                    attribution.reason(), attribution.tenantId());
            insertAuditRow(logininfor);
            return;
        }
        TenantHelper.dynamic(attribution.tenantId(), () -> insertAuditRow(logininfor));
    }

    /**
     * 审计行落库的唯一入口。显式拒绝"归属为空"的行——{@code sys_logininfor.tenant_id}
     * 有 DDL 默认 {@code '000000'}，让空值落下去就是"用默认租户冒充归属"（裁决明令禁止）。
     */
    private void insertAuditRow(SysLogininfor logininfor) {
        if (StringUtils.isBlank(logininfor.getTenantId())) {
            throw new ServiceException("审计行必须显式携带归属（真实租户或平台审计标记），不得依赖 DDL 默认值");
        }
        baseMapper.insert(logininfor);
    }

    /**
     * <b>平台审计域读取</b>（裁决 §7.1 执行范围第 4 条）：无归属登录审计只对平台超管开放，
     * 且必须持有 {@link PlatformAuditAccess}。租户作用域下的普通查询仍按当前租户过滤。
     */
    @Override
    public List<SysLogininforVo> selectPlatformAudit(PlatformAuditAccess access) {
        if (access == null) {
            throw new ServiceException("平台审计域访问必须持有 PlatformAuditAccess");
        }
        return baseMapper.selectPlatformAudit().stream()
                .map(row -> MapstructUtils.convert(row, SysLogininforVo.class))
                .toList();
    }

    /**
     * 查询系统登录日志集合
     *
     * @param logininfor 访问日志对象
     * @return 登录记录集合
     */
    @Override
    public List<SysLogininforVo> selectLogininforList(SysLogininforBo logininfor) {
        Map<String, Object> params = logininfor.getParams();
        return baseMapper.selectVoList(new LambdaQueryWrapper<SysLogininfor>()
            .like(StringUtils.isNotBlank(logininfor.getIpaddr()), SysLogininfor::getIpaddr, logininfor.getIpaddr())
            .eq(StringUtils.isNotBlank(logininfor.getStatus()), SysLogininfor::getStatus, logininfor.getStatus())
            .like(StringUtils.isNotBlank(logininfor.getUserName()), SysLogininfor::getUserName, logininfor.getUserName())
            .between(params.get("beginTime") != null && params.get("endTime") != null,
                SysLogininfor::getLoginTime, params.get("beginTime"), params.get("endTime"))
            .orderByDesc(SysLogininfor::getInfoId));
    }

    /**
     * 批量删除系统登录日志
     *
     * @param infoIds 需要删除的登录日志ID
     * @return 结果
     */
    @Override
    public int deleteLogininforByIds(Long[] infoIds) {
        return baseMapper.deleteByIds(Arrays.asList(infoIds));
    }

    /**
     * 清空系统登录日志
     */
    @Override
    public void cleanLogininfor() {
        baseMapper.delete(new LambdaQueryWrapper<>());
    }
}
