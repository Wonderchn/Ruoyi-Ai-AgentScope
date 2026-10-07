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

package com.nageoffer.ai.ragent.sample.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.sample.controller.request.SampleQuestionCreateRequest;
import com.nageoffer.ai.ragent.sample.controller.request.SampleQuestionPageRequest;
import com.nageoffer.ai.ragent.sample.controller.request.SampleQuestionUpdateRequest;
import com.nageoffer.ai.ragent.sample.controller.vo.SampleQuestionVO;
import com.nageoffer.ai.ragent.sample.service.SampleQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 示例问题控制器
 * 前台各引擎欢迎页取随机若干条，后台走标准 CRUD
 *
 * <p><b>路径面（RW-22-R1）</b>：类级前缀从裸 {@code /sample-questions} 改为
 * {@code /internal/ai/v1/sample-questions}；公开面经网关白名单为
 * {@code /api/ai/v1/sample-questions/**}。裸路径不在
 * {@code DelegatedPrincipalFilter.PROTECTED_PREFIX} 之下 ⇒ 拿不到委托主体
 * （与 {@code IntentTreeController} 等三处同形的缺陷，一并修正）。
 *
 * <p><b>信封（RW-22-R1）</b>：返回 {@link ApiEnvelope}（整数 {@code code}）。
 * 平台 {@code Result} 的字符串 {@code code="0"} 会被网关判成"缺少包络 code"⇒ 503。
 *
 * <p>授权不在本层：动作复用 {@code kb.read}（读）/ {@code config.publish}（写），
 * 不新增 canonical 动作。
 */
@RestController
@RequestMapping("/internal/ai/v1/sample-questions")
@RequiredArgsConstructor
public class SampleQuestionController {

    private final SampleQuestionService sampleQuestionService;

    /**
     * 随机获取示例问题列表，条数由调用方决定
     * 字面量段优先于 /{id} 模板匹配，两者不会打架
     */
    @GetMapping("/random")
    public ApiEnvelope<List<SampleQuestionVO>> listRandom(@RequestParam(defaultValue = "3") int limit) {
        return ApiEnvelope.ok(sampleQuestionService.listRandomQuestions(limit));
    }

    /**
     * 分页查询示例问题列表
     */
    @GetMapping
    public ApiEnvelope<IPage<SampleQuestionVO>> pageQuery(SampleQuestionPageRequest requestParam) {
        return ApiEnvelope.ok(sampleQuestionService.pageQuery(requestParam));
    }

    /**
     * 查询示例问题详情
     */
    @GetMapping("/{id}")
    public ApiEnvelope<SampleQuestionVO> queryById(@PathVariable String id) {
        return ApiEnvelope.ok(sampleQuestionService.queryById(id));
    }

    /**
     * 创建示例问题
     */
    @PostMapping
    public ApiEnvelope<String> create(@RequestBody SampleQuestionCreateRequest requestParam) {
        return ApiEnvelope.ok(sampleQuestionService.create(requestParam));
    }

    /**
     * 更新示例问题
     */
    @PutMapping("/{id}")
    public ApiEnvelope<Void> update(@PathVariable String id, @RequestBody SampleQuestionUpdateRequest requestParam) {
        sampleQuestionService.update(id, requestParam);
        return ApiEnvelope.ok(null);
    }

    /**
     * 删除示例问题
     */
    @DeleteMapping("/{id}")
    public ApiEnvelope<Void> delete(@PathVariable String id) {
        sampleQuestionService.delete(id);
        return ApiEnvelope.ok(null);
    }
}
