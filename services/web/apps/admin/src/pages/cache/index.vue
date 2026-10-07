<!--
  缓存监控 + 服务监控缺口登记（F01 **op15** 的两个子行为）—— RW-15 补齐。

  ## 缓存列表（服务端存在）

  `CacheController`（`@RequestMapping("/monitor/cache")`）：`GET /monitor/cache`（`monitor:cache:list`）
  返回 `R<{info: Properties, dbSize: Long, commandStats: [{name,value}]}>`。
  **服务端只有这一条缓存端点**：没有 `/getNames`、`/getKeys/**`、`/clearCache*`（RuoYi 上游有，
  本仓实查不存在）⇒ 本页只做**只读**展示，不提供"按名清理/按键清理"按钮（不伪造能力）。

  ## 服务监控（RW-14 差异 D1：**服务端缺失**）

  `git grep 'monitor/server|ServerController'` 在 `services/platform` 下**零命中**：
  平台没有 `/monitor/server` 或等价的服务器指标端点。冻结 op15 的"服务监控"因此**无法通过
  补页面达成等价** —— 本页把它显示为**显式缺口**（`data-testid="server-monitor-missing"`），
  并登记 owner：T0 决定是否新增端点 + 权限行 + 迁移号（RW-14 §3 D1）。

  ## 环境事实

  T8 已实证本机无 docker/psql/redis ⇒ 真实浏览器与真实 Redis 往返记 `NOT_RUN`；
  页面行为（组件级）与 API 契约由本卡测试覆盖。
-->
<script setup lang="ts">
import { computed, ref } from 'vue';
import { monitorApi, SYSTEM_F01_PERMISSIONS } from '@/api';
import { usePermission } from '@/composables/usePermission';

const { can } = usePermission();
const permitted = computed(() => can(SYSTEM_F01_PERMISSIONS.cacheList));

interface CacheInfoVo {
  info?: Record<string, unknown> | null;
  dbSize?: number | null;
  commandStats?: Array<{ name?: string; value?: string }> | null;
}

const info = ref<CacheInfoVo | null>(null);
const loading = ref(false);
const error = ref('');
const loaded = ref(false);
const generation = ref(0);

async function load() {
  const current = ++generation.value;
  loading.value = true;
  error.value = '';
  try {
    const result = await monitorApi.cache.info();
    if (current !== generation.value)
      return;
    info.value = (result ?? {}) as CacheInfoVo;
    loaded.value = true;
  }
  catch (caught) {
    if (current !== generation.value)
      return;
    info.value = null;
    loaded.value = false;
    error.value = caught instanceof Error ? caught.message : String(caught);
  }
  finally {
    if (current === generation.value)
      loading.value = false;
  }
}

/** `info` 是 Redis INFO 的键值表（服务端 `Properties`）→ 展平成行。 */
const infoRows = computed(() => {
  const source = info.value?.info;
  if (!source || typeof source !== 'object')
    return [] as Array<{ key: string; value: string }>;
  return Object.entries(source as Record<string, unknown>)
    .map(([key, value]) => ({ key, value: value === null || value === undefined ? '' : String(value) }))
    .sort((a, b) => a.key.localeCompare(b.key));
});

const commandRows = computed(() => (Array.isArray(info.value?.commandStats) ? info.value!.commandStats! : []));

if (permitted.value)
  void load();
</script>

<template>
  <div>
    <ElAlert
      data-testid="server-monitor-missing"
      type="warning"
      :closable="false"
      class="mb-4"
      title="服务监控（op15 子行为）在本仓**服务端不存在**（RW-14 差异 D1：git grep 'monitor/server' 零命中）。补页面无法达成等价；需 T0 决定新增端点 + 权限行 + 迁移号。本页只显示该缺口，不造数据。"
    />

    <ElCard class="mb-4">
      <template #header>
        <div class="toolbar">
          <span data-testid="cache-contract" class="card-header-meta">
            GET /monitor/cache（monitor:cache:list）· 只读：本仓无 /getNames、/getKeys、/clearCache*
          </span>
          <ElButton data-testid="cache-reload" :loading="loading" @click="load()">
            刷新
          </ElButton>
        </div>
      </template>

      <ElAlert
        v-if="!permitted"
        data-testid="cache-no-permission"
        type="warning"
        :closable="false"
        title="当前主体没有 monitor:cache:list，缓存数据区不加载。"
      />
      <template v-else>
        <ElAlert
          v-if="error"
          data-testid="cache-error"
          :title="error"
          type="error"
          :closable="false"
          class="mb-4"
        />
        <div v-else-if="loading" data-testid="cache-loading" class="state-block">
          正在加载缓存信息…
        </div>
        <div v-else-if="!loaded" data-testid="cache-idle" class="state-block">
          尚未加载。
        </div>
        <template v-else>
          <ElDescriptions data-testid="cache-facts" border :column="2" size="small" class="mb-4">
            <ElDescriptionsItem label="dbSize">
              {{ info?.dbSize ?? '—' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="INFO 键数">
              {{ infoRows.length }}
            </ElDescriptionsItem>
          </ElDescriptions>

          <div class="section-title">
            命令统计（commandStats）
          </div>
          <ElTable data-testid="cache-command-rows" :data="commandRows" border size="small" class="mb-4">
            <ElTableColumn prop="name" label="命令" min-width="140" />
            <ElTableColumn prop="value" label="调用次数" min-width="120" />
          </ElTable>

          <div class="section-title">
            Redis INFO
          </div>
          <ElTable data-testid="cache-info-rows" :data="infoRows" border size="small">
            <ElTableColumn prop="key" label="键" min-width="240" />
            <ElTableColumn prop="value" label="值" min-width="320" show-overflow-tooltip />
          </ElTable>
        </template>
      </template>
    </ElCard>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.card-header-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.section-title {
  margin-bottom: 8px;
  font-weight: 600;
}

.state-block {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
</style>
