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

package com.nageoffer.ai.ragent.knowledge.support;

import com.nageoffer.ai.ragent.framework.errorcode.IErrorCode;

/**
 * 知识域错误码。
 *
 * <p><b>信封口径（先说清楚，别按 /api/ai/v1 的整数 code 读）。</b>
 * {@code /knowledge-base/**} 这批控制器走 platform 的
 * {@link com.nageoffer.ai.ragent.framework.convention.Result} 信封：
 * {@code {code:"0", message, data, requestId}}，成功码是<b>字符串</b> {@code "0"}，
 * HTTP 状态是 200（客户端错误也走 200 + 非 0 code）。
 * {@code /api/ai/v1/**} 网关族才是整数 code（{@code {code:200, msg, data}}，
 * 且 {@code HTTP status == code}）。两条族不是同一个模板，前端不能共用解包器。
 *
 * <p>这里的码是<b>字符串</b>码，供前端做精确分支；不要把它当 HTTP 状态用。
 * 之所以不复用 {@code BaseErrorCode.CLIENT_ERROR}：那样"版本冲突"会和"参数不合法"
 * 在外显上完全同形，客户端只能靠 message 做字符串匹配——那是不可维护的契约。
 */
public enum KnowledgeErrorCode implements IErrorCode {

    /**
     * 文档乐观锁冲突：调用方携带的 {@code expectedVersion} 与库里当前版本不一致，
     * 说明在读取与写入之间文档已被其它操作修改过。必须在<b>任何写入之前</b>拒绝，
     * 而不是后写覆盖（last-write-wins 会静默吞掉别人的编辑）。
     */
    DOCUMENT_VERSION_CONFLICT("A000409", "文档已被其他操作修改，请刷新后重试");

    private final String code;

    private final String message;

    KnowledgeErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
