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

package org.ruoyi.aiintegration.authorization;

/**
 * 授权复核端点可注入的故障。
 *
 * <p>接口放在主源集是为了给控制器留出调用点；<b>真实实现只存在于测试源集</b>
 * （Spec §8.6：生产路径不存在该开关）。测试应用卸载该 bean 时，控制器通过
 * {@code ObjectProvider} 得不到实现，故障注入自动失效。
 */
public interface PlatformFaultInjector {

    /**
     * 在授权判定之前注入故障。
     *
     * @param faultHeader 测试请求头 {@code X-P04-Platform-Fault} 的值，可为 {@code null}
     */
    void beforeAuthorization(String faultHeader);
}
