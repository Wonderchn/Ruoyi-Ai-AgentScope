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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** Exercises the real response parser at the HTTP boundary, without a platform service. */
@Tag("dev")
class P04PlatformAuthorizationClientTest {

    private static final DelegatedPrincipal PRINCIPAL = new DelegatedPrincipal(
            "platform", "T1", "U1", "M1", 1, Set.of("rag.chat.submit"), "test-jti");

    private HttpClient httpClient;
    private PlatformAuthorizationClient client;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        P04SecurityProperties properties = new P04SecurityProperties();
        properties.getPlatform().setServiceCredential("synthetic-test-service-credential");
        client = new PlatformAuthorizationClient(httpClient, new ObjectMapper(), properties);
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(status).when(response).statusCode();
        doReturn(body).when(response).body();
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private void rejectsAs(P04AiErrorCode expected) {
        P04AiException error = assertThrows(P04AiException.class,
                () -> client.check(PRINCIPAL, "rag.chat", "KB1"));
        assertEquals(expected, error.errorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "", "[]", "{\"code\":",
            "{\"code\":\"200\",\"data\":{\"allowed\":true,\"policyVersion\":1}}",
            "{\"code\":200.0,\"data\":{\"allowed\":true,\"policyVersion\":1}}",
            "{\"code\":200,\"data\":{\"allowed\":\"true\",\"policyVersion\":1}}",
            "{\"code\":200,\"data\":{\"allowed\":1,\"policyVersion\":1}}",
            "{\"code\":200,\"data\":{\"allowed\":true}}",
            "{\"code\":200,\"data\":{\"allowed\":true,\"policyVersion\":null}}",
            "{\"code\":200,\"data\":{\"allowed\":true,\"policyVersion\":\"1\"}}",
            "{\"code\":200,\"data\":{\"allowed\":true,\"policyVersion\":1.5}}",
            "{\"code\":200,\"data\":{\"allowed\":true,\"policyVersion\":2147483648}}",
            "{\"code\":200,\"data\":{\"allowed\":false,\"allowed\":true,\"policyVersion\":1}}",
            "{\"code\":200,\"data\":{\"allowed\":true,\"policyVersion\":1}} {}",
            "{\"code\":200,\"data\":{\"allowed\":false,\"policyVersion\":1}}",
            "{\"code\":200,\"data\":{\"errorCode\":\"FORBIDDEN\"}}"
    })
    void malformedOrAmbiguousSuccessFailsClosed(String body) throws Exception {
        respond(200, body);
        rejectsAs(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
    }

    @Test
    void validSuccessRetainsTheExplicitPolicyVersion() throws Exception {
        respond(200, "{\"code\":200,\"data\":{\"allowed\":true,\"policyVersion\":1}}");
        AuthorizationChecker.AuthorizeResult result = client.check(PRINCIPAL, "rag.chat", "KB1");
        assertTrue(result.allowed());
        assertEquals(1, result.policyVersion());
    }

    @Test
    void validDenialRetainsItsContractError() throws Exception {
        respond(403, "{\"code\":403,\"data\":{\"errorCode\":\"FORBIDDEN\"}}");
        rejectsAs(P04AiErrorCode.FORBIDDEN);
    }

    @Test
    void mismatchedEnvelopeStatusFailsClosed() throws Exception {
        respond(503, "{\"code\":403,\"data\":{\"errorCode\":\"FORBIDDEN\"}}");
        rejectsAs(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
    }

    @Test
    void mismatchedSymbolicErrorStatusFailsClosed() throws Exception {
        respond(503, "{\"code\":503,\"data\":{\"errorCode\":\"FORBIDDEN\"}}");
        rejectsAs(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
    }

    @Test
    void transportFailureFailsClosed() throws Exception {
        doThrow(new IOException("synthetic transport failure")).when(httpClient)
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        rejectsAs(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
    }
}
