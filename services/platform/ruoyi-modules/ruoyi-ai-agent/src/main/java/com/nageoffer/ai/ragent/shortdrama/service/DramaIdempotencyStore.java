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

package com.nageoffer.ai.ragent.shortdrama.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从创意创建的进程内幂等登记。
 *
 * <p><b>边界如实登记。</b>V9 的 {@code ai_short_drama_project} 没有幂等键列，本切片
 * 也不新增迁移；因此幂等只覆盖<b>单节点进程生命周期</b>（窗口内重复点击/重试）。
 * 重启或多实例并发不具备持久幂等——这属于"未验不改 DONE"的显式 NOT_RUN，
 * 不假装已经具备。
 *
 * <p>语义：
 * <ul>
 *   <li>同一 {@code (tenant, user, key)} 已完成 → 返回既有 projectId，不再调用模型；</li>
 *   <li>正在处理 → 后续请求 409，不并发重复生成；</li>
 *   <li>失败/中断 → 释放登记，允许重试；</li>
 *   <li>不传 key → 不做幂等，按普通创建处理。</li>
 * </ul>
 * 键里带 tenant 与 user，避免不同租户/用户复用同一个客户端 key 相互串结果。
 */
@Component
public class DramaIdempotencyStore {

    /** 登记保留时长：覆盖一次模型生成 + 用户重试窗口。 */
    static final long TTL_MILLIS = 30 * 60 * 1000L;

    private record Entry(boolean inProgress, Long projectId, long expiresAt) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public Optional<Long> completed(DramaActor actor, String key) {
        if (isBlank(key)) {
            return Optional.empty();
        }
        String normalized = key(actor, key);
        Entry entry = entries.get(normalized);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt() < System.currentTimeMillis()) {
            entries.remove(normalized, entry);
            return Optional.empty();
        }
        return entry.inProgress() ? Optional.empty() : Optional.ofNullable(entry.projectId());
    }

    /**
     * 认领一个 key。
     *
     * @return true 表示本次调用获得了生成权；false 表示已有完成或进行中的登记
     */
    public boolean claim(DramaActor actor, String key) {
        if (isBlank(key)) {
            return true;
        }
        String normalized = key(actor, key);
        long now = System.currentTimeMillis();
        Entry fresh = new Entry(true, null, now + TTL_MILLIS);
        Entry existing = entries.putIfAbsent(normalized, fresh);
        if (existing == null) {
            return true;
        }
        if (existing.expiresAt() < now) {
            return entries.replace(normalized, existing, fresh);
        }
        return false;
    }

    public void complete(DramaActor actor, String key, long projectId) {
        if (isBlank(key)) {
            return;
        }
        entries.put(key(actor, key),
                new Entry(false, projectId, System.currentTimeMillis() + TTL_MILLIS));
    }

    public void release(DramaActor actor, String key) {
        if (isBlank(key)) {
            return;
        }
        String normalized = key(actor, key);
        Entry entry = entries.get(normalized);
        if (entry != null && entry.inProgress()) {
            entries.remove(normalized, entry);
        }
    }

    /** 仅测试与诊断使用。 */
    public int size() {
        return entries.size();
    }

    private static String key(DramaActor actor, String key) {
        return actor.tenantId() + ":" + actor.userId() + ":" + key;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
