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

package com.nageoffer.ai.ragent.media.delivery;

/**
 * 媒体对象登记端口（可选）：把媒体结果登记进 {@code ai_resource} 的 OBJECT 行，
 * 使撤权/删除可按父链传播。缺省不装配时媒体仍按任务行属主限定取流。
 */
@FunctionalInterface
public interface MediaObjectRegistrar {

    void register(String tenantId, String objectSegment, String parentType, String parentId,
                  String ownerMemberId, String createdByMember);
}
