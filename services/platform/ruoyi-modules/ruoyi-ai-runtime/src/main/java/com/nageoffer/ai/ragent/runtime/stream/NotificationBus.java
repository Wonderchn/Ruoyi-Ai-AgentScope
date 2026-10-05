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

package com.nageoffer.ai.ragent.runtime.stream;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;

/**
 * 进程内通知总线：outbox relay 投递后唤醒本节点 SSE 订阅。
 *
 * <p>通知只是加速手段：SSE 始终以数据库连续游标回放与轮询补偿为准，
 * 通知丢失/重复/乱序都不会改变可见事件事实（A16/A17）。
 */
@Component
public class NotificationBus {

    private final Map<String, CopyOnWriteArrayList<LongConsumer>> listeners = new ConcurrentHashMap<>();

    private static String key(String tenantId, String runId) {
        return tenantId + '\u0000' + runId;
    }

    public AutoCloseable subscribe(String tenantId, String runId, LongConsumer onNotify) {
        String key = key(tenantId, runId);
        CopyOnWriteArrayList<LongConsumer> list = listeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        list.add(onNotify);
        return () -> {
            list.remove(onNotify);
            if (list.isEmpty()) {
                listeners.remove(key, list);
            }
        };
    }

    /** 投递通知；listener 异常只影响该 listener。 */
    public void publish(String tenantId, String runId, long seq, String eventId) {
        CopyOnWriteArrayList<LongConsumer> list = listeners.get(key(tenantId, runId));
        if (list == null) {
            return;
        }
        for (LongConsumer listener : list) {
            try {
                listener.accept(seq);
            } catch (RuntimeException ignored) {
                // 订阅端异常不能影响 relay；游标补偿会补齐
            }
        }
    }
}
