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

package com.nageoffer.ai.ragent.framework.tenant;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 租户上下文守卫：所有 AI 数据访问的"缺主体即拒绝"统一入口。
 *
 * <p>冻结口径（05 §4.1、00 §4.1）：
 * <ul>
 *   <li>AI 新租户实体缺少主体上下文时<b>直接拒绝</b>，不使用 platform 的 fail-open
 *       租户处理器语义，也不复用"缺 tenant 就忽略"的策略；</li>
 *   <li>显式公共模板域与内部 epoch 查询使用<b>有限白名单</b>放行，
 *       白名单只有明确列举的对象，不做前缀/通配匹配；</li>
 *   <li>tenantId 契约与 {@link ExecutionPrincipal} 一致：1..64、不含冒号；
 *       查询参数里的 tenantId 必须与主体一致，不一致即拒绝（防止用参数切租户）。</li>
 * </ul>
 */
@Component
public class TenantContextGuard {

    private static final Logger log = LoggerFactory.getLogger(TenantContextGuard.class);

    /**
     * 允许无主体的白名单：只有"没有客户归属的静态公共模板/内部版本账本"可以进。
     *
     * <p>刻意用精确集合而不是前缀匹配："以 public_ 开头就放行" 这类规则一旦被
     * 命名迁移或注入命中，就会变成一整片免检区。
     */
    private static final List<String> TENANTLESS_ALLOWLIST = List.of(
            "public_template",
            "ai_acl_epoch",
            "ai_execution_permit",
            "flyway_schema_history_ai");

    /** 当前主体，缺失即拒绝。 */
    public ExecutionPrincipal requirePrincipal() {
        return PrincipalContext.require();
    }

    /** 当前 tenantId，缺失即拒绝。 */
    public String requireTenantId() {
        return requirePrincipal().tenantId();
    }

    /**
     * 要求查询参数里的 tenantId 与当前主体一致。
     *
     * <p>任何"按调用方给的 tenantId 去查"的入口都必须先过这里：
     * 否则一个合法的 T1 主体只要传 {@code tenantId=T2} 就能跨租户读取。
     *
     * @throws ClientException tenantId 为空或与主体不一致
     */
    public String requireMatchingTenant(String tenantIdFromRequest) {
        String tenantId = requireTenantId();
        if (tenantIdFromRequest == null || tenantIdFromRequest.isBlank()) {
            throw new ClientException("tenantId is required");
        }
        if (!tenantId.equals(tenantIdFromRequest)) {
            // 不区分"不存在"与"不属于你"，避免泄露租户存在性
            throw new ClientException("tenantId does not match the current principal");
        }
        return tenantId;
    }

    /**
     * 允许无主体访问的表只有白名单；其余一律拒绝。
     *
     * @throws ServiceException 表不在白名单内却试图无主体访问（这是编码错误，不是客户错误）
     */
    public void requireTenantlessAccessAllowed(String table) {
        if (table == null || table.isBlank()) {
            throw new ServiceException("tenantless access requires an explicit table name");
        }
        if (!TENANTLESS_ALLOWLIST.contains(table)) {
            log.error("tenantless access rejected table={}", table);
            throw new ServiceException("tenantless access is not allowed for table " + table);
        }
    }

    /** 是否为显式允许的无主体表（供断言与诊断，不用于放行判断本身）。 */
    public boolean isTenantlessAllowed(String table) {
        return table != null && TENANTLESS_ALLOWLIST.contains(table);
    }

    /**
     * 断言"这条 SQL 的 tenant 参数与当前主体一致"。
     *
     * <p>原生 SQL 必须逐条独立传 tenant（不指望 ORM 插件自动覆盖），
     * 这里提供统一断言点：调用方把将要绑定的 tenant 参数传进来做最后一道校验。
     *
     * @throws ClientException 参数与主体不一致
     */
    public String requireSqlTenantParameter(String tenantParameter) {
        String tenantId = requireTenantId();
        if (!Objects.equals(tenantId, tenantParameter)) {
            throw new ClientException("sql tenant parameter does not match the current principal");
        }
        return tenantId;
    }

    /** 白名单内容快照，供测试断言"白名单没有偷偷变宽"。 */
    public static List<String> tenantlessAllowlistSnapshot() {
        return TENANTLESS_ALLOWLIST;
    }
}
