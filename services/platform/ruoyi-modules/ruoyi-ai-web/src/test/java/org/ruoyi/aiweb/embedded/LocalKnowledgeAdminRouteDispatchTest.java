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
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeBaseController;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeDocumentController;
import com.nageoffer.ai.ragent.knowledge.controller.vo.IngestionSpecSchemaVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeBaseService;
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
import org.mockito.ArgumentCaptor;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.ai.api.identity.AiIdentityPort;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.ruoyi.aiintegration.web.AiGatewayController;
import org.ruoyi.aiintegration.web.GatewayMultipartConfiguration;
import org.ruoyi.aiweb.AiWebEmbeddedConfiguration;
import org.ruoyi.aiweb.security.AiInternalAccessBoundaryFilter;
import org.ruoyi.aiweb.transport.AiDeliveryReleaser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2-F05-A1：知识管理面 <b>17 条路由</b>（知识库 5 + 文档 12）经网关<b>真实可达</b>的端到端判据。
 *
 * <p><b>为什么必须有这一条。</b>RW-04 期这一面有四种"静态检查都过、真实请求才炸"的形态：
 * ① 白名单没有这 17 条（网关 404，本切片修）；② 控制器不是 bean / 没有内层前缀
 * （内层不命中 ⇒ 503，本切片修）；③ 信封是字符串 code（⇒ 503，RW-04-R8 修）；
 * ④ GET 没有交付回执头（⇒ 503，RW-04-R8 修）。源码扫描证明不了其中任何一条
 * ——尤其本切片：{@code LocalWhitelistHandlerCoverageTest} 只证明"放行有人接"，
 * 不证明"接得住"。故这里用<b>真实 Tomcat + 真实网关 + 真实本地传输</b>逐条发请求。
 *
 * <p><b>正例 = 17 条逐条 200</b>，且每条都断言：到达 handler（响应体带只可能来自替身服务的
 * 独有标记）、整数 {@code "code":200}、<b>没有</b> rag{{@code Result}} 的字符串 {@code "code":"0"}。
 * GET 走字节分支（网关只认回执头 + 整数 code），写面走 JSON 分支。
 *
 * <p><b>交付许可保持"控制器自铸"</b>（D2 决定）：本判据把每次 permit 的
 * {@code (动作, 资源引用)} 逐条捕获——引用是控制器自己的 {@code kb:list}/{@code kb:kb-1}/
 * {@code doc:doc-1}/{@code document:search}/{@code ingestion-spec} 形状，
 * 若哪天改走 {@code AiEmbeddedAdminDeliveryConfiguration} 的 advice，这里会立刻变成
 * {@code admin-read:/api/ai/v1/...} 形状（并因此红）——即"不引入 advice 双铸"有判据在盯。
 *
 * <p><b>负例</b>：白名单外形状 404（与"不存在"同形）、未登录 401、内部前缀对外 404、
 * 缺 scope 403（在 handler 之前）、{@code docs/search} 不被 {@code docs/{docId}} 吃掉
 * （段序遮蔽的回归判据）。
 */
@Tag("dev")
class LocalKnowledgeAdminRouteDispatchTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final int PV = 7;
    private static final int AV = 3;

    /** 17 条路由所需的全部权限（= 白名单动作映射的权限集，零新增）。 */
    private static final Set<String> ALL_PERMISSIONS = Set.of(
            "ai:kb:list", "ai:kb:read", "ai:kb:write", "ai:kb:delete",
            "ai:document:read", "ai:document:download", "ai:document:upload", "ai:document:ingest",
            "ai:config:read");

    private static Tomcat tomcat;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final KnowledgeBaseService baseService = mock(KnowledgeBaseService.class);
    private static final KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
    private static final FileStorageService fileStorageService = mock(FileStorageService.class);
    private static final IngestionSpecSchemaProvider schemaProvider = mock(IngestionSpecSchemaProvider.class);
    private static final RevocationGuard revocations = mock(RevocationGuard.class);
    private static final List<String> acknowledgements = new ArrayList<>();
    private static final List<String> permits = new ArrayList<>();

    /** 请求期权限集合：网关与内层都读它，故"缺 scope"负例改的是真的判定输入。 */
    private static final AtomicReference<Set<String>> CURRENT_PERMISSIONS =
            new AtomicReference<>(ALL_PERMISSIONS);

    @BeforeAll
    static void startContainer() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(FixtureConfig.class);
        context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("fixture", Map.of(
                        "ai.integration.enabled", "true",
                        "ai.integration.transport", "local")));

        tomcat = new Tomcat();
        tomcat.setBaseDir("target/local-knowledge-admin-route-tomcat");
        tomcat.setPort(0);
        File docBase = new File("target/local-knowledge-admin-route-docroot");
        docBase.mkdirs();
        org.apache.catalina.Context ctx = tomcat.addContext("", docBase.getAbsolutePath());

        // 与生产装配同形：内层前缀直接访问一律 404（关闭体）
        org.apache.tomcat.util.descriptor.web.FilterDef def =
                new org.apache.tomcat.util.descriptor.web.FilterDef();
        def.setFilterName("aiInternalAccessBoundaryFilter");
        def.setFilter(new AiInternalAccessBoundaryFilter());
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
        // 与生产同形：Boot 会给 DispatcherServlet 挂 multipart 配置（MultipartAutoConfiguration），
        // 否则容器的 getParts() 直接以 "no multi-part configuration" 500——
        // 那样 multipart 路由在夹具里永远不可达，判据就成了夹具缺陷。
        wrapper.setMultipartConfigElement(new jakarta.servlet.MultipartConfigElement(
                System.getProperty("java.io.tmpdir")));
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
    void resetFixtures() {
        reset(baseService, documentService, fileStorageService, schemaProvider, revocations);
        permits.clear();
        acknowledgements.clear();
        CURRENT_PERMISSIONS.set(ALL_PERMISSIONS);
        when(revocations.enter(any(), anyString(), anyString())).thenAnswer(invocation -> {
            permits.add(invocation.getArgument(1) + "/" + invocation.getArgument(2));
            return new RevocationGuard.Operation(revocations,
                    java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString());
        });
        stubKnowledgeBaseFace();
        stubDocumentFace();
    }

    // ------------------------------------------------------------------ 正例：17 条逐条可达

    @Test
    @DisplayName("17 条路由逐条经网关 200：知识库 5 + 文档 12（含 multipart 上传与裸字节取流）")
    void allSeventeenRoutesAreReachableThroughTheGateway() throws Exception {
        // ---- 知识库面 5 ----
        assertJsonOk("GET", "/api/ai/v1/knowledge-base", null, "f05-kb-list");
        assertJsonOk("GET", "/api/ai/v1/knowledge-base/kb-1", null, "f05-kb-detail");
        assertJsonOk("POST", "/api/ai/v1/knowledge-base", "{\"name\":\"f05-kb-created\"}", "kb-new");
        assertJsonOk("PUT", "/api/ai/v1/knowledge-base/kb-1", "{\"name\":\"f05-renamed\"}", null);
        assertJsonOk("DELETE", "/api/ai/v1/knowledge-base/kb-1", null, null);

        // ---- 文档面 12 ----
        assertJsonOk("GET", "/api/ai/v1/knowledge-base/docs/ingestion-spec-schema", null, "ingestion-spec");
        assertJsonOk("GET", "/api/ai/v1/knowledge-base/docs/search?keyword=f05", null, "f05-hit");
        assertJsonOk("GET", "/api/ai/v1/knowledge-base/docs/doc-1", null, "f05-doc-detail");
        assertJsonOk("GET", "/api/ai/v1/knowledge-base/docs/doc-1/chunk-logs", null, "f05-log");
        assertJsonOk("GET", "/api/ai/v1/knowledge-base/docs/doc-1/preview", null, "f05-preview");
        assertJsonOk("POST", "/api/ai/v1/knowledge-base/docs/doc-1/chunk", null, null);
        assertJsonOk("PUT", "/api/ai/v1/knowledge-base/docs/doc-1", "{\"docName\":\"f05-new\"}", null);
        assertJsonOk("DELETE", "/api/ai/v1/knowledge-base/docs/doc-1", null, null);
        assertJsonOk("PATCH", "/api/ai/v1/knowledge-base/docs/doc-1/enable?value=true", null, null);
        assertJsonOk("GET", "/api/ai/v1/knowledge-base/kb-1/docs", null, "f05-doc-row");

        // 裸字节取流：内容不是 JSON，但字节分支同样要求整数 code（内层自制头）+ 正确 Content-Type
        HttpResponse<byte[]> file = getBytes("/api/ai/v1/knowledge-base/docs/doc-1/file");
        assertThat(file.statusCode()).as(".../file 必须经网关 200").isEqualTo(200);
        assertThat(file.headers().firstValue("Content-Type").orElse("")).contains("application/pdf");
        assertThat(new String(file.body(), StandardCharsets.UTF_8)).contains("f05-pdf");

        // multipart 上传：经通配分支转送时部件必须原样到达内层 handler（见 targetRequest 的部件重建）
        assertMultipartUploadCarriesTheFilePart();
    }

    // ------------------------------------------------------------------ 交付许可：控制器自铸

    @Test
    @DisplayName("GET 的 permit 由控制器自铸（引用是 kb:/doc: 形状），写面零 permit 零 ACK")
    void receiptsAreMintedByTheControllersThemselves() throws Exception {
        assertJsonOk("GET", "/api/ai/v1/knowledge-base", null, null);
        assertJsonOk("GET", "/api/ai/v1/knowledge-base/kb-1", null, null);
        assertJsonOk("GET", "/api/ai/v1/knowledge-base/docs/ingestion-spec-schema", null, null);
        assertJsonOk("GET", "/api/ai/v1/knowledge-base/docs/search?keyword=f05", null, null);
        assertJsonOk("GET", "/api/ai/v1/knowledge-base/docs/doc-1", null, null);
        assertJsonOk("GET", "/api/ai/v1/knowledge-base/docs/doc-1/chunk-logs", null, null);
        assertJsonOk("GET", "/api/ai/v1/knowledge-base/docs/doc-1/preview", null, null);
        assertJsonOk("GET", "/api/ai/v1/knowledge-base/kb-1/docs", null, null);

        // 裸字节取流：内容不是 JSON（不吃整数 code 判据），但同样走字节分支 ⇒ 必须铸回执头，
        // 否则网关会以 "delivery receipt missing" 503。这里只断言 200 + 许可铸造。
        HttpResponse<byte[]> file = getBytes("/api/ai/v1/knowledge-base/docs/doc-1/file");
        assertThat(file.statusCode()).as(".../file 必须经网关 200").isEqualTo(200);
        assertThat(new String(file.body(), StandardCharsets.UTF_8)).contains("f05-pdf");

        assertThat(permits)
                .as("每条 200 的 GET 恰铸一次许可，且引用是控制器自己的形状"
                        + "（若改走 AdminDeliveryAdvice 会变成 admin-read:/api/ai/v1/... ⇒ 此处立刻红，"
                        + "即 D2 的『不引入 advice 双铸』有判据在盯）")
                .containsExactlyInAnyOrder(
                        "kb.list/kb:list",
                        "kb.read/kb:kb-1",
                        "config.read/ingestion-spec",
                        "document.list/document:search",
                        "document.read/doc:doc-1",
                        "document.read/doc:doc-1",
                        "document.read/doc:doc-1",
                        "document.download/doc:doc-1",
                        "document.list/kb:kb-1");
        // 回执发生在**响应已提交之后**（网关 finally：见 AiGatewayController 的字节分支注释），
        // 而真实 HTTP 下客户端可以在服务端走完 finally 之前就拿到响应 ⇒ 计数要按"收敛"等，
        // 不能按"此刻恰好"读（否则判据会变成随调度抖动的假红）。
        awaitAcknowledgements(permits.size());
        assertThat(acknowledgements)
                .as("字节分支的每个 200 必须恰有一次本地回执（缺则内层没铸头，多则许可泄漏）")
                .hasSize(permits.size());

        int before = permits.size();
        assertJsonOk("POST", "/api/ai/v1/knowledge-base/docs/doc-1/chunk", null, null);
        assertJsonOk("DELETE", "/api/ai/v1/knowledge-base/docs/doc-1", null, null);
        assertJsonOk("PATCH", "/api/ai/v1/knowledge-base/docs/doc-1/enable?value=true", null, null);
        assertThat(permits)
                .as("写面走 JSON 分支：网关不会为它释放许可，内层因此不得铸造（否则逐请求泄漏 ACTIVE 许可）")
                .hasSize(before);
    }

    // ------------------------------------------------------------------ 负例：安全边界

    @Test
    @DisplayName("白名单外形状 404、未登录 401、内部前缀对外 404、docs/search 不被 {docId} 吃掉")
    void unregisteredShapesAndUnloggedRequestsStayHidden() throws Exception {
        assertThat(get("/api/ai/v1/knowledge-base/docs/doc-1/unknown").statusCode())
                .as("多一段即不匹配任何白名单条目").isEqualTo(404);
        assertThat(get("/api/ai/v1/knowledge-base/docs/doc-1/chunks/extra/deep").statusCode()).isEqualTo(404);
        assertThat(post("/api/ai/v1/knowledge-base/docs/search", "{}").statusCode())
                .as("方法不同的同形路径不是白名单条目").isEqualTo(404);

        HttpResponse<String> anonymous = CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/ai/v1/knowledge-base"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).as("未登录 → 401（不是 404）").isEqualTo(401);

        // 段序遮蔽回归：search/schema 是 3 段字面量，若被 docs/{docId} 吃掉，
        // search 会落到"文档详情"语义（此处 detail 替身不返回 f05-hit ⇒ 会红）
        HttpResponse<String> search = get("/api/ai/v1/knowledge-base/docs/search?keyword=f05");
        assertThat(search.statusCode()).isEqualTo(200);
        assertThat(search.body()).contains("f05-hit").doesNotContain("f05-doc-detail");
        verify(documentService).search(eq("f05"), anyInt());
        verify(documentService, never()).get("search");

        HttpResponse<String> internal = get("/internal/ai/v1/knowledge-base");
        assertThat(internal.statusCode()).as("内层前缀不是对外新增面").isEqualTo(404);
    }

    @Test
    @DisplayName("缺 scope 时在 handler 之前 403（读/写/下载是三个独立条件）")
    void facesWithoutTheRequiredScopeAreRejected() throws Exception {
        CURRENT_PERMISSIONS.set(Set.of("ai:kb:list"));
        try {
            assertThat(get("/api/ai/v1/knowledge-base").statusCode())
                    .as("持 ai:kb:list 可读列表").isEqualTo(200);
            assertThat(get("/api/ai/v1/knowledge-base/docs/doc-1").statusCode())
                    .as("文档读需要 ai:document:read").isEqualTo(403);
            assertThat(get("/api/ai/v1/knowledge-base/docs/doc-1/file").statusCode())
                    .as("取文件需要 ai:document:download").isEqualTo(403);
            assertThat(delete("/api/ai/v1/knowledge-base/kb-1").statusCode())
                    .as("删除需要 ai:kb:delete").isEqualTo(403);
            assertThat(post("/api/ai/v1/knowledge-base/kb-1/docs/upload", "{}").statusCode())
                    .as("上传需要 ai:document:upload").isEqualTo(403);
            verify(documentService, never()).get(anyString());
            verify(documentService, never()).delete(anyString());
        } finally {
            CURRENT_PERMISSIONS.set(ALL_PERMISSIONS);
        }
    }

    // ------------------------------------------------------------------ multipart

    private void assertMultipartUploadCarriesTheFilePart() throws Exception {
        byte[] pdf = "%PDF-1.4 f05-upload".getBytes(StandardCharsets.UTF_8);
        String boundary = "F05BOUNDARY";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"f05.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n"
                + new String(pdf, StandardCharsets.UTF_8) + "\r\n"
                + "--" + boundary + "--\r\n";
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port
                                + "/api/ai/v1/knowledge-base/kb-1/docs/upload"))
                        .header("Authorization", "Bearer synthetic-session")
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("multipart 上传必须经网关 200（路由已放行 + 部件经本地传输重建）").isEqualTo(200);
        assertThat(response.body()).contains("\"code\":200").contains("f05-doc-uploaded");

        ArgumentCaptor<MultipartFile> fileCaptor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(documentService).upload(eq("kb-1"), any(), fileCaptor.capture());
        MultipartFile delivered = fileCaptor.getValue();
        assertThat(delivered).as("部件必须原样到达内层 handler（体转发会让它为 null）").isNotNull();
        assertThat(delivered.getOriginalFilename()).isEqualTo("f05.pdf");
        assertThat(delivered.getBytes()).isEqualTo(pdf);
    }

    // ------------------------------------------------------------------ 替身数据

    private static void stubKnowledgeBaseFace() {
        KnowledgeBaseVO detail = new KnowledgeBaseVO();
        detail.setId("kb-1");
        detail.setName("f05-kb-detail");
        when(baseService.queryById(anyString())).thenReturn(detail);

        KnowledgeBaseVO row = new KnowledgeBaseVO();
        row.setId("kb-1");
        row.setName("f05-kb-list");
        Page<KnowledgeBaseVO> page = new Page<>(1, 10);
        page.setRecords(List.of(row));
        page.setTotal(1);
        when(baseService.pageQuery(any())).thenReturn(page);
        when(baseService.create(any())).thenReturn("kb-new");
    }

    private static void stubDocumentFace() {
        KnowledgeDocumentVO document = new KnowledgeDocumentVO();
        document.setId("doc-1");
        document.setKbId("kb-1");
        document.setDocName("f05-doc-detail");
        document.setFileType("pdf");
        document.setFileUrl("kb/f05.pdf");
        when(documentService.get(anyString())).thenReturn(document);
        when(documentService.preview(anyString())).thenReturn("f05-preview");
        KnowledgeDocumentVO uploaded = new KnowledgeDocumentVO();
        uploaded.setId("doc-uploaded");
        uploaded.setKbId("kb-1");
        uploaded.setDocName("f05-doc-uploaded");
        when(documentService.upload(anyString(), any(), any())).thenReturn(uploaded);

        KnowledgeDocumentVO docRow = new KnowledgeDocumentVO();
        docRow.setId("doc-1");
        docRow.setDocName("f05-doc-row");
        Page<KnowledgeDocumentVO> docs = new Page<>(1, 10);
        docs.setRecords(List.of(docRow));
        docs.setTotal(1);
        when(documentService.page(anyString(), any())).thenReturn(docs);

        KnowledgeDocumentSearchVO hit = new KnowledgeDocumentSearchVO();
        hit.setId("hit-1");
        hit.setDocName("f05-hit");
        when(documentService.search(any(), anyInt())).thenReturn(List.of(hit));

        KnowledgeDocumentChunkLogVO log = new KnowledgeDocumentChunkLogVO();
        log.setId("log-1");
        log.setStatus("f05-log");
        Page<KnowledgeDocumentChunkLogVO> logs = new Page<>(1, 10);
        logs.setRecords(List.of(log));
        logs.setTotal(1);
        when(documentService.getChunkLogs(anyString(), any())).thenReturn(logs);

        when(fileStorageService.openStream(anyString()))
                .thenReturn(new ByteArrayInputStream("%PDF-1.4 f05-pdf".getBytes(StandardCharsets.UTF_8)));

        when(schemaProvider.describe()).thenReturn(
                new IngestionSpecSchemaVO("ingestion-spec", List.of(), List.of(), List.of(), -1));
    }

    // ------------------------------------------------------------------ HTTP 辅助

    /**
     * 等回执收敛到期望条数（≤3s）：回执在响应提交之后才发生，真实 HTTP 下客户端可能先返回。
     * 超时即由调用方的 {@code hasSize} 断言给出确定失败（本方法不吞错）。
     */
    private static void awaitAcknowledgements(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (acknowledgements.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /**
     * JSON 面判据：200 + 整数 {@code "code":200} + 非 {@code Result} 的字符串 code +
     * （可选）只可能来自替身服务的独有标记。
     */
    private static void assertJsonOk(String method, String path, String body, String marker) throws Exception {
        HttpResponse<String> response = send(method, path, body, "application/json");
        assertThat(response.statusCode())
                .as("%s %s：404=未登记/503=内层不命中或缺回执/403=scope，都不是通过；实际体=%s",
                        method, path, response.body())
                .isEqualTo(200);
        assertThat(response.body())
                .as("%s %s：必须是整数 code 的信封", method, path)
                .contains("\"code\":200")
                .doesNotContain("\"code\":\"0\"");
        if (marker != null) {
            assertThat(response.body())
                    .as("%s %s：独有标记只可能来自该控制器的返回值", method, path)
                    .contains(marker);
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, null);
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return send("POST", path, body, "application/json");
    }

    private static HttpResponse<String> delete(String path) throws Exception {
        return send("DELETE", path, null, null);
    }

    private static HttpResponse<byte[]> getBytes(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + path))
                        .header("Authorization", "Bearer synthetic-session").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpResponse<String> send(String method, String path, String body, String contentType)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer synthetic-session");
        if (body != null) {
            builder.header("Content-Type", contentType);
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------ fixture

    @Configuration
    @EnableWebMvc
    @Import({AiGatewayController.class, AiWebEmbeddedConfiguration.class, GatewayMultipartConfiguration.class})
    static class FixtureConfig {

        @Bean
        AiIntegrationProperties aiIntegrationProperties() {
            AiIntegrationProperties properties = new AiIntegrationProperties();
            properties.setEnabled(true);
            properties.setTransport("local");
            properties.setForwardTimeoutMillis(2000);
            return properties;
        }

        @Bean
        CurrentPrincipalResolver currentPrincipalResolver() {
            return () -> Optional.of(new CurrentPrincipalResolver.CurrentMember(TENANT, USER, MEMBER));
        }

        @Bean
        PlatformIdentitySource platformIdentitySource() {
            return new PlatformIdentitySource() {
                @Override
                public TenantState tenantState(String tenantId) {
                    return TenantState.ENABLED;
                }

                @Override
                public PlatformIdentity membership(String tenantId, String subject, String membershipId) {
                    if (!MEMBER.equals(membershipId)) {
                        return null;
                    }
                    return new PlatformIdentity(TENANT, USER, MEMBER, true, CURRENT_PERMISSIONS.get(), PV);
                }
            };
        }

        @Bean
        AiIdentityPort aiIdentityPort() {
            return () -> Optional.of(new AiExecutionFacts(TENANT, USER, MEMBER, PV, AV,
                    CURRENT_PERMISSIONS.get()));
        }

        @Bean
        AiDeliveryReleaser aiDeliveryReleaser() {
            return (tenantId, memberId, permitId, operationId) ->
                    acknowledgements.add(permitId + ":" + operationId);
        }

        @Bean
        RevocationGuard revocationGuard() {
            return revocations;
        }

        @Bean
        DeliveryPermits deliveryPermits(ObjectProvider<RevocationGuard> guard) {
            return new DeliveryPermits(guard);
        }

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }

        // ---- 被放行面的两端：真实控制器 + 替身服务（业务语义各有其自己的用例）

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
        KnowledgeBaseController knowledgeBaseController(KnowledgeBaseService service,
                                                        DeliveryPermits permits) {
            return new KnowledgeBaseController(service, permits);
        }

        @Bean
        KnowledgeDocumentController knowledgeDocumentController(KnowledgeDocumentService service,
                                                                FileStorageService storage,
                                                                IngestionSpecSchemaProvider schema,
                                                                DeliveryPermits permits) {
            return new KnowledgeDocumentController(service, storage, schema, permits);
        }
    }
}
