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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LocalMinerUProtocolTest {
    private static void reply(HttpExchange x,int status,String value) throws java.io.IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");
        x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);x.close();
    }
    private static LocalMinerUClient client(HttpServer server) {
        var props=new LocalMinerUProperties();props.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());props.setToken("test-only");
        return new LocalMinerUClient(props,new ObjectMapper());
    }
    @Test void cachedAndPendingUploadsBothReachActualOutputWithoutDuplicatePut() throws Exception {
        for(boolean cached:new boolean[]{true,false}) {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var puts=new AtomicInteger();var completes=new AtomicInteger();
            server.createContext("/v1/uploads",x->{
                String path=x.getRequestURI().getPath();x.getRequestBody().readAllBytes();
                if(path.endsWith("/content")){puts.incrementAndGet();reply(x,200,"{}");}
                else if(path.endsWith("/complete")){completes.incrementAndGet();reply(x,200,"{\"status\":\"completed\",\"file\":{\"id\":\"file_1\"}}");}
                else reply(x,200,cached?"{\"id\":\"upload_1\",\"status\":\"completed\",\"file\":{\"id\":\"file_1\"}}":"{\"id\":\"upload_1\",\"status\":\"pending\"}");
            });
            server.createContext("/v1/parse/jobs",x->{
                if("POST".equals(x.getRequestMethod())){
                    assertTrue(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8).contains("file_1"));reply(x,200,"{\"job_id\":\"job_1\"}");
                } else reply(x,200,"{\"status\":\"completed\",\"tier\":\"flash\",\"files\":[{\"status\":\"completed\",\"output_files\":{\"markdown\":{\"file_id\":\"md_1\"}}}]}");
            });
            server.createContext("/v1/files/md_1/content",x->reply(x,200,"P2 protocol marker"));server.start();
            try {
                var result=client(server).parse("%PDF-synthetic".getBytes(),"sample.pdf","hash",30);
                assertEquals("P2 protocol marker",result.markdown());assertEquals("job_1",result.jobId());
                assertEquals(cached?0:1,puts.get());assertEquals(cached?0:1,completes.get());
            } finally {server.stop(0);}
        }
    }
    @Test void vanishedJobAndCancellationNeverBecomeCompleted() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var cancels=new AtomicInteger();
        server.createContext("/v1/parse/jobs/job_1",x->{if("DELETE".equals(x.getRequestMethod())){cancels.incrementAndGet();reply(x,200,"{}");}else reply(x,404,"{}");});server.start();
        try {
            var client=client(server);var job=new LocalMinerUClient.ParseJob("job_1","file_1");
            assertThrows(LocalMinerUClient.JobLostException.class,()->client.awaitResult(job,30));
            assertThrows(LocalMinerUClient.ParseCancelledException.class,()->client.awaitResult(job,30,()->true));
            assertEquals(1,cancels.get());
        } finally {server.stop(0);}
    }
}
