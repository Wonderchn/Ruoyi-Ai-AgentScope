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

package org.ruoyi.aiweb.transport;

import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.ai.api.identity.AiIdentityPort;
import org.ruoyi.aiintegration.web.AiGatewayClient;
import org.ruoyi.aiweb.embedded.AiInternalExceptionResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * R17 F06:2 retry R1（可观测性先行）判据：内嵌转送遇到 abnormal 状态
 * （非 2xx/4xx/503，如内层 500）时，必须把**内层状态码与正文摘录**保留进异常材料，
 * 否则客户端只看到泛化 503，内层真因（如
 * {@code admission conflict without persisted run} 的 500 信封）被整段丢弃——真机两跑
 * 同形 503 且零归因的观测面就是在这里被掐断的。
 *
 * <p>材料纪律（只进服务端日志，不改对外响应语义）：
 * <ul>
 *   <li>保留：{@code status=<code>} + 正文摘录（截断上限在实现中注明）；</li>
 *   <li>脱敏：剥离控制字符（CR/LF/TAB 等），防正文伪造日志行；</li>
 *   <li>有界：超限截断并附 {@code …(truncated)} 标记；空体标 {@code (empty)}。</li>
 * </ul>
 *
 * <p>覆盖两条路径：①内层直接以 5xx 信封应答；②内层处理器抛
 * {@link RunApiException} 经真 {@link AiInternalExceptionResolver} 落 500 错误页
 * （与真机 {@code IngestionTaskRuntimeController.retry} 的失败形态一致）。
 */
@Tag("dev")
class LocalAbnormalStatusMaterialTest {

    private static final AiExecutionFacts FACTS = new AiExecutionFacts(
            "T1", "2101", "platform:T1:2101", 3, 7, Set.of("ai:kb:list"));

    /** fixture 控制器的可配置应答。 */
    private static final AtomicReference<Integer> stubStatus = new AtomicReference<>(200);
    private static final AtomicReference<String> stubBody = new AtomicReference<>("{\"code\":200,\"data\":{}}");

    @BeforeEach
    void resetStubs() {
        stubStatus.set(200);
        stubBody.set("{\"code\":200,\"data\":{}}");
    }

    @AfterEach
    void releaseRequestContext() {
        RequestContextHolder.resetRequestAttributes();
        PrincipalContext.clear();
    }

    private LocalAiGatewayClient client(AiIdentityPort port) {
        return new LocalAiGatewayClient(2000, port, (tenantId, memberId, permitId, operationId) -> { });
    }

    private static AiIdentityPort fixedFacts() {
        return () -> Optional.of(FACTS);
    }

    private AiGatewayClient.ForwardRequest forwardRequest() {
        return new AiGatewayClient.ForwardRequest("GET",
                URI.create("http://local/internal/ai/v1/knowledge-bases"), Map.of(), null);
    }

    private MockHttpServletRequest requestWithWebContext() {
        MockServletContext servletContext = new MockServletContext();
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(AbnormalFixture.class);
        context.setServletContext(servletContext);
        context.refresh();
        servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, context);
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext, "GET", "/api/ai/v1/knowledge-bases");
        request.setAttribute(org.springframework.web.servlet.DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE,
                context);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
        return request;
    }

    @Test
    void abnormalStatusKeepsInnerStatusAndEnvelopeBodyForLogAttribution() {
        requestWithWebContext();
        stubStatus.set(500);
        stubBody.set("{\"code\":500,\"msg\":\"admission conflict without persisted run\","
                + "\"data\":{\"errorCode\":\"INTERNAL_ERROR\"}}");

        assertThatThrownBy(() -> client(fixedFacts()).forward(forwardRequest()))
                .isInstanceOf(AiGatewayClient.UpstreamUnavailableException.class)
                .hasMessageContaining("abnormal upstream status")
                .hasMessageContaining("status=500")
                .hasMessageContaining("admission conflict without persisted run");
        // 对外仍收敛为泛化 503 语义：材料只进异常，不放行给客户端
        assertThat(PrincipalContext.hasPrincipal()).isFalse();
    }

    @Test
    void abnormalStatusKeepsResolverRenderedFiveHundredPage() {
        requestWithWebContext();

        assertThatThrownBy(() -> client(fixedFacts()).forward(new AiGatewayClient.ForwardRequest("GET",
                URI.create("http://local/internal/ai/v1/exploding-run"), Map.of(), null)))
                .isInstanceOf(AiGatewayClient.UpstreamUnavailableException.class)
                .hasMessageContaining("status=500")
                .hasMessageContaining("INTERNAL_ERROR");
    }

    @Test
    void abnormalMaterialIsBoundedAndMarkedWhenTruncated() {
        requestWithWebContext();
        String filler = "x".repeat(600);
        stubStatus.set(500);
        stubBody.set("{\"code\":500,\"msg\":\"" + filler + "TAIL_SENTINEL\","
                + "\"data\":{\"errorCode\":\"INTERNAL_ERROR\"}}");

        Throwable thrown = catchThrowable(() -> client(fixedFacts()).forward(forwardRequest()));

        assertThat(thrown).isInstanceOf(AiGatewayClient.UpstreamUnavailableException.class);
        assertThat(thrown.getMessage()).contains("(truncated)").doesNotContain("TAIL_SENTINEL");
        assertThat(thrown.getMessage().length()).isLessThan(768);
    }

    @Test
    void abnormalMaterialStripsControlCharacters() {
        requestWithWebContext();
        stubStatus.set(500);
        stubBody.set("{\"code\":500,\"msg\":\"line1\r\nline2\tend\","
                + "\"data\":{\"errorCode\":\"INTERNAL_ERROR\"}}");

        Throwable thrown = catchThrowable(() -> client(fixedFacts()).forward(forwardRequest()));

        assertThat(thrown).isInstanceOf(AiGatewayClient.UpstreamUnavailableException.class);
        assertThat(thrown.getMessage()).contains("line1").contains("line2")
                .doesNotContain("\r").doesNotContain("\n").doesNotContain("\t");
        assertThat(thrown.getMessage().chars().noneMatch(ch -> ch < 0x20 || ch == 0x7f)).isTrue();
    }

    @Test
    void abnormalMaterialMarksAnEmptyBody() {
        requestWithWebContext();
        stubStatus.set(500);
        stubBody.set("");

        assertThatThrownBy(() -> client(fixedFacts()).forward(forwardRequest()))
                .isInstanceOf(AiGatewayClient.UpstreamUnavailableException.class)
                .hasMessageContaining("status=500")
                .hasMessageContaining("(empty)");
    }

    @Test
    void byteForwardKeepsAbnormalStatusMaterial() {
        requestWithWebContext();
        stubStatus.set(500);
        stubBody.set("{\"code\":500,\"msg\":\"admission conflict without persisted run\","
                + "\"data\":{\"errorCode\":\"INTERNAL_ERROR\"}}");

        assertThatThrownBy(() -> client(fixedFacts()).forwardBytes(forwardRequest()))
                .isInstanceOf(AiGatewayClient.UpstreamUnavailableException.class)
                .hasMessageContaining("abnormal byte response")
                .hasMessageContaining("status=500")
                .hasMessageContaining("admission conflict without persisted run");
    }

    @RestController
    @RequestMapping("/internal/ai/v1")
    static class AbnormalFixtureController {

        @GetMapping("/knowledge-bases")
        public ResponseEntity<String> listKnowledgeBases() {
            return ResponseEntity.status(stubStatus.get())
                    .header("Content-Type", "application/json")
                    .body(stubBody.get());
        }

        @GetMapping("/exploding-run")
        public ResponseEntity<String> explodingRun() {
            throw new RunApiException(RunErrorCode.INTERNAL_ERROR, "admission conflict without persisted run");
        }
    }

    @Configuration
    @EnableWebMvc
    @Import({AbnormalFixtureController.class, AiInternalExceptionResolver.class})
    static class AbnormalFixture {
    }
}
