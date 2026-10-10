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

package com.nageoffer.ai.ragent.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.QueryTermMappingVO;
import com.nageoffer.ai.ragent.rag.service.QueryTermMappingAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关键词映射管理控制器
 *
 * <p><b>路径面（RW-22-R1，F07-A1 对齐实际）</b>：类级前缀 {@code /internal/ai/v1}；
 * 公开面经网关白名单为 {@code /api/ai/v1/mappings}（含 {@code /mappings/{id}}，
 * {@code AiGatewayController} 白名单逐条登记 {@code config.read}/{@code config.publish}）。
 * 此前 javadoc 误写为 {@code /api/ai/v1/query-terms/**}，该路径不存在。
 * 更早之前无类级 {@code @RequestMapping}，裸路径
 * {@code /mappings/...} 不在委托主体保护前缀下 ⇒ 拿不到主体（WP-034/RW-04-R1 同形缺陷）。
 *
 * <p><b>信封（RW-22-R1）</b>：返回 {@link ApiEnvelope}（整数 {@code code}）。
 * 平台 {@code Result} 的字符串 {@code code="0"} 会被网关判成"缺少包络 code"⇒ 503。
 *
 * <p>授权不在本层：动作复用 {@code config.read}/{@code config.publish}，不新增 canonical 动作。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@RequiredArgsConstructor
public class QueryTermMappingController {

    private final QueryTermMappingAdminService queryTermMappingAdminService;

    /**
     * 分页查询映射规则
     */
    @GetMapping("/mappings")
    public ApiEnvelope<IPage<QueryTermMappingVO>> pageQuery(QueryTermMappingPageRequest requestParam) {
        return ApiEnvelope.ok(queryTermMappingAdminService.pageQuery(requestParam));
    }

    /**
     * 查询映射规则详情
     */
    @GetMapping("/mappings/{id}")
    public ApiEnvelope<QueryTermMappingVO> queryById(@PathVariable String id) {
        return ApiEnvelope.ok(queryTermMappingAdminService.queryById(id));
    }

    /**
     * 创建映射规则
     */
    @PostMapping("/mappings")
    public ApiEnvelope<String> create(@RequestBody QueryTermMappingCreateRequest requestParam) {
        return ApiEnvelope.ok(queryTermMappingAdminService.create(requestParam));
    }

    /**
     * 更新映射规则
     */
    @PutMapping("/mappings/{id}")
    public ApiEnvelope<Void> update(@PathVariable String id, @RequestBody QueryTermMappingUpdateRequest requestParam) {
        queryTermMappingAdminService.update(id, requestParam);
        return ApiEnvelope.ok(null);
    }

    /**
     * 删除映射规则
     */
    @DeleteMapping("/mappings/{id}")
    public ApiEnvelope<Void> delete(@PathVariable String id) {
        queryTermMappingAdminService.delete(id);
        return ApiEnvelope.ok(null);
    }
}
