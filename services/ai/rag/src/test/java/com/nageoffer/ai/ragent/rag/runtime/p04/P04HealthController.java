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

package com.nageoffer.ai.ragent.rag.runtime.p04;

import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 最小测试应用的就绪探针（Spec §8.4 冻结路径 {@code GET /p04/health}）。
 *
 * <p>只存在于测试源集，不是产品接口。
 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "p04.enabled", havingValue = "true")
public class P04HealthController {

    @GetMapping("/p04/health")
    public ApiEnvelope<Map<String, Object>> health() {
        return ApiEnvelope.ok(Map.of("status", "UP", "app", "p04-ai-test"));
    }
}
