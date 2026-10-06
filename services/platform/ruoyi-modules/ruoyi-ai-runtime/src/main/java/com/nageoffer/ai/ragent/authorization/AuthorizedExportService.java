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

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;

/**
 * 授权会话导出（P1.3c）：私有会话内容的唯一导出入口。
 *
 * <p>与下载同一套纪律：入参是资源引用（{@code conv:<id>}），授权判定在前、
 * 数据读取在后；且因为导出是<b>长流</b>，授权不是只在开头判一次——
 * <b>每个批次重新判定</b>（{@link ResourceAuthorizationService#checkBatch}），
 * 撤权（epoch bump 后 STALE/DENY）会让后续批次立即中止，已写出的字节不收回
 * （与 05 §4.3 的边界一致：撤权成功返回后旧操作没有新的业务提交/字节）。
 *
 * <p>查询恒带 {@code tenant_id + member_id}：导出的是本成员自己的会话，
 * 不是"能读到 conversationId 就能导出"。SQL 不返回任何内部对象 key。
 * 默认不装配（{@code ai.integration.enabled=true}）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AuthorizedExportService {

    /** 导出动作的 canonical 名（与 05 §4.2 动作表一致）。 */
    public static final String ACTION_CONVERSATION_EXPORT = "conversation.export";

    /** 每批次行数：批次边界即授权复核点。 */
    static final int BATCH_SIZE = 100;

    private final ObjectProvider<ResourceAuthorizationService> authorizationService;
    private final JdbcTemplate jdbc;
    private com.nageoffer.ai.ragent.framework.security.RevocationGuard revocations;
    private boolean highRiskEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    public void setHighRiskEnabled(@org.springframework.beans.factory.annotation.Value("${ai.integration.high-risk.enabled:false}") boolean enabled) {
        highRiskEnabled = enabled;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setRevocations(com.nageoffer.ai.ragent.framework.security.RevocationGuard revocations) {
        this.revocations = revocations;
    }

    public AuthorizedExportService(ObjectProvider<ResourceAuthorizationService> authorizationService,
                                   JdbcTemplate jdbc) {
        this.authorizationService = authorizationService;
        this.jdbc = jdbc;
    }

    /**
     * 流式导出一个会话（JSON Lines：每条消息一行）。
     *
     * @throws P04AiException 404=无权/不存在、409=版本过期、503=授权事实源不可用
     */
    public void exportConversation(String conversationId, OutputStream out) {
        var export=exportLeased(conversationId);
        try(var operation=export.operation()) {
            try{out.write(export.bytes());out.flush();}catch(IOException e){throw new UncheckedIOException(e);}
        }
    }

    public record LeasedExport(byte[] bytes,com.nageoffer.ai.ragent.framework.security.RevocationGuard.Operation operation) { }

    /**
     * 把 {@code export} 拒绝的**两种成因**编码成可 grep 的稳定 token 串。
     *
     * <p>理由同 {@code AuthorizedDownloadService.permitRefusalReason()}：客户端文案不变，
     * 成因只进服务端日志（BRIEF §6.1 第 11 条）。仅在该守卫成立时调用。
     */
    private String permitRefusalReason() {
        if (!highRiskEnabled && revocations == null) {
            return "high_risk_disabled,revocation_guard_missing";
        }
        return !highRiskEnabled ? "high_risk_disabled" : "revocation_guard_missing";
    }

    public LeasedExport exportLeased(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST);
        }
        ExecutionPrincipal principal = PrincipalContext.require();

        ResourceAuthorizationService authorization = authorizationService.getIfAvailable();
        if (authorization == null) {
            log.error("导出被拒绝：授权服务不可用, tenantId={}", principal.tenantId());
            throw new ServiceException("授权服务不可用");
        }

        String ref = "conv:" + conversationId;
        requireGranted(authorization.check(principal, ACTION_CONVERSATION_EXPORT, ref));

        // 分批读取 + 每批复核：撤权后的批次立即失败，不静默返回"剩余为空"
        if (!highRiskEnabled || revocations == null) {
            // 与 AiResourceWriteService.write() 同一形态的可诊断性缺口：**两种成因共用一句客户端文案**。
            // 客户端文案一字不改；服务端把成因 token 与两个判定位落日志（§6.1 第 11 条）。
            log.warn("export refused reason={} highRiskEnabled={} revocationGuardBound={} tenant={} conversationId={}",
                    permitRefusalReason(), highRiskEnabled, revocations != null, principal.tenantId(), conversationId);
            throw new ServiceException("导出 permit 服务不可用");
        }
        var operation=revocations.enter(principal,ACTION_CONVERSATION_EXPORT,ref);
        var out=new java.io.ByteArrayOutputStream();
        try {
        long offset = 0;
        while (true) {
            List<MessageRow> batch = fetchBatch(principal, conversationId, offset);
            if (batch.isEmpty()) {
                return new LeasedExport(out.toByteArray(),operation);
            }
            requireGranted(authorization.check(principal, ACTION_CONVERSATION_EXPORT, ref));
            writeBatch(out, batch);
            if(out.size()>4*1024*1024){throw new ServiceException("导出超出单次交付上限");}
            offset += batch.size();
            if (batch.size() < BATCH_SIZE) {
                return new LeasedExport(out.toByteArray(),operation);
            }
        }
        } catch(RuntimeException e){operation.close();throw e;}
    }

    private List<MessageRow> fetchBatch(ExecutionPrincipal principal, String conversationId, long offset) {
        String sql = "SELECT id, role, content, create_time FROM platform.ai_message"
                + " WHERE tenant_id = ? AND member_id = ? AND conversation_id = ? AND deleted = 0"
                + " ORDER BY create_time ASC, id ASC LIMIT ? OFFSET ?";
        return jdbc.query(sql, (rs, rowNum) -> new MessageRow(
                        rs.getString("id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getTimestamp("create_time")),
                principal.tenantId(), principal.membershipId(), conversationId, BATCH_SIZE, offset);
    }

    private void writeBatch(OutputStream out, List<MessageRow> batch) {
        try {
            for (MessageRow row : batch) {
                // 手写 JSON Lines：只含导出契约字段，不带任何内部 key/归属列
                out.write(toJsonLine(row).getBytes(StandardCharsets.UTF_8));
                out.write('\n');
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String toJsonLine(MessageRow row) {
        return "{\"id\":\"" + escape(row.id())
                + "\",\"role\":\"" + escape(row.role())
                + "\",\"content\":\"" + escape(row.content())
                + "\",\"createTime\":\"" + (row.createTime() == null ? "" : escape(row.createTime().toString()))
                + "\"}";
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static void requireGranted(Verdict verdict) {
        switch (verdict) {
            case DENY -> throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            case STALE -> throw new com.nageoffer.ai.ragent.framework.security.StaleVersionException(
                    "policy/acl version changed during export");
            case UNKNOWN -> throw new ServiceException("授权事实源不可用，导出已中止");
            case GRANT -> {
                // 继续
            }
        }
    }

    /** 导出契约行：只有这四个字段会离开本服务。 */
    record MessageRow(String id, String role, String content, Timestamp createTime) {
    }
}
