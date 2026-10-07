package org.ruoyi.system.controller.system;


import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.ObjectUtil;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.validate.QueryGroup;
import org.ruoyi.common.web.core.BaseController;
import org.ruoyi.common.log.annotation.Log;
import org.ruoyi.common.log.enums.BusinessType;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.system.domain.bo.SysOssBo;
import org.ruoyi.system.domain.vo.SysOssUploadVo;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 文件上传 控制层
 *
 * @author Lion Li
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/resource/oss")
public class SysOssController extends BaseController {

    private final ISysOssService ossService;

    /**
     * 查询OSS对象存储列表
     */
    @SaCheckPermission("system:oss:list")
    @GetMapping("/list")
    public TableDataInfo<SysOssVo> list(@Validated(QueryGroup.class) SysOssBo bo, PageQuery pageQuery) {
        return ossService.queryPageList(bo, pageQuery);
    }

    /**
     * 查询OSS对象基于id串
     *
     * @param ossIds OSS对象ID串
     */
    @SaCheckPermission("system:oss:query")
    @GetMapping("/listByIds/{ossIds}")
    public R<List<SysOssVo>> listByIds(@NotEmpty(message = "主键不能为空")
                                       @PathVariable Long[] ossIds) {
        List<SysOssVo> list = ossService.listByIds(Arrays.asList(ossIds));
        return R.ok(list);
    }

    /**
     * 上传OSS对象存储
     *
     * @param file 文件
     */
    @SaCheckPermission("system:oss:upload")
    @Log(title = "OSS对象存储", businessType = BusinessType.INSERT)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<SysOssUploadVo> upload(@RequestPart("file") MultipartFile file) {
        if (ObjectUtil.isNull(file)) {
            return R.fail("上传文件不能为空");
        }
        SysOssVo oss = ossService.upload(file);
        SysOssUploadVo uploadVo = new SysOssUploadVo();
        uploadVo.setUrl(oss.getUrl());
        uploadVo.setFileName(oss.getOriginalName());
        uploadVo.setOssId(oss.getOssId().toString());
        return R.ok(uploadVo);
    }

    /**
     * 上传文件（千问百炼版）
     *
     * <p>WP-039（T4）补权限复核：本端点此前只有 {@code @Log}、没有任何 {@code @SaCheckPermission}，
     * 而同文件的 {@code /upload} 要求 {@code system:oss:upload} —— 即"任何已登录用户都能写对象存储"，
     * 违反 D04「无映射拒绝」与"缺身份/空 ACL/未知动作/撤权/停用一律拒绝"。上游参考
     * （ruoyi-ai SysOssController:90）同样是裸端点，属继承缺口，不是本仓引入。
     * 已核：全仓（含 services/web 两个前端应用与 T7 的 admin）**无任何调用点**，因此与兄弟端点对齐、
     * 复用同一条 {@code system:oss:upload} 权限串（不新增规范动作、不新增权限行）。</p>
     *
     * @param file 文件
     */
    @SaCheckPermission("system:oss:upload")
    @Log(title = "上传文件（千问百炼版）", businessType = BusinessType.INSERT)
    @PostMapping(value = "/fileUpload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<SysOssUploadVo> fileUpload(@RequestPart("file") MultipartFile file) {
        if (ObjectUtil.isNull(file)) {
            return R.fail("上传文件不能为空");
        }
        return R.ok(ossService.fileUpload(file));
    }

    /**
     * 下载OSS对象
     *
     * @param ossId OSS对象ID
     */
    @SaCheckPermission("system:oss:download")
    @GetMapping("/download/{ossId}")
    public void download(@PathVariable Long ossId, HttpServletResponse response) throws IOException {
        ossService.download(ossId, response);
    }

    /**
     * 删除OSS对象存储
     *
     * @param ossIds OSS对象ID串
     */
    @SaCheckPermission("system:oss:remove")
    @Log(title = "OSS对象存储", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ossIds}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ossIds) {
        return toAjax(ossService.deleteWithValidByIds(List.of(ossIds), true));
    }

}
