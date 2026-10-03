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

import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 真实 embedding 网关（{@code p2.embedding.mode=real}，默认）。
 *
 * <p><b>真实提供方/模型/维度尚未由维护者确认（输入 I1）</b>：本类按既有
 * {@link EmbeddingService} 路由调用，并在运行期强校验维度；维度不符即明确失败
 * （不截断、不混写）。在 I1 确认前，真实调用不得用于签收。
 */
@Component
@ConditionalOnProperty(name = "p2.embedding.mode", havingValue = "real", matchIfMissing = true)
public class RealEmbeddingGateway implements EmbeddingGateway {

    private final EmbeddingService embeddingService;
    private final int expectedDimension;

    public RealEmbeddingGateway(EmbeddingService embeddingService,
                                @Value("${rag.default.dimension:1536}") int expectedDimension) {
        this.embeddingService = embeddingService;
        this.expectedDimension = expectedDimension;
    }

    @Override
    public String provider() {
        return "configured-provider";
    }

    @Override
    public String model() {
        return "configured-model";
    }

    @Override
    public int dimension() {
        return expectedDimension;
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        List<List<Float>> vectors = embeddingService.embedBatch(texts);
        validate(vectors, texts.size());
        return vectors;
    }

    @Override
    public List<Float> embed(String text) {
        List<Float> vector = embeddingService.embed(text);
        validate(List.of(vector), 1);
        return vector;
    }

    private void validate(List<List<Float>> vectors, int expectedCount) {
        if (vectors == null || vectors.size() != expectedCount) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "embedding batch size mismatch");
        }
        for (List<Float> vector : vectors) {
            if (vector == null || vector.size() != expectedDimension) {
                throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,
                        "embedding dimension mismatch: expected " + expectedDimension
                                + ", got " + (vector == null ? "null" : vector.size()));
            }
            for (Float value : vector) {
                if (value == null || value.isNaN() || value.isInfinite()) {
                    throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "embedding contains invalid values");
                }
            }
        }
    }
}
