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

import com.nageoffer.ai.ragent.framework.exception.ClientException;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;

/**
 * 异步身份引用（P1.3c）：跨进程/跨线程传递<b>身份</b>而不是凭证。
 *
 * <p>冻结口径（05 §4.3：异步任务只传身份引用与源版本，绝不传 bearer/完整主体）：
 * <ul>
 *   <li>字段只有租户/成员/用户与 pv/av、签发时刻——<b>没有</b> scopes、没有 jti、
 *       没有任何 bearer 材料；它回答"这是谁"，不回答"这能干什么"；</li>
 *   <li>{@link #from(ExecutionPrincipal)} 是唯一生产构造来源：主体必须先经验签组件
 *       建立起来，才能被降格为引用；业务 body/header 不参与构造；</li>
 *   <li>{@link #toExecutionPrincipal()} 恢复出的对象是<b>身份引用</b>而非授权证据：
 *       scopes 恒为空集（任何 scope 检查 fail-closed）、jti 是合成标记而非原 bearer 的
 *       jti（原凭证不可被重放）、issuer 是本类的保留值而不是签发方。恢复对象只用于
 *       异步路径的租户定位与归属记录；<b>任何内容访问仍必须在线重判授权</b>，
 *       "带着引用"不豁免任何一次判定。</li>
 * </ul>
 *
 * <p>形状校验在紧凑构造器里执行：tenant/user/membership 复用
 * {@link ExecutionPrincipal} 的同一套契约（含"租户不允许冒号"），
 * pv/av ≥ 1、签发时刻必须为正——畸形引用在边界即拒绝，不进消费逻辑。
 */
public record AsyncPrincipalReference(
        String tenantId,
        String membershipId,
        String userId,
        int policyVersion,
        int aclVersion,
        long issuedAtEpochMilli) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 恢复主体的保留 issuer：显式标明"这是异步身份引用，不是签发方凭证"，
     * 任何把它当真凭证去校验 issuer 白名单的路径都会因为命中不了而拒绝。
     */
    public static final String ASYNC_REF_ISSUER = "async-principal-reference";

    /** 恢复主体的合成 jti 前缀：刻意不同于任何 token 的 jti，使"拿引用当 bearer 重放"不成立。 */
    private static final String ASYNC_REF_JTI_PREFIX = "async-ref:";

    public AsyncPrincipalReference {
        ExecutionPrincipal.requireTenantId(tenantId);
        ExecutionPrincipal.requireUserId(userId);
        ExecutionPrincipal.requireMembershipId(membershipId, tenantId, userId);
        if (policyVersion < 1) {
            throw new ClientException("policyVersion must be >= 1");
        }
        if (aclVersion < 1) {
            throw new ClientException("aclVersion must be >= 1");
        }
        if (issuedAtEpochMilli <= 0) {
            throw new ClientException("issuedAtEpochMilli must be positive");
        }
    }

    /**
     * 从已建立的执行主体降格出身份引用（签发时刻取当前时钟）。
     *
     * @throws ClientException 主体缺失——不存在"从无中生出引用"
     */
    public static AsyncPrincipalReference from(ExecutionPrincipal principal) {
        if (principal == null) {
            throw new ClientException("execution principal is required to build an async reference");
        }
        return of(principal, System.currentTimeMillis());
    }

    /**
     * 测试与确定性场景用的显式构造入口：签发时刻由调用方给定，不读系统时钟。
     * 生产路径一律走 {@link #from(ExecutionPrincipal)}。
     */
    public static AsyncPrincipalReference of(ExecutionPrincipal principal, long issuedAtEpochMilli) {
        if (principal == null) {
            throw new ClientException("execution principal is required to build an async reference");
        }
        return new AsyncPrincipalReference(
                principal.tenantId(),
                principal.membershipId(),
                principal.userId(),
                principal.policyVersion(),
                principal.aclVersion(),
                issuedAtEpochMilli);
    }

    /**
     * 恢复为无 bearer 的身份引用：scopes 空集、合成 jti、保留 issuer。
     *
     * <p>再次强调（注释即契约）：返回值<b>不是</b>授权证据——
     * 它让异步路径能回答"这次操作归属哪个租户/成员"，并让所有 scope
     * 检查 fail-closed；内容访问的授权判定必须在线重新完成。
     */
    public ExecutionPrincipal toExecutionPrincipal() {
        long issuedAtEpochSecond = Math.floorDiv(issuedAtEpochMilli, 1000L);
        return new ExecutionPrincipal(
                tenantId,
                userId,
                membershipId,
                policyVersion,
                aclVersion,
                Set.of(),
                ASYNC_REF_JTI_PREFIX + issuedAtEpochMilli,
                ASYNC_REF_ISSUER,
                issuedAtEpochSecond,
                // 无有效期语义：引用不是凭证，不存在"等待过期"的有效窗口；
                // 写成签发时刻本身以显式表达"这不是一张活着的票"。
                issuedAtEpochSecond);
    }
}
