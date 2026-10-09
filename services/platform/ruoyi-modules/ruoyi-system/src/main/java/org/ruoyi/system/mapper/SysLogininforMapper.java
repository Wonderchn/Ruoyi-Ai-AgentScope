package org.ruoyi.system.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Select;
import org.ruoyi.common.core.constant.TenantConstants;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.system.domain.SysLogininfor;
import org.ruoyi.system.domain.vo.SysLogininforVo;

import java.util.List;

/**
 * 系统访问日志情况信息 数据层
 *
 * @author Lion Li
 */
public interface SysLogininforMapper extends BaseMapperPlus<SysLogininfor, SysLogininforVo> {

    /**
     * <b>R-2-R4：本表唯一的租户行拦截例外</b>（维护者裁决 §7.1 执行范围第 3 条）。
     * 语义与 {@code SysOperLogMapper#insert} 完全一致：逐方法的例外，作用对象是一条 INSERT 语句，
     * 扩不到别的表、扩不到本表的其它语句、也不留下需要恢复的环境状态。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Override
    int insert(SysLogininfor entity);

    /**
     * <b>平台审计域读取</b>（裁决 §7.1 执行范围第 4 条）：只返回无归属审计行。
     * 归属值内联在 SQL 里，调用方无法通过任何参数改变查询范围；
     * 访问还必须先取得 {@code PlatformAuditAccess}（平台超管专用）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_logininfor WHERE tenant_id = '" + TenantConstants.PLATFORM_AUDIT_TENANT_ID
            + "' ORDER BY info_id DESC")
    List<SysLogininfor> selectPlatformAudit();
}
