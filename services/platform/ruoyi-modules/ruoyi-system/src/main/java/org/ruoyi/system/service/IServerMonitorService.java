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

package org.ruoyi.system.service;

import org.ruoyi.system.domain.vo.ServerInfoVo;

/**
 * S2-F01/op14：服务监控快照（只读，无数据面副作用）。
 */
public interface IServerMonitorService {

    /**
     * 采集一次服务监控快照（CPU/内存/JVM/主机/磁盘）。实现须保证失败分支不抛异常、
     * 不可用字段以 {@code -1} 表达（见 {@link ServerInfoVo} 口径）。
     */
    ServerInfoVo snapshot();
}
