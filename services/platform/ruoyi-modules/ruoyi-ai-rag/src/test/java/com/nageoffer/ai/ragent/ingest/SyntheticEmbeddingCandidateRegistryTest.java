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

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R17-F05:3-M2：p2 合成嵌入必须并入运行时嵌入注册表（诊断卡 §2.2）。
 *
 * <p>侦察结论：KB 行在 p2 合成模式登记的 {@code synthetic-feature-hash-1536}
 * （{@code SyntheticEmbeddingGateway.model()} 兜底写库）在 chunk 写面走
 * {@code RoutingEmbeddingService.resolveTarget} 时不在候选表内 → RemoteException
 * → 平台 500 信封 → 网关 fail-closed 成 503「授权服务不可用」。
 *
 * <p>判据（红先行）：shipped {@code application-embedded.yml} 的
 * {@code ai.model.embedding.candidates} 必须包含该 id 且 provider=noop，
 * {@code selectEmbeddingCandidates()} 能解析到它，默认模型次序不被打乱。
 */
@Tag("dev")
class SyntheticEmbeddingCandidateRegistryTest {

    private static final String SYNTHETIC_ID = "synthetic-feature-hash-1536";

    @Test
    void shippedEmbeddedCatalogRegistersTheSyntheticEmbeddingCandidate() {
        AIModelProperties properties = EmbeddedModelCatalogFixture.bindShippedEmbeddedCatalog();
        ModelSelector selector = new ModelSelector(properties, new ModelHealthStore(properties));

        List<ModelTarget> candidates = selector.selectEmbeddingCandidates();
        Optional<ModelTarget> synthetic = candidates.stream()
                .filter(target -> SYNTHETIC_ID.equals(target.id()))
                .findFirst();

        assertThat(synthetic)
                .as("KB 行登记的合成模型必须能在运行时嵌入注册表解析到（否则写腿 resolveTarget 抛 RemoteException）")
                .isPresent();
        assertThat(synthetic.get().candidate().getProvider())
                .as("合成候选走 noop provider（免 provider 配置校验，与 rerank-noop 同形）")
                .isEqualTo("noop");
        assertThat(synthetic.get().candidate().getDimension())
                .as("候选维度必须与部署级向量空间一致")
                .isEqualTo(1536);
        assertThat(candidates.get(0).id())
                .as("新增候选是 priority=100 的兜底档，不得抢走默认模型")
                .isEqualTo("qwen-emb-8b");
    }
}
