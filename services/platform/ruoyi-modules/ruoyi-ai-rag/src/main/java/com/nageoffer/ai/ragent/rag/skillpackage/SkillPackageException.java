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

package com.nageoffer.ai.ragent.rag.skillpackage;

/**
 * 技能包导入导出的业务拒绝。
 *
 * <p>控制器在方法内捕获并映射为具名 4xx 信封（HTTP status == body.code），
 * 不依赖全局异常解析器的兜底映射——否则客户端的"坏包/冲突"会被误标成 403
 * {@code TENANT_CONTEXT_MISSING} 或 5xx。
 */
public class SkillPackageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final SkillPackageError error;

    public SkillPackageException(SkillPackageError error) {
        super(error.message());
        this.error = error;
    }

    public SkillPackageException(SkillPackageError error, String detail) {
        super(error.message() + (detail == null || detail.isBlank() ? "" : "：" + detail));
        this.error = error;
    }

    public SkillPackageError error() {
        return error;
    }

    public int httpStatus() {
        return error.httpStatus();
    }

    public String errorCode() {
        return error.code();
    }
}
