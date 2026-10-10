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

package org.ruoyi.system.controller.monitor;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.web.core.BaseController;
import org.ruoyi.system.domain.vo.ServerInfoVo;
import org.ruoyi.system.service.IServerMonitorService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务监控（S2-F01/op14）。
 *
 * <p>权限沿用种子菜单 117「Admin监控」（hidden，perms={@code monitor:admin:list}，
 * component={@code monitor/admin/index}）——**零迁移、零菜单号**补齐既有预留挂点。
 * 只读端点，无数据面副作用。
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/monitor/server")
public class SysServerController extends BaseController {

    private final IServerMonitorService serverMonitorService;

    /**
     * 服务监控快照（CPU/内存/JVM/主机/磁盘）。
     */
    @SaCheckPermission("monitor:admin:list")
    @GetMapping
    public R<ServerInfoVo> info() {
        return R.ok(serverMonitorService.snapshot());
    }
}
