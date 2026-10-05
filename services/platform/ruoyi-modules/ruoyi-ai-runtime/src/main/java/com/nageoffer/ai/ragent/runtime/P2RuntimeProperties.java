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

package com.nageoffer.ai.ragent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P2 正式运行面配置（默认全部关闭）。
 *
 * <p>冻结值见 {@code mydocs/p2/specs/U00-contract-freeze.md} §4；本类只做绑定，
 * 不在缺省时开启任何执行能力。
 */
@ConfigurationProperties(prefix = "p2")
public class P2RuntimeProperties {

    /** P2 运行面总开关：false 时不注册受理/Worker/SSE/outbox 组件。 */
    private boolean enabled = false;

    private final Worker worker = new Worker();
    private final Events events = new Events();
    private final Outbox outbox = new Outbox();
    private final Upload upload = new Upload();
    private final ObjectStore objectStore = new ObjectStore();
    private final Budget budget = new Budget();
    private final Executor executor = new Executor();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Worker getWorker() {
        return worker;
    }

    public Events getEvents() {
        return events;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public Upload getUpload() {
        return upload;
    }

    public ObjectStore getObjectStore() {
        return objectStore;
    }

    public Budget getBudget() {
        return budget;
    }

    public Executor getExecutor() {
        return executor;
    }

    public static class Worker {
        /** Worker 执行开关；与总开关同时为 true 才执行。 */
        private boolean enabled = false;
        private int heartbeatSeconds = 10;
        private int leaseSeconds = 30;
        private int batch = 4;
        /** 单个 run 最大执行墙钟秒数（0 = 不限制，由 executor 自行截止）。 */
        private int maxWallClockSeconds = 600;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getHeartbeatSeconds() {
            return heartbeatSeconds;
        }

        public void setHeartbeatSeconds(int heartbeatSeconds) {
            this.heartbeatSeconds = heartbeatSeconds;
        }

        public int getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public int getBatch() {
            return batch;
        }

        public void setBatch(int batch) {
            this.batch = batch;
        }

        public int getMaxWallClockSeconds() {
            return maxWallClockSeconds;
        }

        public void setMaxWallClockSeconds(int maxWallClockSeconds) {
            this.maxWallClockSeconds = maxWallClockSeconds;
        }
    }

    public static class Events {
        private int heartbeatSeconds = 15;
        private int retentionHours = 24;
        private int bufferMaxFrames = 512;
        private long bufferMaxBytes = 1024L * 1024L;
        private long pollIntervalMs = 500;
        private int replayBatch = 200;

        public int getHeartbeatSeconds() {
            return heartbeatSeconds;
        }

        public void setHeartbeatSeconds(int heartbeatSeconds) {
            this.heartbeatSeconds = heartbeatSeconds;
        }

        public int getRetentionHours() {
            return retentionHours;
        }

        public void setRetentionHours(int retentionHours) {
            this.retentionHours = retentionHours;
        }

        public int getBufferMaxFrames() {
            return bufferMaxFrames;
        }

        public void setBufferMaxFrames(int bufferMaxFrames) {
            this.bufferMaxFrames = bufferMaxFrames;
        }

        public long getBufferMaxBytes() {
            return bufferMaxBytes;
        }

        public void setBufferMaxBytes(long bufferMaxBytes) {
            this.bufferMaxBytes = bufferMaxBytes;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getReplayBatch() {
            return replayBatch;
        }

        public void setReplayBatch(int replayBatch) {
            this.replayBatch = replayBatch;
        }
    }

    public static class Outbox {
        private boolean relayEnabled = false;
        private int maxAttempts = 8;
        private int backoffSeconds = 5;
        private int batch = 32;
        private int lockSeconds = 30;

        public boolean isRelayEnabled() {
            return relayEnabled;
        }

        public void setRelayEnabled(boolean relayEnabled) {
            this.relayEnabled = relayEnabled;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getBackoffSeconds() {
            return backoffSeconds;
        }

        public void setBackoffSeconds(int backoffSeconds) {
            this.backoffSeconds = backoffSeconds;
        }

        public int getBatch() {
            return batch;
        }

        public void setBatch(int batch) {
            this.batch = batch;
        }

        public int getLockSeconds() {
            return lockSeconds;
        }

        public void setLockSeconds(int lockSeconds) {
            this.lockSeconds = lockSeconds;
        }
    }

    public static class Upload {
        /** 产品 API 首期上限（20MB，与部署一致）；50MB 目标未签收。 */
        private long maxBytes = 50L * 1024L * 1024L;
        private String allowedMime = "application/pdf";

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public String getAllowedMime() {
            return allowedMime;
        }

        public void setAllowedMime(String allowedMime) {
            this.allowedMime = allowedMime;
        }
    }

    public static class ObjectStore {
        /** fs = 本地专属目录（合成验收）；s3/minio 属部署决定，未实现不静默回退。 */
        private String type = "fs";
        private String root = "";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }
    }

    public static class Budget {
        /** 未配置 ai_tenant_budget 行时使用的默认租户上限（合成单位，非真实计费）。 */
        private long defaultTenantUnits = 1000;
        /** 未指定 maxTokens 时每个 run 的默认预占单位。 */
        private long defaultRunUnits = 20;
        /** 每 1000 tokens 折算的合成单位。 */
        private long unitsPerKTok = 1;

        public long getDefaultTenantUnits() {
            return defaultTenantUnits;
        }

        public void setDefaultTenantUnits(long defaultTenantUnits) {
            this.defaultTenantUnits = defaultTenantUnits;
        }

        public long getDefaultRunUnits() {
            return defaultRunUnits;
        }

        public void setDefaultRunUnits(long defaultRunUnits) {
            this.defaultRunUnits = defaultRunUnits;
        }

        public long getUnitsPerKTok() {
            return unitsPerKTok;
        }

        public void setUnitsPerKTok(long unitsPerKTok) {
            this.unitsPerKTok = unitsPerKTok;
        }
    }

    public static class Executor {
        /** real = 真实执行器；synthetic = 显式假执行器（仅专属测试模式，不得当作真实签收）。 */
        private String mode = "real";
        /** 合成执行器每步延迟（毫秒），用于故障窗口注入。 */
        private long syntheticStepDelayMs = 0;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public long getSyntheticStepDelayMs() {
            return syntheticStepDelayMs;
        }

        public void setSyntheticStepDelayMs(long syntheticStepDelayMs) {
            this.syntheticStepDelayMs = syntheticStepDelayMs;
        }
    }
}
