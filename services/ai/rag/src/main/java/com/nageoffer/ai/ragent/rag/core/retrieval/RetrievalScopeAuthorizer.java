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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.AuthorizedResourceScope;
import com.nageoffer.ai.ragent.framework.security.AuthorizedRetrievalScopeResolver;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 检索作用域授权器（P1.3b 的装配点）。
 *
 * <p>它的唯一职责是把"当前主体 + 动作 + 请求候选"变成
 * {@link AuthorizedRetrievalScope}。检索链上的任何组件都不再自己判断能看哪些库。
 *
 * <p>注意它<b>不</b>提供"无主体时返回全库"的分支：没有主体就是
 * {@link AuthorizedRetrievalScope#denied()}，调用方按空作用域处理。
 */
@Component
public class RetrievalScopeAuthorizer {

    /** 检索动作标识（与 05 §4.2 的动作表一致）。 */
    public static final String ACTION_KB_RETRIEVE = "kb.retrieve";

    private final ResourceAuthorizationService authorizationService;
    private final AuthorizedRetrievalScopeResolver<AuthorizedRetrievalScope> resolver;

    /**
     * @param authorizationService 本地资源授权服务（可为 {@code null}：尚未接线时一律拒绝）
     * @param resolver             把资源作用域投影为检索作用域的解析器（可为 {@code null}）
     */
    public RetrievalScopeAuthorizer(ResourceAuthorizationService authorizationService,
                                    AuthorizedRetrievalScopeResolver<AuthorizedRetrievalScope> resolver) {
        this.authorizationService = authorizationService;
        this.resolver = resolver;
    }

    /** 解析当前线程主体的检索作用域。 */
    public AuthorizedRetrievalScope authorize(Collection<String> requestedKbRefs) {
        return authorize(PrincipalContext.get(), requestedKbRefs);
    }

    /**
     * 解析指定主体的检索作用域。
     *
     * @param principal        执行主体；{@code null} → 拒绝（空作用域）
     * @param requestedKbRefs  请求侧候选 KB 引用（可为空）
     */
    public AuthorizedRetrievalScope authorize(ExecutionPrincipal principal, Collection<String> requestedKbRefs) {
        if (principal == null || authorizationService == null || resolver == null) {
            // 三重缺失（无主体 / 未接线 / 无投影实现）都只能得到"没有授权"，
            // 绝不能得到"没有限制"。
            return AuthorizedRetrievalScope.denied();
        }
        AuthorizedResourceScope resourceScope = authorizationService
                .resolveScope(principal, ACTION_KB_RETRIEVE, requestedKbRefs);
        return resolver.toRetrievalScope(resourceScope);
    }

    /**
     * 从一组 KB 事实推导 collection 名（registry 里"逻辑库 → 物理 space"的映射）。
     *
     * <p>抽成静态方法便于单测直接验证映射规则，不必构造完整授权链。
     */
    public static Set<String> collectionsOf(Collection<String> kbRefs, Function<String, String> kbToCollection) {
        Set<String> result = new LinkedHashSet<>();
        if (kbRefs == null || kbToCollection == null) {
            return result;
        }
        for (String kbRef : kbRefs) {
            if (kbRef == null || kbRef.isBlank()) {
                continue;
            }
            String collection = kbToCollection.apply(kbRef);
            if (collection != null && !collection.isBlank()) {
                result.add(collection);
            }
        }
        return result;
    }

    /** 供装配断言使用：授权链是否已具备最小依赖（不代表已授权）。 */
    public boolean isWired() {
        return authorizationService != null && resolver != null;
    }

    /** 供测试与诊断：当前是否已有主体。 */
    public static boolean hasPrincipal() {
        return PrincipalContext.hasPrincipal();
    }

    /** 只读动作清单，供断言"检索只用一个动作"。 */
    public static List<String> declaredActions() {
        return List.of(ACTION_KB_RETRIEVE);
    }
}
