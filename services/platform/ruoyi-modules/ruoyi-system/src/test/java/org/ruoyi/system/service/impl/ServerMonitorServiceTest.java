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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.system.domain.vo.ServerInfoVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2-F01/op14：服务监控快照（纯 JDK 实现，无 mock）。
 *
 * <p>口径：各分区必须齐备；"平台不可用"以 -1 表达（不伪造）；可算字段间必须自洽
 * （used = total - free；usage ∈ [0,100] 或 -1；JVM max ≥ total）。
 */
@Tag("dev")
class ServerMonitorServiceTest {

    private final ServerMonitorServiceImpl service = new ServerMonitorServiceImpl();

    @Test
    void snapshotHasAllSectionsAndSaneValues() {
        ServerInfoVo v = service.snapshot();

        assertNotNull(v.cpu());
        assertNotNull(v.mem());
        assertNotNull(v.jvm());
        assertNotNull(v.sys());
        assertNotNull(v.disk());

        assertTrue(v.cpu().cores() >= 1, "核数必须 >=1");
        assertTrue(v.cpu().systemLoadAverage() == ServerMonitorServiceImpl.UNKNOWN
                || v.cpu().systemLoadAverage() >= 0, "平均负载：-1 或非负");
        assertTrue(v.cpu().systemCpuLoad() == ServerMonitorServiceImpl.UNKNOWN
                || (v.cpu().systemCpuLoad() >= 0 && v.cpu().systemCpuLoad() <= 1), "系统 CPU 负载：-1 或 [0,1]");
        assertTrue(v.cpu().processCpuLoad() == ServerMonitorServiceImpl.UNKNOWN
                || (v.cpu().processCpuLoad() >= 0 && v.cpu().processCpuLoad() <= 1), "进程 CPU 负载：-1 或 [0,1]");

        if (v.mem().total() != -1) {
            assertTrue(v.mem().total() > 0 && v.mem().free() >= 0, "物理内存 total/free 自洽");
            assertEquals(v.mem().total() - v.mem().free(), v.mem().used(), "used = total - free");
        }
        assertTrue(v.mem().usage() == ServerMonitorServiceImpl.UNKNOWN
                || (v.mem().usage() >= 0 && v.mem().usage() <= 100), "内存使用率：-1 或 [0,100]");

        assertTrue(v.jvm().total() > 0, "JVM total > 0");
        assertTrue(v.jvm().max() >= v.jvm().total(), "JVM max >= total");
        assertTrue(v.jvm().free() >= 0, "JVM free >= 0");
        assertEquals(v.jvm().total() - v.jvm().free(), v.jvm().used(), "JVM used = total - free");
        assertTrue(v.jvm().usage() >= 0 && v.jvm().usage() <= 100, "JVM 使用率 ∈ [0,100]");
        assertTrue(v.jvm().version() != null && !v.jvm().version().isBlank(), "java.version 非空");
        assertTrue(v.jvm().home() != null && !v.jvm().home().isBlank(), "java.home 非空");
        assertTrue(v.jvm().startTimeMillis() > 0, "启动时刻 > 0");
        assertTrue(v.jvm().uptimeSeconds() >= 0, "运行时长 >= 0");

        assertNotNull(v.sys().hostName());
        assertTrue(!v.sys().hostName().isBlank(), "主机名非空（无法解析时回退 unknown）");
        assertTrue(v.sys().osName() != null && !v.sys().osName().isBlank(), "os.name 非空");
        assertTrue(v.sys().osArch() != null && !v.sys().osArch().isBlank(), "os.arch 非空");
        assertTrue(v.sys().userDir() != null && !v.sys().userDir().isBlank(), "user.dir 非空");

        assertTrue(v.disk().total() > 0, "运行目录所在盘 total > 0");
        assertTrue(v.disk().free() >= 0, "磁盘 free >= 0");
        assertEquals(v.disk().total() - v.disk().free(), v.disk().used(), "磁盘 used = total - free");
        assertTrue(v.disk().usage() >= 0 && v.disk().usage() <= 100, "磁盘使用率 ∈ [0,100]");
    }

    @Test
    void usagePercentEdgeCases() {
        assertEquals(ServerMonitorServiceImpl.UNKNOWN, ServerMonitorServiceImpl.usagePercent(-1, 100), 0.0,
                "free=-1（不可用）→ -1");
        assertEquals(ServerMonitorServiceImpl.UNKNOWN, ServerMonitorServiceImpl.usagePercent(0, 0), 0.0,
                "total=0 → -1");
        assertEquals(25.0, ServerMonitorServiceImpl.usagePercent(75, 100), 0.0, "75/100 free → 25% used");
        assertEquals(0.0, ServerMonitorServiceImpl.usagePercent(100, 100), 0.0, "全空 → 0%");
    }
}
