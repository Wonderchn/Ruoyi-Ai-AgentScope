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
 * 边对外视图。
 *
 * <p>刻意<b>不</b>回传 tenantId（与 {@code FlowWorkflowVO} 同口径）。
 */
@Data
public class FlowEdgeVO {

    private String uuid;

    /** 所属工作流内部 id（ai_flow_workflow.id）。 */
    private Long workflowId;

    private String sourceNodeUuid;

    private String sourceHandle;

    private String targetNodeUuid;

    private Date createTime;

    private Date updateTime;
}
