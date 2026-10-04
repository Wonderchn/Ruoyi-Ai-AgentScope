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

package com.nageoffer.ai.ragent.framework.integration;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 旧自动能力（MQ listener / 事务回查 / 定时扫描 / 启动期远端 I/O）的统一关闭判定。
 *
 * <p>被判定的能力在 P1.2a 全部保持关闭：真值只有一个来源——
 * {@link SaasBoundaryProperties#legacyListenersEnabled()}，而它在产品配置里
 * 出现 {@code true} 会让启动直接失败。因此运行期这里恒为 closed，任何
 * {@code open(...)} 查询都会抛 {@link ClosedCapabilityException}，
 * <b>不会</b>返回一个可继续执行的分支。
 */
public final class SaasCapabilityBoundary {

    /** 旧能力标识；只用于拒绝审计，不含客户数据。 */
    public enum LegacyCapability {
        /** 知识库文档切分 MQ 消费者。 */
        KB_DOCUMENT_CHUNK_CONSUMER,
        /** 知识库清理 MQ 消费者。 */
        KB_CLEANUP_CONSUMER,
        /** 消息反馈 MQ 消费者。 */
        MESSAGE_FEEDBACK_CONSUMER,
        /** 文档切分事务回查。 */
        KB_DOCUMENT_CHUNK_CHECKER,
        /** 知识库清理事务回查。 */
        KB_CLEANUP_CHECKER,
        /** 文档计划扫描（stuck 恢复）。 */
        DOCUMENT_SCHEDULE_RECOVER,
        /** 文档计划扫描（全量 scan）。 */
        DOCUMENT_SCHEDULE_SCAN,
        /** 对象存储桶启动初始化（含公共读设置）。 */
        STORAGE_INITIALIZER,
        /** 向量空间启动初始化。 */
        VECTOR_SPACE_INITIALIZER,
        /** 关键词共享索引启动初始化。 */
        ES_SHARED_INDEX_INITIALIZER,
        /** Agent MCP 客户端启动连接。 */
        AGENT_MCP_STARTUP
    }

    private final SaasBoundaryProperties properties;

    public SaasCapabilityBoundary(SaasBoundaryProperties properties) {
        this.properties = properties;
    }

    /** 与"旧能力总开关"同义；恒为 false，保留命名便于审计。 */
    public boolean legacyEnabled() {
        return properties != null && properties.isLegacyListenersEnabled();
    }

    /** 测试/装配断言用：不抛异常的纯查询。 */
    public boolean isLegacyOpen() {
        return legacyEnabled();
    }

    /**
     * 判定某个旧能力是否开放；关闭时抛受控异常。
     *
     * <p>调用点必须把它放在<b>日志正文、UserContext 设置、SQL/Redis/对象/模型调用之前</b>，
     * 这样"关闭"才是零副作用，而不是"跑了再回滚"。
     *
     * @throws ClosedCapabilityException 能力未开放（当前恒成立）
     */
    public void requireOpen(LegacyCapability capability) {
        if (!legacyEnabled()) {
            throw new ClosedCapabilityException(capability);
        }
    }

    /**
     * 受控关闭异常。
     *
     * <p>刻意<b>不</b>继承任何被上层 catch-and-continue 处理的业务异常：
     * 旧消费者/回查/调度的 catch 分支不得把它转成"继续执行"或"返回成功"。
     */
    public static final class ClosedCapabilityException extends RuntimeException {

        private final LegacyCapability capability;

        public ClosedCapabilityException(LegacyCapability capability) {
            super("legacy capability is closed: " + capability.name());
            this.capability = capability;
        }

        public LegacyCapability capability() {
            return capability;
        }
    }

    /**
     * 可选注入版本的关闭判定：边界组件缺席时<b>按关闭处理</b>，绝不默认放行。
     *
     * <p>抽成静态方法，让只装配单个组件的轻量测试上下文也能断言"抛在 IO 之前"，
     * 而不必为了构造一个边界 Bean 拉起整个产品上下文。
     *
     * @param provider 可选注入的边界；{@code null}（无注入）与"取不到"同样拒绝
     * @throws ClosedCapabilityException 能力未开放
     */
    public static void requireOpenOrClosed(ObjectProvider<SaasCapabilityBoundary> provider,
                                          LegacyCapability capability) {
        SaasCapabilityBoundary resolved = provider == null ? null : provider.getIfAvailable();
        if (resolved == null) {
            throw new ClosedCapabilityException(capability);
        }
        resolved.requireOpen(capability);
    }
}
