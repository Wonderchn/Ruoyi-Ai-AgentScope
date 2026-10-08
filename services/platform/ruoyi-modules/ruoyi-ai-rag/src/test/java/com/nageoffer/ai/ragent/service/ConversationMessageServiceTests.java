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

package com.nageoffer.ai.ragent.service;

import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Disabled(
            "本类断言数为 0（grep -c assert 实测），并非自动化测试，而是手工联调脚本：仅调 conversationMessageService.listMessages(...) 并 System.out 打印结果。" + 
            "它还需要一个包含会话/消息/反馈表的完整冻结迁移后数据库。" + 
            "真实根因（R-4 / RW-28-C2-R8 实测，推翻 R7 的‘数据源缺失’结论）：" + 
            "测试配置 ruoyi-modules/ruoyi-ai-rag/src/test/resources/application.yaml 只 import 了 " + 
            "file:${user.dir}/../bootstrap/src/main/resources/application.yaml，而 platform reactor 不存在 bootstrap 模块 " + 
            "（find -type d -name bootstrap 仅命中 docs/script/sql/postgres/bootstrap，那是 SQL 目录），" + 
            "optional import 全部落空 ⇒ 测试上下文没有任何 spring 配置。" + 
            "分层阻塞链（R-4 实测，逐层补供后失败点整体前移）：" + 
            "第1层 JDBC 数据源 Failed to determine a suitable driver class（注意：ruoyi-ai-rag/pom.xml 已声明 " + 
            "org.postgresql:postgresql，不是缺驱动 jar，而是测试上下文无 URL 可推断）；" + 
            "补上后第2层 StorageClientConfig.s3Client() 因 rag.storage.* 全为 null 抛 NPE；" + 
            "补上后第3层 Redisson/Redis localhost:6379 Connection refused；" + 
            "其后还有 Milvus 与真实 LLM。" + 
            "注意：本类在默认 -Pdev 下因 pom 的 <groups>${profiles.active}</groups>=dev 而本来就不被选中执行，" + 
            "加 @Disabled 不改变默认构建的任何数字（不增加不减少任何测试计数）。" + 
            "若将来修正 profile 使其可选执行，本类仍必须「重写为真测试」，" + 
            "不能只把 @Disabled 去掉就算恢复。")
public class ConversationMessageServiceTests {

    private final ConversationMessageService conversationMessageService;

    @Test
    public void listMessagesTest() {
        List<ConversationMessageVO> conversationMessageVOList = conversationMessageService.listMessages("2002713020947939330", "admin", 6, ConversationMessageOrder.DESC);
        log.info("conversationMessageVOList: {}", JSONUtil.toJsonStr(conversationMessageVOList));
    }
}
