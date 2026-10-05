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

import java.util.List;

/** 向量化网关：真实实现与显式合成 test double 互斥（由 p2.embedding.mode 选择）。 */
public interface EmbeddingGateway {

    String provider();

    String model();

    int dimension();

    List<List<Float>> embedBatch(List<String> texts);

    List<Float> embed(String text);

    record EmbeddingResult(List<List<Float>> vectors,String providerRequestId,java.util.Map<String,Object> usageRaw) {}

    default EmbeddingResult embedBatchWithUsage(List<String> texts) {
        return new EmbeddingResult(embedBatch(texts),null,"synthetic".equals(provider())?java.util.Map.of("synthetic",true,"batchSize",texts.size()):null);
    }
}
