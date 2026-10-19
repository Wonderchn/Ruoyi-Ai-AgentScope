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

import java.util.List;

/**
 * 公开工作流 START 输入 schema 视图（F13:4）。
 *
 * <p><b>字段白名单（写死）</b>：只回传 {@code uuid/title/inputs/nodes}。
 * <ul>
 *   <li>{@link Node} 只含 {@code uuid/title/type}，<b>不含</b> {@code node_config} /
 *       {@code prompt} / 所有者 / 租户 —— 公开只读面不泄露执行配置与归属；</li>
 *   <li>{@link Input} 是前端渲染输入对话框所需的声明式字段；{@code type} 已归一为
 *       1 文本 / 2 数字 / 4 文件 / 5 布尔。</li>
 * </ul>
 *
 * <p>本视图由服务端按「同租户 + 公开 + 启用 + 未删」谓词读取后构造；不可见时服务层返回
 * {@code null}，由控制器映射为与公开详情同一个 404 文案。
 */
@Data
public class FlowPublicInputsVO {

    /** 工作流对外标识。 */
    private String uuid;

    /** 工作流标题。 */
    private String title;

    /** START 节点声明的用户输入项（解析失败/未声明时为空数组）。 */
    private List<Input> inputs;

    /** 图节点摘要（仅 START/LLM/END），用于前端 step 卡映射标题。 */
    private List<Node> nodes;

    /** 输入项声明。 */
    @Data
    public static class Input {

        /** 输入项 uuid（可缺省）。 */
        private String uuid;

        /** 输入参数名（flow.run inputs 的键）。 */
        private String name;

        /** 展示标题。 */
        private String title;

        /** 归一后的类型：1 文本 / 2 数字 / 4 文件 / 5 布尔。 */
        private Integer type;

        /** 是否必填（仅前端校验使用；服务端不据此拒绝）。 */
        private Boolean required;

        /** 文本长度等上限（来自 {@code limit} 或 legacy {@code max_length}，可缺省）。 */
        private Integer limit;
    }

    /** 图节点摘要。 */
    @Data
    public static class Node {

        /** 节点 uuid。 */
        private String uuid;

        /** 节点标题。 */
        private String title;

        /** 解析后的类型：START / LLM / END。 */
        private String type;
    }
}
