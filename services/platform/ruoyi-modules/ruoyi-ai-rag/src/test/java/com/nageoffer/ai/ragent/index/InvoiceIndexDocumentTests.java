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

package com.nageoffer.ai.ragent.index;

import cn.hutool.core.util.IdUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.AuthorizedResourceScope;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Disabled(
            "本类断言数为 0（grep -c assert 实测）；源码自述为「手工联调用例」，需真实向量库 + 真实 LLM（源码读取这个文件是一个已编译资产）。" + 
            "注意 testSplit 这个用例其实不需要任何外部服务，但它同样没有一条断言，因此它也不构成覆盖；若要保留这段逻辑，应重写为不需外部依赖的纯单元测试。" + 
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
public class InvoiceIndexDocumentTests {

    private final LLMService llmService;
    private final EmbeddingService embeddingService;
    private final MilvusClientV2 milvusClient;
    private final VectorRetrieverService retrieverService;
    private final RAGDefaultProperties ragDefaultProperties;

    private final Tika tika = new Tika();

    @Test
    void indexDocument() throws TikaException, IOException {
        String filePath = "src/main/resources/file/group/group-finance/开票信息.md";
        String actualDocument = extractText(filePath);
        System.out.println(actualDocument);
        List<String> chunks = splitIntoLineChunks(actualDocument, 5);

        String docId = UUID.randomUUID().toString();
        List<JsonObject> rows = buildRowsForChunks(
                docId,
                chunks
        );

        InsertReq req = InsertReq.builder()
                .collectionName(ragDefaultProperties.getCollectionName())
                .data(rows)
                .build();

        InsertResp resp = milvusClient.insert(req);
        log.info("Indexed file document. documentId={},  chunks={}, insertCnt={}", docId, chunks.size(), resp.getInsertCnt());
    }

    @Test
    public void chatLlmQuery() {
        String question = "阿里发票抬头";
        // 手工联调用例（需要真实向量库与模型）：作用域按"默认逻辑库已授权"构造，
        // 不带 collectionNames 表示仍限于已授权集合，而不是查全库
        List<RetrievedChunk> retrievedChunks = retrieverService.retrieve(liveScope(),
                RetrieveRequest.builder()
                        .query(question)
                        .topK(5)
                        .build());

        if (retrievedChunks == null || retrievedChunks.isEmpty()) {
            System.out.println("未检索到与问题相关的文档内容，请尝试换一个问法。");
            return;
        }

        String context = retrievedChunks.stream()
                .map(h -> "- " + h.getText())
                .collect(Collectors.joining("\n"));

        String prompt = """
                你是专业的企业发票信息查询助手，现在根据【文档内容】回答用户关于开票信息的问题。
                
                请严格遵守以下规则：
                
                【回答格式规则】
                1. 回答必须严格基于【文档内容】，不得虚构任何信息。
                2. 如果查询结果只有一个公司，请输出“单条发票信息”的完整格式化内容。
                3. 如果查询到多个公司，请输出 “发票信息列表”，列表中每一项都是完整的一段发票信息，不使用零散的分点描述。
                4. 每条发票信息必须按如下统一格式输出：
                
                开票抬头：xxx
                纳税资质：xxx
                纳税人识别号：xxx
                地址、电话：xxx
                开户银行、账号：xxx
                
                5. 字段有缺失时必须保留字段名并标注“文档未提供该字段”。
                6. 如果文档内没有与用户问题相关的企业，请回答：“文档未包含相关信息。”
                
                【文档内容】
                %s
                
                【用户问题】
                %s
                """.formatted(context, question);

        ChatRequest req = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .thinking(false)
                .temperature(0D)
                .topP(0.7D)
                .build();

        String chat = llmService.chat(req);
        System.out.println(chat);
    }

    /**
     * 手工联调用的授权作用域：默认逻辑库视为已授权。
     * <p>
     * 不是 {@code denied()}——空作用域按新契约会直接返回空集，联调用例就查不到任何东西了。
     */
    private AuthorizedRetrievalScope liveScope() {
        String collection = ragDefaultProperties.getCollectionName();
        List<String> collections = List.of(collection);
        ExecutionPrincipal principal = new ExecutionPrincipal("tenant-local", "1001",
                ExecutionPrincipal.canonicalMembershipId("tenant-local", "1001"),
                1, 1, Set.of(), "jti-live-1", "live-test", 0L, 0L);
        AuthorizedResourceScope resourceScope = AuthorizedResourceScope.granted(
                principal, "kb.retrieve", collections, System.currentTimeMillis());
        return AuthorizedRetrievalScope.of(resourceScope, collections, List.of(), List.of(), collections);
    }

    private String extractText(String filePath) throws TikaException, IOException {
        Path path = Paths.get(filePath);
        Metadata metaData = new Metadata();
        String fileContent = tika.parseToString(Files.newInputStream(path), metaData);

        String prompt = """
                你是一名专业的企业文档清洗助手，负责从 Markdown 文档中提取“有用的业务字段”，并清除以下所有噪声内容：
                
                【需要删除的内容】
                - 所有 Markdown 标记：###、---、:::、```、> 等
                - 所有主题标题、装饰性标题、空白标题
                - 所有无意义分隔符（---、***）
                - 所有多余空行
                - 无意义的提示/说明性文字
                
                【需要保留的内容】
                - 真实业务信息，例如：
                  - 开票抬头
                  - 纳税人识别号
                  - 银行账号
                  - 公司地址与电话
                  - 其他结构化字段
                
                【输出要求】
                1. 仅输出清洗后的核心内容
                2. 保留原始字段结构（例如“开票抬头：xxx”）
                3. 不要添加虚构信息
                4. 不要改变字段顺序
                5. 不要解释，只输出清洗后的文本
                6. 多个发票之间不允许有空行
                
                【需要处理的原文】：
                %s
                """
                .formatted(fileContent);
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .build();
        return llmService.chat(req);
    }

    @Test
    public void testSplit() {
        String text = """
                开票抬头：杭州阿里巴巴健康集团有限公司 \s
                纳税资质：小规模纳税人 \s
                纳税人识别号：91330108MA9A1B2C3X \s
                地址、电话：浙江省杭州市西湖区留和路200号A座801室  0571-88001101 \s
                开户银行、账号：中信银行杭州钱江支行 8110001000000000001 \s
                
                开票抬头：杭州阿里巴巴健康科技有限公司 \s
                纳税资质：一般纳税人 \s
                纳税人识别号：91330108MA4D5E6F7Y \s
                地址、电话：浙江省杭州市西湖区紫荆花北路8号1幢1203室  0571-88001102 \s
                开户银行、账号：中信银行杭州钱江支行 8110001000000000002 \s
                
                开票抬头：杭州腾讯网络科技有限公司 \s
                纳税资质：一般纳税人 \s
                纳税人识别号：91330108MA7G8H9J0K \s
                地址、电话：浙江省杭州市滨江区西兴街道滨安路501号801室 0571-88001103 \s
                开户银行、账号：建行杭州高新支行 3300 0000 0000 0000 1001 \s
                
                开票抬头：杭州快手科技有限公司 \s
                纳税资质：一般纳税人 \s
                纳税人识别号：91330108MA1K2L3M4N \s
                地址、电话：浙江省杭州市西湖区留下街道科创路18号2幢402室 0571-88001104 \s
                开户银行、账号：中信银行杭州钱江支行 8110001000000000003 \s
                
                开票抬头：杭州百度科技有限公司 \s
                纳税资质：增值税一般纳税人 \s
                纳税人识别号：91330108MA5P6Q7R8S \s
                地址、电话：浙江省杭州市西湖区留下街道文一西路600号C座702室 0571-88001105 \s
                开户银行、账号：中信银行杭州钱江支行 8110001000000000004 \s
                
                开票抬头：杭州快手科技有限公司西湖区分公司 \s
                纳税资质：小规模纳税人 \s
                纳税人识别号：91330106MA9T1U2V3W \s
                地址和电话：浙江省杭州市西湖区学院路88号创新大厦5楼510室 0571-88001106 \s
                开户银行：中信银行杭州钱江支行 8110001000000000005 \s
                
                开票抬头：杭州快手科技有限公司余杭分公司 \s
                纳税资质：小规模纳税人 \s
                纳税人识别号：91330110MA4X5Y6Z7A \s
                地址和电话：浙江省杭州市余杭区五常街道文一西路1288号B座903室 0571-88001107 \s
                银行：中信银行杭州钱江支行 8110001000000000006 \s
                
                开票抬头：杭州字节跳动科技有限公司 \s
                纳税资质：一般纳税人 \s
                纳税人识别号：91330110MA8B9C0D1E \s
                地址、电话：浙江省杭州市滨江区长河街道科技园路300号6层A602室 0571-88001108 \s
                开户银行、账号：中信银行杭州钱江支行 7331000000000100001 \s
                
                开票抬头：杭州美团科技有限公司 \s
                纳税资质：一般纳税人 \s
                纳税人识别号：91330110MA2F3G4H5J \s
                地址、电话：浙江省杭州市西湖区留和路300号创新中心9楼901室 0571-88001109 \s
                开户银行、账号：中信银行杭州钱江支行 7331000000000100002 \s
                
                开票抬头：杭州京东数据科技集团有限公司 \s
                纳税资质：一般纳税人 \s
                纳税人识别号：91330106MA6K7L8M9N \s
                地址、电话：浙江省杭州市西湖区紫荆花东路10号3幢702室 0571-88001110 \s
                开户银行、账号：中信银行杭州钱江支行 7331000000000100003 \s
                
                开票抬头：杭州拼多多科技有限公司 \s
                纳税资质：一般纳税人 \s
                纳税人识别号：91330108MA1P2Q3R4S \s
                地址、电话：浙江省杭州市西湖区留和路360号电商产业园5幢603室 0571-88001111 \s
                开户银行、账号：中信银行杭州钱江支行 8110001000000000007 \s
                
                开票抬头：杭州哔哩哔哩科技有限公司 \s
                纳税资质：一般纳税人 \s
                纳税人识别号：91330108MA5T6U7V8W \s
                地址、电话：浙江省杭州市滨江区西兴街道科技一路99号A座1201室 0571-88001112 \s
                开户银行、账号：中信银行杭州钱江支行 7331000000000100004 \s
                
                开票抬头：杭州滴滴出行科技有限公司 \s
                纳税资质：一般纳税人 \s
                纳税人识别号：91330108MA9X1Y2Z3A \s
                地址、电话：浙江省杭州市西湖区三墩镇高新路88号2幢1501室 0571-88001113 \s
                开户银行、账号：中信银行钱江支行 7331000000000100005 \s
                
                开票抬头：杭州小红书科技有限公司 \s
                纳税资质：小规模纳税人 \s
                纳税人识别号：91330108MA4B5C6D7E \s
                地址、电话：浙江省杭州市西湖区留和路400号电商园区3幢502室 0571-88001114 \s
                开户银行、账号：中信银行杭州钱江支行 8110001000000000008 \s
                
                开票抬头：杭州网易科技有限公司 \s
                纳税资质：增值税一般纳税人 \s
                纳税人识别号：91330110MA8F9G0H1J \s
                地址、电话：浙江省杭州市西湖区留和路500号互联网大厦12层1206室 0571-88001115 \s
                开户银行、账号：中信银行杭州钱江支行 7331000000000100006
                """;
        List<String> strings = splitIntoLineChunks(text, 6);
        System.out.println(strings);
    }

    /**
     * 按行拆分
     */
    private List<String> splitIntoLineChunks(String text, int linesPerChunk) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        List<String> lines = Arrays.asList(text.split("\n"));

        for (int i = 0; i < lines.size(); i += linesPerChunk) {
            int end = Math.min(i + linesPerChunk, lines.size());
            List<String> sub = lines.subList(i, end);

            // 检查 chunk 是否全是空行（忽略空白字符）
            boolean allEmpty = sub.stream()
                    .map(String::trim)
                    .allMatch(line -> !StringUtils.hasText(line));

            if (allEmpty) {
                continue; // 跳过纯空 chunk
            }

            // 保留原格式，不过滤 chunk 内的非空行
            String chunk = sub.stream()
                    .map(String::trim) // 去掉每行前后的空格
                    .collect(Collectors.joining("\n"))
                    .trim(); // 整体裁剪

            chunks.add(chunk);
        }

        return chunks;
    }

    /**
     * 构造一批向量插入行
     */
    private List<JsonObject> buildRowsForChunks(String documentId,
                                                List<String> chunks) {
        List<JsonObject> rows = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            if (!StringUtils.hasText(chunk)) continue;

            List<Float> emb = embeddingService.embed(chunk);

            JsonObject row = new JsonObject();
            // 每个 chunk 一个独立主键
            row.addProperty("doc_id", IdUtil.getSnowflakeNextIdStr());
            row.add("embedding", floatListToJson(emb));
            row.addProperty("content", chunk);

            JsonObject metadata = new JsonObject();
            metadata.addProperty("documentId", documentId);
            metadata.addProperty("chunkIndex", i);
            metadata.addProperty("totalChunks", chunks.size());
            metadata.addProperty("timestamp", now);
            row.add("metadata", metadata);

            rows.add(row);
        }

        return rows;
    }


    private JsonArray floatListToJson(List<Float> list) {
        JsonArray arr = new JsonArray();
        list.forEach(arr::add);
        return arr;
    }
}
