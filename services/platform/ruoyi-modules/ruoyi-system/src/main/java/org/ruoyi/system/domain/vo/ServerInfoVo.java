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

package org.ruoyi.system.domain.vo;

import java.io.Serializable;

/**
 * S2-F01/op14：服务监控快照（只读；RuoYi 经典"服务监控"面的字段集）。
 *
 * <p>口径：内存/磁盘为**字节**（前端负责换算展示）；负载类为 JDK 原始语义，
 * 平台不提供时返回 {@code -1}（原样透出不伪造）；usage 为已换算百分数（两位小数，不可算时 -1）。
 *
 * @param cpu  处理器与负载
 * @param mem  物理内存
 * @param jvm  JVM 堆与运行时
 * @param sys  主机与操作系统
 * @param disk 应用运行目录所在盘
 */
public record ServerInfoVo(Cpu cpu, Mem mem, Jvm jvm, Sys sys, Disk disk) implements Serializable {

    /** 处理器：核数、系统平均负载（1 分钟口径由 OS 决定）、系统/进程 CPU 负载（0..1，不可用 -1）。 */
    public record Cpu(int cores, double systemLoadAverage, double systemCpuLoad, double processCpuLoad) {
    }

    /** 物理内存（bytes）与使用率（%，-1=不可算）。 */
    public record Mem(long total, long free, long used, double usage) {
    }

    /** JVM：堆 total/max/free/used（bytes）、使用率（%）、版本、安装目录、启动时刻、运行时长（秒）。 */
    public record Jvm(long total, long max, long free, long used, double usage,
                      String version, String home, long startTimeMillis, long uptimeSeconds) {
    }

    /** 主机与操作系统：主机名、OS 名、架构、运行目录。 */
    public record Sys(String hostName, String osName, String osArch, String userDir) {
    }

    /** 运行目录所在盘（bytes）与使用率（%）。 */
    public record Disk(String path, long total, long free, long usable, long used, double usage) {
    }
}
