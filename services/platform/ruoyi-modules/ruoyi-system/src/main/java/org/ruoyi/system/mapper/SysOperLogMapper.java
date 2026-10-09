package org.ruoyi.system.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Select;
import org.ruoyi.common.core.constant.TenantConstants;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.system.domain.SysOperLog;
import org.ruoyi.system.domain.vo.SysOperLogVo;

import java.util.List;

/**
 * 操作日志 数据层
 *
 * @author Lion Li
 */
public interface SysOperLogMapper extends BaseMapperPlus<SysOperLog, SysOperLogVo> {

    /**
     * <b>R-2-R4：本表唯一的租户行拦截例外</b>（维护者裁决 §7.1 执行范围第 3 条）。
     *
     * <p>审计写入必须能在<b>没有租户上下文</b>时落库（异步监听器里上下文不传播），
     * 而无归属行要显式写 {@link TenantConstants#PLATFORM_AUDIT_TENANT_ID}。
     * 这里用<b>逐方法</b>的 {@code @InterceptorIgnore}，而不是"把整段回调包进 ignore 作用域"：
     *
     * <ul>
     *   <li>例外的作用对象是<b>一条语句</b>（本表的 INSERT），不是一段可执行任意业务逻辑的回调；</li>
     *   <li>它<b>扩不到别的表</b>——其它表在别的 mapper 上，注解不存在；</li>
     *   <li>它<b>扩不到 SELECT/UPDATE/DELETE</b>——那些是同一 mapper 的其它方法，注解不存在
     *       （本表的普通查询仍按当前租户过滤）；</li>
     *   <li>它<b>不留下任何需要恢复的环境状态</b>——没有 ThreadLocal 作用域，也就没有"忘了恢复"的风险。</li>
     * </ul>
     *
     * <p>调用方（{@code SysOperLogServiceImpl}）必须已把 {@code tenant_id} 显式写进实体；
     * 服务层会拒绝 {@code tenant_id} 为空的行，避免退化成依赖 DDL 默认值。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Override
    int insert(SysOperLog entity);

    /**
     * <b>平台审计域读取</b>（裁决 §7.1 执行范围第 4 条）：只返回无归属审计行。
     *
     * <p>归属值<b>直接内联在 SQL 里</b>而不是走参数：调用方<b>无法</b>通过伪造租户号或伪造保留值
     * 改变这次查询的范围。访问还必须先取得 {@code PlatformAuditAccess}（平台超管专用）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_oper_log WHERE tenant_id = '" + TenantConstants.PLATFORM_AUDIT_TENANT_ID
            + "' ORDER BY oper_id DESC")
    List<SysOperLog> selectPlatformAudit();
}
