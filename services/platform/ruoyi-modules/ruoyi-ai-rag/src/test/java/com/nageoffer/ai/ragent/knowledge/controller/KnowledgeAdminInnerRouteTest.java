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

package com.nageoffer.ai.ragent.knowledge.controller;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeBaseService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecSchemaProvider;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * RW-04-R1：知识管理面的<b>类级内层前缀真的生效</b>（真实 DispatcherServlet 映射）。
 *
 * <p>成因是"静态存在 ≠ 运行期可达"：三个控制器原先<b>没有类级 {@code @RequestMapping}</b>，
 * 路径是裸的 {@code /knowledge-base/...}，而
 * {@code DelegatedPrincipalFilter.PROTECTED_PREFIX = "/internal/ai/v1"} —— 裸路径拿不到
 * 委托主体，等于对任何人都不可用。改成类级前缀后必须有人钉住它不被改回去，所以两条独立判据：
 * <ol>
 *   <li><b>注解判据</b>：三个控制器都必须带类级 {@code @RequestMapping("/internal/ai/v1")}；</li>
 *   <li><b>真实映射判据</b>：{@link MockMvc}（真 {@code DispatcherServlet} + 真
 *       {@code RequestMappingHandlerMapping}）逐条请求内层路径，断言<b>不是 404</b>；
 *       同时断言去掉前缀的同形路径<b>就是 404</b>——否则"能通"可能只是兜底路由。</li>
 * </ol>
 *
 * <p>服务层全部替身：本组验的是"路径是否被映射"，业务行为见
 * {@code KnowledgeDocumentVersionTest}/{@code KnowledgeChunkSafetyTest} 等。
 * 状态码断言只区分 404 与非 404：映射成立后，200/400/500 都取决于业务实现，
 * 把它们钉进"路由判据"会让本组在被测业务变化时假失败。
 */
@Tag("dev")
class KnowledgeAdminInnerRouteTest {

    private static final String PREFIX = "/internal/ai/v1";

    private MockMvc mvc;

    private KnowledgeBaseService baseService;
    private KnowledgeDocumentService documentService;
    private KnowledgeChunkService chunkService;
    private FileStorageService fileStorageService;
    private IngestionSpecSchemaProvider schemaProvider;

    @BeforeEach
    void setUp() {
        baseService = mock(KnowledgeBaseService.class);
        documentService = mock(KnowledgeDocumentService.class);
        chunkService = mock(KnowledgeChunkService.class);
        fileStorageService = mock(FileStorageService.class);
        schemaProvider = mock(IngestionSpecSchemaProvider.class);

        KnowledgeDocumentVO document = new KnowledgeDocumentVO();
        document.setId("doc-1");
        document.setDocName("季度报告.pdf");
        document.setFileType("pdf");
        document.setFileUrl("kb/report.pdf");
        // 用 doReturn 而不是 when(...)：when(mock.get(x)) 本身会留下一次调用记录，
        // 后面 "字面量路由没有走到 {docId} 分支" 的 verify(never()) 就会被这次记录污染。
        doReturn(document).when(documentService).get(anyString());
        doReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}))
                .when(fileStorageService).openStream(anyString());

        // GET 列表走网关字节分支 ⇒ 内层要铸交付回执（RW-04-R3 / D2），因此需要真实形状的许可；
        // 主体也必须存在（PrincipalContext.require()），否则那条路由会 500 而不是被映射后的正常响应。
        DeliveryPermits permits = mock(DeliveryPermits.class);
        when(permits.enter(any(), any(), any()))
                .thenReturn(new DeliveryPermits.Permit("permit-1", "operation-1", null));
        PrincipalContext.set(new ExecutionPrincipal("T1", "2101", "platform:T1:2101",
                7, 3, Set.of("ai:document:read"), "jti", "platform", 0, Long.MAX_VALUE));

        mvc = MockMvcBuilders.standaloneSetup(
                new KnowledgeBaseController(baseService, permits),
                new KnowledgeDocumentController(documentService, fileStorageService, schemaProvider, permits),
                new KnowledgeChunkController(chunkService, permits)).build();
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    // ------------------------------------------------------------ 注解判据

    @Test
    @DisplayName("三个知识控制器都带类级内层前缀（不带就等于对任何人不可用）")
    void everyKnowledgeControllerDeclaresTheInnerPrefix() {
        for (Class<?> type : List.of(KnowledgeBaseController.class,
                KnowledgeDocumentController.class, KnowledgeChunkController.class)) {
            RequestMapping mapping = type.getAnnotation(RequestMapping.class);
            assertNotNull(mapping, type.getSimpleName() + " 缺类级 @RequestMapping");
            assertEquals(PREFIX, mapping.value()[0],
                    type.getSimpleName() + " 的类级前缀必须是 " + PREFIX
                            + "：裸路径不在 DelegatedPrincipalFilter 的保护前缀下，拿不到委托主体");
        }
    }

    // ------------------------------------------------------------ 正例：内层路径逐字命中

    @Test
    @DisplayName("知识库面 5 条内层路径逐字命中")
    void knowledgeBaseRoutesAreMappedUnderTheInnerPrefix() throws Exception {
        assertMapped(post(PREFIX + "/knowledge-base").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertMapped(get(PREFIX + "/knowledge-base"));
        assertMapped(get(PREFIX + "/knowledge-base/kb-1"));
        assertMapped(put(PREFIX + "/knowledge-base/kb-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"新名字\"}"));
        assertMapped(delete(PREFIX + "/knowledge-base/kb-1"));
    }

    @Test
    @DisplayName("文档面 12 条内层路径逐字命中（含 multipart 上传与二进制取流）")
    void documentRoutesAreMappedUnderTheInnerPrefix() throws Exception {
        assertMapped(get(PREFIX + "/knowledge-base/kb-1/docs"));
        assertMapped(multipart(PREFIX + "/knowledge-base/kb-1/docs/upload"));
        assertMapped(get(PREFIX + "/knowledge-base/docs/ingestion-spec-schema"));
        assertMapped(get(PREFIX + "/knowledge-base/docs/search").param("keyword", "季度"));
        assertMapped(get(PREFIX + "/knowledge-base/docs/doc-1"));
        assertMapped(put(PREFIX + "/knowledge-base/docs/doc-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"docName\":\"新名字\"}"));
        assertMapped(delete(PREFIX + "/knowledge-base/docs/doc-1"));
        assertMapped(post(PREFIX + "/knowledge-base/docs/doc-1/chunk"));
        assertMapped(patch(PREFIX + "/knowledge-base/docs/doc-1/enable").param("value", "true"));
        assertMapped(get(PREFIX + "/knowledge-base/docs/doc-1/chunk-logs"));
        assertMapped(get(PREFIX + "/knowledge-base/docs/doc-1/preview"));
        assertMapped(get(PREFIX + "/knowledge-base/docs/doc-1/file"));
    }

    @Test
    @DisplayName("分块面 6 条内层路径逐字命中（这是 page-map:56 登记的 API 分母）")
    void chunkRoutesAreMappedUnderTheInnerPrefix() throws Exception {
        assertMapped(get(PREFIX + "/knowledge-base/docs/doc-1/chunks"));
        assertMapped(post(PREFIX + "/knowledge-base/docs/doc-1/chunks")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"内容\"}"));
        assertMapped(put(PREFIX + "/knowledge-base/docs/doc-1/chunks/c1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"内容\"}"));
        assertMapped(delete(PREFIX + "/knowledge-base/docs/doc-1/chunks/c1"));
        assertMapped(patch(PREFIX + "/knowledge-base/docs/doc-1/chunks/c1/enable").param("value", "true"));
        assertMapped(patch(PREFIX + "/knowledge-base/docs/doc-1/chunks/batch-enable")
                .param("value", "true")
                .contentType(MediaType.APPLICATION_JSON).content("{\"chunkIds\":[\"c1\"]}"));
    }

    // ------------------------------------------------------------ 字面量路由优先于 {docId} 模板

    @Test
    @DisplayName("docs/search 与 docs/ingestion-spec-schema 命中字面量方法，不被 {docId} 模板吃掉")
    void literalRoutesWinOverTheDocumentIdTemplate() throws Exception {
        // 段形状相同（/knowledge-base/docs/<一段>）时，Spring 的 RequestMappingHandlerMapping
        // 必须优先字面量；若这里被模板吃掉，search 会退化成"查一个叫 search 的文档"。
        // 网关侧同形风险见报告 §11.4 的顺序要求（ROUTES 按序取首个匹配）。
        mvc.perform(get(PREFIX + "/knowledge-base/docs/search").param("keyword", "季度"));
        verify(documentService).search("季度", 8);
        verify(documentService, never()).get("search");

        mvc.perform(get(PREFIX + "/knowledge-base/docs/ingestion-spec-schema"));
        verify(schemaProvider).describe();
        verify(documentService, never()).get("ingestion-spec-schema");
    }

    // ------------------------------------------------------------ 信封形状判据（RW-04-R3 / D2）

    @Test
    @DisplayName("分块面 6 个方法必须返回整数 code 的 ApiEnvelope（字符串 code 经网关必然 503）")
    void chunkHandlersMustReturnIntegralCodeEnvelope() {
        int checked = 0;
        for (Method method : KnowledgeChunkController.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(GetMapping.class)
                    && !method.isAnnotationPresent(PostMapping.class)
                    && !method.isAnnotationPresent(PutMapping.class)
                    && !method.isAnnotationPresent(DeleteMapping.class)
                    && !method.isAnnotationPresent(PatchMapping.class)) {
                continue;
            }
            checked++;
            Class<?> returned = method.getReturnType();
            // GET 走网关字节分支 ⇒ 必须额外带回执头 ⇒ 用 ResponseEntity 承载（UploadController 同形）
            if (returned == ResponseEntity.class) {
                assertThat(method.getName())
                        .as("只有列表（GET）需要 ResponseEntity 承载回执头，其余应直接返回信封")
                        .isEqualTo("pageQuery");
                assertThat(method.getGenericReturnType().getTypeName())
                        .as("ResponseEntity 的体必须是 ApiEnvelope，不能回退成 Result")
                        .contains("ApiEnvelope");
                continue;
            }
            assertEquals(ApiEnvelope.class, returned,
                    method.getName() + " 的返回类型必须是 ApiEnvelope："
                            + "LocalAiGatewayClient.requireSingleJsonObject 要求 code 是整数，"
                            + "而 Result 的 code 是字符串 \"0\" ⇒ 经网关必然 503");
        }
        assertEquals(6, checked, "分块面应有 6 个映射方法（判据不能空跑）");
    }

    @Test
    @DisplayName("知识库面 5 个方法必须返回整数 code 的 ApiEnvelope（RW-04-R8）")
    void knowledgeBaseHandlersMustReturnIntegralCodeEnvelope() {
        // 与分块面同一条规则，只是这条面在 RW-04-R8 之前还返回 platform Result（字符串 code "0"）。
        // 5 条全部要改：其中 2 条 GET 走网关字节分支，还要用 ResponseEntity 承载回执头。
        assertIntegralEnvelopeShape(KnowledgeBaseController.class, 5, Set.of());
    }

    @Test
    @DisplayName("文档面 11 条 JSON 方法必须返回整数 code 的 ApiEnvelope，裸字节那条单独豁免（RW-04-R8）")
    void documentHandlersMustReturnIntegralCodeEnvelope() {
        // file(...) 直写原始字节（返回 void），不吃 JSON 信封，因此不进信封判据；
        // 它的回执头由 KnowledgeDocumentPrivateDownloadTest#fileShouldCarryDeliveryReceiptHeaders 钉住。
        assertIntegralEnvelopeShape(KnowledgeDocumentController.class, 12, Set.of("file"));
    }

    /**
     * 整数 code 信封形状的通用判据（RW-04-R8）。
     *
     * <p><b>不是恒绿</b>：{@code expectedMappedMethods} 先钉住"扫到了几个方法"，
     * 扫描为空或控制器被掏空都会立刻失败；随后逐条断言返回类型。
     * {@code ResponseEntity} 只允许出现在 GET 上——它承载的是交付回执头，写操作带上它没有意义。
     */
    private static void assertIntegralEnvelopeShape(Class<?> controller, int expectedMappedMethods,
                                                    Set<String> byteStreamMethods) {
        List<Method> mapped = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            if (method.isAnnotationPresent(GetMapping.class)
                    || method.isAnnotationPresent(PostMapping.class)
                    || method.isAnnotationPresent(PutMapping.class)
                    || method.isAnnotationPresent(DeleteMapping.class)
                    || method.isAnnotationPresent(PatchMapping.class)) {
                mapped.add(method);
            }
        }
        assertEquals(expectedMappedMethods, mapped.size(),
                controller.getSimpleName() + " 的映射方法数变了：判据必须钉住分母，否则扫描为空会假绿");

        for (Method method : mapped) {
            if (byteStreamMethods.contains(method.getName())) {
                assertEquals(void.class, method.getReturnType(),
                        method.getName() + " 是裸字节面：必须直写响应，不返回信封");
                assertTrue(method.isAnnotationPresent(GetMapping.class),
                        method.getName() + " 必须是 GET（网关只对 GET 走字节分支并要求回执头）");
                continue;
            }
            if (method.getReturnType() == ResponseEntity.class) {
                assertTrue(method.isAnnotationPresent(GetMapping.class),
                        method.getName() + " 用了 ResponseEntity 但不是 GET：只有 GET 走字节分支才需要承载回执头");
                assertThat(method.getGenericReturnType().getTypeName())
                        .as(method.getName() + " 的 ResponseEntity 体必须是 ApiEnvelope，不能回退成 Result")
                        .contains("ApiEnvelope");
                continue;
            }
            assertEquals(ApiEnvelope.class, method.getReturnType(),
                    method.getName() + " 的返回类型必须是 ApiEnvelope："
                            + "LocalAiGatewayClient.requireSingleJsonObject 要求 code 是整数，"
                            + "而 Result 的 code 是字符串 \"0\" ⇒ 经网关必然 503");
        }
    }

    // ------------------------------------------------------------ 反例：没有前缀就没有映射

    @Test
    @DisplayName("去掉内层前缀的同形路径必须 404（否则'能通'只是兜底路由）")
    void bareLegacyPathsAreNotMapped() throws Exception {
        assertNotMapped(get("/knowledge-base"));
        assertNotMapped(get("/knowledge-base/kb-1"));
        assertNotMapped(get("/knowledge-base/kb-1/docs"));
        assertNotMapped(get("/knowledge-base/docs/doc-1"));
        assertNotMapped(get("/knowledge-base/docs/doc-1/chunks"));
        assertNotMapped(patch("/knowledge-base/docs/doc-1/chunks/batch-enable").param("value", "true"));
    }

    // ------------------------------------------------------------ 夹具

    private void assertMapped(RequestBuilder builder) throws Exception {
        int status = mvc.perform(builder).andReturn().getResponse().getStatus();
        assertNotEquals(404, status,
                "内层路径未被映射（404）：" + builder + " —— 类级前缀或方法映射没生效");
    }

    private void assertNotMapped(RequestBuilder builder) throws Exception {
        int status = mvc.perform(builder).andReturn().getResponse().getStatus();
        assertEquals(404, status,
                "裸路径 " + builder + " 不该有映射：它不在委托主体保护前缀下，通了反而危险");
    }
}
