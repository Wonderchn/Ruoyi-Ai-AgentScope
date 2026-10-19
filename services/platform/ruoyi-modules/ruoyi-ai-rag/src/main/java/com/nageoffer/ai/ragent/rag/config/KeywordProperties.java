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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 关键词检索配置
 * <p>
 * type=none（默认）时不注册任何关键词读写实现，与「从未引入关键词检索」运行期等价
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.keyword")
public class KeywordProperties {

    /**
     * 关键词检索后端类型
     * 可选 none（关闭）/ es；none 时不注册任何关键词读写实现
     */
    private String type = "none";

    /**
     * Elasticsearch 配置
     */
    private Es es = new Es();

    /**
     * 全部知识库共用的物理索引名称
     * <p>
     * 与 Milvus 共享 collection、PG 共享表同构：单索引承载所有知识库，按 collection_name 字段区分
     */
    public String sharedIndex() {
        return es.getIndex();
    }

    @Data
    public static class Es {

        /**
         * ES 连接地址
         */
        private String uris = "http://127.0.0.1:9200";

        /**
         * ES 用户名；与 {@link #password} 同时非空时启用 Basic Auth。
         * <p>
         * 环境变量：{@code RAG_KEYWORD_ES_USERNAME}（Spring relaxed binding）。
         * 凭据值只经部署环境注入，不写入仓库、不进入日志。
         */
        private String username = "";

        /**
         * ES 密码；与 {@link #username} 同时非空时启用 Basic Auth。
         * <p>
         * 环境变量：{@code RAG_KEYWORD_ES_PASSWORD}（Spring relaxed binding）。
         * 凭据值只经部署环境注入，不写入仓库、不进入日志。
         */
        private String password = "";

        /**
         * 共享索引名称：所有知识库的关键词数据都写在此索引，按 collection_name 字段过滤
         */
        private String index = "rag_keyword_store";

        /**
         * 写入分词器
         */
        private String analyzer = "ik_max_word";

        /**
         * 查询分词器
         */
        private String searchAnalyzer = "ik_smart";
    }
}
