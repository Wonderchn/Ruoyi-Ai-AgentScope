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

package com.nageoffer.ai.ragent.agent.trace;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Agent 链路追踪配置（agent.trace 段），上报目标为自托管 LangFuse
 * 两级开关：enabled 决定有没有链路，capture-content 决定链路里带不带业务数据；
 * 追踪总开关默认关闭，但一旦显式开启追踪，内容默认随链路一起采集
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent.trace")
@Validated
public class AgentTraceProperties {

    /**
     * 默认关：开启会装 Reactor 全局钩子并把 span 骨架上报到追踪库
     */
    private boolean enabled = false;

    /**
     * 内容采集默认关闭：没有 yaml 显式开启时只上报结构、状态与用量，不落提示词/模型输出/工具出入参。
     *
     * <p>F18:4 起内嵌闭包沿用这个默认（纯骨架）；独立 AI 应用的 {@code application.yaml}
     * 仍显式写 {@code capture-content: true}，需要可读内容时在部署侧显式开启。
     * 与 enabled 分开是因为两者防的不是一件事：一个决定要不要观测，一个决定观测里落不落业务数据。
     */
    private boolean captureContent = false;

    /**
     * 上报端点（显式开启追踪时的必填项）：OTLP/HTTP traces 路径，
     * 如 {@code http://127.0.0.1:4318/v1/traces}。
     *
     * <p>没有内置默认：缺了就在装配期以指名配置键的错误拒绝启动，
     * 不把空端点交给 SDK 换一个模糊异常。
     */
    private String endpoint;

    /**
     * LangFuse 项目公钥 pk-lf-*，与私钥一起拼 Basic 凭据
     */
    private String publicKey;

    /**
     * LangFuse 项目私钥 sk-lf-*
     */
    private String secretKey;

    /**
     * 单字段截断上限：逐字段独立裁，不做整段截断，否则最新的工具结果会被整块吞掉
     */
    @Min(1024)
    private int maxFieldChars = 32_768;

    /**
     * 单条属性的总预算：字段上限只管一个叶子，几十条消息各自合规拼起来照样能撑到几兆
     * 默认值高于会话上下文预算（agent.context-window-chars），正常一轮不会触发，
     * 配得比 max-field-chars 还小只会让每条属性都走溢出退化，没有意义
     */
    @Min(4096)
    private int maxAttributeChars = 131_072;

    /**
     * 服务版本，写进 resource 的 service.version；留空退回 Maven 构建期写入的 build-info 版本，两者都没有才不写
     * 排查「这条链路是哪一版产出的」只有它答得了，OTel 语义约定里它与 service.name 同属身份三元组
     */
    private String serviceVersion;

    /**
     * 部署环境，写进 resource 的 deployment.environment.name，留空即不写这一项
     */
    private String environment;

    /**
     * 实例标识，写进 resource 的 service.instance.id；留空按容器注入的 HOSTNAME 取，再没有退到启动期随机串
     * 多实例下没它就答不了「这条链路是哪台产出的」，而同名实例又会把两台的链路聚成一族，比缺失更误导
     */
    private String instanceId;
}
