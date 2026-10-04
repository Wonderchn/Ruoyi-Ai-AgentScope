package org.ruoyi.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.json.config.JacksonConfig;
import org.ruoyi.common.json.config.PlatformObjectMapperConfig;
import org.ruoyi.system.domain.bo.SysTenantBo;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class TenantRequestJacksonTest {

    private static final String TENANT_REQUEST = """
        {
          "contactPhone": "13700000000",
          "expireTime": "2027-09-11 00:00:00",
          "accountCount": -1,
          "companyName": "第一集",
          "contactUserName": "刘备",
          "username": "user",
          "password": "123456",
          "packageId": "2018611998196109314"
        }
        """;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        // Boot 4 默认 Jackson 3；应用面使用 Jackson 2，需要显式声明优先映射器与 Jackson2 自动装配
        .withPropertyValues("spring.http.converters.preferred-json-mapper=jackson2")
        .withConfiguration(AutoConfigurations.of(JacksonConfig.class, Jackson2AutoConfiguration.class,
            PlatformObjectMapperConfig.class, HttpMessageConvertersAutoConfiguration.class));

    @Test
    void readsTenantRequestWithApplicationDateFormat() {
        contextRunner.withPropertyValues("spring.jackson2.date-format=yyyy-MM-dd HH:mm:ss").run(context -> {
            MappingJackson2HttpMessageConverter converter = jsonConverter(context);
            assertSame(context.getBean(ObjectMapper.class), converter.getObjectMapper());

            SysTenantBo tenant = readTenant(converter, TENANT_REQUEST);

            assertEquals(expirationDate(), tenant.getExpireTime());
            assertEquals(2018611998196109314L, tenant.getPackageId());
            assertEquals(-1L, tenant.getAccountCount());
            assertEquals("第一集", tenant.getCompanyName());
            assertEquals("user", tenant.getUsername());
        });
    }

    @Test
    void keepsRegisteredDateDeserializerWithoutDateFormatProperty() {
        contextRunner.run(context -> {
            SysTenantBo tenant = readTenant(jsonConverter(context), TENANT_REQUEST);

            assertEquals(expirationDate(), tenant.getExpireTime());
        });
    }

    @Test
    void honorsConfiguredDateSerializationFormat() {
        contextRunner.withPropertyValues("spring.jackson2.date-format=yyyy/MM/dd HH:mm:ss").run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            String json = mapper.writeValueAsString(new DateValue(expirationDate()));

            assertEquals("{\"expireTime\":\"2027/09/11 00:00:00\"}", json);
        });
    }

    @Test
    void preservesWorkflowJsonRoundTripAndNullOmission() {
        contextRunner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            WorkflowValue value = new WorkflowValue(2018611998196109314L,
                LocalDateTime.of(2027, 9, 11, 0, 0), Optional.of("workflow"), null);

            String json = mapper.writeValueAsString(value);
            JsonNode tree = mapper.readTree(json);

            assertTrue(tree.get("id").isTextual());
            assertEquals("2018611998196109314", tree.get("id").asText());
            assertEquals("2027-09-11 00:00:00", tree.get("createdAt").asText());
            assertEquals("workflow", tree.get("name").asText());
            assertFalse(tree.has("remark"));
            assertEquals(value, mapper.readValue(json, WorkflowValue.class));
        });
    }

    @Test
    void acceptsNullExpirationDate() {
        contextRunner.run(context -> {
            String request = TENANT_REQUEST.replace("\"2027-09-11 00:00:00\"", "null");
            SysTenantBo tenant = readTenant(jsonConverter(context), request);

            assertNull(tenant.getExpireTime());
        });
    }

    @Test
    void rejectsInvalidExpirationDate() {
        contextRunner.run(context -> {
            String request = TENANT_REQUEST.replace("2027-09-11 00:00:00", "invalid-date");
            MappingJackson2HttpMessageConverter converter = jsonConverter(context);

            assertThrows(HttpMessageNotReadableException.class, () -> readTenant(converter, request));
        });
    }

    /**
     * Boot 4 不再把 JSON 转换器注册为 {@code MappingJackson2HttpMessageConverter} bean，
     * 而是通过 {@link ServerHttpMessageConvertersCustomizer} 把转换器加入
     * {@link HttpMessageConverters} 构建结果。这里走同一条装配路径取值，
     * 从而仍然验证「HTTP JSON 使用应用权威 ObjectMapper」这一契约。
     */
    private static MappingJackson2HttpMessageConverter jsonConverter(AssertableApplicationContext context) {
        HttpMessageConverters.ServerBuilder builder = HttpMessageConverters.forServer();
        // 服务端转换器由多个 customizer 共同贡献（string / jackson2 / 其它），按 @Order 全部套用，
        // 与 Boot 4 装配路径一致，而不是只取其中一个。
        context.getBeanProvider(ServerHttpMessageConvertersCustomizer.class).orderedStream()
            .forEach(customizer -> customizer.customize(builder));
        // Spring 7 的 HttpMessageConverters 是 Iterable（不再暴露 getConverters()）
        return StreamSupport.stream(builder.build().spliterator(), false)
            .filter(MappingJackson2HttpMessageConverter.class::isInstance)
            .map(MappingJackson2HttpMessageConverter.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Boot 4 未按 preferred-json-mapper=jackson2 注册 Jackson 2 JSON 转换器"));
    }

    private static SysTenantBo readTenant(MappingJackson2HttpMessageConverter converter, String json) throws Exception {
        MockHttpInputMessage input = new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8));
        input.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return (SysTenantBo) converter.read(SysTenantBo.class, input);
    }

    private static Date expirationDate() {
        return Date.from(LocalDateTime.of(2027, 9, 11, 0, 0).atZone(ZoneId.systemDefault()).toInstant());
    }

    private record DateValue(Date expireTime) {
    }

    private record WorkflowValue(Long id, LocalDateTime createdAt, Optional<String> name, String remark) {
    }
}
