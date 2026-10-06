package com.nageoffer.ai.ragent.authorization;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * (3) 交付 permit 泄漏修复的最小切片：只加载 {@link AiDeliveryPermitConfiguration}（不拉既有
 * WebMvcConfigurer，否则 hasSingleBean(WebMvcConfigurer.class) 会红）。
 *
 * <p>锚点断言：holder 必须存在且拦截器**真的**注册进 registry（"能被装配"与"被注册"是两件事）。
 */
@SpringJUnitConfig(AiDeliveryPermitConfiguration.class)
class AiDeliveryPermitConfigurationTest {

    @Autowired
    private AiDeliveryPermitConfiguration configuration;

    @Autowired
    private AiDeliveryPermitConfiguration.DeliveryPermitHolder holder;

    @Autowired
    private AiDeliveryPermitConfiguration.DeliveryPermitInterceptor interceptor;

    /** 具体类断言（不用接口：本仓 @ConditionalOnMissingBean 按返回类型推断有已知坑）。 */
    @Test
    void beansArePresent() {
        assertNotNull(configuration);
        assertNotNull(holder);
        assertNotNull(interceptor);
    }

    /** 真调一次 addInterceptors，验证拦截器确实被注册（不是只"存在于上下文里"）。 */
    @Test
    void interceptorIsRegisteredExactlyOnce() {
        InterceptorRegistry registry = Mockito.mock(InterceptorRegistry.class);
        configuration.addInterceptors(registry);
        Mockito.verify(registry, Mockito.times(1)).addInterceptor(Mockito.any());
    }

    /** 成功路径登记的 permit 全部释放，且清空后二次调用不再有 DB 往返。 */
    @Test
    void closeAllReleasesEveryPermitAndClearsTheList() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AtomicInteger closed = new AtomicInteger();
        holder.register(closed::incrementAndGet, "permit-1", "op-1");
        holder.register(closed::incrementAndGet, "permit-2", "op-2");

        assertEquals(2, holder.closeAll(request));
        assertEquals(2, closed.get());
        assertEquals(0, holder.closeAll(request));
    }

    /** 逐项失败要暴露（记录）但不能中断其余释放，列表也必须清空。 */
    @Test
    void closeAllKeepsReleasingAfterAFailureAndStillClears() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AtomicInteger closed = new AtomicInteger();
        holder.register(() -> {
            throw new IllegalStateException("release failed");
        }, "permit-bad", "op-bad");
        holder.register(closed::incrementAndGet, "permit-good", "op-good");

        assertEquals(1, holder.closeAll(request));
        assertEquals(1, closed.get());
        assertEquals(0, holder.closeAll(request));
    }

    /** 无请求绑定（非 web 线程）时 register 静默跳过，不能把登录/切片打炸。 */
    @Test
    void registerOutsideARequestIsSkipped() {
        holder.register(() -> {
        }, "permit-none", "op-none");
    }

    @AfterEach
    void resetRequestAttributes() {
        RequestContextHolder.resetRequestAttributes();
    }
}
