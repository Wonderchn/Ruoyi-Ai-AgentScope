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

package com.nageoffer.ai.ragent.runtime.config;

/**
 * 运行配置权威不可得（C1.1 fail-closed）。
 *
 * <p>这是一个**拒绝**信号，不是一个可忽略的异常：
 * <ul>
 *   <li>调用方（受理路径 / 公开面）必须把它转成拒绝类响应（503 语义），
 *       <b>不得</b>吞掉后继续用 YAML 值；</li>
 *   <li>它同时覆盖三种成因，且刻意不区分对外文案（避免泄露租户是否存在发布版本）：
 *       无执行主体、该租户无 PUBLISHED 版本、数据库读取失败。</li>
 * </ul>
 *
 * <p>与 {@code POLICY_VERSION_STALE} 的区别：那个码是**授权**语义（许可/epoch 过期），
 * 本异常是**配置权威**语义。把两者混用会把"配置读不到"误报成"授权过期"，
 * 排查方向直接跑偏——这与 C9.3 拒绝复用 {@code POLICY_VERSION_STALE} 的理由同源。
 */
public class ConfigAuthorityUnavailable extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    public ConfigAuthorityUnavailable(String reason) {
        super("published config revision unavailable: " + reason);
        this.reason = reason;
    }

    /** 机器可读的原因（用于日志与证据，不外泄给客户端）。 */
    public String reason() {
        return reason;
    }
}
