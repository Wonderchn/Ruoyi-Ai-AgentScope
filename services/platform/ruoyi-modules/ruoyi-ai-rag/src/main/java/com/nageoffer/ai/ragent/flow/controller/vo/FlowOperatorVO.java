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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 条件操作符对外视图（公开只读面）。
 *
 * <p>字段形状与 legacy {@code OperatorEnum} 的 {@code name/desc} 一致：
 * {@code name} 是条件表达式的机器值（如 {@code contains}、{@code >=}），
 * {@code desc} 是展示文案（如 {@code 包含}、{@code 大于或等于}）。
 * 该面是<b>静态目录</b>，不读库、不随租户数据变化。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlowOperatorVO {

    private String name;

    private String desc;
}
