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

package com.nageoffer.ai.ragent.rag.controller.request;

import lombok.Data;

/**
 * 关键词映射创建请求
 */
@Data
public class QueryTermMappingCreateRequest {

    /**
     * 用户原始短语
     */
    private String sourceTerm;

    /**
     * 归一化后的目标短语
     */
    private String targetTerm;

    /**
     * 匹配类型：仅 1（精确匹配）被接受，缺省视同 1；
     * 2（前缀）/3（正则）/4（整词）为预留值，未实现——传入即明确拒绝（400 语义），
     * 不做静默落库（读侧只认 1，静默收下等于配置失效）。
     */
    private Integer matchType;

    /**
     * 优先级，数值越小优先级越高
     */
    private Integer priority;

    /**
     * 是否生效
     */
    private Boolean enabled;

    /**
     * 备注
     */
    private String remark;
}
