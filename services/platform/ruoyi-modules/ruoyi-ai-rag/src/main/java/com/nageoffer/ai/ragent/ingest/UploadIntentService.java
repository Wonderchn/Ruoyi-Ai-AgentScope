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

package com.nageoffer.ai.ragent.ingest;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Service
public class UploadIntentService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public UploadIntentService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
    }
    public record Intent(String docId, String uploadId, String versionId, String objectKey,
                         String hash, String state, String sha256, long size) { }
    private Intent read(ExecutionPrincipal p, String key) {
        return jdbc.queryForObject("SELECT * FROM ai_upload_intent WHERE tenant_id=? AND member_id=? AND idempotency_key=? FOR UPDATE",
                (rs,n) -> new Intent(rs.getString("doc_id"),rs.getString("upload_id"),rs.getString("version_id"),
                        rs.getString("object_key"),rs.getString("request_hash"),rs.getString("state"),rs.getString("sha256"),rs.getLong("size_bytes")),
                p.tenantId(),p.membershipId(),key);
    }
    public Intent begin(ExecutionPrincipal p, String key, String hash, String sha, long size) {
        return begin(p,key,hash,sha,size,null);
    }
    public Intent begin(ExecutionPrincipal p, String key, String hash, String sha, long size,String targetDoc) {
        if (key == null || !key.matches("[!-~]{1,128}")) { throw new RunApiException(RunErrorCode.BAD_REQUEST,"upload key required"); }
        return tx.execute(s -> {
            String doc=targetDoc==null ? "doc-"+UUID.randomUUID().toString().replace("-","") : targetDoc;
            String upload="upl-"+UUID.randomUUID().toString().replace("-","");
            String version="ver-"+UUID.randomUUID().toString().replace("-","");
            jdbc.update("INSERT INTO ai_upload_intent(tenant_id,member_id,idempotency_key,request_hash,doc_id,upload_id,version_id,object_key,sha256,size_bytes) "
                    +"VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT (tenant_id,member_id,idempotency_key) DO NOTHING",p.tenantId(),p.membershipId(),key,hash,doc,upload,version,
                    "tenants/"+p.tenantId()+"/docs/"+doc+"/"+upload+".pdf",sha,size);
            Intent intent=read(p,key);
            if(!intent.hash().equals(hash)){throw new RunApiException(RunErrorCode.IDEMPOTENCY_KEY_REUSED);}
            return intent;
        });
    }
    public void complete(ExecutionPrincipal p,String key, String hash, Runnable metadata) {
        tx.executeWithoutResult(s -> {
            Intent intent=read(p,key);
            if(!intent.hash().equals(hash)){throw new RunApiException(RunErrorCode.IDEMPOTENCY_KEY_REUSED);}
            if("STORED".equals(intent.state())) {return;}
            metadata.run();
            jdbc.update("UPDATE ai_upload_intent SET state='STORED',updated_at=now() WHERE tenant_id=? AND member_id=? AND idempotency_key=?",p.tenantId(),p.membershipId(),key);
        });
    }
    public void failed(ExecutionPrincipal p,String key) {
        jdbc.update("UPDATE ai_upload_intent SET state='FAILED',updated_at=now() WHERE tenant_id=? AND member_id=? AND idempotency_key=? AND state<>'STORED'",p.tenantId(),p.membershipId(),key);
    }
}
