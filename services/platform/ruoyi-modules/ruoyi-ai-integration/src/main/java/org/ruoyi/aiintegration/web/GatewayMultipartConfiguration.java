package org.ruoyi.aiintegration.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

/** Preserve the bounded gateway upload body until its dedicated streaming handler. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class GatewayMultipartConfiguration {
    @Bean(name = "multipartResolver")
    StandardServletMultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver() {
            @Override public boolean isMultipart(HttpServletRequest request) {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                return !"/api/ai/v1/documents/uploads".equals(path) && super.isMultipart(request);
            }
        };
    }
}
