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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

/** Durable worst-case reservation. Unknown calls never replenish the authorized envelope. */
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

    public String reserve(String provider,String model,long inputBytes,int maxOutputTokens) {
        if(!enabled || !executionId.matches("[a-zA-Z0-9_-]{1,128}") || cap==null || cap.signum()<=0 || cap.compareTo(new BigDecimal("20"))>0)
            throw denied();
        BigDecimal amount=estimate(provider,inputBytes,maxOutputTokens);
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

    public void received(String callId,String providerId,Map<String,Object> usage) {
        transaction.executeWithoutResult(status -> jdbc.update("UPDATE ai_provider_spend SET state='RESPONSE_RECEIVED',provider_request_id=?,usage_raw=?::jsonb WHERE call_id=? AND state='STARTED'",
                providerId,usage==null?null:CanonicalJson.strictMapper().valueToTree(usage).toString(),callId));
    }

    private static RunApiException denied(){return new RunApiException(RunErrorCode.BUDGET_EXCEEDED);}
}
