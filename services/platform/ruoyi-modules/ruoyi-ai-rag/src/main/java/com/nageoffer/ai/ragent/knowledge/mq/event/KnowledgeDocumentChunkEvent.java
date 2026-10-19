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

package com.nageoffer.ai.ragent.knowledge.mq.event;

import com.nageoffer.ai.ragent.framework.context.AsyncPrincipalReference;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文档分块任务事件
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentChunkEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文档 ID
     */
    private String docId;

    /**
     * 知识库 ID
     */
    private String kbId;

    /**
     * 操作人
     */
    private String operator;

    /**
     * 租户 ID（F22:2 真机第四缺口）。
     *
     * <p>消费/回查线程没有登录主体，而 platform 的 MyBatis 租户行拦截器
     * （{@code tenant.enable=true}）对 {@code ai_knowledge_document} 等租户业务表
     * fail-closed：任一 DB 操作之前必须有可信租户。事件必须携带投递侧可信的租户号，
     * 消费侧在任何 DB 访问前把它绑定到当前线程；缺失即按"投递侧漏传"拒绝，
     * 不落默认租户、不静默跳过。
     */
    private String tenantId;

    /**
     * 异步身份引用（F22:2 真机第五缺口）。
     *
     * <p>消费线程既没有登录主体、也没有 AI 侧 {@code PrincipalContext}，而 chunk 执行链
     * （{@code VectorTargetResolver.resolve}）要求一个执行主体。冻结口径（05 §4.3）：
     * 异步任务只传<b>身份引用</b>与源版本，不传 bearer——引用由受理线程的
     * {@code AsyncPrincipalReference.from(PrincipalContext.require())} 生成，
     * 消费侧 {@code toExecutionPrincipal()} 恢复出的对象 scopes 恒为空，
     * 只用于租户/归属定位，任何内容访问仍须在线重判授权。
     */
    private AsyncPrincipalReference principalRef;
}
