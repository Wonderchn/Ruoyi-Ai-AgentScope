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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 合成 embedding：确定性、维度、词项重叠相似度（test double，不是真实语义签收）。 */
class SyntheticEmbeddingGatewayTest {

    private final SyntheticEmbeddingGateway gateway = new SyntheticEmbeddingGateway(1536);

    @Test
    void deterministicAndNormalized() {
        List<Float> first = gateway.embed("marker code P2-MARKER-XYZZY");
        List<Float> second = gateway.embed("marker code P2-MARKER-XYZZY");
        assertEquals(first, second);
        assertEquals(1536, first.size());
        double norm = 0;
        for (Float value : first) {
            norm += (double) value * value;
        }
        assertEquals(1.0, Math.sqrt(norm), 1e-6);
    }

    @Test
    void overlappingTokensScoreHigherThanUnrelated() {
        List<Float> query = gateway.embed("what is the marker code for project phase two");
        List<Float> relevant = gateway.embed("the marker code is P2-MARKER-XYZZY for project phase two");
        List<Float> unrelated = gateway.embed("completely different topic about weather and cooking");
        assertTrue(cosine(query, relevant) > cosine(query, unrelated));
    }

    @Test
    void batchMatchesSingle() {
        List<List<Float>> batch = gateway.embedBatch(List.of("alpha", "beta"));
        assertEquals(gateway.embed("alpha"), batch.get(0));
        assertEquals(gateway.embed("beta"), batch.get(1));
    }

    @Test
    void dimensionMismatchIsRejectedByRealGatewayContract() {
        // 真实网关的维度校验语义：期望 1536 时 3 维向量必须失败（不截断、不混写）
        com.nageoffer.ai.ragent.infra.embedding.EmbeddingService wrongDimension =
                new com.nageoffer.ai.ragent.infra.embedding.EmbeddingService() {
                    @Override
                    public List<Float> embed(String text) {
                        return List.of(0.1f, 0.2f, 0.3f);
                    }

                    @Override
                    public List<Float> embed(String text, String modelId) {
                        return embed(text);
                    }

                    @Override
                    public List<List<Float>> embedBatch(List<String> texts) {
                        return texts.stream().map(this::embed).toList();
                    }

                    @Override
                    public List<List<Float>> embedBatch(List<String> texts, String modelId) {
                        return embedBatch(texts);
                    }
                };
        var real = new RealEmbeddingGateway(wrongDimension, 1536);
        assertThrows(com.nageoffer.ai.ragent.runtime.RunApiException.class, () -> real.embed("x"));
    }

    private double cosine(List<Float> a, List<Float> b) {
        double dot = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
        }
        return dot;
    }
}
