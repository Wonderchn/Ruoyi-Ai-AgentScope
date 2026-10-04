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

package org.ruoyi.aiidentity;

import com.nageoffer.ai.ragent.framework.cache.AuthorizedCacheKey;
import com.nageoffer.ai.ragent.framework.cache.RedisKeySerializer;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.redis.handler.KeyPrefixHandler;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3/C5：Redis 命名空间统一策略（内嵌形态）。
 *
 * <p>决定：内嵌后唯一 Redis 前缀机制 = platform {@code redisson.keyPrefix}
 * （{@link KeyPrefixHandler}，Redisson NameMapper 层）；AI 侧
 * {@link RedisKeySerializer}（{@code framework.cache.redis.prefix}）为独立运行专用，
 * 内嵌不装配。AI 键按形状自带命名空间：{@link AuthorizedCacheKey} 的 {@code p1:}
 * 前缀与其中的 {@code tenantId}/{@code memberId} 段是跨租户缓存串号的防线，
 * 前缀只允许在键外层追加，不得剥离。
 */
@Tag("dev")
class C5RedisNamespaceTest {

    private static final String TENANT = "T1";
    private static final String MEMBER = "platform:T1:2101";

    @Test
    @DisplayName("platform 前缀只在外层追加：AI 授权键的 p1/租户/主体段原样保留，unmap 可还原")
    void platformPrefixPreservesAuthorizedKeyShape() {
        String key = AuthorizedCacheKey.of(TENANT, MEMBER, "document.download", "doc:42", 7, 3, "v9");
        KeyPrefixHandler handler = new KeyPrefixHandler("ruoyi");

        String mapped = handler.map(key);

        assertThat(mapped).isEqualTo("ruoyi:" + key);
        assertThat(mapped).contains(":p1:" + TENANT + ":" + MEMBER + ":");
        // 重复 map 幂等（不得叠加第二层前缀）
        assertThat(handler.map(mapped)).isEqualTo(mapped);
        // unmap 还原为原始键形状（授权段不被改写）
        assertThat(handler.unmap(mapped)).isEqualTo(key);
    }

    @Test
    @DisplayName("空前缀：键原样通过（默认配置行为不变）")
    void emptyPrefixLeavesKeysUntouched() {
        String key = AuthorizedCacheKey.of(TENANT, MEMBER, "kb.read", "kb:1", 7, 3, "v1");
        KeyPrefixHandler handler = new KeyPrefixHandler("");

        assertThat(handler.map(key)).isEqualTo(key);
        assertThat(handler.unmap(key)).isEqualTo(key);
    }

    @Test
    @DisplayName("AI 前缀序列化器是独立运行专用：无 framework.cache.redis.prefix 即不装配")
    void aiPrefixSerializerIsStandaloneOnly() {
        new ApplicationContextRunner()
                .withUserConfiguration(RedisKeySerializer.class)
                .run(context -> assertThat(context).doesNotHaveBean(RedisKeySerializer.class));

        new ApplicationContextRunner()
                .withUserConfiguration(RedisKeySerializer.class)
                .withPropertyValues("framework.cache.redis.prefix=ai:")
                .run(context -> assertThat(context).hasSingleBean(RedisKeySerializer.class));
    }

    @Test
    @DisplayName("授权键自带租户/主体段：不同主体/租户/版本必然不同键（缓存串号防线）")
    void authorizedKeySeparatesTenantAndMember() {
        ExecutionPrincipal principal = new ExecutionPrincipal(TENANT, "2101", MEMBER, 7, 3,
                Set.of("document.download"), "jti", "platform:local", 1L, 2L);
        String base = AuthorizedCacheKey.of(principal, "document.download", "doc:42", "v9");

        String otherMember = AuthorizedCacheKey.of(TENANT, "platform:T1:3001", "document.download",
                "doc:42", 7, 3, "v9");
        String otherTenant = AuthorizedCacheKey.of("T2", "platform:T2:2101", "document.download",
                "doc:42", 7, 3, "v9");
        String newerAcl = AuthorizedCacheKey.of(TENANT, MEMBER, "document.download", "doc:42", 7, 4, "v9");

        assertThat(Set.of(base, otherMember, otherTenant, newerAcl)).hasSize(4);
    }
}
