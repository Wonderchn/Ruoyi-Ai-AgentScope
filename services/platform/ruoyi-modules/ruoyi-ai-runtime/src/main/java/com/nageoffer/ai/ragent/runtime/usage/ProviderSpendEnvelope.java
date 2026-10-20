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

package com.nageoffer.ai.ragent.runtime.usage;

import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable worst-case reservation. Known usage settles down; unknown calls never replenish the authorized envelope. */
@Service
public class ProviderSpendEnvelope {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final String executionId;
    private final BigDecimal cap;

    public ProviderSpendEnvelope(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${p2.providers.spend.enabled:false}") boolean enabled,
            @Value("${p2.providers.spend.execution-id:}") String executionId,
            @Value("${p2.providers.spend.cap-cny:20}") BigDecimal cap) {
        this.jdbc=jdbc;this.transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.enabled=enabled;this.executionId=executionId;this.cap=cap;
    }

    public static BigDecimal estimate(String provider, long inputBytes, int maxOutputTokens) {
        if(inputBytes<0 || inputBytes>1048576 || maxOutputTokens<0 || maxOutputTokens>8192)
            throw denied();
        long inputRate=switch(provider) {case "deepseek" -> 10;case "dashscope" -> 2;default -> throw denied();};
        long outputRate="deepseek".equals(provider)?20:0;
        return BigDecimal.valueOf(Math.addExact(Math.multiplyExact(inputBytes+1024,inputRate),
                Math.multiplyExact((long)maxOutputTokens,outputRate)))
                .divide(BigDecimal.valueOf(1000000),6,RoundingMode.CEILING).max(new BigDecimal("0.010000"));
    }

    /**
     * 媒体计价（F19:5）：单位 = 张数，金额 = 张数 × 单张<b>最坏成本上限</b>。
     *
     * <p>不把媒体费用塞进 token 账本：本方法与文本 {@link #estimate} 同信封、同 cap，
     * 但单位独立（1..10 张），且 provider 只认 {@code dashscope}（当前唯一获批图片链）。
     * 真实单价以 provider 账单为准；这里预占的是上限。
     */
    public static BigDecimal estimateMedia(String provider, int images, BigDecimal maxCostPerImageCny) {
        return estimateMediaUnits(provider, MEDIA_TYPE_IMAGE, images, maxCostPerImageCny);
    }

    /** 媒体类型标识（与 {@code ai_media_task.media_type} / V46 CHECK 一致）。 */
    public static final String MEDIA_TYPE_IMAGE = "image";
    public static final String MEDIA_TYPE_AUDIO = "audio";
    public static final String MEDIA_TYPE_VIDEO = "video";

    /**
     * F19:0/F19:2 媒体计价扩展（与图片同信封/同 cap，单位独立、不混加）。
     *
     * <ul>
     *   <li>{@code image}：单位 = 张数（1..10），{@code maxUnitCostCny} = 单张最坏成本上限；</li>
     *   <li>{@code audio}：单位 = <b>输入字符数</b>（1..100000），
     *       {@code maxUnitCostCny} = 每千字符最坏成本上限（qwen-tts 按输入字符计费）；</li>
     *   <li>{@code video}：单位 = <b>生成次数</b>（1..4，本切片固定 1），
     *       {@code maxUnitCostCny} = 单次最坏成本上限（时长 × 分辨率单价取上界）。</li>
     * </ul>
     *
     * <p>金额向上取整到 6 位小数并保留 0.01 CNY 最低预占；provider 只认 {@code dashscope}。
     * 真实单价以 provider 账单为准，UNKNOWN 不退还预占。
     */
    public static BigDecimal estimateMediaUnits(String provider, String mediaType, long units,
                                                BigDecimal maxUnitCostCny) {
        if (!"dashscope".equals(provider))
            throw denied();
        if (units < 1)
            throw denied();
        if (maxUnitCostCny == null || maxUnitCostCny.signum() <= 0)
            throw denied();
        BigDecimal cost;
        if (MEDIA_TYPE_IMAGE.equals(mediaType)) {
            if (units > 10 || maxUnitCostCny.compareTo(new BigDecimal("5.000000")) > 0)
                throw denied();
            cost = maxUnitCostCny.multiply(BigDecimal.valueOf(units));
        } else if (MEDIA_TYPE_AUDIO.equals(mediaType)) {
            if (units > 100000 || maxUnitCostCny.compareTo(new BigDecimal("1.000000")) > 0)
                throw denied();
            cost = maxUnitCostCny.multiply(BigDecimal.valueOf(units))
                    .divide(BigDecimal.valueOf(1000), 6, RoundingMode.CEILING);
        } else if (MEDIA_TYPE_VIDEO.equals(mediaType)) {
            if (units > 4 || maxUnitCostCny.compareTo(new BigDecimal("10.000000")) > 0)
                throw denied();
            cost = maxUnitCostCny.multiply(BigDecimal.valueOf(units));
        } else {
            throw denied();
        }
        return cost.setScale(6, RoundingMode.CEILING).max(new BigDecimal("0.010000"));
    }

    public String reserve(String provider,String model,long inputBytes,int maxOutputTokens) {
        if(!enabled || !executionId.matches("[a-zA-Z0-9_-]{1,128}") || cap==null || cap.signum()<=0 || cap.compareTo(new BigDecimal("20"))>0)
            throw denied();
        BigDecimal amount=estimate(provider,inputBytes,maxOutputTokens);
        return reserveAmount(provider,model,amount);
    }

    /**
     * 媒体提交前预占（F19:5）。与文本 {@link #reserve} 同执行事实/同 cap，
     * 支付型调用前调用；预占失败 = 零 provider 调用。
     */
    public String reserveMedia(String provider,String model,int images,BigDecimal maxCostPerImageCny) {
        return reserveMedia(provider, model, MEDIA_TYPE_IMAGE, images, maxCostPerImageCny);
    }

    /** F19:0/F19:2 媒体类型化预占；单位与上限口径见 {@link #estimateMediaUnits}。 */
    public String reserveMedia(String provider,String model,String mediaType,long units,
                               BigDecimal maxUnitCostCny) {
        if(!enabled || !executionId.matches("[a-zA-Z0-9_-]{1,128}") || cap==null || cap.signum()<=0 || cap.compareTo(new BigDecimal("20"))>0)
            throw denied();
        BigDecimal amount=estimateMediaUnits(provider,mediaType,units,maxUnitCostCny);
        return reserveAmount(provider,model,amount);
    }

    private String reserveAmount(String provider,String model,BigDecimal amount) {
        // 棘轮修复：每次预占前先把「已有 usage 的历史预占」按实际成本结掉，否则 worst-case
        // 预占只增不减，¥1 额度约 8 次对话就会被永久占满（实测 reserved 顶到 cap）。
        reconcile(50);
        String callId="spend-"+UUID.randomUUID();
        return transaction.execute(status -> {
            jdbc.update("INSERT INTO ai_provider_envelope(execution_id,cap_cny) VALUES (?,?) ON CONFLICT DO NOTHING",executionId,cap);
            var row=jdbc.queryForMap("SELECT cap_cny,reserved_cny FROM ai_provider_envelope WHERE execution_id=? FOR UPDATE",executionId);
            if(((BigDecimal)row.get("cap_cny")).compareTo(cap)!=0) throw denied();
            if(jdbc.update("UPDATE ai_provider_envelope SET reserved_cny=reserved_cny+? WHERE execution_id=? AND reserved_cny+?<=cap_cny",amount,executionId,amount)!=1) throw denied();
            jdbc.update("INSERT INTO ai_provider_spend(call_id,execution_id,provider,model,reserved_cny,state) VALUES (?,?,?,?,?,'STARTED')",callId,executionId,provider,model,amount);
            return callId;
        });
    }

    // ------------------------------------------------------------------ 对账结算（棘轮修复）

    /** 每 1e6 token 的 CNY 上限费率，与 {@link #estimate} 同源；未知 provider 返回 -1。 */
    private static long inputRate(String provider) {
        return switch (provider) { case "deepseek" -> 10; case "dashscope" -> 2; default -> -1; };
    }

    private static long outputRate(String provider) {
        return "deepseek".equals(provider) ? 20 : 0;
    }

    /**
     * 由 provider 返回的 usage 推导**实际成本**；拿不到 token 数就返回 {@code null}（未知）。
     *
     * <p>红线不变：**未知不退**。只有 provider 明确回报 {@code prompt_tokens} 时才结算，
     * 像 {@code {"httpStatus":401}} 这种没有计费事实的行保持原预占不动。
     */
    static BigDecimal actualCost(String provider, String usageJson) {
        if (usageJson == null || usageJson.isBlank()) return null;
        if (inputRate(provider) < 0) return null;
        JsonNode node;
        try { node = CanonicalJson.strictMapper().readTree(usageJson); }
        catch (Exception e) { return null; }
        if (node == null || !node.isObject()) return null;
        JsonNode prompt = node.get("prompt_tokens");
        if (prompt == null || !prompt.isIntegralNumber()) return null;
        long promptTokens = prompt.asLong();
        JsonNode completion = node.get("completion_tokens");
        long completionTokens = (completion != null && completion.isIntegralNumber()) ? completion.asLong() : 0L;
        if (promptTokens < 0 || completionTokens < 0) return null;
        BigDecimal amount = BigDecimal.valueOf(
                        Math.addExact(Math.multiplyExact(promptTokens, inputRate(provider)),
                                Math.multiplyExact(completionTokens, outputRate(provider))))
                .divide(BigDecimal.valueOf(1000000), 6, RoundingMode.CEILING);
        return amount.max(new BigDecimal("0.000001"));
    }

    /**
     * 对账：把已有 provider usage 的预占下调到实际成本，退回差额（幂等、无 DDL 变更）。
     *
     * <p>为什么用"对账"而不是在每个调用点插 settle：聊天是流式的，usage 在流末尾才拿到，
     * 把 callId 从 {@code EnvelopeChatEgressPort} 一路传回执行器要穿透 5 层；而
     * {@code ai_provider_spend} 已经落了 usage，按行对账能用同一个实现覆盖聊天/嵌入/媒体全路径。
     *
     * <p>幂等与并发：先按 {@code reserved_cny > actual} 认领该行（只有一方能更新成功），
     * 认领成功才减信封 —— 重复对账与并发对账都不会重复退款。退款额恒 ≤ 该 call 的预占，
     * 因此不会把别的 call 的预占退掉。
     *
     * @return 本次退回总额
     */
    public BigDecimal reconcile(int limit) {
        if (!enabled) return BigDecimal.ZERO;
        int bounded = Math.max(1, Math.min(200, limit));
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    "SELECT call_id,provider,reserved_cny,COALESCE(usage_raw::text,'') AS usage_json"
                            + " FROM ai_provider_spend WHERE execution_id=? AND state='RESPONSE_RECEIVED'"
                            + " AND reserved_cny>? ORDER BY created_at LIMIT ?",
                    executionId, new BigDecimal("0.000001"), bounded);
        } catch (RuntimeException e) {
            return BigDecimal.ZERO; // 对账是旁路：失败不得影响预占主流程
        }
        BigDecimal refunded = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            String callId = (String) row.get("call_id");
            String provider = (String) row.get("provider");
            BigDecimal reserved = (BigDecimal) row.get("reserved_cny");
            BigDecimal actual = actualCost(provider, (String) row.get("usage_json"));
            if (actual == null || reserved == null || actual.compareTo(reserved) >= 0) continue;
            BigDecimal delta = reserved.subtract(actual);
            Boolean claimed;
            try {
                claimed = transaction.execute(status -> {
                    int owned = jdbc.update(
                            "UPDATE ai_provider_spend SET reserved_cny=?,"
                                    + " usage_raw=COALESCE(usage_raw,'{}'::jsonb)||?::jsonb"
                                    + " WHERE call_id=? AND reserved_cny>? AND state='RESPONSE_RECEIVED'",
                            actual, "{\"settledCny\":" + actual.toPlainString() + "}", callId, actual);
                    if (owned != 1) return Boolean.FALSE;
                    jdbc.update("UPDATE ai_provider_envelope SET reserved_cny=reserved_cny-?"
                                    + " WHERE execution_id=? AND reserved_cny>=?",
                            delta, executionId, delta);
                    return Boolean.TRUE;
                });
            } catch (RuntimeException e) {
                continue;
            }
            if (Boolean.TRUE.equals(claimed)) refunded = refunded.add(delta);
        }
        return refunded;
    }

    /** 余额快照（给 {@code GET /spend/balance}；只读）。 */
    public Map<String, Object> snapshot() {
        BigDecimal reserved = BigDecimal.ZERO;
        if (enabled) {
            try {
                BigDecimal value = jdbc.queryForObject(
                        "SELECT reserved_cny FROM ai_provider_envelope WHERE execution_id=?",
                        BigDecimal.class, executionId);
                if (value != null) reserved = value;
            } catch (RuntimeException ignore) {
                // 未产生过任何调用时信封行不存在：余额即全额
            }
        }
        BigDecimal capValue = cap == null ? BigDecimal.ZERO : cap;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("executionId", enabled ? executionId : null);
        out.put("capCny", capValue.setScale(6, RoundingMode.CEILING));
        out.put("reservedCny", reserved.setScale(6, RoundingMode.CEILING));
        out.put("remainingCny", capValue.subtract(reserved).max(BigDecimal.ZERO).setScale(6, RoundingMode.CEILING));
        return out;
    }

    public void received(String callId,String providerId,Map<String,Object> usage) {
        transaction.executeWithoutResult(status -> jdbc.update("UPDATE ai_provider_spend SET state='RESPONSE_RECEIVED',provider_request_id=?,usage_raw=?::jsonb WHERE call_id=? AND state='STARTED'",
                providerId,usage==null?null:CanonicalJson.strictMapper().valueToTree(usage).toString(),callId));
    }

    private static RunApiException denied(){return new RunApiException(RunErrorCode.BUDGET_EXCEEDED);}
}
