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

/**
 * 流式创建的输出端抽象。
 *
 * <p>控制器用 {@code SseEmitter} 实现它，测试用记录型实现；流式服务本身不依赖
 * Servlet/MVC 类型，便于在没有真实连接的情况下验证"阶段/增量/完成/错误"四类帧。
 */
public interface StreamSink {

    void phase(String phase, String status, String message);

    void stream(String phase, String text);

    void complete(String json);

    void error(String json);

    /** 客户端已断开或输出端已关闭。 */
    boolean isClosed();
}
