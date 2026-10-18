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

package com.nageoffer.ai.ragent.rag.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 知识图谱可视化视图（F08:0/1/3）。
 *
 * <p>数据只来自主库投影（{@link #readSource} 恒为 {@code projection}），读路径零 LightRAG 外呼。
 * 节点的 {@link Node#sources} 是"该实体被哪些文档/知识库提到"的溯源事实；边的
 * {@link Edge#sourcesDerived} 表示来源是从两端节点继承而非边自身 file_path。
 */
@Data
@Builder
public class GraphViewVO {

    /**
     * 节点集
     */
    private List<Node> nodes;

    /**
     * 边集
     */
    private List<Edge> edges;

    /**
     * 是否因节点数上限被截断（供前端提示「图谱过大，仅展示部分」）
     */
    private boolean truncated;

    /**
     * 因主库 join 失败/来源不可解析而丢弃的投影行数（诊断，期望 0）
     */
    private int droppedUnattributed;

    /**
     * 读源标识：固定 {@code projection}，表明本响应未调用外部图后端
     */
    private String readSource;

    /**
     * 图谱节点
     */
    @Data
    @Builder
    public static class Node {

        /**
         * 节点唯一 id，边按此关联
         */
        private String id;

        /**
         * 展示名（实体名）
         */
        private String name;

        /**
         * 实体类型，供前端按类型着色
         */
        private String type;

        /**
         * 实体描述，供悬浮详情
         */
        private String description;

        /**
         * 当前结果集内的度数（供前端决定节点半径）
         */
        private int degree;

        /**
         * 来源文档去重集合
         */
        private List<Source> sources;
    }

    /**
     * 图谱边
     */
    @Data
    @Builder
    public static class Edge {

        /**
         * 边唯一 id
         */
        private String id;

        /**
         * 源节点 id
         */
        private String source;

        /**
         * 目标节点 id
         */
        private String target;

        /**
         * 关系标签（关键词 / 类型）
         */
        private String label;

        /**
         * 关系描述，供前端悬浮展示可读的关系说明
         */
        private String description;

        /**
         * true 表示边自身没有 file_path，来源继承自两端节点
         */
        private boolean sourcesDerived;

        /**
         * 来源文档去重集合
         */
        private List<Source> sources;
    }

    /**
     * 来源文档（文档-实体-关系溯源的最小事实）
     */
    @Data
    @Builder
    public static class Source {

        private String kbId;

        private String docId;

        private String docName;

        /**
         * 知识库 collection 名（展示用；不是授权边界）
         */
        private String collectionName;
    }
}
