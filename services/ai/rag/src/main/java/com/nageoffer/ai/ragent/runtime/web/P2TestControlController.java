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

package com.nageoffer.ai.ragent.runtime.web;

import com.nageoffer.ai.ragent.runtime.P2FaultInjector;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.dao.OutboxDao;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 专属测试控制面（仅 {@code p2.test-control.enabled=true}，生产禁用）。
 *
 * <p>用于验收 A02/A11/A16/A28/A32 的故障窗口注入与状态核对；只操作本服务的
 * 合成 run/outbox，不接受任意 SQL 或文件路径。
 */
@RestController
@RequestMapping("/internal/ai/v1/test")
@ConditionalOnProperty(name = "p2.test-control.enabled", havingValue = "true")
public class P2TestControlController {

    private final P2FaultInjector faults;
    private final OutboxDao outboxDao;
    private final RunLedgerDao runDao;
    private final P2RuntimeProperties properties;

    public P2TestControlController(P2FaultInjector faults, OutboxDao outboxDao, RunLedgerDao runDao,
                                   P2RuntimeProperties properties) {
        this.faults = faults;
        this.outboxDao = outboxDao;
        this.runDao = runDao;
        this.properties = properties;
    }

    public record ArmRequest(String hook, Integer times, Integer pauseMillis) {
    }

    @PostMapping("/fault")
    public Map<String, Object> arm(@RequestBody ArmRequest request) {
        if (request == null || request.hook() == null || request.hook().isBlank()) {
            return Map.of("code", 400, "msg", "hook is required");
        }
        faults.armPause(request.hook(), request.times() == null ? 1 : request.times(),request.pauseMillis()==null?0:request.pauseMillis());
        return Map.of("code", 200, "armed", faults.snapshot());
    }

    @DeleteMapping("/fault")
    public Map<String, Object> clear() {
        faults.clearAll();
        return Map.of("code", 200, "armed", Map.of());
    }

    @GetMapping("/fault")
    public Map<String, Object> inspect() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 200);
        result.put("armed", faults.snapshot());
        result.put("hits", faults.hitSnapshot());
        return result;
    }

    /** 供验收 runner 核对投递与运行事实（只读）。 */
    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 200);
        result.put("outboxPending", outboxDao.countPending());
        result.put("outboxPublished", outboxDao.countByState("PUBLISHED"));
        result.put("outboxDeadLetter", outboxDao.countByState("DEAD_LETTER"));
        result.put("eventsRetentionHours", properties.getEvents().getRetentionHours());
        result.put("worker", Map.of(
                "leaseSeconds", properties.getWorker().getLeaseSeconds(),
                "heartbeatSeconds", properties.getWorker().getHeartbeatSeconds()));
        return result;
    }
}
