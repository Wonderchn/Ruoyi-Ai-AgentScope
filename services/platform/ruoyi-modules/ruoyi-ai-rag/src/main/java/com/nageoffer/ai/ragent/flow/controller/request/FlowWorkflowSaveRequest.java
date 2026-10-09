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
 * 工作流定义新增 / 基础信息更新请求。
 *
 * <p>只承载「基础信息」：标题、备注、是否启用。属主（userId）与租户（tenantId）
 * <b>不是入参</b>，由服务层从 {@code PrincipalContext} 取，避免调用方指定归属。
 */
@Data
public class FlowWorkflowSaveRequest {

    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题最长 100 字符")
    private String title;

    @Size(max = 2000, message = "备注最长 2000 字符")
    private String remark;

    /** 是否启用：0 停放 1 启用（缺省按启用）。 */
    private Integer isEnable;
}
