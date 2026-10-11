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
 * 组件库条目新增 / 更新请求。
 *
 * <p>租户取自 {@code PrincipalContext}，组件 uuid 由服务端铸造；组件库无 user_id 列
 * ⇒ 这是租户级库（DDL 事实，见 {@code AiFlowComponentDO} 类注释）。
 */
@Data
public class FlowComponentSaveRequest {

    @NotBlank(message = "组件名不能为空")
    @Size(max = 32, message = "组件名最长 32 字符")
    private String name;

    @Size(max = 100, message = "组件标题最长 100 字符")
    private String title;

    /** 备注（DDL 该列 NOT NULL；缺省服务端兜底空串）。 */
    @Size(max = 2000, message = "备注最长 2000 字符")
    private String remark;

    /** 展示顺序（缺省 0）。 */
    private Integer displayOrder;

    /** 是否启用：0 停放 1 启用（缺省按启用）。 */
    private Integer isEnable;
}
