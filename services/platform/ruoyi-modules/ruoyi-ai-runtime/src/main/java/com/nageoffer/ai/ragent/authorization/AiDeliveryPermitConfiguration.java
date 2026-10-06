package com.nageoffer.ai.ragent.authorization;

import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * 交付 permit 的 request 作用域持有 + {@code afterCompletion} 单点释放。
 *
 * <p><b>背景（T4 / (3) 交付 permit 泄漏）</b>：{@code AiResourceController.reply(...)} 在 JSON 转发路径
 * 调 {@code revocations.enter(...)} 登记了一条交付 permit，失败路径 {@code operation.close()} 了，
 * 但**成功路径把 permitId 放进响应头后无人释放** ⇒ 每次成功 rename 泄漏 1 条 ACTIVE permit
 * （C9 每次跑完都要人工复位）。本类提供请求级持有者：{@code reply} 登记，拦截器在
 * {@code afterCompletion} 里单点释放。
 *
 * <p><b>为什么不用 {@code @Scope("request")} 代理 bean</b>：{@code RequestContextHolder} 的请求属性
 * 本身就是 request 作用域，且 {@code afterCompletion} 阶段**同线程、请求仍绑定**、仍可读写；
 * 换成 request 作用域代理 bean 反而会在响应完成后抛 {@code IllegalStateException} —— 正是本场景要避免的。
 * 判空只用 {@code RequestContextHolder.getRequestAttributes() == null}（{@code currentRequestAttributes()}
 * 会抛异常，不能当判空手段）。
 *
 * <p><b>幂等</b>：不为"重复释放"写任何分支 —— {@code RevocationGuard.Operation#close()} 有
 * {@code if (closed) return;} 标志、释放本身在 DB 层也幂等；因此 {@code closeAll()} 里捕获到的异常
 * **全是真失败，必须暴露**（逐项 log.error，且无论逐项成败最后都清空列表）。
 */
@AutoConfiguration
public class AiDeliveryPermitConfiguration implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AiDeliveryPermitConfiguration.class);

    @Bean
    public DeliveryPermitHolder deliveryPermitHolder() {
        return new DeliveryPermitHolder();
    }

    @Bean
    public DeliveryPermitInterceptor deliveryPermitInterceptor(DeliveryPermitHolder holder) {
        return new DeliveryPermitInterceptor(holder);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(deliveryPermitInterceptor(deliveryPermitHolder()));
    }

    /**
     * 一次待释放的交付许可。用记录而不是直接持有 {@link RevocationGuard.Operation}，
     * 使本类不依赖 {@code RevocationGuard} 的具体嵌套类型（便于切片测试），并保证日志里
     * permitId/operationId 一定可打。
     */
    public record PendingPermit(AutoCloseable permit, String permitId, String operationId) {
    }

    /**
     * 请求级持有者。非 bean 代理：数据存在请求属性里（{@code SCOPE_REQUEST}），
     * 因此 {@code afterCompletion} 仍可读取。
     */
    public static class DeliveryPermitHolder {

        private static final String ATTR = DeliveryPermitHolder.class.getName() + ".pending";

        /** 登记一条待释放的交付许可；非 web 线程/无请求绑定时静默跳过（不改变 reply 的失败语义）。 */
        @SuppressWarnings("unchecked")
        public void register(AutoCloseable permit, String permitId, String operationId) {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes == null || permit == null) {
                return;
            }
            // 追加而不是覆盖：同一请求可能登记多条（reply 与 kb.retrieve 各有一次 enter）
            Object existing = attributes.getAttribute(ATTR, RequestAttributes.SCOPE_REQUEST);
            List<PendingPermit> pending = existing instanceof List<?> list
                ? new ArrayList<>((List<PendingPermit>) list) : new ArrayList<>();
            pending.add(new PendingPermit(permit, permitId, operationId));
            attributes.setAttribute(ATTR, pending, RequestAttributes.SCOPE_REQUEST);
        }

        /** 单点释放：逐项 catch（真失败要暴露），无论成败最后清空列表（避免同请求二次调用多一次无谓 DB 往返）。 */
        public int closeAll(HttpServletRequest request) {
            if (request == null) {
                return 0;
            }
            Object raw = request.getAttribute(ATTR);
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                request.removeAttribute(ATTR);
                return 0;
            }
            int released = 0;
            try {
                for (Object item : list) {
                    if (!(item instanceof PendingPermit pending)) {
                        continue;
                    }
                    try {
                        pending.permit().close();
                        released++;
                    } catch (Exception e) {
                        log.error("交付 permit 释放失败 permitId={} operationId={}",
                            pending.permitId(), pending.operationId(), e);
                    }
                }
            } finally {
                request.removeAttribute(ATTR);
            }
            return released;
        }
    }

    /** 请求完成即释放本请求登记的全部交付 permit（成功与异常路径都走这里）。 */
    public static class DeliveryPermitInterceptor implements HandlerInterceptor {

        private final DeliveryPermitHolder holder;

        public DeliveryPermitInterceptor(DeliveryPermitHolder holder) {
            this.holder = holder;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                    Exception ex) {
            holder.closeAll(request);
        }
    }
}
