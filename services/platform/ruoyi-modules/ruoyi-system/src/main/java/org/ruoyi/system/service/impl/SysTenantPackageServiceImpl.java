package org.ruoyi.system.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.system.aiidentity.AiPolicyMutationGuard;
import org.ruoyi.system.domain.SysTenant;
import org.ruoyi.system.domain.SysTenantPackage;
import org.ruoyi.system.domain.bo.SysTenantPackageBo;
import org.ruoyi.system.domain.vo.SysTenantPackageVo;
import org.ruoyi.system.mapper.SysTenantMapper;
import org.ruoyi.system.mapper.SysTenantPackageMapper;
import org.ruoyi.system.service.ISysTenantPackageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 租户套餐Service业务层处理
 *
 * @author Michelle.Chung
 */
@RequiredArgsConstructor
@Service
public class SysTenantPackageServiceImpl implements ISysTenantPackageService {

    private final SysTenantPackageMapper baseMapper;
    private final SysTenantMapper tenantMapper;
    private final AiPolicyMutationGuard aiPolicyMutationGuard;

    /**
     * 枚举使用指定套餐集合的租户（P1.2b：受影响租户显式可枚举，禁止「全部租户」）。
     */
    private Set<String> tenantIdsUsingPackages(Collection<Long> packageIds) {
        Set<String> tenantIds = new HashSet<>();
        if (CollUtil.isEmpty(packageIds)) {
            return tenantIds;
        }
        for (SysTenant tenant : tenantMapper.selectList(
            new LambdaQueryWrapper<SysTenant>().in(SysTenant::getPackageId, packageIds))) {
            if (StringUtils.isNotBlank(tenant.getTenantId())) {
                tenantIds.add(tenant.getTenantId());
            }
        }
        return tenantIds;
    }

    /**
     * 查询租户套餐
     */
    @Override
    public SysTenantPackageVo queryById(Long packageId){
        return baseMapper.selectVoById(packageId);
    }

    /**
     * 查询租户套餐列表
     */
    @Override
    public TableDataInfo<SysTenantPackageVo> queryPageList(SysTenantPackageBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<SysTenantPackage> lqw = buildQueryWrapper(bo);
        Page<SysTenantPackageVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    @Override
    public List<SysTenantPackageVo> selectList() {
        return baseMapper.selectVoList(new LambdaQueryWrapper<SysTenantPackage>()
                .eq(SysTenantPackage::getStatus, SystemConstants.NORMAL));
    }

    /**
     * 查询租户套餐列表
     */
    @Override
    public List<SysTenantPackageVo> queryList(SysTenantPackageBo bo) {
        LambdaQueryWrapper<SysTenantPackage> lqw = buildQueryWrapper(bo);
        return baseMapper.selectVoList(lqw);
    }

    private LambdaQueryWrapper<SysTenantPackage> buildQueryWrapper(SysTenantPackageBo bo) {
        LambdaQueryWrapper<SysTenantPackage> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getPackageName()), SysTenantPackage::getPackageName, bo.getPackageName());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), SysTenantPackage::getStatus, bo.getStatus());
        lqw.orderByAsc(SysTenantPackage::getPackageId);
        return lqw;
    }

    /**
     * 新增租户套餐
     *
     * <p>P1.2b：新套餐尚未被任何租户使用，受影响租户集合为空（显式可枚举的空集），
     * 无需递增策略版本。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insertByBo(SysTenantPackageBo bo) {
        SysTenantPackage add = MapstructUtils.convert(bo, SysTenantPackage.class);
        // 保存菜单id
        List<Long> menuIds = Arrays.asList(bo.getMenuIds());
        add.setMenuIds(CollUtil.isNotEmpty(menuIds) ? StringUtils.joinComma(menuIds) : "");
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setPackageId(add.getPackageId());
        }
        return flag;
    }

    /**
     * 修改租户套餐
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(SysTenantPackageBo bo) {
        SysTenantPackage update = MapstructUtils.convert(bo, SysTenantPackage.class);
        // 保存菜单id
        List<Long> menuIds = Arrays.asList(bo.getMenuIds());
        update.setMenuIds(CollUtil.isNotEmpty(menuIds) ? StringUtils.joinComma(menuIds) : "");
        boolean flag = baseMapper.updateById(update) > 0;
        // P1.2b：套餐菜单变更影响使用该套餐的租户 → 同事务递增其策略版本
        if (flag && ObjectUtil.isNotNull(update.getPackageId())) {
            aiPolicyMutationGuard.bump(tenantIdsUsingPackages(List.of(update.getPackageId())));
        }
        return flag;
    }

    /**
     * 校验套餐名称是否唯一
     */
    @Override
    public boolean checkPackageNameUnique(SysTenantPackageBo bo) {
        boolean exist = baseMapper.exists(new LambdaQueryWrapper<SysTenantPackage>()
            .eq(SysTenantPackage::getPackageName, bo.getPackageName())
            .ne(ObjectUtil.isNotNull(bo.getPackageId()), SysTenantPackage::getPackageId, bo.getPackageId()));
        return !exist;
    }

    /**
     * 修改套餐状态
     *
     * @param bo 套餐信息
     * @return 结果
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int updatePackageStatus(SysTenantPackageBo bo) {
        SysTenantPackage tenantPackage = MapstructUtils.convert(bo, SysTenantPackage.class);
        int rows = baseMapper.updateById(tenantPackage);
        // P1.2b：套餐启用/停用影响使用该套餐的租户 → 同事务递增其策略版本
        if (rows > 0 && ObjectUtil.isNotNull(tenantPackage.getPackageId())) {
            aiPolicyMutationGuard.bump(tenantIdsUsingPackages(List.of(tenantPackage.getPackageId())));
        }
        return rows;
    }

    /**
     * 批量删除租户套餐
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if(isValid){
            boolean exists = tenantMapper.exists(new LambdaQueryWrapper<SysTenant>().in(SysTenant::getPackageId, ids));
            if (exists) {
                throw new ServiceException("租户套餐已被使用");
            }
        }
        // 删除前枚举受影响租户（isValid=false 时不校验在用，仍需对在用租户递增版本）
        Set<String> tenantIds = tenantIdsUsingPackages(ids);
        boolean flag = baseMapper.deleteByIds(ids) > 0;
        // P1.2b：同事务递增受影响租户集合的策略版本
        if (flag) {
            aiPolicyMutationGuard.bump(tenantIds);
        }
        return flag;
    }
}
