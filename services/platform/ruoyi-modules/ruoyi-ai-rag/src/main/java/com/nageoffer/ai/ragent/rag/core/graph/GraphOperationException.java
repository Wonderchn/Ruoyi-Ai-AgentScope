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

package com.nageoffer.ai.ragent.rag.core.graph;

/**
 * 图谱面业务异常：携带符号码与 HTTP 状态，由内嵌异常解析器映射为 AI 信封。
 *
 * <p>刻意不继承 {@code ServiceException}：后者统一映射 503，会把 400/404/409 的语义抹平。
 */
public class GraphOperationException extends RuntimeException {

    private final GraphErrorCode errorCode;

    public GraphOperationException(GraphErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public GraphOperationException(GraphErrorCode errorCode, String detail) {
        super(detail == null || detail.isBlank() ? errorCode.message() : detail);
        this.errorCode = errorCode;
    }

    public GraphErrorCode errorCode() {
        return errorCode;
    }

    public int httpStatus() {
        return errorCode.httpStatus();
    }
}
