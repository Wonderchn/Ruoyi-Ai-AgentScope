package org.ruoyi.system.controller.system;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.core.validate.AddGroup;
import org.ruoyi.common.core.validate.EditGroup;
import org.ruoyi.common.excel.utils.ExcelUtil;
import org.ruoyi.common.idempotent.annotation.RepeatSubmit;
import org.ruoyi.common.log.annotation.Log;
import org.ruoyi.common.log.enums.BusinessType;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.web.core.BaseController;
import org.ruoyi.system.domain.bo.SysClientBo;
import org.ruoyi.system.domain.vo.SysClientVo;
import org.ruoyi.system.service.ISysClientService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;

/**
 * 客户端管理
 *
 * @author Michelle.Chung
 * @date 2023-06-18
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/client")
public class SysClientController extends BaseController {

    private final ISysClientService sysClientService;

    /**
     * C1.3：密钥只存引用/掩码，**永不明文返回**。
     * <p>WP-039（T4）发现 B：`sys_client` 是全局表（无 `tenant_id` 列），而本控制器只要求
     * `system:client:*` 权限、没有超管角色闸，`SysClientVo` 又直接带 `clientSecret` ⇒
     * 任意被授予 `system:client:list` 的角色都能读到全部客户端明文密钥（已运行期实证）。
     * <p>掩码放在**响应边界**而不是 VO：`export` 走 EasyExcel（不看 Jackson 注解），
     * 只有在这一层统一改写才能同时覆盖 JSON 列表/详情与 Excel 导出；`queryByClientId`
     * 的内部登录路径不经本控制器，因此仍能拿到真实值（实测无任何运行期代码读取
     * `SysClientVo#getClientSecret()`）。
     */
    private static final String SECRET_MASK = "******";

    private static void maskSecrets(Collection<SysClientVo> rows) {
        if (rows == null) {
            return;
        }
        for (SysClientVo vo : rows) {
            if (vo != null && StringUtils.isNotBlank(vo.getClientSecret())) {
                vo.setClientSecret(SECRET_MASK);
            }
        }
    }

    /**
     * 查询客户端管理列表
     */
    @SaCheckPermission("system:client:list")
    @GetMapping("/list")
    public TableDataInfo<SysClientVo> list(SysClientBo bo, PageQuery pageQuery) {
        TableDataInfo<SysClientVo> page = sysClientService.queryPageList(bo, pageQuery);
        maskSecrets(page.getRows());
        return page;
    }

    /**
     * 导出客户端管理列表
     */
    @SaCheckPermission("system:client:export")
    @Log(title = "客户端管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(SysClientBo bo, HttpServletResponse response) {
        List<SysClientVo> list = sysClientService.queryList(bo);
        maskSecrets(list);
        ExcelUtil.exportExcel(list, "客户端管理", SysClientVo.class, response);
    }

    /**
     * 获取客户端管理详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("system:client:query")
    @GetMapping("/{id}")
    public R<SysClientVo> getInfo(@NotNull(message = "主键不能为空")
                                  @PathVariable Long id) {
        SysClientVo vo = sysClientService.queryById(id);
        maskSecrets(vo == null ? null : List.of(vo));
        return R.ok(vo);
    }

    /**
     * 新增客户端管理
     */
    @SaCheckPermission("system:client:add")
    @Log(title = "客户端管理", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody SysClientBo bo) {
        if (!sysClientService.checkClickKeyUnique(bo)) {
            return R.fail("新增客户端'" + bo.getClientKey() + "'失败，客户端key已存在");
        }
        return toAjax(sysClientService.insertByBo(bo));
    }

    /**
     * 修改客户端管理
     */
    @SaCheckPermission("system:client:edit")
    @Log(title = "客户端管理", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody SysClientBo bo) {
        if (!sysClientService.checkClickKeyUnique(bo)) {
            return R.fail("修改客户端'" + bo.getClientKey() + "'失败，客户端key已存在");
        }
        return toAjax(sysClientService.updateByBo(bo));
    }

    /**
     * 状态修改
     */
    @SaCheckPermission("system:client:edit")
    @Log(title = "客户端管理", businessType = BusinessType.UPDATE)
    @PutMapping("/changeStatus")
    public R<Void> changeStatus(@RequestBody SysClientBo bo) {
        return toAjax(sysClientService.updateClientStatus(bo.getClientId(), bo.getStatus()));
    }

    /**
     * 删除客户端管理
     *
     * @param ids 主键串
     */
    @SaCheckPermission("system:client:remove")
    @Log(title = "客户端管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(sysClientService.deleteWithValidByIds(List.of(ids), true));
    }
}
