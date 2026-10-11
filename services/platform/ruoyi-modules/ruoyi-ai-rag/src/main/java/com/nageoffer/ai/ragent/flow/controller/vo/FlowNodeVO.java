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

package com.nageoffer.ai.ragent.flow.controller.vo;

import lombok.Data;

import java.util.Date;

/**
 * 节点对外视图。
 *
 * <p>刻意<b>不</b>回传 tenantId（与 {@code FlowWorkflowVO} 同口径：租户是服务端资源边界）；
 * 工作流归属经请求路径已知，故只回传节点自身字段与所属工作流的<b>内部</b> id
 * （workflowId，供前端关联同一请求内的资源）。
 */
@Data
public class FlowNodeVO {

    private String uuid;

    /** 所属工作流内部 id（ai_flow_workflow.id）。 */
    private Long workflowId;

    private String title;

    private String remark;

    /** 引用的组件内部 id（ai_flow_component.id）。 */
    private Long workflowComponentId;

    /** 输入参数模板（不透明 JSON 字符串）。 */
    private String inputConfig;

    /** 节点执行配置（不透明 JSON 字符串，可空）。 */
    private String nodeConfig;

    private Double positionX;

    private Double positionY;

    private Date createTime;

    private Date updateTime;
}
