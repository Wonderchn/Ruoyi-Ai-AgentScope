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
  <div class="server-monitor" data-testid="server-monitor">
    <ElAlert
      v-if="error"
      class="mb-4"
      data-testid="server-error"
      :title="error"
      type="error"
      :closable="false"
    />

    <ElCard>
      <template #header>
        <div class="toolbar">
          <div>
            <span class="section-title">服务监控</span>
            <p class="card-header-meta">
              GET /monitor/server · 只读快照
            </p>
          </div>
          <ElButton data-testid="server-refresh" :loading="loading" @click="load">
            刷新
          </ElButton>
        </div>
      </template>

      <div v-if="!info && !error" class="state-block" data-testid="server-loading">
        正在加载服务信息…
      </div>

      <div v-else-if="info" class="server-grid">
        <section class="server-section" data-testid="server-cpu">
          <h3 class="server-section-title">
            CPU
          </h3>
          <dl class="server-facts">
            <div class="server-fact">
              <dt>核心数</dt>
              <dd data-testid="server-cpu-cores">
                {{ info.cpu.cores }}
              </dd>
            </div>
            <div class="server-fact">
              <dt>系统平均负载</dt>
              <dd>{{ fmtLoad(info.cpu.systemLoadAverage) }}</dd>
            </div>
            <div class="server-fact">
              <dt>系统 CPU 负载</dt>
              <dd>{{ fmtLoad(info.cpu.systemCpuLoad) }}</dd>
            </div>
            <div class="server-fact">
              <dt>进程 CPU 负载</dt>
              <dd>{{ fmtLoad(info.cpu.processCpuLoad) }}</dd>
            </div>
          </dl>
        </section>

        <section class="server-section" data-testid="server-mem">
          <h3 class="server-section-title">
            物理内存
          </h3>
          <dl class="server-facts">
            <div class="server-fact">
              <dt>总量</dt>
              <dd>{{ fmtBytes(info.mem.total) }}</dd>
            </div>
            <div class="server-fact">
              <dt>已用</dt>
              <dd>{{ fmtBytes(info.mem.used) }}（{{ fmtPct(info.mem.usage) }}）</dd>
            </div>
            <div class="server-fact">
              <dt>空闲</dt>
              <dd>{{ fmtBytes(info.mem.free) }}</dd>
            </div>
          </dl>
        </section>

        <section class="server-section" data-testid="server-jvm">
          <h3 class="server-section-title">
            JVM
          </h3>
          <dl class="server-facts">
            <div class="server-fact">
              <dt>堆总量 / 最大</dt>
              <dd>{{ fmtBytes(info.jvm.total) }} / {{ fmtBytes(info.jvm.max) }}</dd>
            </div>
            <div class="server-fact">
              <dt>堆已用</dt>
              <dd>{{ fmtBytes(info.jvm.used) }}（{{ fmtPct(info.jvm.usage) }}）</dd>
            </div>
            <div class="server-fact">
              <dt>堆空闲</dt>
              <dd>{{ fmtBytes(info.jvm.free) }}</dd>
            </div>
            <div class="server-fact">
              <dt>Java 版本</dt>
              <dd data-testid="server-jvm-version">
                {{ info.jvm.version }}
              </dd>
            </div>
            <div class="server-fact">
              <dt>运行时长</dt>
              <dd>{{ fmtUptime(info.jvm.uptimeSeconds) }}</dd>
            </div>
          </dl>
        </section>

        <section class="server-section" data-testid="server-sys">
          <h3 class="server-section-title">
            主机
          </h3>
          <dl class="server-facts">
            <div class="server-fact">
              <dt>主机名</dt>
              <dd>{{ info.sys.hostName }}</dd>
            </div>
            <div class="server-fact">
              <dt>操作系统</dt>
              <dd>{{ info.sys.osName }}（{{ info.sys.osArch }}）</dd>
            </div>
            <div class="server-fact">
              <dt>运行目录</dt>
              <dd>{{ info.sys.userDir }}</dd>
            </div>
          </dl>
        </section>

        <section class="server-section" data-testid="server-disk">
          <h3 class="server-section-title">
            磁盘
          </h3>
          <dl class="server-facts">
            <div class="server-fact">
              <dt>路径</dt>
              <dd>{{ info.disk.path }}</dd>
            </div>
            <div class="server-fact">
              <dt>总量</dt>
              <dd>{{ fmtBytes(info.disk.total) }}</dd>
            </div>
            <div class="server-fact">
              <dt>已用</dt>
              <dd>{{ fmtBytes(info.disk.used) }}（{{ fmtPct(info.disk.usage) }}）</dd>
            </div>
            <div class="server-fact">
              <dt>空闲 / 可用</dt>
              <dd>{{ fmtBytes(info.disk.free) }} / {{ fmtBytes(info.disk.usable) }}</dd>
            </div>
          </dl>
        </section>
      </div>
    </ElCard>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
}

.section-title {
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--apple-text);
}

.card-header-meta {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--apple-secondary);
}

.server-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.server-section {
  padding: 20px;
  background: var(--apple-surface-secondary);
  border: 1px solid var(--apple-separator);
  border-radius: 16px;
}

.server-section-title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--apple-text);
}

.server-facts {
  margin: 0;
}

.server-fact {
  display: grid;
  grid-template-columns: 128px minmax(0, 1fr);
  gap: 12px;
  padding: 8px 0;
  border-top: 1px solid var(--apple-separator);
}

.server-fact:first-child {
  border-top: 0;
}

.server-fact dt {
  font-size: 12px;
  color: var(--apple-secondary);
}

.server-fact dd {
  margin: 0;
  overflow-wrap: anywhere;
  font-size: 13px;
  color: var(--apple-text);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 900px) {
  .server-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 480px) {
  .server-section {
    padding: 16px;
  }

  .server-fact {
    grid-template-columns: 108px minmax(0, 1fr);
    gap: 10px;
  }
}
</style>
