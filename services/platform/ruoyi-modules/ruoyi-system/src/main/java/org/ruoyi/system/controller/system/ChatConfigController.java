package org.ruoyi.system.controller.system;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.validate.AddGroup;
import org.ruoyi.common.core.validate.EditGroup;
import org.ruoyi.common.excel.utils.ExcelUtil;
import org.ruoyi.common.idempotent.annotation.RepeatSubmit;
import org.ruoyi.common.log.annotation.Log;
import org.ruoyi.common.log.enums.BusinessType;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.web.core.BaseController;
import org.ruoyi.system.domain.bo.ChatConfigBo;
import org.ruoyi.system.domain.vo.ChatConfigVo;
import org.ruoyi.system.service.IChatConfigService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 配置信息
 *
 * @author ageerle
 * @date 2025-12-14
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/chat/config")
public class ChatConfigController extends BaseController {

    private final IChatConfigService chatConfigService;

    /**
     * 旧配置面的权威声明（RW-06 复核后保持同一语义）。
     *
     * <p>本控制器的读写只落到旧 {@code chat_config} 键值表，<b>不影响运行权威</b>：
     * 决定 run 使用哪个模型/参数/维度/限额的是 {@code platform.ai_runtime_config_revision}
     * 的不可变已发布版本。用户模型选择的读取契约是
     * {@code GET /api/ai/v1/runtime-config/catalog}，发布/撤销/回滚是
     * {@code /api/ai/v1/runtime-config/revisions}。这里的文案刻意指向它们，
     * 避免调用方从旧响应推断"当前模型已被改动"。
     */
    private static final String AUTHORITY_NOTICE =
            "旧配置操作完成；不影响运行权威，请使用 /api/ai/v1/runtime-config/revisions（发布/撤销/回滚）"
                    + " 或 /api/ai/v1/runtime-config/catalog（模型选择读取）";

    @org.springframework.web.bind.annotation.ModelAttribute
    public void runtimeAuthorityNotice(HttpServletResponse response) {
        response.setHeader("X-AI-Affects-Runtime-Authority", "false");
        response.setHeader("X-AI-Runtime-Authority", "platform.ai_runtime_config_revision");
        response.setHeader("Link", "</api/ai/v1/runtime-config/revisions>; rel=\"runtime-config\", "
                + "</api/ai/v1/runtime-config/catalog>; rel=\"runtime-config-catalog\"");
    }

    private R<Void> legacyResult(boolean success) {
        return success ? R.ok(AUTHORITY_NOTICE) : R.fail("旧配置操作失败；不影响运行权威");
    }

    /**
     * 查询配置信息列表
     */
    @SaCheckPermission("system:config:list")
    @GetMapping("/list")
    public TableDataInfo<ChatConfigVo> list(ChatConfigBo bo, PageQuery pageQuery) {
        return chatConfigService.queryPageList(bo, pageQuery);
    }

    /**
     * 导出配置信息列表
     */
    @SaCheckPermission("system:config:export")
    @Log(title = "配置信息", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(ChatConfigBo bo, HttpServletResponse response) {
        List<ChatConfigVo> list = chatConfigService.queryList(bo);
        ExcelUtil.exportExcel(list, "配置信息", ChatConfigVo.class, response);
    }

    /**
     * 获取配置信息详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("system:config:query")
    @GetMapping("/{id}")
    public R<ChatConfigVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(chatConfigService.queryById(id));
    }

    /**
     * 新增配置信息
     */
    @SaCheckPermission("system:config:add")
    @Log(title = "配置信息", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody ChatConfigBo bo) {
        return legacyResult(chatConfigService.insertByBo(bo));
    }

    /**
     * 新增或者修改配置信息
     */
    @SaCheckPermission("system:config:add")
    @Log(title = "新增或者修改配置信息", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/saveOrUpdate")
    public R<Void> saveOrUpdate(@RequestBody List<ChatConfigBo> boList) {
        for (ChatConfigBo chatConfigBo : boList) {
            if (chatConfigBo.getId() == null) {
                chatConfigService.insertByBo(chatConfigBo);
            } else {
                chatConfigService.updateByBo(chatConfigBo);
            }
        }
        return legacyResult(true);
    }


    /**
     * 修改配置信息
     */
    @SaCheckPermission("system:config:edit")
    @Log(title = "配置信息", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody ChatConfigBo bo) {
        return legacyResult(chatConfigService.updateByBo(bo));
    }

    /**
     * 删除配置信息
     *
     * @param ids 主键串
     */
    @SaCheckPermission("system:config:remove")
    @Log(title = "配置信息", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return legacyResult(chatConfigService.deleteWithValidByIds(List.of(ids), true));
    }
}
