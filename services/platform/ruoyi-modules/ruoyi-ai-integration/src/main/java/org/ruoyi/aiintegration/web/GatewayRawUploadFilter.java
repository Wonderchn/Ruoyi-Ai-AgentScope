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

/** The exact raw gateway route authenticates from headers, never multipart parameters. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name="ai.integration.enabled",havingValue="true")
public class GatewayRawUploadFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/api/ai/v1/documents/uploads".equals(
                request.getRequestURI().substring(request.getContextPath().length()));
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
