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

package com.nageoffer.ai.ragent.rag.core.graph;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图谱文档来源标识 {collectionName}_{docId} 的编解码
 * <p>
 * 从右锚定「末段 _docId」即可唯一还原两个分量；库名用户自填、可含下划线且可互为前缀
 * （kb 与 kb_hr 合法共存），从左做子串匹配必然串库——读侧把别库证据划进主路，删侧连带删光别库数据且不可逆，
 * 归属判定必须走这里的解析 + 全名等值
 * <p>
 * 兼容两种 docId 形态：旧知识链的雪花纯数字，以及 P2 文档的 {@code doc-<hex>}
 * （{@code UploadIntentService} 以 {@code doc-} + UUID 去连字符生成）。两种形态都不含下划线，
 * 因此右锚定仍然唯一；数字分支保持在前，避免把库名末段误当 docId
 */
public record GraphFileSource(String collectionName, String docId) {

    /**
     * 右锚定格式：主体严格为 {collectionName}_{docId}，容忍服务端追加的扩展名修饰。
     * docId = 雪花数字（旧链）或 {@code doc-<字母数字>}（P2 上传/摄取）。
     */
    private static final Pattern FORMAT =
            Pattern.compile("^(.+)_((?:\\d+)|(?:doc-[A-Za-z0-9-]+))((?:\\.[A-Za-z0-9]+)*)$");

    /**
     * 写入侧编码，与 parse 互为逆运算
     */
    public static String encode(String collectionName, String docId) {
        return collectionName + "_" + docId;
    }

    /**
     * 从 LightRAG 回传的 file_path 还原来源，先取 basename 以跳过可能的目录前缀；不符合编码返回 null
     */
    public static GraphFileSource parse(String filePath) {
        if (StrUtil.isBlank(filePath)) {
            return null;
        }
        String baseName = filePath.substring(filePath.lastIndexOf('/') + 1);
        Matcher matcher = FORMAT.matcher(baseName);
        return matcher.matches() ? new GraphFileSource(matcher.group(1), matcher.group(2)) : null;
    }

    /**
     * 解析 LightRAG 的多来源合并串（{@code <SEP>} 分隔）。
     *
     * <p>同一实体/关系跨多篇文档时，{@code properties.file_path} 是 {@code <SEP>} 拼接的
     * 来源列表；逐段解析、跳过非法段、按 {@code (collectionName, docId)} 去重。
     * 空/全非法输入返回空列表（调用方据此把该行计为 unattributed，绝不无主放行）。
     */
    public static List<GraphFileSource> parseAll(String filePathField) {
        if (StrUtil.isBlank(filePathField)) {
            return List.of();
        }
        LinkedHashSet<GraphFileSource> parsed = new LinkedHashSet<>();
        for (String part : filePathField.split("<SEP>")) {
            GraphFileSource source = parse(part);
            if (source != null) {
                parsed.add(source);
            }
        }
        return List.copyOf(new ArrayList<>(parsed));
    }
}
