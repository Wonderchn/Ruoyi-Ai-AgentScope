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

package com.nageoffer.ai.ragent.flow.support;

import com.nageoffer.ai.ragent.flow.controller.vo.FlowOperatorVO;

import java.util.List;

/**
 * 条件操作符静态目录（公开只读面，无 DB 访问）。
 *
 * <p><b>逐字来源</b>：legacy 模块（只读参照 {@code 89dce18}）
 * {@code org.ruoyi.workflow.workflow.node.switcher.OperatorEnum} 的 12 项
 * {@code name/desc} 对；顺序也保持 legacy 声明序。这里<b>重写</b>到内嵌包，
 * 不恢复旧模块、不新增依赖。
 *
 * <p>为什么硬编码而不是读库：操作符是图条件的枚举词汇，属于代码契约；
 * 放进数据库反而会让不同租户看到不同词汇。公开面只返回静态清单，不触碰任何租户数据。
 */
public final class FlowOperatorCatalog {

    /** legacy {@code OperatorEnum} 的 12 项，逐字一致（name/desc/顺序）。 */
    private static final List<FlowOperatorVO> OPERATORS = List.of(
            new FlowOperatorVO("contains", "包含"),
            new FlowOperatorVO("not contains", "不包含"),
            new FlowOperatorVO("start with", "开始内容是"),
            new FlowOperatorVO("end with", "结束内容是"),
            new FlowOperatorVO("empty", "为空"),
            new FlowOperatorVO("not empty", "不为空"),
            new FlowOperatorVO("=", "等于"),
            new FlowOperatorVO("!=", "不等于"),
            new FlowOperatorVO(">", "大于"),
            new FlowOperatorVO(">=", "大于或等于"),
            new FlowOperatorVO("<", "小于"),
            new FlowOperatorVO("<=", "小于或等于"));

    private FlowOperatorCatalog() {
    }

    /** @return 12 项不可变操作符目录（调用方不得改写） */
    public static List<FlowOperatorVO> operators() {
        return OPERATORS;
    }
}
