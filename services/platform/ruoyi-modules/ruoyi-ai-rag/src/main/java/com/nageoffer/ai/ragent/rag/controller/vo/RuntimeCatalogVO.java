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

package com.nageoffer.ai.ragent.rag.controller.vo;

import java.util.List;
import java.util.Map;

/**
 * RW-06 运行配置目录视图（模型 / 提供方 / 档位 / 参数 / 维度 / 限额）。
 *
 * <p><b>唯一权威口径。</b>本文件的每个字段都派生自 {@code platform.ai_runtime_config_revision}
 * （V15 运行发布版本）及其管理面附属行（V24 的 tier / setting），<b>不是</b> YAML
 * {@code ai.*} 目录、也不是旧 {@code /chat/config} 或 {@code /system/model}。YAML 目录若出现，
 * 只出现在 {@code SettingsAuthorityView.yamlCatalog} 且被逐项标注
 * {@code authority="yaml-bootstrap-display-only"} 与 {@code runtimeAuthority=false}——
 * 让"看起来像模型目录"的东西显式声明自己不是权威。
 *
 * <p><b>密钥口径。</b>所有视图只携带 {@code credentialRef}（引用，如 {@code env:deepseek}）
 * 与 {@code credentialKind}（引用种类）；**没有**任何字段承载密钥明文。YAML 提供方也只有
 * {@code apiKeyConfigured} 布尔事实，不回显、不掩码回显（掩码回显仍会泄露长度与前后缀）。
 */
public final class RuntimeCatalogVO {

    private RuntimeCatalogVO() {
    }

    /**
     * 用户模型选择的最小读取契约（替代旧 {@code /system/model/modelList}）。
     *
     * <p>{@code models} 里每项都带 {@code selectable}：提供方未获批准（没有连接引导）时
     * 该模型不可选——读取面不允许把"看起来存在但打不通"的模型展示成可选。
     */
    public record CatalogView(
            String authority,
            boolean runtimeAuthority,
            String authorityNote,
            RevisionView revision,
            List<ModelView> models,
            List<ProviderView> providers,
            List<TierView> tiers,
            LimitsView limits,
            Map<String, String> links,
            List<String> notes) {
    }

    /** 不可变发布版本的事实（不含密钥；{@code params} 只含已校验的生成参数）。 */
    public record RevisionView(
            String revisionId,
            long revisionNo,
            String state,
            String providerId,
            String modelId,
            String catalogVersion,
            String paramsHash,
            Map<String, Object> params,
            boolean paramsTrimmed,
            String credentialRef,
            String credentialKind,
            Integer dimension,
            Long budgetUnits,
            String operatorId,
            String publishedAt) {
    }

    /** 目录中的模型项；{@code id} 与 {@code modelId} 同值，供前端选择控件直接使用。 */
    public record ModelView(
            String id,
            String providerId,
            String modelId,
            String catalogVersion,
            Integer dimension,
            boolean current,
            boolean selectable,
            List<String> tierCodes,
            String lastPublishedAt,
            String paramsHash) {
    }

    /** 提供方可用性；{@code approved} 由连接引导（部署注入）裁决，读不到即不批准。 */
    public record ProviderView(
            String providerId,
            boolean approved,
            boolean available,
            boolean endpointConfigured,
            String credentialRef,
            String reason) {
    }

    /** 随版本发布的档位事实（V24 {@code ai_runtime_config_tier}）。 */
    public record TierView(
            String tierCode,
            List<String> candidateIds,
            int failureThreshold,
            int openDurationSeconds) {
    }

    /** 运行限额：维度是发布门槛（1536），其余取自版本参数。 */
    public record LimitsView(
            Integer embeddingDimension,
            Long budgetUnits,
            Integer maxTokens) {
    }

    /**
     * 设置面权威分布：哪些是可写运行事实、哪些仅展示。
     *
     * <p>{@code revisionAvailable=false} 是合法状态（尚未发布任何版本）。它与
     * {@link CatalogView} 的差别是刻意的：目录读取是**选择**，没有权威就拒绝；
     * 本视图是**说明**，没有权威时也要能告诉调用方"什么可写、什么不可写、权威在哪"。
     */
    public record SettingsAuthorityView(
            String authority,
            boolean yamlIsRuntimeAuthority,
            boolean legacyChatConfigAffectsRuntimeAuthority,
            boolean revisionAvailable,
            RevisionView revision,
            List<SettingFact> writableRuntimeFacts,
            List<SettingFact> displayOnly,
            YamlCatalogView yamlCatalog,
            Map<String, String> links,
            List<String> notes) {
    }

    /**
     * 单个设置键的权威归属。
     *
     * @param authority 该键的事实来源（{@code published-revision} / {@code tier} /
     *                  {@code yaml-bootstrap} / {@code deployment-env}）
     * @param writable  是否属于"可写运行事实"（只能通过发布新版本改变）
     */
    public record SettingFact(
            String key,
            String authority,
            boolean writable,
            Object value,
            String detail) {
    }

    /** YAML 目录的展示形态：显式声明它不是运行权威，且不携带任何密钥值。 */
    public record YamlCatalogView(
            String authority,
            String note,
            List<YamlModel> models,
            List<YamlProvider> providers) {
    }

    public record YamlModel(
            String id,
            String group,
            String provider,
            String model,
            Integer dimension,
            boolean enabled,
            boolean runtimeAuthority) {
    }

    public record YamlProvider(
            String providerId,
            boolean apiKeyConfigured,
            boolean urlConfigured) {
    }

    /** 版本列表（管理面历史；发布版本不可删除，列表是只读事实）。 */
    public record RevisionPageView(
            int count,
            List<RevisionView> revisions) {
    }

    /** 档位附加结果：{@code replayed=true} 表示同内容重复提交（幂等），不是新写入。 */
    public record CatalogAttachmentView(
            String revisionId,
            List<TierView> tiers,
            boolean replayed,
            String note) {
    }
}
