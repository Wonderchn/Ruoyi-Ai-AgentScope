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

package com.nageoffer.ai.ragent.flow.runtime;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.stream.RunEventStreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F03:0：flow.run 受理体的顶层 conversationId。
 *
 * <p>只检验控制器构造的 {@link AdmissionRequest}：conversationId 走第三参（顶层），
 * 不得折进 inputs；形状非法在受理前 400。
 */
@Tag("dev")
class FlowRunControllerTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final String FLOW = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private final RunAdmissionService admission = mock(RunAdmissionService.class);
    private final RunEventStreamService stream = mock(RunEventStreamService.class);
    private final FlowRunController controller = new FlowRunController(admission, stream);

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("顶层 conversationId 原样进入 AdmissionRequest 第三参，且不进入 inputs")
    void conversationIdGoesToAdmissionRequestTopLevel() throws Exception {
        PrincipalContext.set(principal());
        when(admission.admit(any(), any(), any())).thenReturn(new RunAdmissionService.AdmissionResult(
                "r-0123456789abcdef0123456789abcdef", "QUEUED", Instant.now(), false));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.run(FLOW, "idem-1",
                "{\"inputs\":{\"name\":\"Ada\"},\"conversationId\":\"conv-1\"}", response);

        ArgumentCaptor<AdmissionRequest> request = ArgumentCaptor.forClass(AdmissionRequest.class);
        verify(admission).admit(any(), eq("idem-1"), request.capture());
        assertThat(request.getValue().conversationId()).isEqualTo("conv-1");
        assertThat(request.getValue().input().path("conversationId").isMissingNode()).isTrue();
        assertThat(request.getValue().input().path("inputs").path("name").asText()).isEqualTo("Ada");
        verify(stream).stream(any(), eq("r-0123456789abcdef0123456789abcdef"), eq(0L), any(), eq("flow.run"));
    }

    @Test
    @DisplayName("无 conversationId：第三参保持 null（既有行为不变）")
    void absentConversationIdStaysNull() throws Exception {
        PrincipalContext.set(principal());
        when(admission.admit(any(), any(), any())).thenReturn(new RunAdmissionService.AdmissionResult(
                "r-0123456789abcdef0123456789abcdef", "QUEUED", Instant.now(), false));

        controller.run(FLOW, "idem-1", "{\"inputs\":{\"name\":\"Ada\"}}", new MockHttpServletResponse());

        ArgumentCaptor<AdmissionRequest> request = ArgumentCaptor.forClass(AdmissionRequest.class);
        verify(admission).admit(any(), eq("idem-1"), request.capture());
        assertThat(request.getValue().conversationId()).isNull();
    }

    @Test
    @DisplayName("conversationId 非字符串/空白：受理前 400")
    void invalidConversationIdIsRejectedBeforeAdmission() throws Exception {
        PrincipalContext.set(principal());
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.run(FLOW, "idem-1", "{\"inputs\":{\"name\":\"Ada\"},\"conversationId\":\"  \"}", response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("BAD_REQUEST");
        verify(admission, org.mockito.Mockito.never()).admit(any(), any(), any());
    }

    private static ExecutionPrincipal principal() {
        long now = Instant.now().getEpochSecond();
        return new ExecutionPrincipal(TENANT, USER, MEMBER, 1, 1, Set.of("flow.run"),
                "jti", "platform", now, now + 300);
    }
}
