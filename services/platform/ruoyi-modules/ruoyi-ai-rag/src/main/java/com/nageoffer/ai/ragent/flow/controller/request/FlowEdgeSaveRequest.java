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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 边新增 / 更新请求。
 *
 * <p>只承载边自身字段：所属工作流来自路径且经 owner+tenant 校验，边 uuid 由服务端铸造，
 * 租户取自 {@code PrincipalContext}。端点不存在性/同工作流性校验留后续切片
 * （本切片为最小 CRUD 骨架，只在服务层校验工作流归属）。
 */
@Data
public class FlowEdgeSaveRequest {

    @NotBlank(message = "起始节点 uuid 不能为空")
    @Size(max = 32, message = "起始节点 uuid 最长 32 字符")
    private String sourceNodeUuid;

    @Size(max = 32, message = "起始锚点最长 32 字符")
    private String sourceHandle;

    @NotBlank(message = "目标节点 uuid 不能为空")
    @Size(max = 32, message = "目标节点 uuid 最长 32 字符")
    private String targetNodeUuid;
}
