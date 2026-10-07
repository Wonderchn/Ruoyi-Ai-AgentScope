package com.nageoffer.ai.ragent.authorization;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
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
 *
 * <p><b>本类此前缺 {@code @Tag("dev")} ⇒ 在 {@code -Pdev} 下一行都没跑</b>（父 pom 把 surefire 配成
 * {@code <groups>${profiles.active}</groups>}，交付构建日志里这个类出现 0 次）。加上之后这 5 条才真正进入验收。
 */
@Tag("dev")
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

    /**
     * 无请求绑定（非 web 线程、内部直调、异步收尾）时，{@code register} 必须**就地释放**这条 permit。
     *
     * <p>旧写法叫 {@code registerOutsideARequestIsSkipped} 且**只调用不断言**，等于把"既不登记也不释放"
     * 固化成期望行为 —— 而调用方 {@code AiResourceController.reply} 在登记之前已经
     * {@code revocations.enter()} 往库里落了 ACTIVE permit，静默跳过就是 (3) 泄漏的第二条路径。
     */
    @Test
    void registerOutsideARequestReleasesThePermitInPlace() {
        AtomicInteger closed = new AtomicInteger();
        holder.register(closed::incrementAndGet, "permit-none", "op-none");

        assertEquals(1, closed.get(), "无请求绑定时 permit 必须就地释放，不能留在 ACTIVE");
    }

    @AfterEach
    void resetRequestAttributes() {
        RequestContextHolder.resetRequestAttributes();
    }
}
