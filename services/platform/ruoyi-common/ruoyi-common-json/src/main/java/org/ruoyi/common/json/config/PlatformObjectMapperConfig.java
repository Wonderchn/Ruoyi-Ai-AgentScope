package org.ruoyi.common.json.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * platform 侧权威 ObjectMapper。此前该 bean 由 ruoyi-aiflow 的 BeanConfig 以 @Primary 提供，
 * 旧 AI 模块退场后必须在保留侧落地，否则 Long 精度保护与 null 省略策略会静默消失。
 * <p>
 * 必须在 Jackson2AutoConfiguration 之前注册：其 jacksonObjectMapper 同时带 @Primary 与
 * {@code @ConditionalOnMissingBean}，若让它先注册就会出现两个 @Primary 的 ObjectMapper，
 * 上下文以 "more than one 'primary' bean found" 启动失败。
 * <p>
 * Boot 4 下应用面统一使用 Jackson 2（Jackson 3 已从 web starter 传递路径摘除），
 * 因此对齐 Jackson2AutoConfiguration 而不是 JacksonAutoConfiguration。
 */
@AutoConfiguration(before = Jackson2AutoConfiguration.class)
public class PlatformObjectMapperConfig {

    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Bean
    @Primary
    public ObjectMapper platformObjectMapper(Jackson2ObjectMapperBuilder builder) {
        // 沿用容器构建器，保留 spring.jackson.* 与 JacksonConfig 贡献的模块
        ObjectMapper objectMapper = builder.createXmlMapper(false).build();

        SimpleModule platformModule = new SimpleModule();
        // Long 一律输出为字符串，避免前端 JS 精度丢失
        platformModule.addSerializer(Long.class, ToStringSerializer.instance);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        platformModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(formatter));
        platformModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(formatter));

        // SimpleModule 先注册、JavaTimeModule 后注册：Jackson 按类型 id 去重，
        // 后者与容器已注册的 JavaTimeModule 同类型会被跳过，因此上面自定义的处理器实际生效。
        objectMapper.registerModules(platformModule, new JavaTimeModule());
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return objectMapper;
    }
}
