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

package com.nageoffer.ai.ragent.rag.runtime.p04;

import com.nageoffer.ai.ragent.rag.runtime.AclProvider;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

/**
 * <b>测试专用</b>的合成资源 ACL（Spec §7.3 / §8.1 的 C4）。
 *
 * <p>为什么是替身：AI 基线没有 tenant/ACL 列（{@code t_knowledge_base} 只有
 * {@code created_by}/{@code deleted}）。P0.4 因此只验证"授权集合参与判定且可被撤销"，
 * 不声称持久化 ACL 已实现——那是 P1。
 *
 * <p>合成事实：KB-A 仅授权 T1/M1；KB-B 仅授权 T2/M1T2；U0（T1/M0）有提交功能权限但
 * <b>没有任何</b> KB 授权（对应"空的有效授权集合必须拒绝"）。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "p04.enabled", havingValue = "true")
public class SyntheticAclProvider implements AclProvider {

    /** T1 的知识库 A。 */
    public static final String KB_A = "KB-A";

    /** T2 的知识库 B。 */
    public static final String KB_B = "KB-B";

    private final Set<String> grants = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> versions = new ConcurrentHashMap<>();

    public SyntheticAclProvider() {
        grant("T1", "M1", "rag.chat", KB_A);
        grant("T2", "M1T2", "rag.chat", KB_B);
        versions.put("T1", new AtomicInteger(1));
        versions.put("T2", new AtomicInteger(1));
    }

    @Override
    public int aclVersion(String tenantId) {
        AtomicInteger version = versions.get(tenantId);
        return version == null ? 0 : version.get();
    }

    @Override
    public boolean canAccess(String tenantId, String membershipId, String action, String resourceRef) {
        if (tenantId == null || membershipId == null || resourceRef == null) {
            return false;
        }
        return grants.contains(key(tenantId, membershipId, action, resourceRef));
    }

    /** 测试控制面：授权/撤权（N07「仅撤 AI ACL」场景）。 */
    public synchronized void setGranted(String tenantId, String membershipId, String action, String resourceRef,
                           boolean granted) {
        AtomicInteger version = versions.get(tenantId);
        if (version == null) {
            throw new IllegalArgumentException("unknown synthetic tenant");
        }
        boolean changed;
        if (granted) {
            changed = grants.add(key(tenantId, membershipId, action, resourceRef));
        } else {
            changed = grants.remove(key(tenantId, membershipId, action, resourceRef));
        }
        if (changed) {
            version.incrementAndGet();
        }
    }

    private void grant(String tenantId, String membershipId, String action, String resourceRef) {
        grants.add(key(tenantId, membershipId, action, resourceRef));
    }

    private static String key(String tenantId, String membershipId, String action, String resourceRef) {
        return tenantId + "|" + membershipId + "|" + action + "|" + resourceRef;
    }
}
