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

package com.nageoffer.ai.ragent.framework.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Redis Key 序列化（独立运行专用：{@code framework.cache.redis.prefix}）。
 *
 * <p><b>内嵌（E3/C5）不装配本类</b>：内嵌后唯一 Redis 前缀机制是 platform 的
 * {@code redisson.keyPrefix}（{@code KeyPrefixHandler}，Redisson NameMapper 层）；
 * AI 侧键按<b>形状</b>自带命名空间（如 {@code AuthorizedCacheKey} 的 {@code p1:} 前缀、
 * 幂等注解的 {@code keyPrefix()}），不依赖第二个前缀通道——避免双前缀与前缀漂移。
 */
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(name = "framework.cache.redis.prefix")
public class RedisKeySerializer implements RedisSerializer<String> {

    @Value("${framework.cache.redis.prefix:}")
    private String keyPrefix;

    @Override
    public byte[] serialize(String key) throws SerializationException {
        String builderKey = keyPrefix + key;
        return builderKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String deserialize(byte[] bytes) throws SerializationException {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
