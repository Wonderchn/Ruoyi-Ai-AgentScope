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

package org.ruoyi.aiweb.embedded;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeBaseController;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeChunkController;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeDocumentController;
import com.nageoffer.ai.ragent.knowledge.controller.vo.IngestionSpecSchemaVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeChunkVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeBaseService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecSchemaProvider;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.ai.api.identity.AiIdentityPort;
import org.ruoyi.aiweb.transport.AiDeliveryReleaser;
import org.ruoyi.aiweb.transport.LocalAiGatewayClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * RW-04-R3 / D2 + <b>RW-04-R8</b>：知识管理三个面（分块 / 知识库 / 文档）的
 * <b>网关级信封与回执头</b>判据（真实本地传输，不是内层 MVC）。
 *
 * <p><b>D2 的两个条件（缺一即 503）。</b>
 * <ol>
 *   <li><b>整数 code</b>：{@code LocalAiGatewayClient.requireSingleJsonObject(...)} 要求
 *       {@code code} 是整数且等于 HTTP 状态（{@code LocalAiGatewayClient.java:458-473}）；
 *       内层原先返回 {@code framework.convention.Result}（字符串 {@code "0"}）⇒ 必然
 *       {@code missing envelope code}。</li>
 *   <li><b>交付回执头</b>：网关对 {@code GET} 一律走<b>字节分支</b>
 *       （{@code AiGatewayController:366-367} 的 {@code method.equals("GET")}），
 *       而 {@code forwardBytes()} 对 200 响应还要求
 *       {@code X-AI-Delivery-Permit}/{@code X-AI-Delivery-Operation} 匹配 {@code [0-9a-f-]{36}}
 *       （{@code LocalAiGatewayClient.java:163-170}），缺任一即 {@code delivery receipt missing}。</li>
 * </ol>
 *
 * <p><b>RW-04-R8 的范围变化。</b>本类原先只覆盖分块面 6 条；知识库面 5 条与文档面
 * 11 条 JSON 方法此前返回字符串 code，本卡把它们改成整数 {@link ApiEnvelope}（GET 另带回执头），
 * 正例因此扩到 <b>23 条路由</b>。类名相应从 {@code LocalKnowledgeChunkEnvelopeDispatchTest}
 * 改为本名（git rename，不是新建文件）。
 *
 * <p><b>为什么不用 {@code /api/ai/v1/...} 直接打网关。</b>那需要
 * {@code AiGatewayController.ROUTES} 里先有这些白名单条目；RW-04-R8 <b>刻意不登记</b>
 * 知识库面/文档面的路由（闭包还缺 15 个单例 bean 与 2 组集合元素，注册它们只会得到
 * 一个启动失败或永远 404 的公开面）。本判据刻意跑在<b>白名单之下游</b>，并按网关的同一条件
 * 选择分支（{@link #usesByteBranch(String)}），因此白名单一旦登记，这里跑的就是运行期真实路径。
 * <b>本判据不是公开可达性判据</b>：它证明的是"这两个面的响应经真实本地传输后仍是合法信封
 * + 带回执"；公开面此刻仍然关闭（见 {@code LocalWhitelistEnvelopeShapeTest} 的
 * {@code knowledgeAdminFacesAreStillUnrouted}）。
 *
 * <p><b>multipart 上传不在本判据里。</b>{@code POST /knowledge-base/{kbId}/docs/upload}
 * 声明 {@code consumes = multipart/form-data}，而探针只会发 JSON ⇒ 内层不匹配、返回 415，
 * 那是"探针的局限"，不是信封问题。它的映射由 {@code KnowledgeAdminInnerRouteTest} 用真
 * {@code multipart(...)} 请求钉住。
 *
 * <p><b>harness 的诚实边界</b>：内层 service 用替身；{@link DeliveryPermits} 是真的
 * （只把 {@code RevocationGuard} 换成替身，回执 id 为真实 UUID 形状）。
 */
@Tag("dev")
class LocalKnowledgeAdminEnvelopeDispatchTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final int PV = 7;
    private static final int AV = 3;

    /** 与冻结页 map 的「文档分块」面一致：读走 document.read，写走 kb.write。 */
    private static final Set<String> SCOPES = Set.of(
            "ai:document:read", "ai:kb:write", "ai:kb:read", "ai:kb:list");

    private static Tomcat tomcat;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final KnowledgeChunkService chunkService = mock(KnowledgeChunkService.class);
    private static final KnowledgeBaseService baseService = mock(KnowledgeBaseService.class);
    private static final KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
    private static final FileStorageService fileStorageService = mock(FileStorageService.class);
    private static final IngestionSpecSchemaProvider schemaProvider = mock(IngestionSpecSchemaProvider.class);
    private static final AiDeliveryReleaser deliveryReleaser = mock(AiDeliveryReleaser.class);
    private static final RevocationGuard revocations = mock(RevocationGuard.class);

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("fixture", Map.of(
                        "ai.integration.enabled", "true",
                        "ai.integration.transport", "local")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-knowledge-envelope-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-knowledge-envelope-docroot");
        docBase.mkdirs();
        org.apache.catalina.Context ctx = tomcat.addContext("", docBase.getAbsolutePath());

        // 与生产同形的内层边界过滤器：直接访问 /internal/ai/v1/** 一律 404（见 directInternalPathStays404）
        org.apache.tomcat.util.descriptor.web.FilterDef def =
                new org.apache.tomcat.util.descriptor.web.FilterDef();
        def.setFilterName("aiInternalAccessBoundaryFilter");
        def.setFilter(new org.ruoyi.aiweb.security.AiInternalAccessBoundaryFilter());
        ctx.addFilterDef(def);
        org.apache.tomcat.util.descriptor.web.FilterMap map =
                new org.apache.tomcat.util.descriptor.web.FilterMap();
        map.setFilterName("aiInternalAccessBoundaryFilter");
        map.addURLPatternDecoded("/internal/ai/v1/*");
        map.setDispatcher("REQUEST");
        ctx.addFilterMap(map);

        org.apache.catalina.core.StandardWrapper wrapper =
                (org.apache.catalina.core.StandardWrapper) Tomcat.addServlet(ctx, "dispatcher",
                        new org.springframework.web.servlet.DispatcherServlet(context));
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/", "dispatcher");

        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterAll
    static void stopContainer() throws LifecycleException {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @BeforeEach
    void resetDoubles() {
        reset(chunkService, baseService, documentService, fileStorageService, schemaProvider,
                deliveryReleaser, revocations);
        // 回执 id 必须是 UUID 形状（真实 DeliveryPermits 走 RevocationGuard），否则字节分支会拒
        when(revocations.enter(any(), any(), any())).thenAnswer(invocation ->
                new RevocationGuard.Operation(revocations, UUID.randomUUID().toString(),
                        UUID.randomUUID().toString()));
        stubKnowledgeBaseFace();
        stubDocumentFace();
    }

    // ------------------------------------------------------------------ 正例：分块面

    @Test
    void chunkRoutesSurviveTheGatewayEnvelopeCheck() throws Exception {
        KnowledgeChunkVO vo = new KnowledgeChunkVO();
        vo.setId("c1");
        vo.setDocId("doc-1");
        vo.setChunkIndex(0);
        vo.setContent("内容");
        vo.setEnabled(1);
        Page<KnowledgeChunkVO> page = new Page<>(1, 10);
        page.setRecords(List.of(vo));
        page.setTotal(1);
        when(chunkService.pageQuery(anyString(), any())).thenReturn(page);
        when(chunkService.create(anyString(), any())).thenReturn(vo);

        // GET 列表：字节分支 ⇒ 整数 code + 回执头都要满足
        assertForwardOk("GET", "/knowledge-base/docs/doc-1/chunks", null, "\"c1\"");
        // 其余 5 条：JSON 分支 ⇒ 只需整数 code
        assertForwardOk("POST", "/knowledge-base/docs/doc-1/chunks", "{\"content\":\"内容\"}", "\"c1\"");
        assertForwardOk("PUT", "/knowledge-base/docs/doc-1/chunks/c1", "{\"content\":\"改\"}", null);
        assertForwardOk("DELETE", "/knowledge-base/docs/doc-1/chunks/c1", null, null);
        assertForwardOk("PATCH", "/knowledge-base/docs/doc-1/chunks/c1/enable?value=true", null, null);
        assertForwardOk("PATCH", "/knowledge-base/docs/doc-1/chunks/batch-enable?value=true",
                "{\"chunkIds\":[\"c1\"]}", null);
    }

    @Test
    void chunkListCarriesDeliveryReceiptForTheByteBranch() throws Exception {
        Page<KnowledgeChunkVO> page = new Page<>(1, 10);
        page.setRecords(List.of());
        when(chunkService.pageQuery(anyString(), any())).thenReturn(page);

        Map<String, Object> probed = probe("GET", "/knowledge-base/docs/doc-1/chunks", null);

        assertThat(probed.get("ok")).isEqualTo(true);
        assertThat(probed.get("branch")).isEqualTo("bytes");
        assertThat(String.valueOf(probed.get("permit"))).matches("[0-9a-f-]{36}");
        assertThat(String.valueOf(probed.get("operation"))).matches("[0-9a-f-]{36}");
        String body = String.valueOf(probed.get("body"));
        assertThat(body).contains("\"code\":200");
        assertThat(body).doesNotContain("\"code\":\"0\"");
    }

    @Test
    void jsonBranchRoutesDoNotMintReceipts() throws Exception {
        // 写入面走 JSON 分支：网关不会为它释放许可，所以内层**不得**铸造回执，
        // 否则每个写请求都会留下一个永久 ACTIVE 的许可（许可泄漏）。
        when(chunkService.create(anyString(), any())).thenReturn(new KnowledgeChunkVO());

        Map<String, Object> probed = probe("POST", "/knowledge-base/docs/doc-1/chunks",
                "{\"content\":\"内容\"}");

        assertThat(probed.get("ok")).isEqualTo(true);
        assertThat(probed.get("branch")).isEqualTo("json");
        assertThat(String.valueOf(probed.get("permit"))).isEmpty();
        assertThat(String.valueOf(probed.get("operation"))).isEmpty();
    }

    // ------------------------------------------------------------------ 正例：知识库面（RW-04-R8）

    @Test
    @DisplayName("知识库面 5 条经真实本地传输都必须过关（RW-04-R8：字符串 code 已改成整数）")
    void knowledgeBaseRoutesSurviveTheGatewayEnvelopeCheck() throws Exception {
        // 两条 GET：字节分支 ⇒ 整数 code + 回执头都要满足
        assertForwardOk("GET", "/knowledge-base", null, "\"kb-1\"");
        assertForwardOk("GET", "/knowledge-base/kb-1", null, "\"kb-1\"");
        // 三条写操作：JSON 分支 ⇒ 只需整数 code
        assertForwardOk("POST", "/knowledge-base", "{\"name\":\"新库\"}", "\"kb-new\"");
        assertForwardOk("PUT", "/knowledge-base/kb-1", "{\"name\":\"新名字\"}", null);
        assertForwardOk("DELETE", "/knowledge-base/kb-1", null, null);
    }

    @Test
    @DisplayName("知识库面 GET 必须带回执头，且 body 是整数 code（RW-04-R8）")
    void knowledgeBaseGetCarriesDeliveryReceiptAndIntegralCode() throws Exception {
        Map<String, Object> probed = probe("GET", "/knowledge-base/kb-1", null);

        assertThat(probed.get("ok")).isEqualTo(true);
        assertThat(probed.get("branch")).isEqualTo("bytes");
        assertThat(String.valueOf(probed.get("permit"))).matches("[0-9a-f-]{36}");
        assertThat(String.valueOf(probed.get("operation"))).matches("[0-9a-f-]{36}");
        String body = String.valueOf(probed.get("body"));
        assertThat(body).contains("\"code\":200");
        // 字符串 code 是这条路由曾经必然 503 的原因；反向钉住它不再出现
        assertThat(body).doesNotContain("\"code\":\"0\"");
    }

    @Test
    @DisplayName("知识库面的写操作走 JSON 分支，不铸回执（许可泄漏防护）")
    void knowledgeBaseWritesDoNotMintReceipts() throws Exception {
        Map<String, Object> probed = probe("DELETE", "/knowledge-base/kb-1", null);

        assertThat(probed.get("ok")).isEqualTo(true);
        assertThat(probed.get("branch")).isEqualTo("json");
        assertThat(String.valueOf(probed.get("permit"))).isEmpty();
        assertThat(String.valueOf(probed.get("operation"))).isEmpty();
    }

    // ------------------------------------------------------------------ 正例：文档面（RW-04-R8）

    @Test
    @DisplayName("文档面 11 条 JSON 路由经真实本地传输都必须过关（RW-04-R8）")
    void documentJsonRoutesSurviveTheGatewayEnvelopeCheck() throws Exception {
        // 六条 GET：字节分支 ⇒ 整数 code + 回执头都要满足
        assertForwardOk("GET", "/knowledge-base/kb-1/docs", null, "\"doc-1\"");
        assertForwardOk("GET", "/knowledge-base/docs/doc-1", null, "\"doc-1\"");
        assertForwardOk("GET", "/knowledge-base/docs/ingestion-spec-schema", null, "\"ingestion-spec\"");
        assertForwardOk("GET", "/knowledge-base/docs/search?keyword=q", null, "\"hit-1\"");
        assertForwardOk("GET", "/knowledge-base/docs/doc-1/chunk-logs", null, "\"log-1\"");
        assertForwardOk("GET", "/knowledge-base/docs/doc-1/preview", null, "预览");
        // 五条写操作：JSON 分支 ⇒ 只需整数 code
        assertForwardOk("POST", "/knowledge-base/docs/doc-1/chunk", null, null);
        assertForwardOk("PUT", "/knowledge-base/docs/doc-1", "{\"docName\":\"新名字\"}", null);
        assertForwardOk("DELETE", "/knowledge-base/docs/doc-1", null, null);
        assertForwardOk("PATCH", "/knowledge-base/docs/doc-1/enable?value=true", null, null);
    }

    @Test
    @DisplayName("文档详情 GET 必须带回执头，且 body 是整数 code（RW-04-R8）")
    void documentGetCarriesDeliveryReceiptAndIntegralCode() throws Exception {
        Map<String, Object> probed = probe("GET", "/knowledge-base/docs/doc-1", null);

        assertThat(probed.get("ok")).isEqualTo(true);
        assertThat(probed.get("branch")).isEqualTo("bytes");
        String body = String.valueOf(probed.get("body"));
        assertThat(body).contains("\"code\":200");
        assertThat(body).contains("\"id\":\"doc-1\"");
        assertThat(body).doesNotContain("\"code\":\"0\"");
    }

    @Test
    @DisplayName("文档面裸字节取流只靠回执头过关（内容不是 JSON，不吃整数 code 判据）")
    void documentFileRouteCarriesDeliveryReceipt() throws Exception {
        Map<String, Object> probed = probe("GET", "/knowledge-base/docs/doc-1/file", null);

        assertThat(probed.get("ok"))
                .as("裸字节面：实际=" + probed).isEqualTo(true);
        assertThat(probed.get("branch")).isEqualTo("bytes");
        assertThat(String.valueOf(probed.get("type"))).isEqualTo("application/pdf");
        assertThat(String.valueOf(probed.get("permit"))).matches("[0-9a-f-]{36}");
        assertThat(String.valueOf(probed.get("operation"))).matches("[0-9a-f-]{36}");
    }

    // ------------------------------------------------------------------ 负对照：两个条件各自可证

    @Test
    void stringCodeEnvelopeIsRejectedOnTheSamePath() throws Exception {
        // 负对照 ①：一个**返回字符串 code** 的内层 handler，在同一 harness 上必须失败
        // ⇒ "整数 code"这一条真的被判了，判据不是恒真。
        //
        // RW-04-R8 变更：这条负对照原先打的是真实的 KnowledgeBaseController（当时它还是
        // Result，返回字符串 "0"）。本卡把它改成整数信封后，继续拿它当负例就等于把判据
        // 钉成恒绿，因此换成只存在于本测试装配里的 StringCodeProbeController。判据方向不变。
        Map<String, Object> probed = probe("GET", "/probe/string-code", null);

        assertThat(probed.get("ok"))
                .as("字符串 code 的信封必须被本地传输拒绝；若这里变成 true，说明判据失效")
                .isEqualTo(false);
        assertThat(String.valueOf(probed.get("message"))).contains("missing envelope code");
    }

    @Test
    void integralCodeWithoutReceiptHeadersStillFails() throws Exception {
        // 负对照 ②：只满足整数 code、**不带回执头**的内层 handler，在字节分支上仍必须被拒。
        // 这证明"回执头"是独立的第二个条件（R 复核的补充），不会被 code 判据顺带覆盖。
        Map<String, Object> probed = probe("GET", "/probe/no-receipt", null);

        assertThat(probed.get("ok"))
                .as("整数 code 但没有回执头时，字节分支必须拒绝；否则本判据两个条件只验了一个")
                .isEqualTo(false);
        assertThat(String.valueOf(probed.get("message"))).contains("delivery receipt missing");
    }

    @Test
    void directInternalPathStays404() throws Exception {
        // 边界不变式：内层前缀不对外（与信封形状无关，但同一容器里必须仍然成立）
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port
                                + "/internal/ai/v1/knowledge-base/docs/doc-1/chunks"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ harness

    private void assertForwardOk(String method, String subPath, String body, String expectInBody)
            throws Exception {
        Map<String, Object> probed = probe(method, subPath, body);

        assertThat(probed.get("ok"))
                .as(method + " " + subPath + " 经本地传输必须成功，实际：" + probed)
                .isEqualTo(true);
        assertThat(probed.get("status")).isEqualTo(200);
        String forwarded = String.valueOf(probed.get("body"));
        if (usesByteBranch(method)) {
            // 字节分支对 200 响应的两个条件：JSON 体整数 code（裸字节面除外）+ 两个回执头
            assertThat(String.valueOf(probed.get("permit"))).matches("[0-9a-f-]{36}");
            assertThat(String.valueOf(probed.get("operation"))).matches("[0-9a-f-]{36}");
            if (isJsonResponse(probed)) {
                assertThat(forwarded).contains("\"code\":200");
            }
        } else {
            assertThat(forwarded).contains("\"code\":200");
        }
        if (expectInBody != null) {
            assertThat(forwarded).contains(expectInBody);
        }
    }

    /** {@code .../file} 是裸字节面：字节分支对它只查回执头，不查整数 code（见类注释）。 */
    private static boolean isJsonResponse(Map<String, Object> probed) {
        String type = String.valueOf(probed.get("type")).toLowerCase(Locale.ROOT);
        return !type.startsWith("application/octet-stream")
                && !type.startsWith("application/pdf");
    }

    /**
     * 与 {@code AiGatewayController:366-367} 同形的分支选择。
     *
     * <p>本判据涉及的动作都不在 {@code document.download}/{@code conversation.export}/
     * {@code kb.retrieve} 之列，因此字节分支的唯一触发条件是 {@code GET}。
     * 分支选择必须与生产一致，否则会验错分支——这正是 D2 的第二半被漏掉的原因。
     */
    private static boolean usesByteBranch(String method) {
        return "GET".equals(method);
    }

    /** 打探针端点：由探针在请求线程内调用真实的本地传输。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> probe(String method, String subPath, String body) throws Exception {
        StringBuilder url = new StringBuilder("http://127.0.0.1:").append(port)
                .append("/probe/envelope?subPath=")
                .append(java.net.URLEncoder.encode(subPath, StandardCharsets.UTF_8));
        if (body != null) {
            url.append("&body=").append(java.net.URLEncoder.encode(body, StandardCharsets.UTF_8));
        }
        // 外层方法必须与内层方法一致：本地传输用 BodyProvidingRequest 包装**外层请求**，
        // 内层 handler 看到的 getMethod() 就是外层的。
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .header("Authorization", "Bearer synthetic-session")
                .method(method, publisher);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.body(), Map.class);
    }

    // ------------------------------------------------------------------ fixture 数据

    private void stubKnowledgeBaseFace() {
        KnowledgeBaseVO knowledgeBase = new KnowledgeBaseVO();
        knowledgeBase.setId("kb-1");
        knowledgeBase.setName("知识库一");
        when(baseService.queryById(anyString())).thenReturn(knowledgeBase);

        Page<KnowledgeBaseVO> page = new Page<>(1, 10);
        page.setRecords(List.of(knowledgeBase));
        page.setTotal(1);
        when(baseService.pageQuery(any())).thenReturn(page);

        when(baseService.create(any())).thenReturn("kb-new");
    }

    private void stubDocumentFace() {
        KnowledgeDocumentVO document = new KnowledgeDocumentVO();
        document.setId("doc-1");
        document.setKbId("kb-1");
        document.setDocName("季度报告.pdf");
        document.setFileType("pdf");
        document.setFileUrl("kb/report.pdf");
        when(documentService.get(anyString())).thenReturn(document);
        when(documentService.upload(anyString(), any(), any())).thenReturn(document);
        when(documentService.preview(anyString())).thenReturn("预览正文");

        Page<KnowledgeDocumentVO> documents = new Page<>(1, 10);
        documents.setRecords(List.of(document));
        documents.setTotal(1);
        when(documentService.page(anyString(), any())).thenReturn(documents);

        KnowledgeDocumentSearchVO hit = new KnowledgeDocumentSearchVO();
        hit.setId("hit-1");
        when(documentService.search(any(), anyInt())).thenReturn(List.of(hit));

        KnowledgeDocumentChunkLogVO log = new KnowledgeDocumentChunkLogVO();
        log.setId("log-1");
        Page<KnowledgeDocumentChunkLogVO> logs = new Page<>(1, 10);
        logs.setRecords(List.of(log));
        logs.setTotal(1);
        when(documentService.getChunkLogs(anyString(), any())).thenReturn(logs);

        when(fileStorageService.openStream(anyString()))
                .thenReturn(new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));

        IngestionSpecSchemaVO schema = new IngestionSpecSchemaVO(
                "ingestion-spec", List.of(), List.of(), List.of(), -1);
        when(schemaProvider.describe()).thenReturn(schema);
    }

    // ------------------------------------------------------------------ fixture

    @Configuration
    @EnableWebMvc
    @Import({KnowledgeChunkController.class, KnowledgeBaseController.class,
            KnowledgeDocumentController.class, EnvelopeProbeController.class,
            NoReceiptProbeController.class, StringCodeProbeController.class})
    static class FixtureConfig {

        @Bean
        KnowledgeChunkService knowledgeChunkService() {
            return chunkService;
        }

        @Bean
        KnowledgeBaseService knowledgeBaseService() {
            return baseService;
        }

        @Bean
        KnowledgeDocumentService knowledgeDocumentService() {
            return documentService;
        }

        @Bean
        FileStorageService fileStorageService() {
            return fileStorageService;
        }

        @Bean
        IngestionSpecSchemaProvider ingestionSpecSchemaProvider() {
            return schemaProvider;
        }

        @Bean
        AiIdentityPort aiIdentityPort() {
            return () -> Optional.of(new AiExecutionFacts(TENANT, USER, MEMBER, PV, AV, SCOPES));
        }

        @Bean
        AiDeliveryReleaser aiDeliveryReleaser() {
            return deliveryReleaser;
        }

        @Bean
        RevocationGuard revocationGuard() {
            return revocations;
        }

        /** 真实 {@link DeliveryPermits}：只把 RevocationGuard 换成替身（回执 id 仍是真 UUID 形状）。 */
        @Bean
        DeliveryPermits deliveryPermits(ObjectProvider<RevocationGuard> guard) {
            return new DeliveryPermits(guard);
        }

        @Bean
        LocalAiGatewayClient localAiGatewayClient(AiIdentityPort identityPort,
                                                  AiDeliveryReleaser releaser) {
            return new LocalAiGatewayClient(2000, identityPort, releaser);
        }
    }

    /**
     * 探针控制器：在<b>请求线程内</b>按网关同一条件选择分支调用真实本地传输
     * （{@code dispatch} 依赖 {@code RequestContextHolder}，所以必须由请求驱动），
     * 把转发结果原样回给测试。
     *
     * <p>它等价于 {@code AiGatewayController} 在 {@code transport=local} 下做的事：
     * 构造 {@code ForwardRequest} → {@code forward}/{@code forwardBytes} → 按结果回写。
     * 唯一被跳过的是白名单匹配（T0 独占文件），已在类注释里声明。
     */
    @RestController
    static class EnvelopeProbeController {

        private final LocalAiGatewayClient client;

        EnvelopeProbeController(LocalAiGatewayClient client) {
            this.client = client;
        }

        @RequestMapping(value = "/probe/envelope",
                method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                        RequestMethod.DELETE, RequestMethod.PATCH})
        Map<String, Object> probe(jakarta.servlet.http.HttpServletRequest outer,
                                  @RequestParam String subPath,
                                  @RequestParam(required = false) String body) {
            String method = outer.getMethod();
            byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
            URI target = URI.create("http://local/internal/ai/v1" + subPath);
            Map<String, Object> result = new LinkedHashMap<>();
            try {
                if (usesByteBranch(method)) {
                    LocalAiGatewayClient.ByteResponse transfer = client.forwardBytes(
                            new LocalAiGatewayClient.ForwardRequest(method, target, Map.of(), payload));
                    result.put("ok", true);
                    result.put("branch", "bytes");
                    result.put("status", transfer.status());
                    result.put("permit", transfer.permitId());
                    result.put("operation", transfer.operationId());
                    result.put("type", transfer.contentType());
                    result.put("body", new String(transfer.bytes(), StandardCharsets.UTF_8));
                } else {
                    LocalAiGatewayClient.ForwardResponse forward = client.forward(
                            new LocalAiGatewayClient.ForwardRequest(method, target, Map.of(), payload));
                    result.put("ok", true);
                    result.put("branch", "json");
                    result.put("status", forward.status());
                    result.put("permit", "");
                    result.put("operation", "");
                    result.put("type", "application/json");
                    result.put("body", forward.body());
                }
            } catch (RuntimeException rejected) {
                result.put("ok", false);
                result.put("error", rejected.getClass().getSimpleName());
                result.put("message", String.valueOf(rejected.getMessage()));
            }
            return result;
        }
    }

    /**
     * 负对照用的内层 handler：整数 code、但**不带**回执头。
     * 只存在于本测试的装配里，生产不注册（用于证明字节分支的两个条件是独立的）。
     */
    @RestController
    @RequestMapping("/internal/ai/v1")
    static class NoReceiptProbeController {

        @GetMapping("/probe/no-receipt")
        ApiEnvelope<Map<String, Object>> noReceipt() {
            return ApiEnvelope.ok(Map.of("probe", "no-receipt"));
        }
    }

    /**
     * 负对照用的内层 handler：返回 platform {@link Result}（字符串 {@code code}）。
     *
     * <p>RW-04-R8 之前这条负对照打的是真实的 {@code KnowledgeBaseController}（它当时还是
     * {@code Result}）。本卡把那两个控制器改成整数信封后，继续拿它们当负例就等于把判据
     * 钉成恒绿，因此换成这个只存在于本测试装配里的替身——判据方向不变。
     */
    @RestController
    @RequestMapping("/internal/ai/v1")
    static class StringCodeProbeController {

        @GetMapping("/probe/string-code")
        Result<Map<String, Object>> stringCode() {
            return Results.success(Map.of("probe", "string-code"));
        }
    }
}