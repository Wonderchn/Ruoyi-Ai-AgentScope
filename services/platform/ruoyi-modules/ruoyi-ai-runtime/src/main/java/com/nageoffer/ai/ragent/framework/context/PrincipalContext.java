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

package com.nageoffer.ai.ragent.framework.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.nageoffer.ai.ragent.framework.exception.ClientException;

/**
 * 执行主体持有器：显式传参是首选，本 holder 只作<b>同步兼容</b>。
 *
 * <p>冻结口径（05 §4.1/§4.3）：
 * <ul>
 *   <li>holder 只用于同步调用链的兼容；异步任务<b>只</b>传身份引用与源版本，
 *       绝不传 bearer/完整主体；</li>
 *   <li>必须 {@code finally} 清理，且清理要能看到"我之前是谁"——
 *       嵌套调用（同步委托另一个主体）不得互相污染；</li>
 *   <li>与旧 {@code UserContext}（AI 自己的 ai_legacy_user 上下文）是两套东西：
 *       旧上下文既没有 tenant 也没有版本，<b>不能</b>用来满足 P1 的主体要求。</li>
 * </ul>
 */
public final class PrincipalContext {

    private static final TransmittableThreadLocal<ExecutionPrincipal> CONTEXT =
            new TransmittableThreadLocal<>();

    private PrincipalContext() {
    }

    /**
     * 设置当前主体；返回被替换掉的旧主体（可能为 {@code null}）。
     *
     * <p>返回旧值是为了让调用方能在 {@code finally} 里恢复而不是"清空"，
     * 否则嵌套场景外层会丢失自己的主体。
     */
    public static ExecutionPrincipal set(ExecutionPrincipal principal) {
        if (principal == null) {
            throw new ClientException("principal must not be null");
        }
        ExecutionPrincipal previous = CONTEXT.get();
        CONTEXT.set(principal);
        return previous;
    }

    /** 恢复先前主体；{@code previous} 为 {@code null} 时表示清空。 */
    public static void restore(ExecutionPrincipal previous) {
        if (previous == null) {
            CONTEXT.remove();
        } else {
            CONTEXT.set(previous);
        }
    }

    /** 当前主体；无主体返回 {@code null}（调用方应自行决定拒绝方式）。 */
    public static ExecutionPrincipal get() {
        return CONTEXT.get();
    }

    /**
     * 当前主体，缺失即拒绝。
     *
     * <p>这是"缺主体必须拒绝"的统一入口：DAO/检索/对象/缓存等底层都要用它，
     * 而不是 {@code get()} 后自己判空（那很容易漏一处）。
     *
     * @throws ClientException 无主体
     */
    public static ExecutionPrincipal require() {
        ExecutionPrincipal principal = CONTEXT.get();
        if (principal == null) {
            throw new ClientException("no execution principal in current context");
        }
        return principal;
    }

    /** 是否存在主体（用于诊断与断言，不用于放行判断）。 */
    public static boolean hasPrincipal() {
        return CONTEXT.get() != null;
    }

    /** 清理当前主体。 */
    public static void clear() {
        CONTEXT.remove();
    }
}
