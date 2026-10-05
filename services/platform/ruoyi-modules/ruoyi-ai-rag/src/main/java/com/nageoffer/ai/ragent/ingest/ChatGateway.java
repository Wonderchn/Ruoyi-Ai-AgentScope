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

package com.nageoffer.ai.ragent.ingest;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 问答模型网关：真实提供方与显式合成 test double 互斥（p2.chat.mode）。 */
public interface ChatGateway extends org.ruoyi.ai.api.runtime.ChatPort<ChatMessage> {

    String provider();

    String model();

    /**
     * 流式问答。providerRequestId/usageRaw 为 null 表示提供方未返回 → 上层记待核对。
     */
    ChatResult stream(List<ChatMessage> messages, int maxTokens, Consumer<String> onDelta);


}
