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

package com.nageoffer.ai.ragent.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.rag.controller.request.RagTraceRunPageRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceDetailVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceNodeVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RagTraceRunVO;
import com.nageoffer.ai.ragent.rag.trace.RagTraceReadScope;

import java.util.List;

/**
 * RAG Trace 查询服务。
 *
 * <p>F18/RW-23：所有读方法都要求调用方显式给出 {@link RagTraceReadScope} —— 限域不是
 * "服务内部猜一个"，而是调用点必须做的决定；缺主体的构造会 fail-closed。
 */
public interface RagTraceQueryService {

    /**
     * 分页查询链路运行记录（限域内）。
     */
    IPage<RagTraceRunVO> pageRuns(RagTraceRunPageRequest request, RagTraceReadScope scope);

    /**
     * 链路详情（限域内）。
     *
     * <p>跨租户（或不在可见范围内）的 traceId 与"不存在"同外显：返回 {@code null}，
     * 不泄露该链路是否存在。
     */
    RagTraceDetailVO detail(String traceId, RagTraceReadScope scope);

    /**
     * 链路节点（限域内）。
     *
     * <p>节点表没有租户列，因此必须先命中"限域内的父 run"；不在范围内返回空列表，
     * 与"链路不存在/无节点"同外显。
     */
    List<RagTraceNodeVO> listNodes(String traceId, RagTraceReadScope scope);
}
