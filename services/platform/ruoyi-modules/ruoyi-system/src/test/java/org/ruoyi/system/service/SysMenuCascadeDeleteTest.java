/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ruoyi.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.system.aiidentity.AiPolicyMutationGuard;
import org.ruoyi.system.domain.SysMenu;
import org.ruoyi.system.domain.SysRole;
import org.ruoyi.system.domain.SysRoleMenu;
import org.ruoyi.system.domain.SysTenant;
import org.ruoyi.system.domain.SysTenantPackage;
import org.ruoyi.system.mapper.SysMenuMapper;
import org.ruoyi.system.mapper.SysRoleMapper;
import org.ruoyi.system.mapper.SysRoleMenuMapper;
import org.ruoyi.system.mapper.SysTenantMapper;
import org.ruoyi.system.mapper.SysTenantPackageMapper;
import org.ruoyi.system.service.impl.SysMenuServiceImpl;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2-F01/op4：菜单级联删除语义（mock mapper，不引新测试依赖）。
 *
 * <p>口径：级联=服务端展开全部后代（调用方只传根）＋回收角色绑定＋清洗租户套餐 CSV
 * ＋递增受影响租户策略版本；单删=保持保守拒绝，且"被套餐引用"给出精确错误（由
 * {@link SysMenuServiceImpl#tenantPackagesReferencing(Long)} 支撑，逐 token 精确匹配）。
 */
@Tag("dev")
class SysMenuCascadeDeleteTest {

    static {
        // 脱离 SqlSession 时 lambda 列名缓存是空的，条件构造器取不出 SQL 片段
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysMenu.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysRoleMenu.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysTenant.class);
    }

    private final SysMenuMapper menuMapper = mock(SysMenuMapper.class);
    private final SysRoleMapper roleMapper = mock(SysRoleMapper.class);
    private final SysRoleMenuMapper roleMenuMapper = mock(SysRoleMenuMapper.class);
    private final SysTenantPackageMapper tenantPackageMapper = mock(SysTenantPackageMapper.class);
    private final SysTenantMapper tenantMapper = mock(SysTenantMapper.class);
    private final AiPolicyMutationGuard guard = mock(AiPolicyMutationGuard.class);

    private SysMenuServiceImpl service() {
        return new SysMenuServiceImpl(menuMapper, roleMapper, roleMenuMapper, tenantPackageMapper,
                tenantMapper, guard);
    }

    private static SysMenu menu(long id, Long parentId) {
        SysMenu m = new SysMenu();
        m.setMenuId(id);
        m.setParentId(parentId);
        return m;
    }

    private static SysTenantPackage pkg(long id, String name, String menuIds) {
        SysTenantPackage p = new SysTenantPackage();
        p.setPackageId(id);
        p.setPackageName(name);
        p.setMenuIds(menuIds);
        return p;
    }

    @Test
    void cascadeExpandsDescendantsUnbindsRolesCleansPackagesAndBumps() {
        // 树：1 → 2 → 3；另有无关 9
        when(menuMapper.selectList(any())).thenReturn(List.of(menu(1, 0L), menu(2, 1L), menu(3, 2L), menu(9, 0L)));
        // 闭包内菜单 3 被角色 7 绑定 → 租户 t1
        SysRoleMenu rm = new SysRoleMenu();
        rm.setMenuId(3L);
        rm.setRoleId(7L);
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(rm));
        SysRole role = new SysRole();
        role.setRoleId(7L);
        role.setTenantId("t1");
        when(roleMapper.selectByIds(any())).thenReturn(List.of(role));
        // 套餐 pkgA 引用 "3,9" → 清洗后应为 "9"（9 不在闭包）
        SysTenantPackage a = pkg(11L, "pkgA", "3,9");
        when(tenantPackageMapper.selectList(any())).thenReturn(List.of(a));
        when(roleMenuMapper.deleteByMenuIds(any())).thenReturn(1);
        when(menuMapper.deleteByIds(any())).thenReturn(3);

        Map<String, Object> counts = service().deleteMenuCascade(List.of(1L));

        ArgumentCaptor<List<Long>> del = ArgumentCaptor.forClass(List.class);
        verify(menuMapper).deleteByIds(del.capture());
        assertEquals(Set.of(1L, 2L, 3L), new HashSet<>(del.getValue()), "后代必须被服务端展开删除");
        verify(roleMenuMapper).deleteByMenuIds(del.capture());
        assertEquals(Set.of(1L, 2L, 3L), new HashSet<>(del.getValue()), "角色绑定按展开集回收");
        assertEquals("9", a.getMenuIds(), "套餐 CSV 只应剔除闭包内 id");
        verify(tenantPackageMapper).updateById(a);
        verify(guard).bump(Set.of("t1"));
        assertEquals(3, counts.get("menus"));
        assertEquals(1, counts.get("roleBindings"));
        assertEquals(1, counts.get("packages"));
        assertEquals(1, counts.get("packageRefs"));
    }

    @Test
    void referencedMenuReportedForSingleDeletePreciseRejection() {
        when(tenantPackageMapper.selectList(any())).thenReturn(List.of(
            pkg(11L, "pkgA", "3,9"),
            pkg(12L, "pkgB", "13"),        // 子串陷阱：13 不应命中 3
            pkg(13L, "pkgC", null)));

        assertEquals(List.of("pkgA"), service().tenantPackagesReferencing(3L));
        assertTrue(service().tenantPackagesReferencing(4L).isEmpty());
        assertTrue(service().tenantPackagesReferencing(null).isEmpty());
    }

    @Test
    void cascadeWithoutBindingsBumpsExplicitEmptySetNeverAllTenants() {
        when(menuMapper.selectList(any())).thenReturn(List.of(menu(5, 0L)));
        when(roleMenuMapper.selectList(any())).thenReturn(List.of());
        when(tenantPackageMapper.selectList(any())).thenReturn(List.of());
        when(menuMapper.deleteByIds(any())).thenReturn(1);

        Map<String, Object> counts = service().deleteMenuCascade(List.of(5L));

        // P1.2b 护栏：受影响租户必须显式可枚举——无绑定时传空集（guard 内部 no-op），
        // 绝不放大为"全租户"。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> cap = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(guard).bump(cap.capture());
        assertTrue(cap.getValue().isEmpty(), "必须传显式空集，而不是近似/全量租户");
        assertEquals(1, counts.get("menus"));
        assertEquals(0, counts.get("packages"));
    }

    @Test
    void packageOnlyReferenceBumpsTenantsUsingTheCleanedPackage() {
        // 复核 P2：菜单 7 无任何角色绑定、仅被套餐 11 引用 → bump 集合必须并入"使用套餐 11 的租户"
        when(menuMapper.selectList(any())).thenReturn(List.of(menu(7, 0L)));
        when(roleMenuMapper.selectList(any())).thenReturn(List.of());
        SysTenantPackage p = pkg(11L, "pkgOnlyRef", "7,9");
        when(tenantPackageMapper.selectList(any())).thenReturn(List.of(p));
        SysTenant t = new SysTenant();
        t.setTenantId("t9");
        t.setPackageId(11L);
        when(tenantMapper.selectList(any())).thenReturn(List.of(t));
        when(menuMapper.deleteByIds(any())).thenReturn(1);

        Map<String, Object> counts = service().deleteMenuCascade(List.of(7L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> cap = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(guard).bump(cap.capture());
        assertEquals(Set.of("t9"), new HashSet<>(cap.getValue()), "套餐维度受影响租户必须入 bump 集合");
        assertEquals(1, counts.get("packages"));
        assertEquals(1, counts.get("packageRefs"));
    }

    @Test
    void orphanBindingsOnlyCleanupStillBumps() {
        // 复核 P3-3：菜单行已不存在（rows=0），孤儿 role_menu 绑定被回收 → 仍须 bump
        when(menuMapper.selectList(any())).thenReturn(List.of(menu(8, 0L)));
        SysRoleMenu rm = new SysRoleMenu();
        rm.setMenuId(8L);
        rm.setRoleId(7L);
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(rm));
        SysRole role = new SysRole();
        role.setRoleId(7L);
        role.setTenantId("t1");
        when(roleMapper.selectByIds(any())).thenReturn(List.of(role));
        when(tenantPackageMapper.selectList(any())).thenReturn(List.of());
        when(roleMenuMapper.deleteByMenuIds(any())).thenReturn(1);
        when(menuMapper.deleteByIds(any())).thenReturn(0);

        Map<String, Object> counts = service().deleteMenuCascade(List.of(8L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> cap = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(guard).bump(cap.capture());
        assertEquals(Set.of("t1"), new HashSet<>(cap.getValue()), "孤儿清理同样触发受影响租户版本递增");
        assertEquals(0, counts.get("menus"));
        assertEquals(1, counts.get("roleBindings"));
    }

    @Test
    void whitespaceOnlyNormalizationDoesNotCountOrWrite() {
        // 复核 P3-1：CSV 仅空白差异、无实际移除 → 不写库、不计数
        when(menuMapper.selectList(any())).thenReturn(List.of(menu(5, 0L)));
        when(roleMenuMapper.selectList(any())).thenReturn(List.of());
        when(tenantPackageMapper.selectList(any())).thenReturn(List.of(pkg(11L, "pkgSpaced", " 9 , 10 ")));
        when(menuMapper.deleteByIds(any())).thenReturn(1);

        Map<String, Object> counts = service().deleteMenuCascade(List.of(5L));

        verify(tenantPackageMapper, never()).updateById(any(SysTenantPackage.class));
        assertEquals(0, counts.get("packages"));
        assertEquals(0, counts.get("packageRefs"));
    }
}
