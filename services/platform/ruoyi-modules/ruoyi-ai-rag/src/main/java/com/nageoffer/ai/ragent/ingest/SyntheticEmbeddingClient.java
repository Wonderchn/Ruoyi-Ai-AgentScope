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

import com.nageoffer.ai.ragent.infra.embedding.EmbeddingClient;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R17-F05:3-M2：把 p2 合成嵌入接入运行时嵌入注册表。
 *
 * <p>缺陷（诊断卡 §2.2）：KB 行在 p2 合成模式登记的 {@code synthetic-feature-hash-<dim>}
 * （由 {@link SyntheticEmbeddingGateway#model()} 兜底写库）不在
 * {@code ai.model.embedding.candidates} 里，chunk 写面的
 * {@code RoutingEmbeddingService.resolveTarget} 解析不到它即抛 {@code RemoteException}
 * → 平台 500 信封 → 网关 fail-closed 成 503「授权服务不可用」。
 *
 * <p>修法：按 {@code rerank-noop} 先例，在目录里登记一条 {@code provider: noop} 的
 * 合成候选，并由本类提供该 provider 的 {@link EmbeddingClient} 实现。路由服务按
 * provider 收集 client（{@code RoutingEmbeddingService} 构造参数
 * {@code List<EmbeddingClient>} → {@code clientsByProvider}），因此无需改动
 * {@code RoutingEmbeddingService} 或装配根。
 *
 * <p><b>零外呼</b>：{@code embed}/{@code embedBatch} 只委托同进程的
 * {@link SyntheticEmbeddingGateway} 做特征哈希，不发任何 HTTP；与网关本身同一开关
 * （{@code p2.embedding.mode=synthetic}），非合成模式下一并不装配。
 */
@Component
@ConditionalOnProperty(name = "p2.embedding.mode", havingValue = "synthetic")
public class SyntheticEmbeddingClient implements EmbeddingClient {

    private final SyntheticEmbeddingGateway gateway;

    public SyntheticEmbeddingClient(SyntheticEmbeddingGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String provider() {
        // 与 application-embedded.yml 里合成候选的 provider 同值（noop 免 provider 配置校验）
        return ModelProvider.NOOP.getId();
    }

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        return gateway.embed(text);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        return gateway.embedBatch(texts);
    }
}
