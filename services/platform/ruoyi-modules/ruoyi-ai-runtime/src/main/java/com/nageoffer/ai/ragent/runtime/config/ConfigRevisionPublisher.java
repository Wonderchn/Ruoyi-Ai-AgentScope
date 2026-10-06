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

import java.time.Instant;

/**
 * 配置发布权威的**写侧**端口（WP-040：管理面与运行面同一权威）。
 *
 * <p><b>为什么它必须存在。</b>Wave 2 落了读侧三件套（{@link EngineModelAuthority}/
 * {@link RunConfigBindingPort}/{@code ProviderConnectionPort}），但
 * {@code ai_runtime_config_revision} 在全仓**没有任何写入点** —— 读端口在，
 * "发布一个版本"这件事却只能靠手写 SQL 完成。一张没人能写的表构不成权威，
 * "改一处另一处同步变"（A1）的前半句（改）因此落空。本端口把发布/撤权收敛为
 * 唯一写入口。
 *
 * <p><b>唯一权威，不是第二个权威。</b>它写的正是读端口读的那张表
 * （{@code platform.ai_runtime_config_revision}，V15）：发布即插入新行（不可变），
 * 撤权即该行 {@code state='PUBLISHED'→'REVOKED'}（不可逆，V15 触发器强制）。
 * 发布成功后**下一次受理**经 {@link EngineModelAuthority} 读到的就是新版本
 * （C1.2 第 1 行），已受理 run 的绑定不变（C1.2 第 2 行，run 记录的是
 * 不可变 revision_id）。
 *
 * <p><b>密钥纪律（K3）。</b>{@code paramsJson} 只收非密钥参数（温度/上限等），
 * {@code credentialRef} 只收**引用/掩码**；本端口没有任何接受密钥明文的形参，
 * 也绝不把入参原样写日志。
 *
 * <p><b>embedding 维度（WP-040 A2）。</b>维度是版本的发布事实之一：它决定这份配置
 * 产出的向量能否写进冻结迁移固定 1536 维的统一向量列。发布侧在唯一写入口做
 * fail-closed 校验（维度 ≠ {@link #REQUIRED_DIMENSION} 拒绝），实现层的
 * INSERT 约束（CHECK）再兜一层底。
 */
public interface ConfigRevisionPublisher {

    /** 与冻结迁移一致：统一向量列物理维度固定 1536（V15/V7）。 */
    int REQUIRED_DIMENSION = 1536;

    /**
     * 发布一个新的配置版本（INSERT 一条新行；不可变）。
     *
     * @param command 发布命令（provider/model/catalog/params/credentialRef/dimension）
     * @return 发布事实（含租户内单调递增的 {@code revision_no} 与发布时间）
     * @throws ConfigAuthorityUnavailable 没有主体、事实缺失、维度 != 1536、数据库写入失败
     */
    ConfigRevisionFacts publish(ConfigRevisionCommand command);

    /**
     * 撤权（state PUBLISHED → REVOKED；不可逆）。撤权立即生效：
     * 绑定该版本的新受理被 {@code requirePublished} 拒绝，执行期
     * {@code requireBoundRevision} 对该版本一律拒绝（C1.3）。
     *
     * @throws ConfigAuthorityUnavailable 无主体、版本不存在或已撤权、数据库写入失败
     */
    void revoke(String revisionId, String operatorId);

    /**
     * 读取单个版本（管理面列表/详情的读端；与运行期读同一张表）。
     *
     * @throws ConfigAuthorityUnavailable 无主体、版本不存在、数据库读取失败
     */
    ConfigRevisionFacts require(String revisionId);

    /** 发布命令。{@code paramsJson} 只收非密钥参数；{@code credentialRef} 只收引用/掩码。 */
    record ConfigRevisionCommand(
            String providerId,
            String modelId,
            String catalogVersion,
            String paramsHash,
            String paramsJson,
            String credentialRef,
            int dimension) {

        /** 规范化命令（trim）；事实缺失时字段为 {@code null}，由实现统一拒绝。 */
        public ConfigRevisionCommand normalized() {
            return new ConfigRevisionCommand(trim(providerId), trim(modelId), trim(catalogVersion),
                    trim(paramsHash), trim(paramsJson), trim(credentialRef), dimension);
        }

        private static String trim(String value) {
            return value == null ? null : value.trim();
        }
    }

    /** 发布结果摘要（给管理面响应用：只有引用/掩码，没有密钥明文）。 */
    record PublishResult(String tenantId, String revisionId, long revisionNo, Instant publishedAt) {
    }
}
