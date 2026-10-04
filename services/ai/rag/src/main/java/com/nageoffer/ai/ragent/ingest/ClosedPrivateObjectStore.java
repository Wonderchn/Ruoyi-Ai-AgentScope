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

package com.nageoffer.ai.ragent.ingest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * p2 运行时关闭时的私有对象存储占位：应用可以零配置启动，但任何读写都是显式拒绝。
 *
 * <p>默认能力关闭的边界落在装配层：不存在"空实现静默成功"，每次访问都以
 * {@link com.nageoffer.ai.ragent.runtime.RunErrorCode} 明确失败，保持 fail-closed。
 */
@Component
@ConditionalOnProperty(name = "p2.enabled", havingValue = "false", matchIfMissing = true)
public class ClosedPrivateObjectStore implements PrivateObjectStore {

    static final com.nageoffer.ai.ragent.runtime.RunApiException CLOSED =
            new com.nageoffer.ai.ragent.runtime.RunApiException(
                    com.nageoffer.ai.ragent.runtime.RunErrorCode.BAD_REQUEST, "p2 runtime is closed; private object storage is unavailable");

    @Override
    public String type() {
        return "closed";
    }

    @Override
    public String put(String objectKey, byte[] content) {
        throw CLOSED;
    }

    @Override
    public StoredObject putStream(String objectKey, InputStream input, long maxBytes) {
        throw CLOSED;
    }

    @Override
    public byte[] get(String objectKey) {
        throw CLOSED;
    }

    @Override
    public boolean exists(String objectKey) {
        throw CLOSED;
    }

    @Override
    public void delete(String objectKey) {
        throw CLOSED;
    }

    @Override
    public String sha256Of(String objectKey) {
        throw CLOSED;
    }
}
