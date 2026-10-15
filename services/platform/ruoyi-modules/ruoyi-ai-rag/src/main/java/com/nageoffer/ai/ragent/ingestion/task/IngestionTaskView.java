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

package com.nageoffer.ai.ragent.ingestion.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.RunLifecycleService;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F06:1/2 任务读视图组装：读侧复用 {@code ai_ingestion_task} / {@code ai_ingestion_task_node}，
 * 并先用 {@link RunLifecycleService#get} 过 {@code RunAccessService.visible}（managed run 的
 * 成员/策略/ACL 当前性），再据 run 身份读任务行，避免把无 run 的旧任务行披露到本入口。
 */
@Component
public class IngestionTaskView {

    private final IngestionTaskRepository tasks;
    private final RunLifecycleService lifecycle;
    private final ObjectMapper objectMapper;

    public IngestionTaskView(IngestionTaskRepository tasks, RunLifecycleService lifecycle, ObjectMapper objectMapper) {
        this.tasks = tasks;
        this.lifecycle = lifecycle;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> detail(ExecutionPrincipal principal, String runId) {
        lifecycle.get(principal, runId);
        IngestionTaskRepository.TaskRow row = tasks.findTask(principal.tenantId(), runId)
                .orElseThrow(() -> new com.nageoffer.ai.ragent.runtime.RunApiException(
                        com.nageoffer.ai.ragent.runtime.RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        return taskView(row);
    }

    public Map<String, Object> page(ExecutionPrincipal principal, int pageNo, int pageSize, String status) {
        int current = Math.max(1, pageNo);
        int size = Math.min(100, Math.max(1, pageSize));
        long total = tasks.countTasks(principal.tenantId(), principal.membershipId(), status);
        List<Map<String, Object>> records = tasks
                .listTasks(principal.tenantId(), principal.membershipId(), status, size, (current - 1) * size)
                .stream().map(this::taskView).toList();
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("records", records);
        page.put("total", total);
        page.put("current", current);
        page.put("size", size);
        return page;
    }

    public List<Map<String, Object>> nodes(ExecutionPrincipal principal, String runId) {
        lifecycle.get(principal, runId);
        return tasks.listNodes(principal.tenantId(), runId).stream().map(this::nodeView).toList();
    }

    /** 受理控制器/执行器读完父任务元数据后构造重试入参用。 */
    public Map<String, Object> parentMetadata(ExecutionPrincipal principal, String runId) {
        lifecycle.get(principal, runId);
        IngestionTaskRepository.TaskRow row = tasks.findTask(principal.tenantId(), runId)
                .orElseThrow(() -> new com.nageoffer.ai.ragent.runtime.RunApiException(
                        com.nageoffer.ai.ragent.runtime.RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        return parse(row.metadataJson());
    }

    private Map<String, Object> taskView(IngestionTaskRepository.TaskRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("runId", row.id());
        view.put("pipelineId", row.pipelineId());
        view.put("sourceType", row.sourceType());
        view.put("sourceLocation", row.sourceLocation());
        view.put("sourceFileName", row.sourceFileName());
        view.put("status", row.status());
        view.put("chunkCount", row.chunkCount());
        view.put("errorMessage", row.errorMessage());
        view.put("metadata", parse(row.metadataJson()));
        view.put("logs", parseAny(row.logsJson()));
        view.put("startedAt", row.startedAt());
        view.put("completedAt", row.completedAt());
        view.put("createdBy", row.createdBy());
        view.put("createTime", row.createTime());
        view.put("updateTime", row.updateTime());
        return view;
    }

    private Map<String, Object> nodeView(IngestionTaskRepository.NodeRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("taskId", row.taskId());
        view.put("pipelineId", row.pipelineId());
        view.put("nodeId", row.nodeId());
        view.put("nodeType", row.nodeType());
        view.put("nodeOrder", row.nodeOrder());
        view.put("status", row.status());
        view.put("durationMs", row.durationMs());
        view.put("message", row.message());
        view.put("errorMessage", row.errorMessage());
        view.put("output", parseAny(row.outputJson()));
        view.put("createTime", row.createTime());
        view.put("updateTime", row.updateTime());
        return view;
    }

    /** 供生命周期视图复用（取消/重试回执中的 run 摘要）。 */
    public Map<String, Object> runView(RunRecord run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", run.runId());
        view.put("status", run.status());
        view.put("version", run.version());
        view.put("attempt", run.attempt());
        view.put("cancelRequestedAt", run.cancelRequestedAt());
        view.put("errorCode", run.errorCode());
        return view;
    }

    private Map<String, Object> parse(String json) {
        Object value = parseAny(json);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return Map.of();
    }

    private Object parseAny(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}
