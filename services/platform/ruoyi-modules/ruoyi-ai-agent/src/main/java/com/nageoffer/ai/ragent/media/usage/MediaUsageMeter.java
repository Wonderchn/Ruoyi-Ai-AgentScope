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

package com.nageoffer.ai.ragent.media.usage;

import com.nageoffer.ai.ragent.media.config.MediaProperties;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.Map;

/**
 * F19:5 媒体计价/预占：复用 {@link ProviderSpendEnvelope} 的口径，不另起放宽信封。
 *
 * <p>计费单位按媒体类型独立：图片=张数（本切片固定 1）、语音=输入字符数、
 * 视频=生成次数。提交前按"单位数 × 该类最坏成本上限"预占；预占失败 = 零 provider 调用。
 * UNKNOWN 结果不退还预占（信封保持 STARTED）；成功只把状态推进到
 * RESPONSE_RECEIVED，真实单价以 provider 账单为准。
 *
 * <p>媒体用量不写 {@code ai_model_call}（该表有 run_id 外键且行契约面向文本调用）；
 * 用量事实落在 {@code ai_media_task.usage_raw/units/reserved_cny/spend_call_id}，
 * 单位与 token 账本不混加。
 */
public class MediaUsageMeter {

    private final ObjectProvider<ProviderSpendEnvelope> envelope;
    private final MediaProperties properties;

    public MediaUsageMeter(ObjectProvider<ProviderSpendEnvelope> envelope, MediaProperties properties) {
        this.envelope = envelope;
        this.properties = properties;
    }

    /** 一次已预占的付费调用（{@code units} 按媒体类型：张/字符/次）。 */
    public record Reserved(String callId, BigDecimal reservedCny, long units) {
    }

    /**
     * 提交前预占预算（图片兼容入口）。
     *
     * @throws RunApiException BUDGET_EXCEEDED（信封缺失/关闭/超额）；调用方必须零 provider 调用
     */
    public Reserved reserve(String model, int images) {
        return reserve(model, ProviderSpendEnvelope.MEDIA_TYPE_IMAGE, images);
    }

    /**
     * 按媒体类型预占：image=张数、audio=输入字符数、video=生成次数。
     *
     * <p>上限来源：图片 {@code max-cost-per-image-cny}、音频
     * {@code max-cost-per-1k-chars-cny}（每千字符）、视频 {@code max-cost-per-video-cny}；
     * 三者都是<b>最坏成本上限</b>，真实单价以 provider 账单为准。
     *
     * @throws RunApiException BUDGET_EXCEEDED（信封缺失/关闭/超额）；调用方必须零 provider 调用
     */
    public Reserved reserve(String model, String mediaType, long units) {
        ProviderSpendEnvelope spend = envelope.getIfAvailable();
        if (spend == null) {
            // 没有信封 = 没有可核对的预算权威：拒绝付费调用，而不是默认放行。
            throw new RunApiException(RunErrorCode.BUDGET_EXCEEDED, "媒体预算信封未装配，拒绝发起付费调用");
        }
        String billingProvider;
        BigDecimal maxUnit;
        if (ProviderSpendEnvelope.MEDIA_TYPE_IMAGE.equals(mediaType)) {
            billingProvider = properties.getImage().getBillingProvider();
            maxUnit = properties.getImage().getMaxCostPerImageCny();
        } else if (ProviderSpendEnvelope.MEDIA_TYPE_AUDIO.equals(mediaType)) {
            billingProvider = properties.getAudio().getBillingProvider();
            maxUnit = properties.getAudio().getMaxCostPer1kCharsCny();
        } else if (ProviderSpendEnvelope.MEDIA_TYPE_VIDEO.equals(mediaType)) {
            billingProvider = properties.getVideo().getBillingProvider();
            maxUnit = properties.getVideo().getMaxCostPerVideoCny();
        } else {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "未知媒体类型");
        }
        String callId = spend.reserveMedia(billingProvider, model, mediaType, units, maxUnit);
        BigDecimal amount = ProviderSpendEnvelope.estimateMediaUnits(
                billingProvider, mediaType, units, maxUnit);
        return new Reserved(callId, amount, units);
    }

    /**
     * provider 已返回结果：把信封状态推进到 RESPONSE_RECEIVED。
     *
     * <p>重复轮询是幂等的（信封 UPDATE 只在 STARTED 上生效）；用量未知时调用方不得
     * 调用本方法，预占保持 STARTED。
     */
    public void received(Reserved reserved, String providerRequestId, Map<String, Object> usage) {
        if (reserved == null || reserved.callId() == null) {
            return;
        }
        ProviderSpendEnvelope spend = envelope.getIfAvailable();
        if (spend == null) {
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE, "媒体预算信封不可用");
        }
        spend.received(reserved.callId(), providerRequestId, usage);
    }
}
