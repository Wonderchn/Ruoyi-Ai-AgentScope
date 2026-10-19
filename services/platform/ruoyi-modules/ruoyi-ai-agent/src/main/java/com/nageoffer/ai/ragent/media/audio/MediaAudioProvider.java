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

package com.nageoffer.ai.ragent.media.audio;

/**
 * F19:0 语音提供方窄端口：同步提交 + 结果字节下载。
 *
 * <p>本端口只描述服务端任务权威需要的事实，不把 provider 裸 URL 交给上层视图。
 * 结果地址只允许 {@link #download(String, long, String)} 在主机白名单与大小上限内消费。
 */
public interface MediaAudioProvider {

    /** providerCode（前端契约，例如 {@code Tongyiwanx}）。 */
    String providerName();

    /**
     * 提交一次同步语音合成。
     *
     * @throws com.nageoffer.ai.ragent.media.image.MediaProviderRejectedException    明确拒绝（未创建付费任务）
     * @throws com.nageoffer.ai.ragent.media.image.MediaProviderUnavailableException 结果未知（超时/5xx/无结果）
     */
    MediaAudioReceipt submit(MediaAudioRequest request);

    /** 下载 provider 结果字节（仅 https + 白名单 host + 大小上限）。 */
    byte[] download(String providerUrl, long maxBytes, String requestedFormat);

    /**
     * 结果字节 MIME 嗅探（wav/mp3/aac/ogg/flac；pcm 无文件头，按请求格式判定）。
     *
     * @throws com.nageoffer.ai.ragent.media.image.MediaProviderRejectedException 无法识别的音频内容
     */
    String sniffMime(byte[] bytes, String requestedFormat);
}
