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

package com.nageoffer.ai.ragent.runtime.usage;

import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 模型外发白名单闸门（P0.3 §8.1：白名单为空 = 禁止真实客户数据外发）。
 *
 * <p>在调用提供方<b>之前</b>判定：名单为空或提供方不在名单 → 拒绝，0 提供方调用；
 * 主模型失败不得改发未允许的供应商（不静默回落）。
 */
@Component
@ConfigurationProperties(prefix = "p2.chat.egress")
public class EgressPolicy {

    /** 是否允许外发（默认 false：不向任何提供方发送数据）。 */
    private boolean enabled = false;

    /** 允许的提供方（逗号分隔，如 deepseek）；空 = 全部拒绝。 */
    private String allowedProviders = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAllowedProviders() {
        return allowedProviders;
    }

    public void setAllowedProviders(String allowedProviders) {
        this.allowedProviders = allowedProviders;
    }

    public Set<String> providers() {
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(allowedProviders.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .forEach(result::add);
        return result;
    }

    /** 外发前检查：不允许 → EGRESS_NOT_ALLOWED（403，不产生任何提供方调用）。 */
    public void requireAllowed(String provider) {
        if (!enabled || providers().isEmpty() || !providers().contains(provider)) {
            throw new RunApiException(RunErrorCode.EGRESS_NOT_ALLOWED);
        }
    }

    public boolean allows(String provider) {
        return enabled && providers().contains(provider);
    }
}
