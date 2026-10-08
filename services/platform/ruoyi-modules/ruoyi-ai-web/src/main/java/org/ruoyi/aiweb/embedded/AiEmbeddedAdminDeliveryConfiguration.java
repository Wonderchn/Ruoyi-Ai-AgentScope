package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.audit.controller.BizChangeLogController;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.rag.controller.IntentTreeController;
import com.nageoffer.ai.ragent.rag.controller.QueryTermMappingController;
import com.nageoffer.ai.ragent.rag.controller.RagTraceController;
import com.nageoffer.ai.ragent.rag.eval.EvalController;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import com.nageoffer.ai.ragent.sample.controller.SampleQuestionController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Exact controller GET replies receive P2 delivery permits; writes and failures do not. */
@AutoConfiguration
@ConditionalOnEmbeddedLocal
@ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
@Import(AiEmbeddedAdminDeliveryConfiguration.AdminDeliveryAdvice.class)
public class AiEmbeddedAdminDeliveryConfiguration {
    @RestControllerAdvice(assignableTypes = {IntentTreeController.class, QueryTermMappingController.class,
            SampleQuestionController.class, RagTraceController.class, BizChangeLogController.class,
            EvalController.class})
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
    public static class AdminDeliveryAdvice implements ResponseBodyAdvice<Object> {
        private final DeliveryPermits permits;

        public AdminDeliveryAdvice(DeliveryPermits permits) {
            this.permits = permits;
        }

        @Override
        public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
            return method.hasMethodAnnotation(GetMapping.class) && action(method) != null;
        }

        @Override
        public Object beforeBodyWrite(Object body, MethodParameter method, MediaType type,
                                      Class<? extends HttpMessageConverter<?>> converter,
                                      ServerHttpRequest request, ServerHttpResponse response) {
            if (request.getMethod() != HttpMethod.GET || !(body instanceof ApiEnvelope<?> envelope)
                    || envelope.code() != 200) {
                return body;
            }
            String action = action(method);
            if (action == null) {
                return body;
            }
            var permit = permits.enter(PrincipalContext.require(), action,
                    "admin-read:" + request.getURI().getRawPath());
            try {
                response.getHeaders().set("Cache-Control", "no-store");
                response.getHeaders().set("X-AI-Delivery-Permit", permit.permitId());
                response.getHeaders().set("X-AI-Delivery-Operation", permit.operationId());
                return body;
            } catch (RuntimeException failure) {
                permit.close();
                throw failure;
            }
        }

        private static String action(MethodParameter method) {
            Class<?> controller = method.getContainingClass();
            if (controller == IntentTreeController.class || controller == QueryTermMappingController.class) {
                return "config.read";
            }
            if (controller == SampleQuestionController.class) {
                return "kb.read";
            }
            if (controller == RagTraceController.class) {
                return "nodes".equals(method.getMethod().getName()) ? "run.events" : "run.get";
            }
            if (controller == BizChangeLogController.class) {
                return "run.get";
            }
            return controller == EvalController.class ? "kb.retrieve" : null;
        }
    }
}
