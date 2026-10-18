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

package org.ruoyi.aiweb.embedded;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.core.graph.GraphErrorCode;
import com.nageoffer.ai.ragent.rag.core.graph.GraphOperationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** R14：GraphOperationException 的 HTTP 状态与符号码经统一 AI 信封外显。 */
@Tag("dev")
class GraphExceptionResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("409 TRUNCATED：HTTP 409 + data.errorCode 不丢失")
    void truncatedMapsTo409WithSymbol() throws Exception {
        var response = new MockHttpServletResponse();
        new AiInternalExceptionResolver().resolveException(new MockHttpServletRequest(),
                response, null, new GraphOperationException(GraphErrorCode.GRAPH_PROJECTION_TRUNCATED));
        JsonNode body = mapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(body.path("code").asInt()).isEqualTo(409);
        assertThat(body.path("data").path("errorCode").asText()).isEqualTo("GRAPH_PROJECTION_TRUNCATED");
        assertThat(body.path("msg").asText()).isNotBlank();
    }

    @Test
    @DisplayName("503 后端不可用与 404 资源语义各自保留")
    void otherCodesKeepTheirStatus() {
        var unavailable = new MockHttpServletResponse();
        new AiInternalExceptionResolver().resolveException(new MockHttpServletRequest(),
                unavailable, null, new GraphOperationException(GraphErrorCode.GRAPH_BACKEND_UNAVAILABLE));
        assertThat(unavailable.getStatus()).isEqualTo(503);

        var notFound = new MockHttpServletResponse();
        new AiInternalExceptionResolver().resolveException(new MockHttpServletRequest(),
                notFound, null, new GraphOperationException(GraphErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        assertThat(notFound.getStatus()).isEqualTo(404);
    }
}
