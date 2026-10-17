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

package com.nageoffer.ai.ragent.attachment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F04：PdfBounds 时间边界。生产路径固定 5s 预算；这里用显式截止时间证明“超时=拒绝”这条边界真实生效，
 * 而不是只有页数/字符两个维度。
 */
@Tag("dev")
class PdfBoundsDeadlineTest {

    @Test
    @DisplayName("正例：有界入口在预算内正常提取")
    void boundedEntryExtractsWithinBudget() throws Exception {
        String text = PdfBounds.extract(PdfFixtures.pdfWithPageTexts(List.of("page one marker"), false, false));
        assertThat(text).contains("page one marker");
    }

    @Test
    @DisplayName("负例：已过期的解析截止时间必须拒绝（超时不是放行）")
    void expiredDeadlineIsRefused() throws Exception {
        byte[] pdf = PdfFixtures.pdfWithPageTexts(List.of("page one marker"), false, false);
        assertThatThrownBy(() -> PdfBounds.extract(pdf, System.nanoTime() - 1_000_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("time limit");
    }
}
