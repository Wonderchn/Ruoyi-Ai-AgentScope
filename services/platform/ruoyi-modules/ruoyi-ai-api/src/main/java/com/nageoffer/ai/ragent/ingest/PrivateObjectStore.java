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

import java.io.InputStream;

/** 私有对象存储抽象：所有产品文件（上传原件/解析产物）只存私有空间。 */
public interface PrivateObjectStore {

    String type();

    record StoredObject(String objectKey, long sizeBytes, String sha256) {
    }

    String put(String objectKey, byte[] content);

    StoredObject putStream(String objectKey, InputStream input, long maxBytes);

    byte[] get(String objectKey);

    boolean exists(String objectKey);

    void delete(String objectKey);

    String sha256Of(String objectKey);
}
