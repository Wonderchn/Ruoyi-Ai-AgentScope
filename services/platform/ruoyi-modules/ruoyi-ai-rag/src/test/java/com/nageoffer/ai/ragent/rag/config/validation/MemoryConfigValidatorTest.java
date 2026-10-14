/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.config.validation;

import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.Tag;

/**
 * 记忆配置启动校验钉子：开了摘要才校验轮数关系，startTurns 必须 > keepTurns
 * F16:0 负例（summaryStartTurns<=historyKeepTurns 启动拒）在此固化
 */
@Tag("dev")
class MemoryConfigValidatorTest {

    private final ConstraintValidatorContext context = mock(ConstraintValidatorContext.class);
    private final MemoryConfigValidator validator = new MemoryConfigValidator();

    @Test
    @DisplayName("开摘要且 startTurns<=keepTurns：拒绝并给出建议模板")
    void rejectsStartTurnsNotAfterKeepTurnsWhenSummaryEnabled() {
        MemoryProperties config = new MemoryProperties();
        config.setSummaryEnabled(true);
        config.setSummaryStartTurns(8);
        config.setHistoryKeepTurns(8);

        assertThat(validator.isValid(config, context)).isFalse();
        verify(context).disableDefaultConstraintViolation();
        verify(context).buildConstraintViolationWithTemplate(anyString());
    }

    @Test
    @DisplayName("开摘要且 startTurns>keepTurns：通过且不动 context")
    void acceptsStartTurnsAfterKeepTurnsWhenSummaryEnabled() {
        MemoryProperties config = new MemoryProperties();
        config.setSummaryEnabled(true);
        config.setSummaryStartTurns(9);
        config.setHistoryKeepTurns(8);

        assertThat(validator.isValid(config, context)).isTrue();
        verify(context, never()).disableDefaultConstraintViolation();
        verify(context, never()).buildConstraintViolationWithTemplate(anyString());
    }

    @Test
    @DisplayName("关摘要：轮数关系不校验")
    void skipsTurnsCheckWhenSummaryDisabled() {
        MemoryProperties config = new MemoryProperties();
        config.setSummaryEnabled(false);
        config.setSummaryStartTurns(8);
        config.setHistoryKeepTurns(8);

        assertThat(validator.isValid(config, context)).isTrue();
        verify(context, never()).disableDefaultConstraintViolation();
    }

    @Test
    @DisplayName("配置缺席：放行（绑定器只在有实例时校验）")
    void acceptsNullConfig() {
        assertThat(validator.isValid(null, context)).isTrue();
    }
}
