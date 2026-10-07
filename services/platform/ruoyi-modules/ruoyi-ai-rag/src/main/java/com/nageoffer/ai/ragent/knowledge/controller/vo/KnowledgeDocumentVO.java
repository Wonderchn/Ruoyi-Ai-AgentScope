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

package com.nageoffer.ai.ragent.knowledge.controller.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档视图对象
 */
@Data
public class KnowledgeDocumentVO {

    /**
     * 文档唯一标识
     */
    private String id;

    /**
     * 知识库ID
     */
    private String kbId;

    /**
     * 文档名称
     */
    private String docName;

    /**
     * 来源类型
     */
    private String sourceType;

    /**
     * 来源位置
     */
    private String sourceLocation;

    /**
     * 是否开启定时拉取
     */
    private Integer scheduleEnabled;

    /**
     * 定时表达式
     */
    private String scheduleCron;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 切片数量
     */
    private Integer chunkCount;

    /**
     * 文件URL
     */
    private String fileUrl;

    /**
     * 文件类型
     */
    private String fileType;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 处理模式：chunk / pipeline
     * - chunk: 使用分块策略直接分块
     * - pipeline: 使用数据通道进行清洗处理
     */
    private String processMode;

    /**
     * 文档级摄取配置（JSON）：解析档位 + 分块预算
     */
    private String ingestionSpec;

    /**
     * 数据通道（Pipeline）ID
     * 仅在 processMode=pipeline 时有效
     */
    private String pipelineId;

    /**
     * 状态（如：解析中、已解析、解析失败等）
     */
    private String status;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 更新人
     */
    private String updatedBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 乐观锁版本：等于 {@code update_time} 的 epoch 毫秒。
     *
     * <p><b>为什么单独出一个字段而不是让前端用 updateTime。</b>
     * {@code updateTime} 的线路形状由 Jackson 的日期格式决定（可能是字符串、可能丢精度），
     * 而版本比较必须是精确的整数相等。这里给出的毫秒整数与库里 {@code update_time}
     * 是同一个瞬间，前端原样回传即可（PUT / PATCH enable 的 {@code expectedVersion}）。
     */
    private Long version;

    /**
     * 是否存在被手工编辑过的分块（基于 chunk.updateTime > chunk.createTime 推断）
     * 仅查询时填充，不持久化。重新分块会将该状态清零
     */
    private Boolean chunksEdited;
}
