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

package com.nageoffer.ai.ragent.authorization;

import com.nageoffer.ai.ragent.framework.context.AsyncPrincipalReference;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Tag;

/**
 * P1.3c 护栏：异步身份引用。
 *
 * <p>异步/消费链路只传"身份引用 + 源版本"，<b>不传 bearer</b>：
 * 引用是普通值对象，泄漏它不等于泄漏可重放的令牌；策略/ACL 版本随引用携带，
 * 消费侧恢复后必须能发现"引用签发后发生的撤权"。这里钉住：
 * <ul>
 *   <li>字段清单里没有 token/bearer/credential；</li>
 *   <li>恢复出的主体 scopes 为空集（引用不携带权限快照——权限必须重新在线求值）；</li>
 *   <li>形状非法（缺租户/坏 membership/版本 <1）一律拒绝。</li>
 * </ul>
 */
@Tag("dev")
class P1AsyncContextIsolationTest {

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal("T1", "2101", "platform:T1:2101", 7, 3,
                Set.of("ai:kb:list"), "jti-t1-9", "platform",
                1_700_000_000L, 1_700_000_060L);
    }

    @Test
    @DisplayName("引用字段清单不含任何凭据载体：只有身份与双版本")
    void referenceCarriesNoBearer() {
        String[] names = Arrays.stream(AsyncPrincipalReference.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
        assertThat(names).containsExactlyInAnyOrder(
                "tenantId", "membershipId", "userId", "policyVersion", "aclVersion", "issuedAtEpochMilli");
        for (String name : names) {
            assertThat(name.toLowerCase()).doesNotContain("token");
            assertThat(name.toLowerCase()).doesNotContain("bearer");
            assertThat(name.toLowerCase()).doesNotContain("credential");
            assertThat(name.toLowerCase()).doesNotContain("secret");
        }
    }

    @Test
    @DisplayName("from/toExecutionPrincipal 往返：身份与双版本保留，scopes 为空集（权限必须重新求值）")
    void roundTripKeepsIdentityAndVersionsButNoScopes() {
        ExecutionPrincipal source = principal();
        AsyncPrincipalReference ref = AsyncPrincipalReference.from(source);

        assertThat(ref.tenantId()).isEqualTo("T1");
        assertThat(ref.membershipId()).isEqualTo("platform:T1:2101");
        assertThat(ref.policyVersion()).isEqualTo(7);
        assertThat(ref.aclVersion()).isEqualTo(3);

        ExecutionPrincipal restored = ref.toExecutionPrincipal();
        assertThat(restored.tenantId()).isEqualTo("T1");
        assertThat(restored.membershipId()).isEqualTo("platform:T1:2101");
        assertThat(restored.policyVersion()).isEqualTo(7);
        assertThat(restored.aclVersion()).isEqualTo(3);
        assertThat(restored.scopes()).as("引用不携带权限快照").isEmpty();
        // 合成 jti 与保留 issuer：明确"这不是一张活着的票"，拿引用当 bearer 重放不成立
        assertThat(restored.jti()).startsWith("async-ref:");
        assertThat(restored.issuer()).isEqualTo(AsyncPrincipalReference.ASYNC_REF_ISSUER);
    }

    @Test
    @DisplayName("形状非法一律拒绝：缺租户/坏 membership/版本<1")
    void malformedReferencesAreRejected() {
        // membershipId 必须是 canonical platform:<tenant>:<user> 形态（由 ExecutionPrincipal 构造时校验）
        assertThatThrownBy(() -> new AsyncPrincipalReference("T1", "not-canonical", "2101", 7, 3, 1L)
                .toExecutionPrincipal())
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> new AsyncPrincipalReference("", "platform:T1:2101", "2101", 7, 3, 1L)
                .toExecutionPrincipal())
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> new AsyncPrincipalReference("T1", "platform:T1:2101", "2101", 0, 3, 1L)
                .toExecutionPrincipal())
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> AsyncPrincipalReference.from(null))
                .isInstanceOf(ClientException.class);
    }
}
