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

package com.nageoffer.ai.ragent.flow.controller.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 「我的工作流搜索」查询参数。
 *
 * <p>与 op3 定义一致：搜索范围恒为<b>当前主体本人</b>的工作流，因此
 * <b>没有</b> userId / tenantId 入参 —— 属主与租户只从 {@code PrincipalContext} 取，
 * 不由客户端指定（否则搜索范围可被调用方改写）。
 */
@Data
public class FlowWorkflowQueryRequest {

    /** 标题关键字（可选，含即匹配）。 */
    private String keyword;

    /** 是否启用过滤（可选：0 停放 / 1 启用）。 */
    private Integer isEnable;

    @Min(value = 1, message = "页码最小为 1")
    private Integer current = 1;

    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = 100, message = "每页条数最大为 100")
    private Integer size = 10;
}
