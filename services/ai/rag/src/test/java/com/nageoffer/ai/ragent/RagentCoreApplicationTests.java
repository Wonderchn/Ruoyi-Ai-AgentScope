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

package com.nageoffer.ai.ragent;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled(
            "本类断言数为 0：测试体是空体 void contextLoads() {}，它只意味着‘上下文能启动’，而启动本身需要上述整条外部依赖链。" +
            "属于最不值得单独为之单绿的一类：它的‘绿’会变成一个需要整套外部服务才能达到的声明。" +
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
class RagentCoreApplicationTests {

    @Test
    void contextLoads() {
    }

}
