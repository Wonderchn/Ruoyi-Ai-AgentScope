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

import com.nageoffer.ai.ragent.rag.core.rewrite.MultiQuestionRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Disabled(
            "本类确实有断言（assertNotNull / rewrittenQuestion 非空 / 子问题非空 以及‘子问题覆盖淘宝和天猫’），但它断言的是真实 LLM 的输出，在 CI 上天然不确定。" +
            "若要恢复，必须先用 MockWebServer 把 LLM 取代，把断言改为对被测解析/切分逻辑的断言，而不是对外部模型输出的断言。" +
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
public class MultiQuestionRewriteServiceTests {

    private final MultiQuestionRewriteService multiQuestionRewriteService;

    @Test
    public void shouldReturnRewriteAndSubQuestions() {
        String question = "你好呀，淘宝和天猫数据安全怎么做的？";

        RewriteResult result = multiQuestionRewriteService.rewriteWithSplit(question);

        Assertions.assertNotNull(result);
        Assertions.assertNotNull(result.rewrittenQuestion());
        Assertions.assertFalse(result.rewrittenQuestion().isBlank());

        List<String> subs = result.subQuestions();
        Assertions.assertNotNull(subs);
        Assertions.assertFalse(subs.isEmpty());
        Assertions.assertTrue(subs.stream().allMatch(s -> s != null && !s.isBlank()));
        boolean hasTaobao = subs.stream().anyMatch(s -> s.contains("淘宝"));
        boolean hasTmall = subs.stream().anyMatch(s -> s.contains("天猫"));
        Assertions.assertTrue(hasTaobao && hasTmall, "期望子问题能覆盖并列主体：淘宝和天猫");
    }
}
