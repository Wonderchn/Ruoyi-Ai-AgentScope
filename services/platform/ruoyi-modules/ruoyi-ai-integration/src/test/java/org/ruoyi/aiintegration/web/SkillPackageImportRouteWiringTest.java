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

package org.ruoyi.aiintegration.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F11:4：技能包导入必须走专用流通道（精确映射 + 两处 multipart 排除成对登记）。
 *
 * <p>这是结构性事实的静态钉住：真实 multipart 可达性由真机腿证明。
 */
@Tag("dev")
class SkillPackageImportRouteWiringTest {

    private static final String IMPORT_PATH = "/api/ai/v1/agent-catalog/agent-skills/import";

    @Test
    void importIsAnExactStreamPostMapping() throws Exception {
        Method method = AiGatewayStreamController.class.getMethod(
                "importSkillPackage", HttpServletRequest.class, HttpServletResponse.class);
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).contains("/agent-catalog/agent-skills/import");
    }

    @Test
    void multipartExclusionsArePairedForImport() throws Exception {
        assertThat(rawStreamUpload(GatewayMultipartConfiguration.class)).isTrue();
        assertThat(rawStreamUpload(GatewayRawUploadFilter.class)).isTrue();
    }

    private static boolean rawStreamUpload(Class<?> type) throws Exception {
        Method method = type.getDeclaredMethod("isRawStreamUpload", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, IMPORT_PATH);
    }
}
