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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.ingestion.service.IntentTreeService;
import com.nageoffer.ai.ragent.rag.dto.RecommendedQuestionsPayload;
import com.nageoffer.ai.ragent.rag.service.QueryTermMappingAdminService;
import com.nageoffer.ai.ragent.rag.service.RecommendedQuestionService;
import com.nageoffer.ai.ragent.sample.controller.SampleQuestionController;
import com.nageoffer.ai.ragent.sample.service.SampleQuestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * RW-22-R1：意图 / 词映射 / 推荐 / 示例四控制器的**内层前缀归位**与**信封形状**判据。
 *
 * <p>两条独立判据，缺一不可：
 * <ol>
 *   <li><b>路径归位</b>：类级 {@code @RequestMapping("/internal/ai/v1...")} 真的生效（真
 *       {@code DispatcherServlet} + 真 {@code RequestMappingHandlerMapping} 逐条请求内层路径，
 *       断言<b>不是 404</b>），且**去掉前缀的同形裸路径就是 404**——否则"能通"可能只是兜底路由。
 *       归位前这些控制器是裸根路径，不在 {@code DelegatedPrincipalFilter.PROTECTED_PREFIX}
 *       之下 ⇒ 拿不到委托主体（与 WP-034 / RW-04-R1 同形缺陷）；</li>
 *   <li><b>信封形状</b>：成功响应必须是<b>整数</b> {@code code}（{@code ApiEnvelope}）。
 *       平台 {@code Result} 的字符串 {@code code="0"} 会被网关
 *       {@code LocalAiGatewayClient.requireSingleJsonObject} 判成"缺少包络 code"并收敛为
 *       <b>503</b>（本项目两次实测定案）⇒ "只归位不换信封 = 放行了但不可用"。
 *       本判据直接断言 JSON 里 {@code code} 是<b>数字</b>且等于 200，把这条钉死。</li>
 * </ol>
 *
 * <p>服务层全部替身：本组验的是"路径是否被映射 + 信封是什么形状"，业务行为不在本组。
 * 状态码断言只区分 404 与非 404（映射成立后 200/400/500 取决于业务实现，把它们钉进
 * 路由判据会让本组在被测业务变化时假失败）。
 */
@Tag("dev")
class IntentAdminInnerRouteTest {

    private static final String PREFIX = "/internal/ai/v1";
    private static final String TENANT = "t1";
    private static final String MEMBER = "platform:t1:1001";
    private static final String USER = "1001";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        IntentTreeService intentTreeService = mock(IntentTreeService.class);
        when(intentTreeService.getFullTree()).thenReturn(List.of());
        when(intentTreeService.createNode(any())).thenReturn("node-1");

        QueryTermMappingAdminService mappings = mock(QueryTermMappingAdminService.class);

        RecommendedQuestionService recommended = mock(RecommendedQuestionService.class);
        when(recommended.generate(anyString(), anyString()))
                .thenReturn(RecommendedQuestionsPayload.empty());

        SampleQuestionService samples = mock(SampleQuestionService.class);
        when(samples.listRandomQuestions(org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

        mvc = MockMvcBuilders.standaloneSetup(
                new IntentTreeController(intentTreeService),
                new QueryTermMappingController(mappings),
                new RecommendedQuestionController(recommended),
                new SampleQuestionController(samples)).build();

        PrincipalContext.set(new ExecutionPrincipal(TENANT, USER, MEMBER, 1, 1, Set.of(),
                "jti", "platform", 1_700_000_000L, 1_700_000_060L));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    // ------------------------------------------------------------ ① 路径归位

    @Test
    @DisplayName("四个控制器都必须带类级 /internal/ai/v1 前缀（注解判据，防被改回裸路径）")
    void everyControllerCarriesTheInternalPrefix() {
        for (Class<?> type : List.of(IntentTreeController.class, QueryTermMappingController.class,
                RecommendedQuestionController.class, SampleQuestionController.class)) {
            RequestMapping mapping = type.getAnnotation(RequestMapping.class);
            assertNotNull(mapping, type.getSimpleName() + " 缺类级 @RequestMapping（会落回裸根路径）");
            assertTrue(mapping.value().length > 0, type.getSimpleName() + " 的 @RequestMapping 没有路径");
            assertTrue(mapping.value()[0].startsWith(PREFIX),
                    type.getSimpleName() + " 的类级前缀不在 " + PREFIX + " 之下：" + mapping.value()[0]);
        }
    }

    @Test
    @DisplayName("意图树：内层路径全部被映射（不是 404）")
    void intentTreeIsMappedUnderTheInternalPrefix() throws Exception {
        assertMapped(get(PREFIX + "/intent-tree/trees"));
        assertMapped(post(PREFIX + "/intent-tree").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertMapped(put(PREFIX + "/intent-tree/n1").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertMapped(delete(PREFIX + "/intent-tree/n1"));
        assertMapped(post(PREFIX + "/intent-tree/batch/enable").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[]}"));
        assertMapped(post(PREFIX + "/intent-tree/batch/disable").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[]}"));
        assertMapped(post(PREFIX + "/intent-tree/batch/delete").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[]}"));
    }

    @Test
    @DisplayName("词映射 / 推荐 / 示例：内层路径全部被映射（不是 404）")
    void otherThreeSurfacesAreMappedUnderTheInternalPrefix() throws Exception {
        assertMapped(get(PREFIX + "/mappings"));
        assertMapped(get(PREFIX + "/mappings/m1"));
        assertMapped(post(PREFIX + "/mappings").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertMapped(put(PREFIX + "/mappings/m1").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertMapped(delete(PREFIX + "/mappings/m1"));
        assertMapped(post(PREFIX + "/conversations/messages/msg-1/recommended-questions"));
        assertMapped(get(PREFIX + "/sample-questions/random").param("limit", "3"));
        assertMapped(get(PREFIX + "/sample-questions"));
        assertMapped(get(PREFIX + "/sample-questions/sq-1"));
    }

    @Test
    @DisplayName("裸路径（归位前的形状）必须 404 —— 它不在委托主体保护前缀下，通了反而危险")
    void bareLegacyPathsAreNotMapped() throws Exception {
        assertNotMapped(get("/intent-tree/trees"));
        assertNotMapped(post("/intent-tree").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertNotMapped(get("/mappings"));
        assertNotMapped(post("/conversations/messages/msg-1/recommended-questions"));
        assertNotMapped(get("/sample-questions/random").param("limit", "3"));
        assertNotMapped(get("/sample-questions"));
    }

    // ------------------------------------------------------------ ② 信封形状（整数 code）

    @Test
    @DisplayName("成功响应的 code 必须是整数 200（字符串 \"0\" 会被网关收敛成 503）")
    void successEnvelopeCarriesAnIntegerCode() throws Exception {
        for (RequestBuilder builder : List.of(
                get(PREFIX + "/intent-tree/trees"),
                get(PREFIX + "/mappings"),
                post(PREFIX + "/conversations/messages/msg-1/recommended-questions"),
                get(PREFIX + "/sample-questions/random").param("limit", "3"),
                get(PREFIX + "/sample-questions"))) {
            String body = mvc.perform(builder).andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("\"code\":200"),
                    "成功响应必须是整数 code=200 的包络，实际：" + body);
            assertTrue(!body.contains("\"code\":\"0\""),
                    "不得再出现平台 Result 的字符串 code=\"0\"，实际：" + body);
        }
    }

    @Test
    @DisplayName("原本 void 的写方法也返回整数 code 包络（同一控制器不分裂成两种成功形状）")
    void previouslyVoidWritesAlsoReturnAnEnvelope() throws Exception {
        String updated = mvc.perform(put(PREFIX + "/intent-tree/n1")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(updated.contains("\"code\":200"), "更新节点应返回整数 code 包络，实际：" + updated);

        String deleted = mvc.perform(delete(PREFIX + "/mappings/m1"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(deleted.contains("\"code\":200"), "删除映射应返回整数 code 包络，实际：" + deleted);
    }

    @Test
    @DisplayName("推荐追问：身份取自 PrincipalContext（内嵌态 UserContext 恒为空 ⇒ 传 null 只会查不到）")
    void recommendedQuestionsUseTheCanonicalPrincipal() throws Exception {
        RecommendedQuestionService service = mock(RecommendedQuestionService.class);
        when(service.generate(anyString(), anyString())).thenReturn(RecommendedQuestionsPayload.empty());
        MockMvc local = MockMvcBuilders.standaloneSetup(new RecommendedQuestionController(service)).build();

        local.perform(post(PREFIX + "/conversations/messages/msg-9/recommended-questions"));

        org.mockito.Mockito.verify(service).generate("msg-9", USER);
    }

    @Test
    @DisplayName("推荐追问：缺主体即拒绝（不退化成无用户限定的查询）")
    void recommendedQuestionsRefuseWithoutPrincipal() {
        RecommendedQuestionService service = mock(RecommendedQuestionService.class);
        MockMvc local = MockMvcBuilders.standaloneSetup(new RecommendedQuestionController(service)).build();
        PrincipalContext.clear();

        try {
            local.perform(post(PREFIX + "/conversations/messages/msg-9/recommended-questions"));
        } catch (Exception expected) {
            // PrincipalContext.require() 的 ClientException 由框架冒泡；关键断言是"没有调用服务"
        }
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).generate(anyString(), anyString());
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
