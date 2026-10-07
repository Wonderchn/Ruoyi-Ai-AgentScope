package org.ruoyi.common.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 拒绝不安全 HTTP 方法的容器无关配置。
 * <p>
 * 原 {@code UndertowConfig} 用 Undertow 的 {@code DisallowedMethodsHandler} 拒绝
 * CONNECT/TRACE/TRACK。Boot 4 已移除 Undertow 支持，改用 Tomcat；这里用最高优先级的
 * 过滤器保留同一安全语义，行为不再依赖具体容器：
 * <ul>
 *   <li>CONNECT/TRACE/TRACK 一律 405，且不带响应体，不进入后续过滤链与控制器；</li>
 *   <li>其它方法不受影响，SSE、multipart 上传与 WebSocket 升级路径继续按原语义工作。</li>
 * </ul>
 * Undertow 专有的 WebSocket 缓冲区池与虚拟线程执行器在 Tomcat 下由容器自身管理，
 * 不再需要显式配置；虚拟线程统一由 {@code spring.threads.virtual.enabled} 控制。
 */
@AutoConfiguration
public class UnsafeHttpMethodConfig {

    /** 与迁移前 Undertow 配置一致的三类不安全方法。 */
    private static final Set<String> DISALLOWED = Set.of("CONNECT", "TRACE", "TRACK");

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> disallowedHttpMethodsFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                if (DISALLOWED.contains(request.getMethod().toUpperCase())) {
                    response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                    response.setContentLength(0);
                    return;
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("disallowedHttpMethodsFilter");
        return registration;
    }

}
