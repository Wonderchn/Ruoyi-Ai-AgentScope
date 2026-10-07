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

package org.ruoyi.system.monitor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.constant.HttpStatus;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.SpringUtils;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.common.trace.domain.bo.TraceRunBo;
import org.ruoyi.common.trace.domain.vo.TraceDetailVo;
import org.ruoyi.common.trace.domain.vo.TraceNodeVo;
import org.ruoyi.common.trace.domain.vo.TraceRunVo;
import org.ruoyi.common.trace.service.TraceRecordService;
import org.ruoyi.system.controller.monitor.TraceController;
import org.ruoyi.system.rw14.Rw14TestContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RW-23（F18 op15 / op6）：平台 monitor 链路追踪必须按登录租户限域。
 *
 * <p>改前行为（可复现）：{@code list} 把请求里的 {@link TraceRunBo} 原样下传，而
 * {@code TraceRecordServiceImpl} 只在"调用方自己填了 tenantId"时才过滤 —— 不传即跨租户全量；
 * {@code run/nodes/detail} 三个按 traceId 的查询没有租户校验，跨租户可读。
 */
@Tag("dev")
class RW23MonitorTraceTenantScopeTest {

    private static final String TENANT_A = "T-A";

    private static final String TENANT_B = "T-B";

    private static final String TENANT_PROPERTY = "rw23-tenant-enable";

    private final TraceRecordService traceRecordService = mock(TraceRecordService.class);

    private TraceController controller;

    private ConfigurableEnvironment environment;

    @BeforeEach
    void setUp() {
        Rw14TestContext.install();
        environment = (ConfigurableEnvironment) SpringUtils.context().getEnvironment();
        enableTenant(TENANT_A);
        controller = new TraceController(traceRecordService);
    }

    @AfterEach
    void tearDown() {
        TenantHelper.clearDynamic();
        environment.getPropertySources().remove(TENANT_PROPERTY);
    }

    @Test
    @DisplayName("列表：请求里伪造的 tenantId 被登录租户覆盖（改前：原样下传 → 跨租户全量）")
    void listOverridesForgedTenantId() {
        TraceRunBo forged = new TraceRunBo();
        forged.setTenantId(TENANT_B);

        controller.list(forged, new PageQuery(1, 10));

        assertEquals(TENANT_A, forged.getTenantId(), "租户范围只能来自登录上下文");
    }

    @Test
    @DisplayName("run：本租户链路可见，跨租户与不存在同外显（data=null）")
    void runIsTenantScoped() {
        when(traceRecordService.getRun("own")).thenReturn(run("own", TENANT_A));
        when(traceRecordService.getRun("foreign")).thenReturn(run("foreign", TENANT_B));
        when(traceRecordService.getRun("missing")).thenReturn(null);

        assertEquals("own", controller.run("own").getData().getTraceId());
        assertNull(controller.run("foreign").getData());
        assertNull(controller.run("missing").getData());
    }

    @Test
    @DisplayName("run：链路行没有租户列（历史脏行）同样不可见")
    void runWithoutTenantColumnIsInvisible() {
        when(traceRecordService.getRun("no-tenant")).thenReturn(run("no-tenant", null));

        assertNull(controller.run("no-tenant").getData());
    }

    @Test
    @DisplayName("nodes：跨租户父链路不发节点查询（与不存在同外显：空列表）")
    void foreignTraceNodesNeverQueried() {
        when(traceRecordService.getRun("foreign")).thenReturn(run("foreign", TENANT_B));

        R<List<TraceNodeVo>> response = controller.nodes("foreign");

        assertTrue(response.getData().isEmpty());
        verify(traceRecordService, never()).listNodes(anyString());
    }

    @Test
    @DisplayName("nodes：本租户父链路正常返回节点")
    void ownTraceNodesReturned() {
        when(traceRecordService.getRun("own")).thenReturn(run("own", TENANT_A));
        TraceNodeVo node = new TraceNodeVo();
        node.setNodeId("n1");
        when(traceRecordService.listNodes("own")).thenReturn(List.of(node));

        R<List<TraceNodeVo>> response = controller.nodes("own");

        assertEquals(1, response.getData().size());
        assertEquals("n1", response.getData().get(0).getNodeId());
    }

    @Test
    @DisplayName("detail：跨租户与不存在同外显（data=null），本租户返回明细")
    void detailIsTenantScoped() {
        TraceDetailVo own = new TraceDetailVo();
        own.setRun(run("own", TENANT_A));
        TraceDetailVo foreign = new TraceDetailVo();
        foreign.setRun(run("foreign", TENANT_B));
        when(traceRecordService.getDetail("own")).thenReturn(own);
        when(traceRecordService.getDetail("foreign")).thenReturn(foreign);
        when(traceRecordService.getDetail("missing")).thenReturn(null);

        assertNotNull(controller.detail("own").getData());
        assertNull(controller.detail("foreign").getData());
        assertNull(controller.detail("missing").getData());
    }

    @Test
    @DisplayName("缺租户上下文：显式拒绝（403 语义），不放宽为查全部")
    void missingTenantContextIsRejected() {
        TenantHelper.clearDynamic();
        environment.getPropertySources().remove(TENANT_PROPERTY);
        environment.getPropertySources().addFirst(new MapPropertySource(TENANT_PROPERTY, Map.of("tenant.enable", "false")));

        ServiceException denied = assertThrows(ServiceException.class, () -> controller.list(new TraceRunBo(), new PageQuery(1, 10)));
        assertEquals(HttpStatus.FORBIDDEN, denied.getCode());
        verify(traceRecordService, never()).pageRuns(any(), any());
    }

    private void enableTenant(String tenantId) {
        environment.getPropertySources().addFirst(new MapPropertySource(TENANT_PROPERTY, Map.of("tenant.enable", "true")));
        TenantHelper.setDynamic(tenantId, false);
    }

    private TraceRunVo run(String traceId, String tenantId) {
        TraceRunVo vo = new TraceRunVo();
        vo.setTraceId(traceId);
        vo.setTenantId(tenantId);
        return vo;
    }
}
