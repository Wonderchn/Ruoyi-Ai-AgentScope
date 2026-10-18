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

package com.nageoffer.ai.ragent.flow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F13:2 runtime 视图组装（§3.6）：列表/详情/节点。
 *
 * <p><b>节点视图以账本为权威。</b>投影行（{@code ai_flow_runtime_node}）可能落后于
 * {@code ai_run_step}（崩溃窗口）；当 {@code ai_run_step.state='COMPLETED'} 存在时，
 * 视图的 {@code status/output} 取该 step（权威），否则取投影行。
 */
public final class FlowRuntimeViews {

    private FlowRuntimeViews() {
    }

    public static Map<String, Object> list(List<FlowRuntimeRepository.RuntimeView> rows, long total,
                                           int current, int size, ObjectMapper mapper) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (FlowRuntimeRepository.RuntimeView row : rows) {
            records.add(runtime(row, mapper));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", records);
        data.put("total", total);
        data.put("current", current);
        data.put("size", size);
        data.put("pages", size <= 0 ? 0 : (total + size - 1) / size);
        return data;
    }

    public static Map<String, Object> runtime(FlowRuntimeRepository.RuntimeView row, ObjectMapper mapper) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("uuid", row.uuid());
        view.put("workflowUuid", row.workflowUuid());
        view.put("status", row.status());
        view.put("statusRemark", row.statusRemark());
        view.put("output", json(row.outputJson(), mapper));
        view.put("runStatus", row.runStatus());
        view.put("errorCode", row.errorCode());
        view.put("createTime", row.createTime());
        view.put("updateTime", row.updateTime());
        return view;
    }

    public static List<Map<String, Object>> nodes(List<FlowRuntimeRepository.RuntimeNodeView> rows,
                                                  ObjectMapper mapper) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (FlowRuntimeRepository.RuntimeNodeView row : rows) {
            boolean stepCompleted = "COMPLETED".equals(row.stepState());
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("nodeId", row.nodeId());
            view.put("nodeUuid", row.nodeUuid());
            // 权威来源：step 完成 -> 3；否则投影行（1 进行中 / 2 失败 / 3 成功）。
            view.put("status", stepCompleted ? 3 : row.status());
            view.put("statusRemark", row.statusRemark());
            view.put("input", json(row.inputJson(), mapper));
            view.put("output", json(stepCompleted && row.stepRefJson() != null
                    ? row.stepRefJson() : row.outputJson(), mapper));
            view.put("stepState", row.stepState());
            view.put("createTime", row.createTime());
            view.put("updateTime", row.updateTime());
            views.add(view);
        }
        return views;
    }

    private static JsonNode json(String raw, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception invalid) {
            // 投影里的坏 JSON 不外泄、不 500：视图缺失该字段而不是伪造内容。
            return null;
        }
    }
}
