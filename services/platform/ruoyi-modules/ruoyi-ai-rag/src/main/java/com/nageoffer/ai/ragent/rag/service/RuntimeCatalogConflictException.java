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

package com.nageoffer.ai.ragent.rag.service;

/**
 * 已发布版本的事实冲突：对同一不可变版本重复附加**不同**档位。
 *
 * <p>单独一个异常类型而不是复用 {@code ConfigAuthorityUnavailable}：后者在协议上映射为
 * 503（"权威读不到，拒绝"），而本情形权威读到了、也拒绝了——它是 409（版本冲突）。
 * 两者混用会把"客户端要改做法"错报成"服务端依赖挂了"。
 */
public class RuntimeCatalogConflictException extends RuntimeException {

    public RuntimeCatalogConflictException(String message) {
        super(message);
    }
}
