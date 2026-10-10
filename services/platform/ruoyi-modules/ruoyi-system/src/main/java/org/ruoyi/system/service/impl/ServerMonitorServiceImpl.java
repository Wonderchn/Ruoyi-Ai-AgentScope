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

package org.ruoyi.system.service.impl;

import org.ruoyi.system.domain.vo.ServerInfoVo;
import org.ruoyi.system.service.IServerMonitorService;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;

/**
 * S2-F01/op14：服务监控快照实现（纯 JDK，无新依赖、无外部调用、无副作用）。
 *
 * <p>数据源：{@code java.lang.management} + {@code com.sun.management.OperatingSystemMXBean}
 * （JDK 自带 jdk.management 导出 API；不引入 OSHI/Micrometer 额外依赖）。
 * 平台不支持的指标（如某些 OS 的 CPU load）返回 {@code -1}——**原样透出，不做假值**。
 */
@Service
public class ServerMonitorServiceImpl implements IServerMonitorService {

    /** 平台不提供该指标时的哨兵值（JDK 惯例也是 -1）。 */
    static final double UNKNOWN = -1.0;

    @Override
    public ServerInfoVo snapshot() {
        return new ServerInfoVo(cpu(), mem(), jvm(), sys(), disk());
    }

    private ServerInfoVo.Cpu cpu() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        double systemCpuLoad = UNKNOWN;
        double processCpuLoad = UNKNOWN;
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            systemCpuLoad = sun.getCpuLoad();
            processCpuLoad = sun.getProcessCpuLoad();
        }
        return new ServerInfoVo.Cpu(os.getAvailableProcessors(), os.getSystemLoadAverage(),
                systemCpuLoad, processCpuLoad);
    }

    private ServerInfoVo.Mem mem() {
        long total = -1L;
        long free = -1L;
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            total = sun.getTotalMemorySize();
            free = sun.getFreeMemorySize();
        }
        long used = (total > 0 && free >= 0) ? total - free : -1L;
        return new ServerInfoVo.Mem(total, free, used, usagePercent(free, total));
    }

    private ServerInfoVo.Jvm jvm() {
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        long max = runtime.maxMemory();
        long used = total - free;
        RuntimeMXBean mx = ManagementFactory.getRuntimeMXBean();
        return new ServerInfoVo.Jvm(total, max, free, used, usagePercent(free, total),
                System.getProperty("java.version"), System.getProperty("java.home"),
                mx.getStartTime(), mx.getUptime() / 1000L);
    }

    private ServerInfoVo.Sys sys() {
        String hostName;
        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignore) {
            // 无法解析主机名不构成监控失败：如实回退 unknown，不抛异常
            hostName = "unknown";
        }
        return new ServerInfoVo.Sys(hostName, System.getProperty("os.name"),
                System.getProperty("os.arch"), System.getProperty("user.dir"));
    }

    private ServerInfoVo.Disk disk() {
        File dir = new File(System.getProperty("user.dir"));
        long total = dir.getTotalSpace();
        long free = dir.getFreeSpace();
        long usable = dir.getUsableSpace();
        long used = total > 0 ? total - free : -1L;
        return new ServerInfoVo.Disk(dir.getAbsolutePath(), total, free, usable, used,
                usagePercent(free, total));
    }

    /** free/total → 已用百分比（两位小数）；不可算返回 {@link #UNKNOWN}。 */
    static double usagePercent(long free, long total) {
        if (total <= 0 || free < 0) {
            return UNKNOWN;
        }
        return Math.round((1.0 - (double) free / total) * 10000) / 100.0;
    }
}
