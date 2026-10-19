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

package com.nageoffer.ai.ragent.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * F19 媒体面配置（{@code ai.embedded.media.*}）。
 *
 * <p><b>凭据纪律。</b>{@code apiKey} 只来自环境变量引用（shipped yml 用
 * {@code ${DASHSCOPE_API_KEY:}}），本类不读取任何 provider_config/credentials 文件，
 * 不打印、不落盘、不把 key 放进任何响应或日志。空白 key = 支付型调用 fail-closed。
 *
 * <p><b>费用纪律。</b>{@code maxCostPerImageCny} 是单张图的<b>最坏成本上限</b>（不是
 * 声称的官方单价）：每次提交前按 {@code 张数 × 上限} 预占预算信封；
 * UNKNOWN 结果不退还预占。真实单价以 provider 账单为准，窗口只核对上限内。
 */
@ConfigurationProperties(prefix = "ai.embedded.media")
public class MediaProperties {

    /** 媒体面总开关；关闭时整组 bean 不装配。 */
    private boolean enabled = true;

    private Image image = new Image();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Image getImage() {
        return image;
    }

    public void setImage(Image image) {
        this.image = image;
    }

    /** 图片生成配置。 */
    public static class Image {

        /** 图片子开关。 */
        private boolean enabled = true;

        /** 对前端暴露的 providerCode（与 workbench 的 supportsMedia 对齐）。 */
        private String provider = "Tongyiwanx";

        /** 计费/预算信封中的 provider 标识（真实账单主体）。 */
        private String billingProvider = "dashscope";

        /** 唯一允许的图片模型名（模型目录延期，不伪造目录）。 */
        private String modelName = "wanx2.1-t2i-turbo";

        /** DashScope API 根地址（图片同步/异步接口都在其 /api/v1 下）。 */
        private String apiHost = "https://dashscope.aliyuncs.com";

        /** API key；只允许环境变量注入，默认空 = fail-closed。 */
        private String apiKey = "";

        /** 请求未给尺寸时使用的默认尺寸。 */
        private String defaultSize = "1024*1024";

        /** 提示词长度上限。 */
        private int maxPromptLength = 2000;

        /** 单次请求允许的图片张数（本切片固定 1，字段保留给计价扩展）。 */
        private int maxImages = 1;

        /** 结果对象最大字节数（网关字节分支上限 4MiB 以内）。 */
        private long maxBytes = 4L * 1024 * 1024;

        /** 单张图预占预算上限（CNY）。 */
        private BigDecimal maxCostPerImageCny = new BigDecimal("0.50");

        /** 允许下载结果的 host 后缀（逗号分隔，匹配 host 或 *.suffix）。 */
        private String allowedResultHosts = "aliyuncs.com";

        /** 建连超时。 */
        private int connectTimeoutMs = 5000;

        /** 单次请求超时。 */
        private int requestTimeoutMs = 30000;

        /** 同步 generateImage 的最长轮询时间。 */
        private int pollTimeoutMs = 60000;

        /** 同步 generateImage 的轮询间隔。 */
        private int pollIntervalMs = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBillingProvider() {
            return billingProvider;
        }

        public void setBillingProvider(String billingProvider) {
            this.billingProvider = billingProvider;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public String getApiHost() {
            return apiHost;
        }

        public void setApiHost(String apiHost) {
            this.apiHost = apiHost;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getDefaultSize() {
            return defaultSize;
        }

        public void setDefaultSize(String defaultSize) {
            this.defaultSize = defaultSize;
        }

        public int getMaxPromptLength() {
            return maxPromptLength;
        }

        public void setMaxPromptLength(int maxPromptLength) {
            this.maxPromptLength = maxPromptLength;
        }

        public int getMaxImages() {
            return maxImages;
        }

        public void setMaxImages(int maxImages) {
            this.maxImages = maxImages;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public BigDecimal getMaxCostPerImageCny() {
            return maxCostPerImageCny;
        }

        public void setMaxCostPerImageCny(BigDecimal maxCostPerImageCny) {
            this.maxCostPerImageCny = maxCostPerImageCny;
        }

        public String getAllowedResultHosts() {
            return allowedResultHosts;
        }

        public void setAllowedResultHosts(String allowedResultHosts) {
            this.allowedResultHosts = allowedResultHosts;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getRequestTimeoutMs() {
            return requestTimeoutMs;
        }

        public void setRequestTimeoutMs(int requestTimeoutMs) {
            this.requestTimeoutMs = requestTimeoutMs;
        }

        public int getPollTimeoutMs() {
            return pollTimeoutMs;
        }

        public void setPollTimeoutMs(int pollTimeoutMs) {
            this.pollTimeoutMs = pollTimeoutMs;
        }

        public int getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(int pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        /** 解析允许的结果 host 后缀列表。 */
        public List<String> allowedResultHosts() {
            List<String> hosts = new ArrayList<>();
            if (allowedResultHosts == null) {
                return hosts;
            }
            for (String raw : allowedResultHosts.split(",")) {
                String host = raw.trim().toLowerCase(java.util.Locale.ROOT);
                if (!host.isEmpty()) {
                    hosts.add(host);
                }
            }
            return hosts;
        }
    }
}
