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

package com.nageoffer.ai.ragent.rag.core.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.3b Milvus 标量过滤表达式契约（不需要任何 Milvus 服务，直接调用包内静态方法）。
 *
 * <p>所有逻辑库共用同一个物理 collection，标量过滤表达式就是唯一的隔离边界。
 * 本用例锁死四件事：
 * <ol>
 *   <li>表达式<b>永远</b>以租户条件开头；</li>
 *   <li>collection 列表为 null/空时不得退化成"没有条件"；</li>
 *   <li>租户缺失/空白时不得退化成"没有租户条件"；</li>
 *   <li>库名/租户名里的 {@code "} 与 {@code \} 必须转义，否则可以改写整个表达式。</li>
 * </ol>
 */
class P1MilvusFilterTest {

    private static final String TENANT = "T1";

    /** 租户子句必须出现在表达式最前面：任何输入下都不允许被后置或省略。 */
    private static final String TENANT_CLAUSE = "tenant_id == \"T1\"";

    // ------------------------------------------------------------ 正常路径

    @Test
    @DisplayName("单库：租户条件在前、库名精确匹配（精确串断言）")
    void singleCollectionFilterCarriesTenantAndExactCollection() {
        String filter = MilvusVectorRetrieverService.buildFilter(TENANT, List.of("kb_a"));

        assertEquals("tenant_id == \"T1\" and collection_name == \"kb_a\"", filter);
        assertTrue(filter.contains("tenant_id"), "过滤表达式必须包含租户条件，实际=" + filter);
        assertTrue(filter.contains("kb_a"), "过滤表达式必须包含被授权的逻辑库名，实际=" + filter);
        assertTrue(filter.startsWith(TENANT_CLAUSE), "租户条件必须位于表达式最前面，实际=" + filter);
    }

    @Test
    @DisplayName("多库：使用 in [...] 形式，且仍然带租户条件")
    void multipleCollectionsUseInFormWithTenant() {
        String filter = MilvusVectorRetrieverService.buildFilter(TENANT, List.of("kb_a", "kb_b"));

        assertEquals("tenant_id == \"T1\" and collection_name in [\"kb_a\", \"kb_b\"]", filter);
        assertTrue(filter.contains("in ["), "多库必须走 in [...] 形式，实际=" + filter);
        assertTrue(filter.contains("\"kb_a\", \"kb_b\""), "多库顺序应与入参一致，实际=" + filter);
        assertTrue(filter.contains("tenant_id"), "多库形式同样必须带租户条件，实际=" + filter);
        assertTrue(filter.startsWith(TENANT_CLAUSE), "租户条件必须位于表达式最前面，实际=" + filter);
    }

    // ------------------------------------------------------------ 历史缺陷：空列表不得变成"无过滤"

    @Test
    @DisplayName("空 collection 列表：绝不返回 null，必须是带租户条件的恒假表达式")
    void emptyCollectionListMustNotReturnNullFilter() {
        // 历史缺陷（必须永远不再出现）：旧的 buildCollectionFilter 在 collection 列表为空时返回 null，
        // 调用方随即省略 filter(...)。Milvus 的语义是"没有 filter 就没有过滤"，于是
        // "没有条件"被当成了"没有限制"——一次请求就能把共享物理 collection 里所有租户的数据全部扫出来。
        // 这个用例就是那条缺陷的墓碑：返回值必须非 null、必须仍带租户条件、且必须是恒假条件。
        String filter = MilvusVectorRetrieverService.buildFilter(TENANT, List.of());

        assertNotNull(filter, "空 collection 列表不得返回 null：null 会被调用方当成“无过滤”，等于查全库");
        assertFalse(filter.isBlank(), "空列表时必须给出恒假表达式，而不是空/空白字符串");
        assertTrue(filter.contains("tenant_id"), "即使列表为空也仍必须带租户条件，实际=" + filter);
        assertTrue(filter.startsWith(TENANT_CLAUSE), "租户条件必须位于表达式最前面，实际=" + filter);
        // 恒假：collection_name 被约束为空字符串，正常写入的行不可能满足该条件
        assertTrue(filter.contains("collection_name == \"\""),
                "列表为空时必须退化为恒假条件（collection_name == \"\"），实际=" + filter);
        assertEquals("tenant_id == \"T1\" and collection_name == \"\"", filter);
    }

    @Test
    @DisplayName("collection 列表为 null：与空列表同语义，同样不得返回无租户条件的表达式")
    void nullCollectionListMustNotReturnNullFilter() {
        String filter = MilvusVectorRetrieverService.buildFilter(TENANT, null);

        assertNotNull(filter, "null 列表同样不得返回 null：那等于“无过滤 = 查全库”");
        assertFalse(filter.isBlank(), "null 列表必须给出恒假表达式，而不是空/空白字符串");
        assertTrue(filter.contains("tenant_id"), "null 列表也必须带租户条件，实际=" + filter);
        assertTrue(filter.startsWith(TENANT_CLAUSE), "租户条件必须位于表达式最前面，实际=" + filter);
        assertTrue(filter.contains("collection_name == \"\""), "null 列表必须退化为恒假条件，实际=" + filter);
    }

    // ------------------------------------------------------------ 租户缺失

    @Test
    @DisplayName("租户缺失/空白：退化成 tenant_id == \"\" 的恒假条件，绝不产生“没有租户条件”的表达式")
    void missingTenantNeverProducesTenantLessFilter() {
        // 租户缺失说明调用链没把授权主体的租户带下来。正确行为是"查不到任何行"，而不是"少一个条件"：
        // ExecutionPrincipal.requireTenantId 要求租户非空白，因此真实数据里不存在 tenant_id = "" 的行，
        // 该条件必定不匹配——这是"拒绝"，不是"放宽"。
        for (String tenant : new String[] {null, "", "   "}) {
            String filter = MilvusVectorRetrieverService.buildFilter(tenant, List.of("kb_a"));

            assertNotNull(filter, "tenant=" + tenant + " 时不得返回 null（等于查全库）");
            assertFalse(filter.isBlank(), "tenant=" + tenant + " 时不得返回空/空白表达式");
            assertTrue(filter.contains("tenant_id"), "tenant=" + tenant + " 时仍必须带租户条件，实际=" + filter);
            assertTrue(filter.startsWith("tenant_id == \"\""),
                    "tenant=" + tenant + " 时必须退化成空租户的恒假条件，实际=" + filter);
            assertEquals("tenant_id == \"\" and collection_name == \"kb_a\"", filter,
                    "tenant=" + tenant + " 时表达式形状必须是“空租户 + 精确库名”");
        }
    }

    // ------------------------------------------------------------ 转义

    @Test
    @DisplayName("库名含引号：原始引号不得以未转义形式进入表达式，不能改写过滤条件")
    void quoteInCollectionNameIsEscaped() {
        String evilName = "kb\"evil";

        // escapeFilterValue 的直接契约：引号以 \" 形式转义
        assertEquals("kb\\\"evil", MilvusVectorRetrieverService.escapeFilterValue(evilName));
        assertEquals("", MilvusVectorRetrieverService.escapeFilterValue(null), "null 不能变成字面量 \"null\"");

        String filter = MilvusVectorRetrieverService.buildFilter(TENANT, List.of(evilName));

        assertEquals("tenant_id == \"T1\" and collection_name == \"kb\\\"evil\"", filter);
        assertTrue(filter.startsWith(TENANT_CLAUSE), "租户条件必须位于表达式最前面，实际=" + filter);
        assertTrue(filter.contains("\"kb\\\"evil\""), "库名必须以转义形式出现，实际=" + filter);
        // 未转义的原始库名会把 collection_name 的字符串字面量提前闭合，后面的字符变成表达式语法
        assertFalse(filter.contains("\"kb\"evil\""), "出现未转义的引号说明表达式可被库名改写，实际=" + filter);
        // 解析出的最后一个字面量必须原样等于转义后的库名（内部引号没有逃出字面量）
        assertEquals(MilvusVectorRetrieverService.escapeFilterValue(evilName), lastStringLiteralBody(filter),
                "库名字面量必须闭合且内容不被改写，实际=" + filter);
    }

    @Test
    @DisplayName("库名含反斜杠：必须双写，且引号转义不被反斜杠吞掉")
    void backslashInCollectionNameIsDoubled() {
        String backslashName = "kb\\evil"; // 实际字符：kb\evil

        assertEquals("kb\\\\evil", MilvusVectorRetrieverService.escapeFilterValue(backslashName),
                "反斜杠必须双写，否则会与后续的转义序列重新组合");

        String filter = MilvusVectorRetrieverService.buildFilter(TENANT, List.of(backslashName));

        assertEquals("tenant_id == \"T1\" and collection_name == \"kb\\\\evil\"", filter);
        assertTrue(filter.contains("collection_name == \"kb\\\\evil\""),
                "库名必须以双写反斜杠的形式出现，实际=" + filter);
        assertEquals(MilvusVectorRetrieverService.escapeFilterValue(backslashName), lastStringLiteralBody(filter),
                "库名字面量必须闭合且内容不被改写，实际=" + filter);
    }

    @Test
    @DisplayName("反斜杠 + 引号组合：转义后不能被解析成提前闭合的字面量")
    void backslashBeforeQuoteDoesNotEscapeTheDelimiter() {
        // 名字以反斜杠结尾时，未经双写的反斜杠会把后面的闭合引号"转义"掉，让字面量吞掉后续表达式
        String trickyName = "kb\\";

        String filter = MilvusVectorRetrieverService.buildFilter(TENANT, List.of(trickyName));

        assertEquals("tenant_id == \"T1\" and collection_name == \"kb\\\\\"", filter);
        assertEquals(MilvusVectorRetrieverService.escapeFilterValue(trickyName), lastStringLiteralBody(filter),
                "以反斜杠结尾的库名不得吞掉闭合引号，实际=" + filter);
    }

    @Test
    @DisplayName("租户名含引号：同样必须转义，不能只转义库名")
    void quoteInTenantIsEscaped() {
        String tenant = "T1\"x";

        assertEquals("T1\\\"x", MilvusVectorRetrieverService.escapeFilterValue(tenant));

        String filter = MilvusVectorRetrieverService.buildFilter(tenant, List.of("kb_a"));

        assertEquals("tenant_id == \"T1\\\"x\" and collection_name == \"kb_a\"", filter);
        assertTrue(filter.startsWith("tenant_id == \"T1\\\"x\""),
                "租户条件的字面量必须被转义且位于最前面，实际=" + filter);
        assertFalse(filter.contains("tenant_id == \"T1\"x\""),
                "出现未转义的租户引号说明表达式可被租户名改写，实际=" + filter);
    }

    // ------------------------------------------------------------ 全输入矩阵

    @Test
    @DisplayName("全输入组合：非 null 非空白的返回值必须以租户条件开头（防止未来后置或丢掉租户条件）")
    void everyReturnedFilterStartsWithTenantClause() {
        List<List<String>> collectionCases = List.of(
                List.<String>of(),
                List.of("kb_a"),
                List.of("kb_a", "kb_b"),
                List.of("kb\"evil"));
        String[] tenantCases = {null, "", "   ", TENANT};

        for (String tenant : tenantCases) {
            String expectedTenantClause = "tenant_id == \""
                    + MilvusVectorRetrieverService.escapeFilterValue(tenant == null || tenant.isBlank() ? "" : tenant)
                    + "\"";
            for (List<String> collections : collectionCases) {
                String filter = MilvusVectorRetrieverService.buildFilter(tenant, collections);

                assertNotNull(filter, "tenant=" + tenant + " collections=" + collections + " 返回了 null");
                assertFalse(filter.isBlank(), "tenant=" + tenant + " collections=" + collections + " 返回了空白表达式");
                assertTrue(filter.startsWith(expectedTenantClause),
                        "tenant=" + tenant + " collections=" + collections + " 的表达式没有以租户条件开头，实际=" + filter);
            }
        }
    }

    // ------------------------------------------------------------ 辅助

    /**
     * 取出表达式里最后一个字符串字面量的内容，用于证明"库名没有逃出自己的字面量"。
     *
     * <p>做法是正向扫描出所有<b>未被反斜杠转义</b>的引号：转义正确时它们恰好是各字面量的
     * 开闭引号，最后两个定界的就是库名字面量；如果库名里的引号没有转义，它自己会被当成
     * 定界引号，取出的内容就不再等于转义后的原值——这正是本方法要抓的缺陷。
     */
    private static String lastStringLiteralBody(String expression) {
        assertNotNull(expression, "表达式不得为 null");
        List<Integer> delimiters = new ArrayList<>();
        int backslashes = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '\\') {
                backslashes++;
                continue;
            }
            if (c == '"' && backslashes % 2 == 0) {
                delimiters.add(i);
            }
            backslashes = 0;
        }
        assertTrue(delimiters.size() >= 2, "表达式里没有成对的字符串字面量，实际=" + expression);
        int open = delimiters.get(delimiters.size() - 2);
        int close = delimiters.get(delimiters.size() - 1);
        return expression.substring(open + 1, close);
    }
}
