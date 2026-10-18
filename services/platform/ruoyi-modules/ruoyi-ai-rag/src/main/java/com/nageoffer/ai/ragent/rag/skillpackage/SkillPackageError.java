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
 * F11:4 技能包导入导出的具名错误码。
 *
 * <p>每个错误都绑定一个 4xx HTTP 状态：坏包/缺参 400、超限 413、冲突 409、未找到 404。
 * 导入导出<b>不得</b>以 5xx 或 HTTP 200 + 业务错误码收尾（内嵌网关要求
 * {@code HTTP status == body.code}），也不得在拒绝路径留下部分写入。
 */
public enum SkillPackageError {

    /** multipart 缺少 {@code file} 或文件名/内容为空。 */
    MISSING_FILE(400, "MISSING_FILE", "缺少技能包文件"),

    /** ZIP 结构、entry 路径、manifest 或正文不满足冻结契约。 */
    INVALID_PACKAGE(400, "INVALID_PACKAGE", "技能包不合法"),

    /** ZIP/entry/解压总量/entry 数超过冻结上限。 */
    PACKAGE_TOO_LARGE(413, "PACKAGE_TOO_LARGE", "技能包超过大小限制"),

    /** skillCode 已存在；默认拒绝，不覆盖既有行。 */
    SKILL_CONFLICT(409, "SKILL_CONFLICT", "技能标识已存在"),

    /** 导出目标不存在（含跨租户不可见）。 */
    SKILL_NOT_FOUND(404, "SKILL_NOT_FOUND", "技能不存在");

    private final int httpStatus;
    private final String code;
    private final String message;

    SkillPackageError(int httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** 符号码，写入 {@code ApiEnvelope.data.errorCode}。 */
    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
