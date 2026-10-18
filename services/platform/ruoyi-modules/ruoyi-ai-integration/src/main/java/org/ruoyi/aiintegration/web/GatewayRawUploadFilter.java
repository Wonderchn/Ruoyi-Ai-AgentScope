package org.ruoyi.aiintegration.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

/**
 * 专用流上传路由的 multipart 参数抑制：这些精确路径只从 header 鉴权，绝不读 multipart 参数。
 *
 * <p>排除集与 {@link GatewayMultipartConfiguration} <b>成对</b>：
 * <ul>
 *   <li>{@code POST /api/ai/v1/documents/uploads}（workbench/P2 专用流上传）；</li>
 *   <li>{@code POST /api/ai/v1/knowledge-base/{kbId}/docs/upload}（admin 上传，F05:1）。</li>
 * </ul>
 * 一处漏登记即可能在鉴权路径上触发容器 multipart 解析（消费原始体）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name="ai.integration.enabled",havingValue="true")
public class GatewayRawUploadFilter extends OncePerRequestFilter {

    /** 与 {@code GatewayMultipartConfiguration} 的同一路径形状（成对，逐字一致）。 */
    private static boolean isRawStreamUpload(String path) {
        return "/api/ai/v1/documents/uploads".equals(path)
                || path.matches("/api/ai/v1/knowledge-base/[^/]+/docs/upload")
                // F11:4 技能包导入：与 GatewayMultipartConfiguration 成对登记
                || "/api/ai/v1/agent-catalog/agent-skills/import".equals(path);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod()) || !isRawStreamUpload(path);
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public String getParameter(String name){return null;}
            @Override public String[] getParameterValues(String name){return null;}
            @Override public Map<String,String[]> getParameterMap(){return Map.of();}
            @Override public Enumeration<String> getParameterNames(){return Collections.emptyEnumeration();}
        },response);
    }
}
