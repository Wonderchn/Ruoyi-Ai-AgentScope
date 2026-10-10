package org.ruoyi.system.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.constant.Constants;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.core.utils.StreamUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.core.utils.TreeBuildUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.system.aiidentity.AiPolicyMutationGuard;
import org.ruoyi.system.domain.SysMenu;
import org.ruoyi.system.domain.SysRole;
import org.ruoyi.system.domain.SysRoleMenu;
import org.ruoyi.system.domain.SysTenant;
import org.ruoyi.system.domain.SysTenantPackage;
import org.ruoyi.system.domain.bo.SysMenuBo;
import org.ruoyi.system.domain.vo.MetaVo;
import org.ruoyi.system.domain.vo.RouterVo;
import org.ruoyi.system.domain.vo.SysMenuVo;
import org.ruoyi.system.mapper.SysMenuMapper;
import org.ruoyi.system.mapper.SysRoleMapper;
import org.ruoyi.system.mapper.SysRoleMenuMapper;
import org.ruoyi.system.mapper.SysTenantMapper;
import org.ruoyi.system.mapper.SysTenantPackageMapper;
import org.ruoyi.system.service.ISysMenuService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 菜单 业务层处理
 *
 * @author Lion Li
 */
@RequiredArgsConstructor
@Service
public class SysMenuServiceImpl implements ISysMenuService {

    private final SysMenuMapper baseMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysTenantPackageMapper tenantPackageMapper;
    private final SysTenantMapper tenantMapper;
    private final AiPolicyMutationGuard aiPolicyMutationGuard;

    /**
     * 菜单 perms 变更影响的是「绑定了该菜单的角色所属租户」（P1.2b：受影响租户
     * 必须显式可枚举，禁止按全部租户递增）。
     */
    private Set<String> tenantIdsBoundToMenus(List<Long> menuIds) {
        Set<String> tenantIds = new HashSet<>();
        if (CollUtil.isEmpty(menuIds)) {
            return tenantIds;
        }
        List<SysRoleMenu> roleMenus = roleMenuMapper.selectList(
            new LambdaQueryWrapper<SysRoleMenu>().in(SysRoleMenu::getMenuId, menuIds));
        if (CollUtil.isEmpty(roleMenus)) {
            return tenantIds;
        }
        List<Long> roleIds = StreamUtils.toList(roleMenus, SysRoleMenu::getRoleId);
        // 菜单是全局表、角色归属租户：跨租户读取忽略租户过滤
        for (SysRole role : TenantHelper.ignore(() -> roleMapper.selectByIds(roleIds))) {
            if (StringUtils.isNotBlank(role.getTenantId())) {
                tenantIds.add(role.getTenantId());
            }
        }
        return tenantIds;
    }

    /**
     * 根据用户查询系统菜单列表
     *
     * @param userId 用户ID
     * @return 菜单列表
     */
    @Override
    public List<SysMenuVo> selectMenuList(Long userId) {
        return selectMenuList(new SysMenuBo(), userId);
    }

    /**
     * 查询系统菜单列表
     *
     * @param menu 菜单信息
     * @return 菜单列表
     */
    @Override
    public List<SysMenuVo> selectMenuList(SysMenuBo menu, Long userId) {
        List<SysMenuVo> menuList;
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
        // 管理员显示所有菜单信息 不是管理员 按用户id过滤菜单
        if (!LoginHelper.isSuperAdmin(userId)) {
            // 通过用户id获取角色id 通过角色id获取菜单id 然后in菜单
            wrapper.inSql(SysMenu::getMenuId, baseMapper.buildMenuByUserSql(userId));
        }
        menuList = baseMapper.selectVoList(
            wrapper.like(StringUtils.isNotBlank(menu.getMenuName()), SysMenu::getMenuName, menu.getMenuName())
                .eq(StringUtils.isNotBlank(menu.getVisible()), SysMenu::getVisible, menu.getVisible())
                .eq(StringUtils.isNotBlank(menu.getStatus()), SysMenu::getStatus, menu.getStatus())
                .eq(StringUtils.isNotBlank(menu.getMenuType()), SysMenu::getMenuType, menu.getMenuType())
                .eq(ObjectUtil.isNotNull(menu.getParentId()), SysMenu::getParentId, menu.getParentId())
                .orderByAsc(SysMenu::getParentId)
                .orderByAsc(SysMenu::getOrderNum));
        return menuList;
    }

    /**
     * 根据用户ID查询权限
     *
     * @param userId 用户ID
     * @return 权限列表
     */
    @Override
    public Set<String> selectMenuPermsByUserId(Long userId) {
        return baseMapper.selectMenuPermsByUserId(userId);
    }

    /**
     * 根据角色ID查询权限
     *
     * @param roleId 角色ID
     * @return 权限列表
     */
    @Override
    public Set<String> selectMenuPermsByRoleId(Long roleId) {
        return baseMapper.selectMenuPermsByRoleId(roleId);
    }

    /**
     * 根据用户ID查询菜单
     *
     * @param userId 用户名称
     * @return 菜单列表
     */
    @Override
    public List<SysMenu> selectMenuTreeByUserId(Long userId) {
        List<SysMenu> menus;
        if (LoginHelper.isSuperAdmin(userId)) {
            menus = baseMapper.selectMenuTreeAll();
        } else {
            LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
            menus = baseMapper.selectList(
                wrapper.in(SysMenu::getMenuType, SystemConstants.TYPE_DIR, SystemConstants.TYPE_MENU)
                    .eq(SysMenu::getStatus, SystemConstants.NORMAL)
                    .inSql(SysMenu::getMenuId, baseMapper.buildMenuByUserSql(userId))
                    .orderByAsc(SysMenu::getParentId)
                    .orderByAsc(SysMenu::getOrderNum));
        }
        return getChildPerms(menus, Constants.TOP_PARENT_ID);
    }

    /**
     * 根据角色ID查询菜单树信息
     *
     * @param roleId 角色ID
     * @return 选中菜单列表
     */
    @Override
    public List<Long> selectMenuListByRoleId(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        return baseMapper.selectMenuListByRoleId(roleId, role.getMenuCheckStrictly());
    }

    /**
     * 根据租户套餐ID查询菜单树信息
     *
     * @param packageId 租户套餐ID
     * @return 选中菜单列表
     */
    @Override
    public List<Long> selectMenuListByPackageId(Long packageId) {
        SysTenantPackage tenantPackage = tenantPackageMapper.selectById(packageId);
        List<Long> menuIds = StringUtils.splitTo(tenantPackage.getMenuIds(), Convert::toLong);
        if (CollUtil.isEmpty(menuIds)) {
            return List.of();
        }
        List<Long> parentIds = null;
        if (tenantPackage.getMenuCheckStrictly()) {
            parentIds = baseMapper.selectObjs(new LambdaQueryWrapper<SysMenu>()
                .select(SysMenu::getParentId)
                .in(SysMenu::getMenuId, menuIds), x -> {
                return Convert.toLong(x);
            });
        }
        return baseMapper.selectObjs(new LambdaQueryWrapper<SysMenu>()
            .select(SysMenu::getMenuId)
            .in(SysMenu::getMenuId, menuIds)
            .notIn(CollUtil.isNotEmpty(parentIds), SysMenu::getMenuId, parentIds), x -> {
            return Convert.toLong(x);
        });
    }

    /**
     * 构建前端路由所需要的菜单
     * 路由name命名规则 path首字母转大写 + id
     *
     * @param menus 菜单列表
     * @return 路由列表
     */
    @Override
    public List<RouterVo> buildMenus(List<SysMenu> menus) {
        List<RouterVo> routers = new LinkedList<>();
        for (SysMenu menu : menus) {
            String name = menu.getRouteName() + menu.getMenuId();
            RouterVo router = new RouterVo();
            router.setHidden("1".equals(menu.getVisible()));
            router.setName(name);
            router.setPath(menu.getRouterPath());
            router.setComponent(menu.getComponentInfo());
            router.setQuery(menu.getQueryParam());
            router.setMeta(new MetaVo(menu.getMenuName(), menu.getIcon(), StringUtils.equals("1", menu.getIsCache()), menu.getPath(), menu.getRemark()));
            List<SysMenu> cMenus = menu.getChildren();
            if (CollUtil.isNotEmpty(cMenus) && SystemConstants.TYPE_DIR.equals(menu.getMenuType())) {
                router.setAlwaysShow(true);
                router.setRedirect("noRedirect");
                router.setChildren(buildMenus(cMenus));
            } else if (menu.isMenuFrame()) {
                String frameName = StringUtils.capitalize(menu.getPath()) + menu.getMenuId();
                router.setMeta(null);
                List<RouterVo> childrenList = new ArrayList<>();
                RouterVo children = new RouterVo();
                children.setPath(menu.getPath());
                children.setComponent(menu.getComponent());
                children.setName(frameName);
                children.setMeta(new MetaVo(menu.getMenuName(), menu.getIcon(), StringUtils.equals("1", menu.getIsCache()), menu.getPath(), menu.getRemark()));
                children.setQuery(menu.getQueryParam());
                childrenList.add(children);
                router.setChildren(childrenList);
            } else if (menu.getParentId().equals(Constants.TOP_PARENT_ID) && menu.isInnerLink()) {
                router.setMeta(new MetaVo(menu.getMenuName(), menu.getIcon()));
                router.setPath("/");
                List<RouterVo> childrenList = new ArrayList<>();
                RouterVo children = new RouterVo();
                String routerPath = SysMenu.innerLinkReplaceEach(menu.getPath());
                String innerLinkName = StringUtils.capitalize(routerPath) + menu.getMenuId();
                children.setPath(routerPath);
                children.setComponent(SystemConstants.INNER_LINK);
                children.setName(innerLinkName);
                children.setMeta(new MetaVo(menu.getMenuName(), menu.getIcon(), menu.getPath()));
                childrenList.add(children);
                router.setChildren(childrenList);
            }
            routers.add(router);
        }
        return routers;
    }

    /**
     * 构建前端所需要下拉树结构
     *
     * @param menus 菜单列表
     * @return 下拉树结构列表
     */
    @Override
    public List<Tree<Long>> buildMenuTreeSelect(List<SysMenuVo> menus) {
        if (CollUtil.isEmpty(menus)) {
            return CollUtil.newArrayList();
        }
        return TreeBuildUtils.build(menus, (menu, tree) -> {
            Tree<Long> menuTree = tree.setId(menu.getMenuId())
                .setParentId(menu.getParentId())
                .setName(menu.getMenuName())
                .setWeight(menu.getOrderNum());
            menuTree.put("menuType", menu.getMenuType());
            menuTree.put("icon", menu.getIcon());
            menuTree.put("visible", menu.getVisible());
            menuTree.put("status", menu.getStatus());
        });
    }

    /**
     * 根据菜单ID查询信息
     *
     * @param menuId 菜单ID
     * @return 菜单信息
     */
    @Override
    public SysMenuVo selectMenuById(Long menuId) {
        return baseMapper.selectVoById(menuId);
    }

    /**
     * 是否存在菜单子节点
     *
     * @param menuId 菜单ID
     * @return 结果
     */
    @Override
    public boolean hasChildByMenuId(Long menuId) {
        return baseMapper.exists(new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getParentId, menuId));
    }

    /**
     * 是否存在菜单子节点
     *
     * @param menuIds 菜单ID串
     * @return 结果
     */
    @Override
    public boolean hasChildByMenuId(List<Long> menuIds) {
        return baseMapper.exists(new LambdaQueryWrapper<SysMenu>().in(SysMenu::getParentId, menuIds).notIn(SysMenu::getMenuId, menuIds));
    }

    /**
     * 查询菜单使用数量
     *
     * @param menuId 菜单ID
     * @return 结果
     */
    @Override
    public boolean checkMenuExistRole(Long menuId) {
        return roleMenuMapper.exists(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getMenuId, menuId));
    }

    /**
     * 新增保存菜单信息
     *
     * <p>P1.2b：新菜单尚未绑定任何角色，受影响租户集合为空（显式可枚举的空集），
     * 无需递增策略版本。
     *
     * @param bo 菜单信息
     * @return 结果
     */
    @Override
    public int insertMenu(SysMenuBo bo) {
        SysMenu menu = MapstructUtils.convert(bo, SysMenu.class);
        return baseMapper.insert(menu);
    }

    /**
     * 修改保存菜单信息
     *
     * @param bo 菜单信息
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateMenu(SysMenuBo bo) {
        SysMenu menu = MapstructUtils.convert(bo, SysMenu.class);
        int rows = baseMapper.updateById(menu);
        // P1.2b：菜单 perms/状态变更影响绑定角色所属租户 → 同事务递增其策略版本
        aiPolicyMutationGuard.bump(tenantIdsBoundToMenus(List.of(menu.getMenuId())));
        return rows;
    }

    /**
     * 删除菜单管理信息
     *
     * @param menuId 菜单ID
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteMenuById(Long menuId) {
        // 删除前枚举受影响租户（角色-菜单关联将被一并清理）
        Set<String> tenantIds = tenantIdsBoundToMenus(List.of(menuId));
        int rows = baseMapper.deleteById(menuId);
        // P1.2b：同事务递增受影响租户的策略版本
        if (rows > 0) {
            aiPolicyMutationGuard.bump(tenantIds);
        }
        return rows;
    }

    /**
     * 批量删除菜单管理信息
     *
     * @param menuIds 菜单ID串
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMenuById(List<Long> menuIds) {
        // 删除前枚举受影响租户
        Set<String> tenantIds = tenantIdsBoundToMenus(menuIds);
        baseMapper.deleteByIds(menuIds);
        roleMenuMapper.deleteByMenuIds(menuIds);
        // P1.2b：同事务递增受影响租户的策略版本
        aiPolicyMutationGuard.bump(tenantIds);
    }

    /**
     * 查询引用该菜单的租户套餐名（S2-F01/op4）。CSV 逐 token 精确匹配，避免子串误伤
     * （如 123 不应命中 1234）。
     */
    @Override
    public List<String> tenantPackagesReferencing(Long menuId) {
        List<String> names = new ArrayList<>();
        if (menuId == null) {
            return names;
        }
        String token = menuId.toString();
        for (SysTenantPackage pkg : tenantPackageMapper.selectList(null)) {
            if (csvContainsToken(pkg.getMenuIds(), token)) {
                names.add(pkg.getPackageName());
            }
        }
        return names;
    }

    /**
     * 级联删除菜单子树（S2-F01/op4）。
     *
     * <p>与 {@link #deleteMenuById(List)} 的差别：**服务端展开全部后代**（调用方只传根即可，
     * 对齐前端「级联删除」直觉），并一并回收角色绑定、清洗租户套餐 menu_ids、递增受影响
     * 租户策略版本。单删（{@link #deleteMenuById(Long)}）保持"有子/已分配/被套餐引用"
     * 三拒绝的保守语义；级联是显式强删路径，因此不拒绝、只清理，并回报清理计数。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteMenuCascade(List<Long> menuIds) {
        // 1) 展开闭包：菜单为全局小表，一次读入内存计算后代（含多层）
        List<SysMenu> all = baseMapper.selectList(new LambdaQueryWrapper<SysMenu>()
            .select(SysMenu::getMenuId, SysMenu::getParentId));
        Set<Long> closure = new LinkedHashSet<>(menuIds);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (SysMenu m : all) {
                if (m.getParentId() != null && closure.contains(m.getParentId()) && closure.add(m.getMenuId())) {
                    grew = true;
                }
            }
        }
        List<Long> ids = new ArrayList<>(closure);
        // 2) 删除前枚举受影响租户（角色-菜单绑定将被一并回收）
        Set<String> tenantIds = tenantIdsBoundToMenus(ids);
        // 3) 回收角色绑定
        int roleBindings = roleMenuMapper.deleteByMenuIds(ids);
        // 4) 清洗租户套餐 CSV（逐 token 去除闭包内 id）
        Set<Long> cleanedPackageIds = new LinkedHashSet<>();
        int packages = 0;
        int packageRefs = 0;
        for (SysTenantPackage pkg : tenantPackageMapper.selectList(null)) {
            String before = pkg.getMenuIds();
            if (StringUtils.isBlank(before)) {
                continue;
            }
            String after = csvRemoveTokens(before, closure);
            int removed = countTokens(before) - countTokens(after);
            // 复核 P3-1：仅"实际移除 token>0"才写库与计数（空白归一化不触发写库）
            if (removed > 0) {
                pkg.setMenuIds(after);
                tenantPackageMapper.updateById(pkg);
                packages++;
                packageRefs += removed; // 复核 P3-2：按出现次数计（重复 token 属数据异常，各计一次）
                cleanedPackageIds.add(pkg.getPackageId());
            }
        }
        // 5) 删除菜单行 + 策略版本。
        // 复核 P2：受影响租户 = 角色绑定租户 ∪ **被清洗套餐的使用租户**（与
        // SysTenantPackageServiceImpl#tenantIdsUsingPackages 同口径——仅挂在套餐里、
        // 无角色绑定的菜单在库内真实可达）；仍显式枚举、无全租户放大。
        // 复核 P3-3：任一清理实际发生即 bump（孤儿绑定/仅套餐引用时菜单行数可为 0）。
        int rows = baseMapper.deleteByIds(ids);
        if (rows > 0 || roleBindings > 0 || packages > 0) {
            Set<String> affected = new LinkedHashSet<>(tenantIds);
            affected.addAll(tenantIdsUsingPackages(cleanedPackageIds));
            aiPolicyMutationGuard.bump(affected);
        }
        Map<String, Object> counts = new HashMap<>();
        counts.put("menus", rows);
        counts.put("roleBindings", roleBindings);
        counts.put("packages", packages);
        counts.put("packageRefs", packageRefs);
        return counts;
    }

    /**
     * 枚举使用指定套餐集合的租户（P1.2b：受影响租户显式可枚举，禁止"全部租户"；
     * 与 {@code SysTenantPackageServiceImpl#tenantIdsUsingPackages} 同口径）。
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

    private static boolean csvContainsToken(String csv, String token) {
        if (StringUtils.isBlank(csv)) {
            return false;
        }
        for (String t : csv.split(",")) {
            if (token.equals(t.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String csvRemoveTokens(String csv, Set<Long> remove) {
        List<String> kept = new ArrayList<>();
        for (String t : csv.split(",")) {
            String trimmed = t.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Long id = null;
            try {
                id = Long.valueOf(trimmed);
            } catch (NumberFormatException ignore) {
                // 非数字 token 原样保留（不猜测、不丢弃）
            }
            if (id != null && remove.contains(id)) {
                continue;
            }
            kept.add(trimmed);
        }
        return String.join(",", kept);
    }

    private static int countTokens(String csv) {
        if (StringUtils.isBlank(csv)) {
            return 0;
        }
        int n = 0;
        for (String t : csv.split(",")) {
            if (!t.trim().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 校验菜单名称是否唯一
     *
     * @param menu 菜单信息
     * @return 结果
     */
    @Override
    public boolean checkMenuNameUnique(SysMenuBo menu) {
        boolean exist = baseMapper.exists(new LambdaQueryWrapper<SysMenu>()
            .eq(SysMenu::getMenuName, menu.getMenuName())
            .eq(SysMenu::getParentId, menu.getParentId())
            .ne(ObjectUtil.isNotNull(menu.getMenuId()), SysMenu::getMenuId, menu.getMenuId()));
        return !exist;
    }

    /**
     * 根据父节点的ID获取所有子节点
     *
     * @param list     分类表
     * @param parentId 传入的父节点ID
     * @return String
     */
    private List<SysMenu> getChildPerms(List<SysMenu> list, Long parentId) {
        List<SysMenu> returnList = new ArrayList<>();
        for (SysMenu t : list) {
            // 一、根据传入的某个父节点ID,遍历该父节点的所有子节点
            if (t.getParentId().equals(parentId)) {
                recursionFn(list, t);
                returnList.add(t);
            }
        }
        return returnList;
    }

    /**
     * 递归列表
     */
    private void recursionFn(List<SysMenu> list, SysMenu t) {
        // 得到子节点列表
        List<SysMenu> childList = StreamUtils.filter(list, n -> n.getParentId().equals(t.getMenuId()));
        t.setChildren(childList);
        for (SysMenu tChild : childList) {
            // 判断是否有子节点
            if (list.stream().anyMatch(n -> n.getParentId().equals(tChild.getMenuId()))) {
                recursionFn(list, tChild);
            }
        }
    }

}
