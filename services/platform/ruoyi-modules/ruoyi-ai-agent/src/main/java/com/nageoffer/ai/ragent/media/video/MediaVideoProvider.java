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

package com.nageoffer.ai.ragent.media.video;

/**
 * F19:2 视频提供方窄端口：异步提交、轮询、结果字节下载。
 *
 * <p>本端口只描述服务端任务权威需要的事实，不把 provider 裸 URL 交给上层视图。
 */
public interface MediaVideoProvider {

    /** providerCode（前端契约，例如 {@code Tongyiwanx}）。 */
    String providerName();

    /**
     * 提交一次视频生成，返回 provider 侧任务 ID。
     *
     * @throws com.nageoffer.ai.ragent.media.image.MediaProviderRejectedException    明确拒绝（未创建付费任务）
     * @throws com.nageoffer.ai.ragent.media.image.MediaProviderUnavailableException 结果未知（超时/5xx/无任务 ID）
     */
    String submit(MediaVideoRequest request);

    /**
     * 查询 provider 侧任务。
     *
     * @throws com.nageoffer.ai.ragent.media.image.MediaProviderRejectedException    任务不存在/明确拒绝
     * @throws com.nageoffer.ai.ragent.media.image.MediaProviderUnavailableException 查询暂时不可用（不改变任务状态）
     */
    MediaVideoPoll poll(String providerTaskId);

    /** 下载 provider 结果字节（仅 https + 白名单 host + 大小上限）。 */
    byte[] download(String providerUrl, long maxBytes);

    /**
     * 结果字节 MIME 嗅探（mp4/webm/ogg）。
     *
     * @throws com.nageoffer.ai.ragent.media.image.MediaProviderRejectedException 无法识别的视频内容
     */
    String sniffMime(byte[] bytes);
}
