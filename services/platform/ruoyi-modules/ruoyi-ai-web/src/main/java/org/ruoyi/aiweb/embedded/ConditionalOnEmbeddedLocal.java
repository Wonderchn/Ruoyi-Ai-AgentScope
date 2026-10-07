package org.ruoyi.aiweb.embedded;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Require both embedded switches on each independently scannable component.
 * Conditions on an enclosing configuration do not apply to a nested component
 * discovered by the platform's component scan.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnEmbeddedLocal.EmbeddedLocalCondition.class)
public @interface ConditionalOnEmbeddedLocal {

    class EmbeddedLocalCondition extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var environment = context.getEnvironment();
            if (!"true".equalsIgnoreCase(environment.getProperty("ai.integration.enabled"))) {
                return ConditionOutcome.noMatch("ai.integration.enabled must be true");
            }
            if (!"local".equalsIgnoreCase(environment.getProperty("ai.integration.transport"))) {
                return ConditionOutcome.noMatch("ai.integration.transport must be local");
            }
            return ConditionOutcome.match("AI integration is enabled with local transport");
        }
    }
}
