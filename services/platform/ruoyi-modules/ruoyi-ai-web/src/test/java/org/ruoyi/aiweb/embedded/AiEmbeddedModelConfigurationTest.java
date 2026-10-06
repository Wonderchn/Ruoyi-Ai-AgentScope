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

package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.ChatClient;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.RoutingLLMService;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingClient;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.infra.rerank.RerankClient;
import com.nageoffer.ai.ragent.infra.vlm.VlmService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP-030：模型路由与提供方内嵌装配的判据。
 *
 * <p>这一组盯的是"注册了 ChatClient 但链路仍然不可用"的具体成因：
 * <ol>
 *   <li>路由链的编排件与基类 {@code @Autowired} 依赖都在
 *       {@code com.nageoffer.ai.ragent.infra.*}，platform 扫描根是 {@code org.ruoyi}
 *       → 它们从来不是 bean（{@link #modelChainIsAbsentUnlessTheGateIsOn()} 钉门控，
 *       {@link #modelChainIsFullyAssembledWhenEnabled()} 钉真装配）；</li>
 *   <li>chat 客户端用 {@code @Autowired} 字段、嵌入客户端用构造参数取 {@code OkHttpClient}，
 *       两种形态都需要显式提供基础设施 bean，否则启动即缺依赖
 *       （{@link #providerClientsCoverEveryChatProvider()} 与
 *       {@link #embeddingAndRerankClientsCoverTheirCatalogProviders()}）；</li>
 *   <li>档位配置错误若留到请求期会静默降级成"档位缺失"
 *       （{@link #tierResolutionFollowsTheConfiguredCatalog()} 钉正常档位解析，
 *       {@link #brokenTierCatalogFailsAtStartup()} 钉启动期 fail-fast）。</li>
 * </ol>
 *
 * <p>另有判据专门盯"配置权威"与"fail-closed"：
 * {@link #embeddedCatalogResolvesEveryCandidateForItsOwnCapability()} 从真实
 * {@code application-embedded.yml} 绑定并<em>按各自能力类型</em>解析 URL
 * （provider 未登记或缺该能力 endpoint 即失败），
 * {@link #catalogFileCarriesNoLiteralSecrets()} 断言该文件不含明文密钥。
 *
 * <p>注意：凡打开模型链的用例都必须同时给出完整合法目录——档位校验器是启动期
 * fail-fast 的，缺 {@code ai.chat.tiers} 会让上下文直接启动失败（这是设计，不是缺陷）。
 */
@Tag("dev")
class AiEmbeddedModelConfigurationTest {

    private static final String[] EMBEDDED_LOCAL_MODEL_ON = {
            "ai.integration.enabled=true", "ai.integration.transport=local", "ai.model.enabled=true"};

    /** 真实内嵌 profile 文件：模型目录与密钥解引用的唯一权威。 */
    private static final Path EMBEDDED_YML = Path.of("..", "..", "ruoyi-admin", "src", "main",
            "resources", "application-embedded.yml").toAbsolutePath().normalize();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedModelConfiguration.class);

    // ------------------------------------------------------------------ 装配

    @Test
    @DisplayName("ai.model.enabled=true 时路由链、4 个服务面与基础设施 bean 完整装配")
    void modelChainIsFullyAssembledWhenEnabled() {
        runner.withPropertyValues(EMBEDDED_LOCAL_MODEL_ON)
                .withPropertyValues(catalogProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AIModelProperties.class);
                    assertThat(context).hasSingleBean(ModelSelector.class);
                    assertThat(context).hasSingleBean(ModelHealthStore.class);
                    assertThat(context).hasSingleBean(com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor.class);
                    assertThat(context).hasSingleBean(com.nageoffer.ai.ragent.infra.chat.LlmFirstPacketProbe.class);
                    // 4 个服务面：调用点按接口注入时不该歧义
                    assertThat(context).hasSingleBean(LLMService.class);
                    assertThat(context.getBean(LLMService.class)).isInstanceOf(RoutingLLMService.class);
                    assertThat(context).hasSingleBean(com.nageoffer.ai.ragent.infra.embedding.EmbeddingService.class);
                    assertThat(context).hasSingleBean(com.nageoffer.ai.ragent.infra.rerank.RerankService.class);
                    assertThat(context).hasSingleBean(VlmService.class);
                    // 基类用 @Autowired 字段需要的 4 个基础设施 bean，缺任一都会让 ChatClient 启动失败
                    assertThat(context).hasBean("syncHttpClient");
                    assertThat(context).hasBean("streamingHttpClient");
                    assertThat(context).hasBean("modelStreamExecutor");
                    assertThat(context).hasSingleBean(
                            com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport.class);
                    // 档位校验器必须参与装配，否则结构性错误无从 fail-fast
                    assertThat(context).hasSingleBean(
                            com.nageoffer.ai.ragent.infra.model.ChatTierConfigValidator.class);
                });
    }

    @Test
    @DisplayName("缺少 ai.model.enabled 时模型链不装配（负例：不能靠扫描意外生效）")
    void modelChainIsAbsentUnlessTheGateIsOn() {
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LLMService.class);
                    assertThat(context).doesNotHaveBean(ModelSelector.class);
                    assertThat(context).doesNotHaveBean(ChatClient.class);
                    assertThat(context).doesNotHaveBean("streamingHttpClient");
                });
        runner.withPropertyValues("ai.model.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(LLMService.class));
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=http",
                        "ai.model.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(LLMService.class));
    }

    @Test
    @DisplayName("chat 客户端覆盖全部非 noop 提供商（每个枚举值都必须有客户端）")
    void providerClientsCoverEveryChatProvider() {
        runner.withPropertyValues(EMBEDDED_LOCAL_MODEL_ON)
                .withPropertyValues(catalogProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Map<String, ChatClient> clients = context.getBeansOfType(ChatClient.class);
                    Set<String> actual = new TreeSet<>();
                    clients.values().forEach(client -> actual.add(client.provider()));
                    Set<String> expected = new TreeSet<>();
                    for (ModelProvider provider : ModelProvider.values()) {
                        if (provider != ModelProvider.NOOP) {
                            expected.add(provider.getId());
                        }
                    }
                    assertThat(actual)
                            .as("提供商枚举里每个值都必须有 chat 客户端，否则该提供商的候选在路由期"
                                    + "解析不到 client 而被逐个跳过（等于该提供商静默不可用）")
                            .isEqualTo(expected);
                });
    }

    @Test
    @DisplayName("嵌入与重排客户端覆盖目录中用到的提供商（构造参数注入形态）")
    void embeddingAndRerankClientsCoverTheirCatalogProviders() {
        runner.withPropertyValues(EMBEDDED_LOCAL_MODEL_ON)
                .withPropertyValues(catalogProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Set<String> embeddingProviders = new TreeSet<>();
                    context.getBeansOfType(EmbeddingClient.class).values()
                            .forEach(client -> embeddingProviders.add(client.provider()));
                    assertThat(embeddingProviders)
                            .as("嵌入客户端覆盖全部非 noop 提供商；目录里 ai.embedding 用到的 "
                                    + "bailian/ollama/aihubmix 必须都在其中")
                            .containsExactlyInAnyOrder("bailian", "ollama", "aihubmix", "siliconflow")
                            .contains("bailian", "ollama", "aihubmix");
                    Set<String> rerankProviders = new TreeSet<>();
                    context.getBeansOfType(RerankClient.class).values()
                            .forEach(client -> rerankProviders.add(client.provider()));
                    assertThat(rerankProviders).contains("noop");
                });
    }

    // ------------------------------------------------------------------ 档位与容错行为

    @Test
    @DisplayName("档位解析按配置生效：默认 standard、显式覆盖、preferred 置顶、thinking 走 deep 并过滤")
    void tierResolutionFollowsTheConfiguredCatalog() {
        runner.withPropertyValues(EMBEDDED_LOCAL_MODEL_ON)
                .withPropertyValues(catalogProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelSelector selector = context.getBean(ModelSelector.class);

                    List<ModelTarget> standard = selector.selectChatCandidates(false);
                    assertThat(ids(standard)).containsExactly("m-a", "m-b", "m-c");
                    assertThat(standard).allSatisfy(target -> assertThat(target.timeoutMs()).isEqualTo(1000L));

                    assertThat(ids(selector.selectChatCandidates(false, Tier.FAST)))
                            .as("显式档位覆盖必须生效")
                            .containsExactly("m-c");

                    assertThat(ids(selector.selectChatCandidates(false, null, "m-b")))
                            .as("preferred 置队首，失败后仍回退到档位其余候选")
                            .containsExactly("m-b", "m-a", "m-c");

                    List<ModelTarget> deep = selector.selectChatCandidates(true);
                    assertThat(ids(deep))
                            .as("thinking 走 deep 档；m-a 未声明支持思考，必须被过滤")
                            .containsExactly("m-b", "m-c");
                    assertThat(deep).allSatisfy(target -> assertThat(target.timeoutMs()).isEqualTo(3000L));
                });
    }

    @Test
    @DisplayName("enabled=false 与提供方未登记的候选被丢弃，不伪造可用性")
    void disabledOrphanAndUnregisteredCandidatesAreDroppedNotFaked() {
        runner.withPropertyValues(EMBEDDED_LOCAL_MODEL_ON)
                .withPropertyValues(catalogProperties())
                .withPropertyValues("ai.chat.tiers.standard.candidates[0]=m-a",
                        "ai.chat.tiers.standard.candidates[1]=m-b",
                        "ai.chat.tiers.standard.candidates[2]=m-disabled",
                        "ai.chat.candidates[2].provider=not-registered",
                        // fast 档只留 m-disabled，用来单独证明 enabled=false 被尊重
                        "ai.chat.tiers.fast.candidates[0]=m-disabled",
                        "ai.chat.tiers.deep.candidates[0]=m-b")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelSelector selector = context.getBean(ModelSelector.class);
                    assertThat(ids(selector.selectChatCandidates(false)))
                            .as("provider 未在 ai.providers 登记的 m-c 与 enabled=false 的 m-disabled "
                                    + "都不得进入候选列表")
                            .containsExactly("m-a", "m-b");
                    assertThat(selector.selectChatCandidates(false, Tier.FAST))
                            .as("enabled=false 的候选即使被档位显式引用也不得返回")
                            .isEmpty();
                });
        // "档位引用了未登记在 candidates 的 id" 不是运行期静默跳过，而是启动期 fail-fast：
        // 由 brokenTierCatalogFailsAtStartup 覆盖。
    }

    @Test
    @DisplayName("连续失败达阈值即熔断退出候选；恢复窗口后半开只放一个探测名额")
    void openCircuitRemovesTheCandidate() {
        runner.withPropertyValues(EMBEDDED_LOCAL_MODEL_ON)
                .withPropertyValues(catalogProperties())
                // 恢复窗口原来是 1ms —— 那是**竞态**：本用例在"未超时必须拒绝"(见下)之前还要跑
                // 若干断言与一次 selectChatCandidates，耗时可能 >1ms ⇒ 窗口已过 ⇒ 半开名额被发放
                // ⇒ 断言随机失败（实测在 `clean package` 全量里红、同一提交的 `test` 里绿）。
                // 取 1000ms：远大于两条断言的开销（<1ms），又短到能被 sleepBriefly() 明确跨过。
                .withPropertyValues("ai.selection.failure-threshold=2", "ai.selection.open-duration-ms=1000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelSelector selector = context.getBean(ModelSelector.class);
                    ModelHealthStore health = context.getBean(ModelHealthStore.class);

                    assertThat(health.isUnavailable("m-a")).as("初始 CLOSED 状态可调用").isFalse();
                    assertThat(ids(selector.selectChatCandidates(false))).contains("m-a");

                    // 阈值 2：第一次失败还不熔断
                    health.markFailure("m-a");
                    assertThat(health.isUnavailable("m-a")).as("单次失败不熔断").isFalse();
                    assertThat(ids(selector.selectChatCandidates(false)))
                            .as("单次失败后仍应留在候选列表")
                            .contains("m-a");

                    // 第二次失败：熔断打开
                    health.markFailure("m-a");
                    assertThat(health.isUnavailable("m-a")).as("达到阈值后熔断打开").isTrue();
                    assertThat(ids(selector.selectChatCandidates(false)))
                            .as("熔断打开期间该模型必须退出候选列表，不能再被选中")
                            .doesNotContain("m-a");
                    assertThat(health.allowCall("m-a"))
                            .as("OPEN 且恢复窗口未过时必须拒绝调用（fail-closed）")
                            .isNull();

                    // 恢复窗口（1ms）过后：半开，只放一个在途探测
                    sleepBriefly();
                    ModelHealthStore.CallPermit first = health.allowCall("m-a");
                    assertThat(first)
                            .as("恢复窗口过后应进入半开并给出一个探测名额")
                            .isNotNull();
                    assertThat(first.halfOpenToken())
                            .as("半开探测必须带非零令牌，否则 releaseHalfOpenPermit 无法归还名额")
                            .isPositive();
                    ModelHealthStore.CallPermit second = health.allowCall("m-a");
                    assertThat(second)
                            .as("半开状态只允许一个在途探测，第二个调用必须被拒绝")
                            .isNull();
                    assertThat(ids(selector.selectChatCandidates(false)))
                            .as("半开且有在途探测时该模型仍不应进入候选列表")
                            .doesNotContain("m-a");

                    health.releaseHalfOpenPermit(first);
                    assertThat(health.allowCall("m-a"))
                            .as("归还名额后必须可以再次探测（否则模型永久不可恢复）")
                            .isNotNull();

                    health.markSuccess("m-a");
                    assertThat(health.isUnavailable("m-a")).as("成功后回到 CLOSED").isFalse();
                    assertThat(ids(selector.selectChatCandidates(false))).contains("m-a");
                });
    }

    /** 跨过 open-duration-ms 的窗口（测试配置里是 1000ms）；留 300ms 余量以免被调度/GC 吃掉。 */
    private static void sleepBriefly() {
        try {
            Thread.sleep(1300L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待恢复窗口时被中断", e);
        }
    }

    @Test
    @DisplayName("候选全空时路由执行器显式抛错，不静默降级或假成功")
    void emptyCandidateListFailsLoudly() {
        // 标准档三个候选全部 disabled：selector 返回空候选列表
        runner.withPropertyValues(EMBEDDED_LOCAL_MODEL_ON)
                .withPropertyValues(catalogProperties())
                .withPropertyValues("ai.chat.tiers.standard.candidates[0]=m-disabled",
                        "ai.chat.tiers.standard.candidates[1]=m-disabled",
                        "ai.chat.tiers.standard.candidates[2]=m-disabled")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelSelector selector = context.getBean(ModelSelector.class);
                    assertThat(selector.selectChatCandidates(false)).isEmpty();

                    LLMService llm = context.getBean(LLMService.class);
                    assertThatThrownBy(() -> llm.chat(new ChatRequest()))
                            .as("无可用候选时必须抛显式异常，不能返回空成功")
                            .hasMessageContaining("No");
                });
    }

    @Test
    @DisplayName("档位配置结构性错误在启动期 fail-fast（不留到请求期静默降级）")
    void brokenTierCatalogFailsAtStartup() {
        // deep-thinking-tier 指向的档位没有任何"已启用且支持思考"的候选
        runner.withPropertyValues(EMBEDDED_LOCAL_MODEL_ON)
                .withPropertyValues(catalogProperties())
                .withPropertyValues("ai.chat.candidates[1].supports-thinking=false",
                        "ai.chat.candidates[2].supports-thinking=false")
                .run(context -> assertThat(context).hasFailed());
        // 档位引用了未登记的候选 id
        runner.withPropertyValues(EMBEDDED_LOCAL_MODEL_ON)
                .withPropertyValues(catalogProperties())
                .withPropertyValues("ai.chat.tiers.standard.candidates[0]=not-in-registry")
                .run(context -> assertThat(context).hasFailed());
        // 某个档位缺 timeout-ms
        runner.withPropertyValues(EMBEDDED_LOCAL_MODEL_ON)
                .withPropertyValues(catalogProperties())
                .withPropertyValues("ai.chat.tiers.fast.timeout-ms=")
                .run(context -> assertThat(context).hasFailed());
        // 候选 id 重复
        runner.withPropertyValues(EMBEDDED_LOCAL_MODEL_ON)
                .withPropertyValues(catalogProperties())
                .withPropertyValues("ai.chat.candidates[1].id=m-a")
                .run(context -> assertThat(context).hasFailed());
        // 完全没有档位配置（这正是"内嵌 profile 漏写 ai.chat.tiers"的形态）
        runner.withPropertyValues(EMBEDDED_LOCAL_MODEL_ON)
                .run(context -> assertThat(context).hasFailed());
    }

    // ------------------------------------------------------------------ 真实 profile 文件

    @Test
    @DisplayName("Tier 枚举与内嵌 profile 的档位一一覆盖（缺一个都会在启动期失败）")
    void everyTierEnumValueHasATierInTheEmbeddedProfile() {
        AIModelProperties properties = bindEmbeddedCatalog();
        Set<String> tierKeys = new TreeSet<>(properties.getChat().getTiers().keySet());
        Set<String> enumKeys = new TreeSet<>();
        for (Tier tier : Tier.values()) {
            enumKeys.add(tier.getKey());
        }
        assertThat(tierKeys).isEqualTo(enumKeys);
        assertThat(properties.getChat().getDefaultTier()).isIn(tierKeys);
        assertThat(properties.getChat().getDeepThinkingTier()).isIn(tierKeys);
        assertThat(properties.getChat().getTiers().values())
                .allSatisfy(tier -> {
                    assertThat(tier.getTimeoutMs()).as("档位超时预算是必填").isNotNull().isPositive();
                    assertThat(tier.getCandidates()).isNotEmpty();
                });
    }

    @Test
    @DisplayName("内嵌 profile 的每个候选都能按自己的能力类型解析出调用 URL")
    void embeddedCatalogResolvesEveryCandidateForItsOwnCapability() {
        AIModelProperties properties = bindEmbeddedCatalog();
        List<String> unresolved = new ArrayList<>();
        collect(properties, properties.getChat().getCandidates(), "chat", ModelCapability.CHAT, unresolved);
        collect(properties, properties.getEmbedding().getCandidates(), "embedding",
                ModelCapability.EMBEDDING, unresolved);
        collect(properties, properties.getRerank().getCandidates(), "rerank", ModelCapability.RERANK, unresolved);

        assertThat(unresolved)
                .as("每个候选都必须能解析出 URL：provider 未在 ai.providers 登记，"
                        + "或该能力类型的 endpoint 缺失，都会让该候选在运行期不可用")
                .isEmpty();

        // 反向锚点：证明上面的断言不是恒真
        AIModelProperties.ModelCandidate orphan = new AIModelProperties.ModelCandidate();
        orphan.setId("orphan");
        orphan.setProvider("no-such-provider");
        orphan.setModel("no-such-model");
        assertThatThrownBy(() -> ModelUrlResolver.resolveUrl(
                properties.getProviders().get(orphan.getProvider()), orphan, ModelCapability.CHAT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseUrl is missing");
    }

    @Test
    @DisplayName("内嵌 profile 里 ai.* 只有一个顶层块（重复键会被严格解析拒绝，或被静默覆盖）")
    void embeddedProfileHasExactlyOneAiBlock() throws IOException {
        String yaml = Files.readString(EMBEDDED_YML);
        long aiBlocks = yaml.lines().filter(line -> line.equals("ai:")).count();
        assertThat(aiBlocks)
                .as("重复的顶层 ai: 会让后写的块静默覆盖前一个块——"
                        + "例如模型目录覆盖掉 ai.integration.enabled，直到运行期才暴露")
                .isEqualTo(1);
        assertThat(yaml).contains("enabled: true");
        assertThat(yaml).contains("model:");
    }

    @Test
    @DisplayName("嵌入候选声明的维度与部署配置一致（冻结迁移固定 1536，缺配置 fail-closed）")
    void embeddingCandidatesDeclareADimension() throws IOException {
        AIModelProperties properties = bindEmbeddedCatalog();
        String yaml = Files.readString(EMBEDDED_YML);
        assertThat(yaml)
                .as("冻结迁移把统一向量列的物理维度固定在 1536，且 VectorTargetResolver 在缺配置时"
                        + "fail-closed 拒绝；内嵌 profile 必须显式声明该属性")
                .contains("rag:")
                .contains("dimension:");
        assertThat(properties.getEmbedding().getCandidates())
                .as("嵌入候选必须逐个声明维度")
                .isNotEmpty()
                .allSatisfy(candidate -> assertThat(candidate.getDimension()).isNotNull());
        assertThat(properties.getEmbedding().getDefaultModel()).isNotBlank();
    }

    @Test
    @DisplayName("模型目录文件不含明文密钥：api-key 一律经环境变量解引用")
    void catalogFileCarriesNoLiteralSecrets() throws IOException {
        String yaml = Files.readString(EMBEDDED_YML);
        List<String> apiKeyLines = yaml.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("api-key:"))
                .toList();
        assertThat(apiKeyLines)
                .as("内嵌 profile 必须逐个提供商声明密钥解引用，不能靠隐式默认")
                .isNotEmpty();
        assertThat(apiKeyLines)
                .as("api-key 只能写成 ${ENV_VAR:} 形式；出现字面量就是把密钥写进了公开仓库")
                .allSatisfy(line -> assertThat(line).matches("api-key:\\s*\\$\\{[A-Za-z0-9_]+:}"));
    }

    // ------------------------------------------------------------------ 辅助

    private static List<String> ids(List<ModelTarget> targets) {
        List<String> ids = new ArrayList<>();
        targets.forEach(target -> ids.add(target.id()));
        return ids;
    }

    private static void collect(AIModelProperties properties, List<AIModelProperties.ModelCandidate> candidates,
                                String group, ModelCapability capability, List<String> unresolved) {
        if (candidates == null) {
            return;
        }
        for (AIModelProperties.ModelCandidate candidate : candidates) {
            // noop 是显式登记的"无后端"候选：ModelSelector 允许它没有 providers 条目，
            // 解析 URL 对它本来就不适用，因此这里按同一条规则跳过而不是算作缺陷。
            if (ModelProvider.NOOP.matches(candidate.getProvider())) {
                continue;
            }
            try {
                ModelUrlResolver.resolveUrl(properties.getProviders().get(candidate.getProvider()),
                        candidate, capability);
            } catch (RuntimeException e) {
                unresolved.add(group + ":" + candidate.getId() + " -> " + e.getMessage());
            }
        }
    }

    private static AIModelProperties bindEmbeddedCatalog() {
        Assumptions.assumeTrue(Files.exists(EMBEDDED_YML),
                "内嵌 profile 文件不在预期位置（工作目录可能不是模块目录）: " + EMBEDDED_YML);
        List<PropertySource<?>> sources;
        try {
            sources = new YamlPropertySourceLoader().load("embedded", new FileSystemResource(EMBEDDED_YML));
        } catch (IOException e) {
            throw new IllegalStateException("读取内嵌 profile 失败: " + EMBEDDED_YML, e);
        }
        // Binder 只接受 ConfigurationPropertySource；用 Spring 的适配器包一层，
        // 不自己实现属性解析（否则就绕过了 Spring 的松散绑定语义）。
        // 占位符解析也必须接上：目录里 dimension 写的是 ${rag.default.dimension}，
        // 没有解析器时绑定到 Integer 会直接失败——那不是"文件有问题"，而是探针缺了运行期上下文。
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        return new Binder(ConfigurationPropertySources.from(propertySources),
                new PropertySourcesPlaceholdersResolver(propertySources))
                .bind("ai", Bindable.of(AIModelProperties.class))
                .orElseThrow(() -> new IllegalStateException("application-embedded.yml 里没有可绑定的 ai.* 块"));
    }

    /**
     * 用 property-values 表达一份最小合法目录（不依赖真实 profile 文件），
     * 让档位/容错行为判据与"真实文件解析"判据互不耦合。
     */
    private static String[] catalogProperties() {
        return Stream.of(
                "ai.providers.p1.url=https://provider-one.test",
                "ai.providers.p1.endpoints.chat=/v1/chat/completions",
                "ai.providers.p1.endpoints.embedding=/v1/embeddings",
                "ai.providers.p1.endpoints.rerank=/v1/rerank",
                "ai.chat.candidates[0].id=m-a",
                "ai.chat.candidates[0].provider=p1",
                "ai.chat.candidates[0].model=model-a",
                "ai.chat.candidates[1].id=m-b",
                "ai.chat.candidates[1].provider=p1",
                "ai.chat.candidates[1].model=model-b",
                "ai.chat.candidates[1].supports-thinking=true",
                "ai.chat.candidates[2].id=m-c",
                "ai.chat.candidates[2].provider=p1",
                "ai.chat.candidates[2].model=model-c",
                "ai.chat.candidates[2].supports-thinking=true",
                "ai.chat.candidates[3].id=m-disabled",
                "ai.chat.candidates[3].provider=p1",
                "ai.chat.candidates[3].model=model-disabled",
                "ai.chat.candidates[3].enabled=false",
                "ai.chat.default-tier=standard",
                "ai.chat.deep-thinking-tier=deep",
                "ai.chat.tiers.fast.candidates[0]=m-c",
                "ai.chat.tiers.fast.timeout-ms=500",
                "ai.chat.tiers.standard.candidates[0]=m-a",
                "ai.chat.tiers.standard.candidates[1]=m-b",
                "ai.chat.tiers.standard.candidates[2]=m-c",
                "ai.chat.tiers.standard.timeout-ms=1000",
                "ai.chat.tiers.deep.candidates[0]=m-a",
                "ai.chat.tiers.deep.candidates[1]=m-b",
                "ai.chat.tiers.deep.candidates[2]=m-c",
                "ai.chat.tiers.deep.timeout-ms=3000",
                "ai.embedding.default-model=e-a",
                "ai.embedding.candidates[0].id=e-a",
                "ai.embedding.candidates[0].provider=p1",
                "ai.embedding.candidates[0].model=embed-a",
                "ai.embedding.candidates[0].dimension=1536",
                "ai.rerank.default-model=r-a",
                "ai.rerank.candidates[0].id=r-a",
                "ai.rerank.candidates[0].provider=p1",
                "ai.rerank.candidates[0].model=rerank-a",
                "ai.vlm.default-model=v-a",
                "ai.vlm.candidates[0].id=v-a",
                "ai.vlm.candidates[0].provider=p1",
                "ai.vlm.candidates[0].model=vlm-a"
        ).toArray(String[]::new);
    }
}
