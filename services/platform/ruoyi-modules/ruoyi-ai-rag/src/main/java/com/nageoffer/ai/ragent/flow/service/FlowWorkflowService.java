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

package com.nageoffer.ai.ragent.flow.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.flow.controller.request.FlowWorkflowQueryRequest;
import com.nageoffer.ai.ragent.flow.controller.request.FlowWorkflowSaveRequest;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowWorkflowVO;

/**
 * AIFlow 工作流定义服务（F13 op3 + op0 的定义增删改查部分）。
 */
public interface FlowWorkflowService {

    /** 「我的工作流搜索」：范围恒为当前主体本人 + 当前租户。 */
    IPage<FlowWorkflowVO> searchMine(FlowWorkflowQueryRequest request);

    /** 按 uuid 读取，限本人 + 本租户。 */
    FlowWorkflowVO getMine(String uuid);

    /** 新增工作流定义，属主与租户取自当前主体。 */
    FlowWorkflowVO create(FlowWorkflowSaveRequest request);

    /** 更新基础信息（标题/备注/启用），限本人 + 本租户。 */
    FlowWorkflowVO updateBasicInfo(String uuid, FlowWorkflowSaveRequest request);

    /** 逻辑删除，限本人 + 本租户。 */
    void delete(String uuid);
}
