package org.ruoyi.aiintegration.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

/**
 * Preserve the bounded gateway upload body until its dedicated streaming handler.
 *
 * <p>走专用流通道（{@link AiGatewayStreamController}）的上传路径必须在这里排除，
 * 否则外层 multipartResolver 先把请求解析成 {@code StandardMultipartHttpServletRequest}
 * （原始体被消费；本地形态再包一层会把表单字段变成 ApplicationPart 形态 ⇒ 400，
 * 见 red/a1-fixfield-web/ 的复核 F1）：
 * <ul>
 *   <li>{@code POST /api/ai/v1/documents/uploads}（workbench/P2 专用流上传）；</li>
 *   <li>{@code POST /api/ai/v1/knowledge-base/{kbId}/docs/upload}（admin 上传，F05:1
 *       复用同一传输机制）。</li>
 * </ul>
 *
 * <p><b>与 {@link GatewayRawUploadFilter} 的排除集成对</b>：两处必须同时登记同一路径集
 * （一处漏登记即外层消费 multipart，重蹈 a1-fixfield 的 400 坑）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class GatewayMultipartConfiguration {

    /**
     * 专用流上传的路径形状：exact 路径 + 单段 {@code kbId} 的 admin 上传
     * （与网关路由形状一致；{@code [^/]+} 只放宽段内容、不放宽段数）。
     */
    private static boolean isRawStreamUpload(String path) {
        return "/api/ai/v1/documents/uploads".equals(path)
                || path.matches("/api/ai/v1/knowledge-base/[^/]+/docs/upload")
                // F11:4 技能包导入（5 MiB 级 multipart；通用转发 2 MiB 上限不适用，
                // 必须走专用流通道并经 GatewayRawUploadFilter 成对排除）
                || "/api/ai/v1/agent-catalog/agent-skills/import".equals(path);
    }

    @Bean(name = "multipartResolver")
    StandardServletMultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver() {
            @Override public boolean isMultipart(HttpServletRequest request) {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                return !isRawStreamUpload(path) && super.isMultipart(request);
            }
        };
    }
}
