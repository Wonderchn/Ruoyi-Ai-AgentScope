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

package com.nageoffer.ai.ragent.rag.rewrite;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Disabled(
            "本类确实有断言（assertNotNull / rewritten 非空），但同样断的是真实 LLM 输出。" +
            "若要恢复，必须先用 MockWebServer 把 LLM 取代，断言对的应是被测的改写/抽取逻辑。" +
            "真实根因（R-5 / R-4 / RW-28-C2-R8 实测，推翻 R7 的‘统一数据源缺失’结论）：" +
            "本类是 @SpringBootTest 全上下文启动型测试，而测试上下文没有可推断的外部依赖配置。" +
            "分层阻塞链（R-8 在本 reactor 逐层补供后实测）：第1层 Redisson 在 localhost:6379 连接拒绝，" +
            "其中 AI 侧的初始失败点与 platform 侧不同（platform 侧首先报的是 JDBC 数据源），" +
            "说明两个 reactor 的测试上下文缺失的外部依赖不同，但结论一致：数据源不是唯一阻塞。" +
            "其后还有 Milvus 与真实 LLM。" +
            "注意：本类在 -Pci 下因 pom 的 8 条 <exclude> 按名排除而本来就不执行，" +
            "加 @Disabled 不改变 -Pci 构建的任何数字（不增加不减少任何测试计数）。" +
            "严禁移除那 8 条 <exclude>：那会让本类立即失败。" +
            "若将来修正 profile 使其可选执行，本类仍必须「重写为真测试」，" +
            "不能只把 @Disabled 去掉就算恢复。")
public class QueryRewriteTests {

    private final LLMService llmService;
    private final QueryRewriteService defaultQueryRewriteService;

    private static final String QUERY_REWRITE_PROMPT = """
            你是一个“查询改写器（Query Rewriter）”，只用于 RAG 系统的【检索阶段】。
            
            你的唯一目标：
            将用户的自然语言问题，改写成适合向量检索 / 关键字检索的【简洁、连贯的自然语言查询】，只保留与知识库检索相关的关键信息。
            
            【输入】
            - 当前用户问题：一段自然语言
            
            【输出】
            - 仅返回 1 条改写后的查询语句，用于检索知识库
            
            【改写规则】
            1. 只做“查询改写”，不要回答问题，不要规划任务，不要生成步骤或方案。
            2. 改写后的查询必须是**一条完整的自然语言句子**（问句或陈述句均可），而不是若干名词或短语的简单堆砌。
               - ✅ 示例风格（示意）：
                 - “了解公司整体业务架构，以及不同业务线之间的关系，帮助新人快速入门。”
                 - “说明某系统的主要功能，以及与其他相关系统之间的关联。”
               - ❌ 禁止：
                 - “公司业务 新人入门 业务线关系”
            3. 保留 / 强化的内容：
               - 关键实体：公司名、系统名、模块名、功能名、文档名等
               - 关键限制：时间范围、角色身份、终端类型、环境（测试/生产）等
               - 业务场景：如“请假流程”“发票抬头”“接口限流规则”等
            4. 必须删除或忽略的内容：
               - 礼貌用语：如“请帮我看一下”“麻烦详细说明一下”等
               - 面向回答的指令：如“分点回答”“一步一步分析”“给出最佳实践”“帮我规划方案”等
               - 与知识库无关的闲聊、感受、寒暄
               - 关于系统/模型行为的描述：如“你先检索知识库再进行网络搜索”“你的思维链是这样”“你底层会怎么做”等，这些都不要出现在改写后的查询中。
            5. 不要添加原文中没有的新条件、新假设或新需求，不要自行扩展查询范围。
            6. 保持原问题的语言（中文问就用中文，英文问就用英文）。
            7. 如果原问题中存在“这个”“它”“上面提到的流程”等指代，但缺乏足够上下文，请不要胡乱猜测，可以保留指代或用问题中已有的具体词语替换，但不能编造新实体。
            
            【输出格式要求】
            - 只输出改写后的查询句子本身，不要任何解释、前缀或后缀。
            - 不要出现“改写查询为：”“检索语句：”之类说明性文字。
            
            【用户问题】
            %s
            """;

    @ParameterizedTest(name = "QueryRewrite 用例 {index}：{0}")
    @ValueSource(strings = {
            "请帮我查询下直快赔数据安全文档",
            "你底层用的什么模型",
            "OA 系统主要提供哪些功能？测试环境 Redis 地址是多少？数据安全怎么做的？",
            "OA 系统和保险系统主要提供哪些功能？数据安全怎么做的？"
    })
    public void testQueryRewrite(String question) {
        String rewritten = rewriteQuery(question);
        Assertions.assertNotNull(rewritten);
        Assertions.assertFalse(rewritten.isBlank());
    }

    @Test
    public void testQueryTermMapping() {
        defaultQueryRewriteService.rewrite("阿里使用的是钉钉么？");
    }

    /**
     * 小工具方法：封装一次调用，避免每个用例重复代码
     */
    private String rewriteQuery(String userQuestion) {
        String prompt = QUERY_REWRITE_PROMPT.formatted(userQuestion);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .thinking(false)
                .build();

        String rewritten = llmService.chat(request);

        log.info("\n用户问题：{}\n改写查询：{}", userQuestion, rewritten);
        return rewritten;
    }
}
