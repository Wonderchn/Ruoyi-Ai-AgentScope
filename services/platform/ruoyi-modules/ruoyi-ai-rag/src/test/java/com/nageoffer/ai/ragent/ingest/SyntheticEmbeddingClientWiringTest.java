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

import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingClient;
import com.nageoffer.ai.ragent.infra.embedding.RoutingEmbeddingService;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R17-F05:3-M2：合成嵌入 client 被运行时注册表收集并解析到（诊断卡 §2.2 / §4.1）。
 *
 * <p>判据覆盖"装配收集路径"：{@code RoutingEmbeddingService} 按 provider 从构造注入的
 * {@code List<EmbeddingClient>} 建 {@code clientsByProvider}；这里用真实目录绑定的
 * {@link ModelSelector} + 真实 {@link ModelRoutingExecutor}/{@link ModelHealthStore}
 * 驱动该路径，证明合成候选的 {@code provider=noop} 能被新 client 命中。
 *
 * <p>零外呼：合成 client 只持有同进程 {@link SyntheticEmbeddingGateway}（特征哈希），
 * 结果与直接调用网关逐位一致且可重复；未注册 client 时按 fail-closed 拒绝（不静默回退）。
 */
@Tag("dev")
class SyntheticEmbeddingClientWiringTest {

    private static final String SYNTHETIC_ID = "synthetic-feature-hash-1536";
    private static final int DIMENSION = 1536;

    private static AIModelProperties properties;
    private static ModelSelector selector;
    private static ModelRoutingExecutor executor;
    private static SyntheticEmbeddingGateway gateway;

    @BeforeAll
    static void setUp() {
        properties = EmbeddedModelCatalogFixture.bindShippedEmbeddedCatalog();
        ModelHealthStore healthStore = new ModelHealthStore(properties);
        selector = new ModelSelector(properties, healthStore);
        executor = new ModelRoutingExecutor(healthStore);
        gateway = new SyntheticEmbeddingGateway(DIMENSION);
    }

    @Test
    void syntheticClientProviderMatchesTheRegisteredCatalogCandidate() {
        EmbeddingClient client = new SyntheticEmbeddingClient(gateway);
        ModelTarget synthetic = selector.selectEmbeddingCandidates().stream()
                .filter(target -> SYNTHETIC_ID.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("目录里没有合成候选: " + SYNTHETIC_ID));

        assertThat(client.provider())
                .as("client 的 provider 必须与候选登记的 provider 同值，否则 clientsByProvider 命中不到")
                .isEqualTo(synthetic.candidate().getProvider())
                .isEqualTo("noop");
    }

    @Test
    void routingServiceCollectsTheSyntheticClientAndEmbedsWithoutAnyHttp() {
        RoutingEmbeddingService service = new RoutingEmbeddingService(
                selector, executor, List.of(new SyntheticEmbeddingClient(gateway)));

        List<String> texts = List.of("季度营收同比", "季度营收环比");
        List<List<Float>> viaRouting = service.embedBatch(texts, SYNTHETIC_ID);

        // 逐位等于同进程网关的输出 = 纯委托、零外呼、无网络抖动
        assertThat(viaRouting).isEqualTo(gateway.embedBatch(texts));
        assertThat(viaRouting).hasSize(2);
        assertThat(viaRouting).allSatisfy(vector -> assertThat(vector).hasSize(DIMENSION));
        // 确定性：同一输入重复调用结果一致（进程内哈希，不依赖远端）
        assertThat(service.embedBatch(texts, SYNTHETIC_ID)).isEqualTo(viaRouting);
        // 单条入口同样走合成 client
        assertThat(service.embed(texts.get(0), SYNTHETIC_ID)).isEqualTo(gateway.embed(texts.get(0)));
    }

    @Test
    void syntheticCandidateWithoutItsClientFailsClosedInsteadOfFallingBackToARealProvider() {
        // 非合成模式（或 client 未装配）下 provider 无对应 client：必须显式失败，不做静默降级
        RoutingEmbeddingService withoutClient = new RoutingEmbeddingService(selector, executor, List.of());

        assertThatThrownBy(() -> withoutClient.embedBatch(List.of("季度营收同比"), SYNTHETIC_ID))
                .as("provider 未注册时 fail-closed，不能被当成真实嵌入静默兜底")
                .isInstanceOf(RemoteException.class);

        // 合成 client 也不能冒充真实 provider：real 模型 id 落在 bailian/ollama，仍应失败
        RoutingEmbeddingService syntheticOnly = new RoutingEmbeddingService(
                selector, executor, List.of(new SyntheticEmbeddingClient(gateway)));
        assertThatThrownBy(() -> syntheticOnly.embedBatch(List.of("季度营收同比"), "qwen-emb-8b"))
                .as("noop client 只服务 noop provider，不得跨 provider 命中真实模型")
                .isInstanceOf(RemoteException.class);
    }
}
