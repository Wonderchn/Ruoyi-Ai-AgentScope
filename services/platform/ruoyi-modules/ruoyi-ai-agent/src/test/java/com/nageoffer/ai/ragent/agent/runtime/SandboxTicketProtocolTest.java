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

package com.nageoffer.ai.ragent.agent.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class SandboxTicketProtocolTest {
    private HttpServer server;
    private SandboxTicketClient client;
    private final AtomicInteger posts=new AtomicInteger();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var config=new P3Properties();config.getSandbox().setEnabled(true);config.getSandbox().setNamespace("unit-owned");config.getSandbox().setCredential("synthetic-test-only");client=new SandboxTicketClient(config,java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/tickets"));
    }
    @AfterEach void stop(){if(server!=null) server.stop(0);}
    @Test void actualBoundedTransportSendsFixedNamespaceAndValidatesFinalReceipt() {
        server.createContext("/tickets",exchange->{
            assertEquals("unit-owned",exchange.getRequestHeaders().getFirst("X-Sandbox-Namespace"));
            assertEquals("Bearer synthetic-test-only",exchange.getRequestHeaders().getFirst("Authorization"));
            if("POST".equals(exchange.getRequestMethod())) posts.incrementAndGet();
            byte[] bytes="{\"state\":\"FOUND\",\"final\":true,\"operationKey\":\"op-test\",\"argsHash\":\"hash\",\"externalId\":\"ticket-00000000000000000000000000000000\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        var args=AgentModelAdapter.parse("{\"title\":\"test\",\"details\":\"test\"}");
        assertNotNull(client.verifyFound(client.create("op-test","hash",args),"op-test","hash"));
        assertNotNull(client.verifyFound(client.query("op-test"),"op-test","hash"));assertEquals(1,posts.get());
        assertThrows(SandboxTicketClient.UnknownOutcome.class,()->client.verifyFound(client.query("op-test"),"op-test","changed"));
    }
    @Test void redirectsAndLostResponsesHaveNoAutomaticPostRetry() {
        server.createContext("/tickets",exchange->{posts.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Location","http://127.0.0.1:19520/retry");exchange.sendResponseHeaders(307,-1);exchange.close();});
        server.createContext("/retry",exchange->{posts.incrementAndGet();exchange.close();});server.start();
        assertThrows(SandboxTicketClient.UnknownOutcome.class,()->client.create("op-test","hash",AgentModelAdapter.parse("{\"title\":\"test\",\"details\":\"test\"}")));
        assertEquals(1,posts.get());
    }
    @Test void absentIsNeverProofOfNoWriteAndFoundIsBoundToAllFields() {
        for(String receipt:new String[]{"{\"state\":\"ABSENT\",\"final\":false}","{\"state\":\"FOUND\",\"final\":true,\"operationKey\":\"wrong\",\"argsHash\":\"hash\",\"externalId\":\"ticket-00000000000000000000000000000000\"}"})
            assertThrows(SandboxTicketClient.UnknownOutcome.class,()->client.verifyFound(AgentModelAdapter.parse(receipt),"op-test","hash"));
    }
}
