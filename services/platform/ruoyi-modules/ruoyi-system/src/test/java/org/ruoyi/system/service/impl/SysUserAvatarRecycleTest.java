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

package org.ruoyi.system.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.service.OssService;
import org.ruoyi.common.oss.exception.OssException;
import org.ruoyi.system.aiidentity.AiPolicyMutationGuard;
import org.ruoyi.system.domain.SysUser;
import org.ruoyi.system.mapper.SysDeptMapper;
import org.ruoyi.system.mapper.SysPostMapper;
import org.ruoyi.system.mapper.SysRoleMapper;
import org.ruoyi.system.mapper.SysUserMapper;
import org.ruoyi.system.mapper.SysUserPostMapper;
import org.ruoyi.system.mapper.SysUserRoleMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2-F01/op12：头像更新后的旧对象回收语义（mock 依赖，不引新测试依赖）。
 *
 * <p>口径：更新成功且旧头像存在（非 null/非 0）且发生变化 ⇒ 尽力回收旧对象；
 * 回收失败只留痕不阻断（返回仍为 true）；无旧值/未变化/更新失败 ⇒ 不回收。
 */
@Tag("dev")
class SysUserAvatarRecycleTest {

    static {
        // 脱离 SqlSession 时 lambda 列名缓存是空的，条件构造器取不出 SQL 片段
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUser.class);
    }

    private final SysUserMapper baseMapper = mock(SysUserMapper.class);
    private final OssService ossService = mock(OssService.class);

    private SysUserServiceImpl service() {
        return new SysUserServiceImpl(baseMapper, mock(SysDeptMapper.class), mock(SysRoleMapper.class),
                mock(SysPostMapper.class), mock(SysUserRoleMapper.class), mock(SysUserPostMapper.class),
                mock(AiPolicyMutationGuard.class), ossService);
    }

    private static SysUser user(Long avatar) {
        SysUser u = new SysUser();
        u.setUserId(7L);
        u.setAvatar(avatar);
        return u;
    }

    @Test
    void recyclesOldAvatarAfterSuccessfulChange() {
        when(baseMapper.selectById(7L)).thenReturn(user(777L));
        when(baseMapper.update(any(), any())).thenReturn(1);

        assertTrue(service().updateUserAvatar(7L, 888L));
        verify(ossService).deleteFile(777L);
    }

    @Test
    void keepsWhenNoPreviousAvatar() {
        when(baseMapper.selectById(7L)).thenReturn(user(null));
        when(baseMapper.update(any(), any())).thenReturn(1);

        assertTrue(service().updateUserAvatar(7L, 888L));
        verify(ossService, never()).deleteFile(anyLong());
    }

    @Test
    void keepsWhenPreviousAvatarEmpty() {
        when(baseMapper.selectById(7L)).thenReturn(user(0L));
        when(baseMapper.update(any(), any())).thenReturn(1);

        assertTrue(service().updateUserAvatar(7L, 888L));
        verify(ossService, never()).deleteFile(anyLong());
    }

    @Test
    void keepsWhenAvatarUnchanged() {
        when(baseMapper.selectById(7L)).thenReturn(user(777L));
        when(baseMapper.update(any(), any())).thenReturn(1);

        assertTrue(service().updateUserAvatar(7L, 777L));
        verify(ossService, never()).deleteFile(anyLong());
    }

    @Test
    void updateFailureSkipsRecycleAndReturnsFalse() {
        when(baseMapper.selectById(7L)).thenReturn(user(777L));
        when(baseMapper.update(any(), any())).thenReturn(0);

        assertFalse(service().updateUserAvatar(7L, 888L));
        verify(ossService, never()).deleteFile(anyLong());
    }

    @Test
    void recycleFailureDoesNotBreakUpdate() {
        when(baseMapper.selectById(7L)).thenReturn(user(777L));
        when(baseMapper.update(any(), any())).thenReturn(1);
        when(ossService.deleteFile(777L)).thenThrow(new OssException("boom"));

        assertTrue(service().updateUserAvatar(7L, 888L), "回收失败不得阻断头像更新结果");
        verify(ossService).deleteFile(777L);
    }
}
