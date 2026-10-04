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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 显式合成 embedding test double（{@code p2.embedding.mode=synthetic}，仅专属测试）。
 *
 * <p>特征哈希（词/中文字符二元组）产生确定性向量：相同词项重叠的文本有更高余弦
 * 相似度，足以验证检索链路与版本隔离；<b>不是真实语义 embedding</b>，
 * 不得用于 A30/A34 的真实模型签收（真实签收需输入 I1）。
 */
@Component
@ConditionalOnProperty(name = "p2.embedding.mode", havingValue = "synthetic")
public class SyntheticEmbeddingGateway implements EmbeddingGateway {

    private final int dimension;

    public SyntheticEmbeddingGateway(@Value("${rag.default.dimension:1536}") int dimension) {
        this.dimension = dimension;
    }

    @Override
    public String provider() {
        return "synthetic";
    }

    @Override
    public String model() {
        return "synthetic-feature-hash-" + dimension;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        List<List<Float>> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }

    @Override
    public List<Float> embed(String text) {
        float[] vector = new float[dimension];
        for (String token : tokens(text)) {
            int hash = tokenHash(token);
            int index = Math.floorMod(hash, dimension);
            float sign = ((hash >>> 16) & 1) == 0 ? 1f : -1f;
            vector[index] += sign;
        }
        double norm = 0;
        for (float value : vector) {
            norm += (double) value * value;
        }
        norm = Math.sqrt(norm);
        List<Float> result = new ArrayList<>(dimension);
        for (float value : vector) {
            result.add(norm == 0 ? 0f : (float) (value / norm));
        }
        return result;
    }

    private List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        String lower = text.toLowerCase();
        StringBuilder word = new StringBuilder();
        List<String> cjk = new ArrayList<>();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                word.append(c);
                if (c >= 0x4E00 && c <= 0x9FFF) {
                    cjk.add(String.valueOf(c));
                }
            } else {
                if (word.length() > 0) {
                    tokens.add(word.toString());
                    word.setLength(0);
                }
            }
        }
        if (word.length() > 0) {
            tokens.add(word.toString());
        }
        // 中文二元组补充
        for (int i = 0; i + 1 < cjk.size(); i++) {
            tokens.add(cjk.get(i) + cjk.get(i + 1));
        }
        return tokens;
    }

    private int tokenHash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16) | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
        } catch (Exception e) {
            return token.hashCode();
        }
    }
}
