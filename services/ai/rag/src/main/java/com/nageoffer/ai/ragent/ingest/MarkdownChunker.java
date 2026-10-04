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

package com.nageoffer.ai.ragent.ingest;

import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * P2 确定性 Markdown 分块器（策略冻结 {@value #STRATEGY}）。
 *
 * <p>chunk 逻辑键含文档/版本/策略/序号：同版本重跑产生相同键（不重复 chunk）；
 * 内容 hash 用于核对与晚到保护。旧 Markdown 检查点保留未知页号；新产物按源页独立分块。
 */
@Component
public class MarkdownChunker {

    public static final String STRATEGY = "p2-md-v1";
    public static final String PAGE_STRATEGY = "p2-pages-v2";
    public static final int DEFAULT_MAX_CHARS = 900;

    public record ChunkDraft(int index, String chunkKey, String content, String contentHash, int charCount,
                             Integer pageFrom, Integer pageTo) {
    }

    public List<ChunkDraft> chunkPages(String docId, String versionId, List<MinerUPageContent.Page> pages, int maxChars) {
        List<ChunkDraft> drafts = new ArrayList<>();
        int previous = 0;
        for (MinerUPageContent.Page page : pages) {
            if (page.number() <= previous) throw new IllegalArgumentException("pages must be positive and ordered");
            previous = page.number();
            for (ChunkDraft piece : chunk(docId, versionId, page.markdown(), maxChars)) {
                int index = drafts.size();
                String key = "c-" + CanonicalJson.sha256(docId + "|" + versionId + "|" + PAGE_STRATEGY
                        + "|" + page.number() + "|" + piece.index()).substring(0, 32);
                drafts.add(new ChunkDraft(index, key, piece.content(), piece.contentHash(), piece.charCount(),
                        page.number(), page.number()));
            }
        }
        return List.copyOf(drafts);
    }

    public List<ChunkDraft> chunk(String docId, String versionId, String markdown, int maxChars) {
        int limit = maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;
        List<String> blocks = splitBlocks(markdown);
        List<String> packed = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks) {
            for (String piece : hardSplit(block, limit)) {
                if (current.length() > 0 && current.length() + piece.length() + 2 > limit) {
                    packed.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(piece);
            }
        }
        if (current.length() > 0) {
            packed.add(current.toString());
        }
        List<ChunkDraft> drafts = new ArrayList<>();
        for (int i = 0; i < packed.size(); i++) {
            String content = packed.get(i).strip();
            if (content.isEmpty()) {
                continue;
            }
            String key = "c-" + CanonicalJson.sha256(docId + "|" + versionId + "|" + STRATEGY + "|" + i)
                    .substring(0, 32);
            drafts.add(new ChunkDraft(i, key, content, CanonicalJson.sha256(content), content.length(), null, null));
        }
        return drafts;
    }

    private List<String> splitBlocks(String markdown) {
        String normalized = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : normalized.split("\n", -1)) {
            if (line.isBlank()) {
                if (current.length() > 0) {
                    blocks.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        if (current.length() > 0) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    private List<String> hardSplit(String block, int limit) {
        List<String> pieces = new ArrayList<>();
        if (block.length() <= limit) {
            pieces.add(block);
            return pieces;
        }
        int start = 0;
        while (start < block.length()) {
            int end = Math.min(block.length(), start + limit);
            pieces.add(block.substring(start, end));
            start = end;
        }
        return pieces;
    }
}
