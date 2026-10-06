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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-031B：AI 侧 MyBatis Mapper 真的被注册进 platform 应用。
 *
 * <p>判据分两层：
 * <ol>
 *   <li><b>容器层</b>：把 {@link AiEmbeddedMapperConfiguration} 装进一个真实容器，
 *       断言 7 个包里的 Mapper 都产生了 {@code MapperFactoryBean} 定义（以及开关关闭时没有）。
 *       用"标记为 lazy"而不是起完整 MyBatis 工厂：本判据要证明的是<b>扫描与注册</b>，
 *       真库往返由 WP-025 的 {@code MergedTableMapperPostgresTest} 负责，两者不互相冒充。</li>
 *   <li><b>覆盖层</b>：磁盘上每一个 AI Mapper 接口所在包都必须落在
 *       {@code @MapperScan} 声明的包清单里——否则"新加一个包里的 Mapper"会静默不被扫描，
 *       而容器层判据只检查已知的 7 个包，抓不到这种漂移。</li>
 * </ol>
 *
 * <p>还有一条钉住"为什么不用逗号写 {@code mapperPackage}"：platform 的
 * {@code MybatisPlusConfig} 用 {@code @MapperScan("${mybatis-plus.mapperPackage}")}，
 * 该占位符解析后是<b>单个</b>包路径；把两段用逗号拼进去会两段都扫不到，
 * 静默失去全部 Mapper。所以这里断言配置值仍是单段。
 */
@Tag("dev")
class AiEmbeddedMapperConfigurationTest {

    private static final Path MODULES = Path.of("services", "platform", "ruoyi-modules");
    private static final Path ADMIN_YML = Path.of("services", "platform", "ruoyi-admin", "src", "main",
            "resources", "application.yml");

    /** 每个包挑一个代表接口，断言它真的被注册。 */
    private static final List<String> REPRESENTATIVE_MAPPERS = List.of(
            "conversationMapper", "conversationMessageMapper", "messageFeedbackMapper",
            "knowledgeBaseMapper", "knowledgeDocumentMapper", "knowledgeChunkMapper",
            "ingestionPipelineMapper", "ingestionTaskMapper", "userMapper",
            "bizChangeLogMapper", "sampleQuestionMapper", "agentStateMapper",
            "agentMemoryMapper", "agentConversationMapper");

    @Test
    @DisplayName("ai.integration.enabled=true 时 7 个 AI Mapper 包全部产生 Mapper bean 定义")
    void aiMappersAreRegisteredWhenEmbeddedIsOn() {
        try (AnnotationConfigApplicationContext context = context("true")) {
            Set<String> names = new TreeSet<>(List.of(context.getBeanDefinitionNames()));
            assertThat(names)
                    .as("AI 侧 Mapper 必须真的成为 bean 定义；此前 mapperPackage=org.ruoyi.**.mapper 一个都不扫")
                    .containsAll(REPRESENTATIVE_MAPPERS);

            // 覆盖到全部 7 个包（每个包至少一个）
            assertThat(mapperPackagesWithRegisteredBeans(context))
                    .as("7 个 AI Mapper 包都要有代表 bean")
                    .isEqualTo(declaredScanPackages());
        }
    }

    @Test
    @DisplayName("负例：没有 ai.integration.enabled 时不注册任何 AI Mapper")
    void aiMappersAreAbsentWithoutTheSwitch() {
        try (AnnotationConfigApplicationContext context = context(null)) {
            Set<String> names = new TreeSet<>(List.of(context.getBeanDefinitionNames()));
            assertThat(names)
                    .as("门控关闭时不能靠类路径扫描意外注册")
                    .doesNotContainAnyElementsOf(REPRESENTATIVE_MAPPERS);
            assertThat(mapperPackagesWithRegisteredBeans(context)).isEmpty();
        }
    }

    @Test
    @DisplayName("覆盖层：磁盘上每个 AI Mapper 接口的包都在 @MapperScan 清单里")
    void everyAiMapperPackageIsScanned() throws IOException {
        Set<String> declared = declaredScanPackages();
        assertThat(declared).hasSize(7);

        Set<String> onDisk = new TreeSet<>();
        try (Stream<Path> stream = Files.walk(locate(MODULES))) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                    .toList()) {
                String rel = file.toString().replace('\\', '/');
                if (!rel.contains("/dao/mapper/")) {
                    continue;
                }
                Matcher m = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE)
                        .matcher(Files.readString(file, StandardCharsets.UTF_8));
                if (m.find()) {
                    onDisk.add(m.group(1));
                }
            }
        }
        assertThat(onDisk)
                .as("AI Mapper 接口所在包若不在 @MapperScan 清单里，就会静默不被注册")
                .isSubsetOf(declared);
        assertThat(onDisk)
                .as("锚点：解析器必须真的读到 AI Mapper 包，否则本判据是空跑")
                .hasSizeGreaterThanOrEqualTo(7);
    }

    @Test
    @DisplayName("platform 的 mapperPackage 仍是单段包路径（逗号拼接会两段都扫不到）")
    void platformMapperPackageStaysSingleSegment() throws IOException {
        String yml = Files.readString(locate(ADMIN_YML), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("^\\s*mapperPackage:\\s*(\\S+)\\s*$", Pattern.MULTILINE).matcher(yml);
        assertThat(m.find()).as("application.yml 必须有 mapperPackage").isTrue();
        String value = m.group(1);
        assertThat(value)
                .as("mapperPackage 是 @MapperScan 的单个占位符参数；逗号不会拆包，"
                        + "写两段的结果是两段都扫不到。AI Mapper 用独立的显式 @MapperScan 注册。")
                .isEqualTo("org.ruoyi.**.mapper");
        assertThat(value).doesNotContain(",");
    }

    // ------------------------------------------------------------------ helpers

    private static AnnotationConfigApplicationContext context(String enabled) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (enabled != null) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("test",
                            java.util.Map.of("ai.integration.enabled", enabled)));
        }
        context.register(AiEmbeddedMapperConfiguration.class, LazyMapperDefinitions.class);
        context.refresh();
        return context;
    }

    /**
     * 把 {@code MapperFactoryBean} 定义标成 lazy：本判据只验证"扫描与注册"，
     * 实例化需要完整 MyBatis 工厂（那部分由真库测试负责）。
     */
    static class LazyMapperDefinitions implements BeanFactoryPostProcessor {
        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            for (String name : beanFactory.getBeanDefinitionNames()) {
                BeanDefinition definition = beanFactory.getBeanDefinition(name);
                String className = definition.getBeanClassName();
                if (className != null && className.contains("MapperFactoryBean")) {
                    definition.setLazyInit(true);
                }
            }
        }
    }

    private static Set<String> declaredScanPackages() {
        org.mybatis.spring.annotation.MapperScan scan = AiEmbeddedMapperConfiguration.class
                .getAnnotation(org.mybatis.spring.annotation.MapperScan.class);
        assertThat(scan).as("@MapperScan 必须直接在配置类上（不是元注解间接声明）").isNotNull();
        return new LinkedHashSet<>(List.of(scan.value()));
    }

    private static Set<String> mapperPackagesWithRegisteredBeans(AnnotationConfigApplicationContext context) {
        Set<String> found = new TreeSet<>();
        for (String name : context.getBeanDefinitionNames()) {
            BeanDefinition definition = context.getBeanDefinition(name);
            String className = definition.getBeanClassName();
            if (className == null || !className.contains("MapperFactoryBean")) {
                continue;
            }
            // MapperFactoryBean 的 mapperInterface 通常作为第 0 个构造参数传入；
            // 少数注册方式不带索引参数，这时按 bean 名反推包（bean 名默认是接口简单名的首字母小写形式），
            // 反推不出来就跳过——判据本身靠 declaredScanPackages 的覆盖层兜底。
            org.springframework.beans.factory.config.ConstructorArgumentValues args =
                    definition.getConstructorArgumentValues();
            org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder holder =
                    args.getIndexedArgumentValue(0, Object.class);
            if (holder == null) {
                holder = args.getGenericArgumentValue(Object.class);
            }
            Object iface = holder == null ? null : holder.getValue();
            if (iface instanceof Class<?> mapperInterface) {
                found.add(mapperInterface.getPackageName());
            } else if (iface instanceof String text) {
                int dot = text.lastIndexOf('.');
                if (dot > 0) {
                    found.add(text.substring(0, dot));
                }
            }
        }
        return found;
    }

    private static Path locate(Path relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate " + relative + " from " + Path.of("").toAbsolutePath());
    }

    /** 保留：便于诊断时列出容器里全部 mapper bean 名。 */
    static List<String> mapperBeanNames(AnnotationConfigApplicationContext context) {
        List<String> out = new ArrayList<>();
        for (String name : context.getBeanDefinitionNames()) {
            String className = context.getBeanDefinition(name).getBeanClassName();
            if (className != null && className.contains("MapperFactoryBean")) {
                out.add(name);
            }
        }
        return out;
    }
}
