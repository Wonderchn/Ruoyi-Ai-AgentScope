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

package com.nageoffer.ai.ragent.rag.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * RW-06 运行配置目录的读/写只读口径（管理面）。
 *
 * <p><b>它不是第二套权威，而是权威的目录投影。</b>每个方法都读写
 * {@code platform.ai_runtime_config_revision}（V15 唯一运行权威）与其管理面附属行
 * （V24 {@code ai_runtime_config_tier} / {@code ai_runtime_config_setting}）。
 * 「发布」不在这里实现：它由既有 {@code ConfigRevisionPublisher} 承担（发布事务 + 审计同事务）。
 * 本端口只做两件事：
 * <ol>
 *   <li>把权威投影成目录读取（模型 / 提供方 / 档位 / 维度 / 限额）；</li>
 *   <li>把**已发布版本**的档位事实写一次（write-once）：档位随版本固定，
 *       改档位 = 发布新版本，而不是改历史行。</li>
 * </ol>
 *
 * <p><b>租户是参数而不是上下文。</b>所有方法显式接收 {@code tenantId}，由调用方从
 * {@code ExecutionPrincipal} 取；本层不再读全局上下文，避免"某条路径忘了限定租户"
 * 变成跨租户读。实现必须把 {@code tenant_id} 放进每条 SQL 的 WHERE 子句。
 */
public interface RuntimeCatalogService {

    /** 唯一运行权威表名（供调用方做锚点断言与响应标注）。 */
    String AUTHORITY = "platform.ai_runtime_config_revision";

    /**
     * 当前运行权威版本：该租户 {@code revision_no} 最大的 {@code PUBLISHED} 行。
     *
     * @return 无已发布版本时返回 {@code null}（由调用方按 fail-closed 处理，绝不回退 YAML）
     */
    RevisionRow currentRevision(String tenantId);

    /** 版本序列（含 REVOKED），按 {@code revision_no} 倒序。 */
    List<RevisionRow> revisions(String tenantId, int limit);

    /** 某个版本的档位行。 */
    List<TierRow> tiers(String tenantId, String revisionId);

    /** 某个版本的设置键值行（V24 {@code ai_runtime_config_setting}）。 */
    Map<String, Object> settings(String tenantId, String revisionId);

    /**
     * 该租户发布历史里出现过的模型目录（去重后按最近发布排序）。
     * 目录来自权威表本身，因此"改一处另一处同步变"是结构性的。
     */
    List<ModelRow> knownModels(String tenantId, int limit);

    /**
     * 把档位事实附加到**已存在且已发布**的版本上（write-once）。
     *
     * <p>同版本同档位的重复提交：内容相同 ⇒ 幂等返回 {@code replayed=true}；
     * 内容不同 ⇒ 抛 {@link RuntimeCatalogConflictException}（409）。这是"不可变版本"的
     * 直接推论：档位属于版本事实，改档位必须发布新版本。
     *
     * @param operatorId 已认证的操作者（写入审计归属）
     */
    AttachResult attachTiers(String tenantId, String revisionId, List<TierSpec> tiers, String operatorId);

    /** 发布版本行（含 {@code params_json} 原样 JSON 文本，供读取侧展开）。 */
    record RevisionRow(
            String tenantId,
            String revisionId,
            long revisionNo,
            String state,
            String providerId,
            String modelId,
            String catalogVersion,
            String paramsHash,
            String paramsJson,
            String credentialRef,
            String operatorId,
            Instant publishedAt,
            Integer dimension,
            Long budgetUnits) {
    }

    record TierRow(
            String revisionId,
            String tierCode,
            List<String> candidateIds,
            int failureThreshold,
            int openDurationSeconds) {
    }

    record ModelRow(
            String providerId,
            String modelId,
            String catalogVersion,
            Integer dimension,
            Instant publishedAt,
            String paramsHash) {
    }

    record TierSpec(
            String tierCode,
            List<String> candidateIds,
            int failureThreshold,
            int openDurationSeconds) {
    }

    record AttachResult(String revisionId, List<TierRow> tiers, boolean replayed) {
    }
}
