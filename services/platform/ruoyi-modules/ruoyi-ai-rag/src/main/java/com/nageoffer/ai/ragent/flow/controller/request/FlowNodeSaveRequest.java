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
 * 节点新增 / 更新请求。
 *
 * <p>只承载节点自身字段：属主（userId）、租户（tenantId）、所属工作流（workflowId）与
 * 节点 uuid <b>都不是入参</b>——工作流来自路径（{@code /flows/{uuid}/nodes}）且经
 * owner+tenant 校验，uuid 由服务端铸造，属主/租户取自 {@code PrincipalContext}。
 *
 * <p>两个 jsonb 字段以<b>不透明 JSON 字符串</b>搬运（与 {@code JsonbTypeHandler} 同口径）；
 * 结构化校验（IO schema/引用完整性/环检测）留后续切片。
 */
@Data
public class FlowNodeSaveRequest {

    @NotBlank(message = "节点标题不能为空")
    @Size(max = 100, message = "节点标题最长 100 字符")
    private String title;

    @Size(max = 500, message = "节点备注最长 500 字符")
    private String remark;

    /** 引用的组件内部 id（ai_flow_component.id；缺省 0 = 无引用）。 */
    private Long workflowComponentId;

    /** 输入参数模板（JSON 字符串；缺省服务端兜底 "{}"，DDL 该列 NOT NULL）。 */
    @Size(max = 20000, message = "inputConfig 最长 20000 字符")
    private String inputConfig;

    /** 节点执行配置（JSON 字符串，可空）。 */
    @Size(max = 20000, message = "nodeConfig 最长 20000 字符")
    private String nodeConfig;

    /** 画布 x 坐标（缺省 0）。 */
    private Double positionX;

    /** 画布 y 坐标（缺省 0）。 */
    private Double positionY;
}
