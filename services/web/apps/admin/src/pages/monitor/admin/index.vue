<!--
  服务监控（S2-F01/op14；种子菜单 117「Admin监控」的页面落地）。

  端点：GET /monitor/server（SysServerController）→ R<ServerInfoVo>，**单资源、非分页**
  （照 trace.run 的 get() 写，写成 getRows 会静默拿到 {rows: []} 假空态）。

  口径：负载类不可用为 -1（展示 "-"，不伪造）；内存/JVM/磁盘为字节，本页换算；
  usage 为服务端已换算百分数（-1=不可算）。只读页，无任何写操作。
-->
<script setup lang="ts">
import type { ServerInfoVo } from '@/api/monitor';
import { onMounted, ref } from 'vue';
import { monitorApi } from '@/api/bound';

const loading = ref(false);
const error = ref('');
const info = ref<ServerInfoVo | null>(null);

function fmtBytes(v?: number): string {
  if (v === undefined || v === null || v < 0)
    return '-';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let n = v;
  let i = 0;
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024;
    i += 1;
  }
  return `${n.toFixed(1)} ${units[i]}`;
}

function fmtPct(v?: number): string {
  return v === undefined || v < 0 ? '-' : `${v}%`;
}

function fmtLoad(v?: number): string {
  return v === undefined || v < 0 ? '-' : v.toFixed(2);
}

function fmtUptime(sec?: number): string {
  if (sec === undefined || sec < 0)
    return '-';
  const d = Math.floor(sec / 86400);
  const h = Math.floor((sec % 86400) / 3600);
  const m = Math.floor((sec % 3600) / 60);
  return `${d} 天 ${h} 时 ${m} 分`;
}

async function load(): Promise<void> {
  loading.value = true;
  error.value = '';
  try {
    info.value = await monitorApi.server.info();
  }
  catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e);
  }
  finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="app-container" data-testid="server-monitor">
    <div class="server-head">
      <h3>服务监控</h3>
      <button data-testid="server-refresh" :disabled="loading" @click="load">
        {{ loading ? '刷新中…' : '刷新' }}
      </button>
    </div>
    <p v-if="error" class="server-error" data-testid="server-error">
      {{ error }}
    </p>
    <template v-if="info">
      <section data-testid="server-cpu">
        <h4>CPU</h4>
        <table class="server-table">
          <tbody>
            <tr>
              <td>核心数</td>
              <td data-testid="server-cpu-cores">
                {{ info.cpu.cores }}
              </td>
            </tr>
            <tr><td>系统平均负载</td><td>{{ fmtLoad(info.cpu.systemLoadAverage) }}</td></tr>
            <tr><td>系统 CPU 负载</td><td>{{ fmtLoad(info.cpu.systemCpuLoad) }}</td></tr>
            <tr><td>进程 CPU 负载</td><td>{{ fmtLoad(info.cpu.processCpuLoad) }}</td></tr>
          </tbody>
        </table>
      </section>
      <section data-testid="server-mem">
        <h4>物理内存</h4>
        <table class="server-table">
          <tbody>
            <tr><td>总量</td><td>{{ fmtBytes(info.mem.total) }}</td></tr>
            <tr><td>已用</td><td>{{ fmtBytes(info.mem.used) }}（{{ fmtPct(info.mem.usage) }}）</td></tr>
            <tr><td>空闲</td><td>{{ fmtBytes(info.mem.free) }}</td></tr>
          </tbody>
        </table>
      </section>
      <section data-testid="server-jvm">
        <h4>JVM</h4>
        <table class="server-table">
          <tbody>
            <tr><td>堆总量 / 最大</td><td>{{ fmtBytes(info.jvm.total) }} / {{ fmtBytes(info.jvm.max) }}</td></tr>
            <tr><td>堆已用</td><td>{{ fmtBytes(info.jvm.used) }}（{{ fmtPct(info.jvm.usage) }}）</td></tr>
            <tr><td>堆空闲</td><td>{{ fmtBytes(info.jvm.free) }}</td></tr>
            <tr>
              <td>Java 版本</td>
              <td data-testid="server-jvm-version">
                {{ info.jvm.version }}
              </td>
            </tr>
            <tr><td>运行时长</td><td>{{ fmtUptime(info.jvm.uptimeSeconds) }}</td></tr>
          </tbody>
        </table>
      </section>
      <section data-testid="server-sys">
        <h4>主机</h4>
        <table class="server-table">
          <tbody>
            <tr><td>主机名</td><td>{{ info.sys.hostName }}</td></tr>
            <tr><td>操作系统</td><td>{{ info.sys.osName }}（{{ info.sys.osArch }}）</td></tr>
            <tr><td>运行目录</td><td>{{ info.sys.userDir }}</td></tr>
          </tbody>
        </table>
      </section>
      <section data-testid="server-disk">
        <h4>磁盘</h4>
        <table class="server-table">
          <tbody>
            <tr><td>路径</td><td>{{ info.disk.path }}</td></tr>
            <tr><td>总量</td><td>{{ fmtBytes(info.disk.total) }}</td></tr>
            <tr><td>已用</td><td>{{ fmtBytes(info.disk.used) }}（{{ fmtPct(info.disk.usage) }}）</td></tr>
            <tr><td>空闲 / 可用</td><td>{{ fmtBytes(info.disk.free) }} / {{ fmtBytes(info.disk.usable) }}</td></tr>
          </tbody>
        </table>
      </section>
    </template>
    <p v-else-if="!error" data-testid="server-loading">
      加载中…
    </p>
  </div>
</template>

<style scoped>
.server-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.server-error {
  color: #c45656;
}
.server-table {
  border-collapse: collapse;
  margin-bottom: 16px;
}
.server-table td {
  border: 1px solid #dcdfe6;
  padding: 4px 12px;
}
.server-table td:first-child {
  color: #606266;
  width: 140px;
}
</style>
