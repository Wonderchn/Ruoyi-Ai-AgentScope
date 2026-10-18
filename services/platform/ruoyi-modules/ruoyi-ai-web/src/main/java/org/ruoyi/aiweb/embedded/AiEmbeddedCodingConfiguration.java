package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.coding.config.CodingHarnessBeans;
import com.nageoffer.ai.ragent.coding.controller.CodingHarnessController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * F20-A 编程助手 Harness 的内嵌装配。
 *
 * <p>三道门与既有内嵌面一致：{@code ai.integration.enabled=true}、
 * {@code ai.integration.transport=local}、{@code p2.enabled=true}。任一门关闭时整面消失。
 *
 * <p>装配内容是显式闭包（{@link CodingHarnessBeans} + {@link CodingHarnessController}）：
 * {@code com.nageoffer.ai.ragent.coding} 不在 platform 扫描根下，不显式登记就不是 bean。
 * 默认模型网关 fail-closed（运行终态 {@code MODEL_NOT_CONFIGURED}），execute_process 默认关闭，
 * Docker 策略未配置时拒绝且零执行。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedCodingConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
    @Import({CodingHarnessBeans.class, CodingHarnessController.class})
    static class CodingSurface {
    }
}
