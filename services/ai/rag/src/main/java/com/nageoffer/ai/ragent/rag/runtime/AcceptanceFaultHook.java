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

package com.nageoffer.ai.ragent.rag.runtime;

/**
 * 受理事务的故障注入点（Spec §7.4 的 F01/F02）。
 *
 * <p>接口放在主源集只是为了给事务边界留出调用点；<b>真实实现只存在于测试源集</b>，
 * 由控制器/存储通过 {@code ObjectProvider} 查找——未部署实现时自动失效，因此生产路径
 * 不存在该开关。
 */
public interface AcceptanceFaultHook {

    /**
     * 四类业务记录已写入、事务<b>尚未提交</b>时调用；抛出即整体回滚（F01）。
     */
    void beforeCommit(String runId);

    /**
     * 事务<b>已提交</b>后调用；抛出用于模拟"提交成功但响应丢失"（F02）。
     */
    void afterCommit(String runId);
}
