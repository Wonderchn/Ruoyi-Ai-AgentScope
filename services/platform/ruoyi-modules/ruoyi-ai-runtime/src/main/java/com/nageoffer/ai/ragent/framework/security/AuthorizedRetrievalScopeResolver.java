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

package com.nageoffer.ai.ragent.framework.security;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;

import java.util.Collection;

/**
 * 检索作用域解析端口（P1.3b）。
 *
 * <p>检索层需要"本次请求到底能看哪些库/文档"，但它的调用方（通道、direct Bean、
 * async 消费）可能没有主体。因此解析必须先经过主体判定：<b>没有主体就没有作用域</b>，
 * 而不是"没有主体就查全部"。
 *
 * <p>类型参数 {@code T} 是该层自己的"检索作用域"类型：framework 不能反向依赖
 * rag 的检索类型，所以这里只固定契约形状，具体类型由 rag 侧给出。
 *
 * @param <T> 检索作用域类型
 */
public interface AuthorizedRetrievalScopeResolver<T> {

    /**
     * 解析本次检索的授权作用域。
     *
     * @param principal 当前执行主体；{@code null} 表示调用链没有主体
     * @param action    检索动作（如 {@code kb.retrieve}）
     * @param requested 请求侧候选资源引用（可为空 = 不额外收窄）
     * @return 授权作用域
     * @throws com.nageoffer.ai.ragent.framework.exception.ClientException 无主体
     * @throws StaleVersionException pv/av 与本地事实不一致
     * @throws com.nageoffer.ai.ragent.framework.exception.ServiceException 授权源不可用（不放行）
     */
    AuthorizedResourceScope resolve(ExecutionPrincipal principal, String action, Collection<String> requested);

    /**
     * 把通用资源作用域投影为检索作用域（按 KB / DOC / 已发布 chunk 分层 + collection 映射）。
     *
     * <p>投影所需的分层事实必须来自同一版本快照；不允许在这一步再去查一次 registry
     * 而造成"两次读不同版本"。
     */
    T toRetrievalScope(AuthorizedResourceScope resourceScope);
}
