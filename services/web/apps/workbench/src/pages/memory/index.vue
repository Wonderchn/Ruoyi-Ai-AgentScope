<!--
  上下文记忆（WP-048 工作台侧）。

  **这是真页面，不是静态页**：数据全部来自 `GET /api/ai/v1/memories`（经平台网关，
  `memory.read → ai:memory:read`）。视图状态机在 `@/api/ai/memory-view`（有单测），
  本文件只做渲染与调用。

  两类刻意为之的行为：
  1. **失败不画成空列表**：403/503/网络失败各自有文案与"下一步"，且结构上不带 rows
     （`showsMemoryList` 为 false）。"暂无记忆"只有 `200 + data:[]` 才可能出现。
  2. **迟到响应丢弃**：每次加载快照 `epoch`，响应回来比对；切租户/退出（`user.authEpoch`）
     或在途期间重新加载都会让旧响应作废。
-->
<script setup lang="ts">
import type { MemoryRow } from '@/api/ai/memories';
import type { MemoryViewState } from '@/api/ai/memory-view';
import { computed, onMounted, ref, watch } from 'vue';
import { createMemoryApi, MEMORY_LIMIT_DEFAULT, MEMORY_LIMIT_MAX } from '@/api/ai/memories';
import { canRetry, toMemoryViewState } from '@/api/ai/memory-view';
import { useUserStore } from '@/stores';

const user = useUserStore();

const memories = createMemoryApi({
  baseUrl: import.meta.env.VITE_API_URL,
  clientId: import.meta.env.VITE_CLIENT_ID,
  identity: () => ({ token: user.token, epoch: user.authEpoch }),
  onAuthExpired: () => user.handleAuthExpired('/memory'),
});

const state = ref<MemoryViewState>({ kind: 'loading' });
const offset = ref(0);
const limit = ref(MEMORY_LIMIT_DEFAULT);
const loading = ref(false);

/** 每次加载/切租户递增；响应回来时必须仍相等，否则丢弃（迟到响应隔离）。 */
let viewEpoch = 0;

async function load(targetOffset = offset.value) {
  const captured = ++viewEpoch;
  const currentEpoch = user.authEpoch;
  const valid = () => captured === viewEpoch && currentEpoch === user.authEpoch;

  if (!user.token) {
    state.value = { kind: 'auth-expired', message: '尚未登录。', hint: '登录后即可查看本租户/本成员的记忆。' };
    return;
  }

  loading.value = true;
  state.value = { kind: 'loading' };
  try {
    const result = await memories.listMemories(targetOffset, limit.value);
    if (!valid())
      return;
    offset.value = result.offset;
    state.value = toMemoryViewState({
      kind: 'loaded',
      rows: result.rows,
      offset: result.offset,
      limit: result.limit,
      hasMore: result.hasMore,
    });
  }
  catch (error) {
    if (!valid())
      return;
    state.value = toMemoryViewState({ kind: 'failed', error });
  }
  finally {
    if (valid())
      loading.value = false;
  }
}

const page = computed(() => Math.floor(offset.value / limit.value) + 1);
const canPrev = computed(() => offset.value > 0 && !loading.value);
const canNext = computed(() => state.value.kind === 'rows' && state.value.hasMore && !loading.value);
const rows = computed<MemoryRow[]>(() => (state.value.kind === 'rows' ? state.value.rows : []));

function prev() {
  if (canPrev.value)
    void load(Math.max(0, offset.value - limit.value));
}

function next() {
  if (canNext.value)
    void load(offset.value + limit.value);
}

onMounted(() => {
  void load(0);
});

watch(() => user.authEpoch, (now, before) => {
  // 退出/切租户：在途响应立即作废，并按新身份重新加载。
  viewEpoch++;
  offset.value = 0;
  if (now !== before)
    void load(0);
});
</script>

<template>
  <div class="p-4 space-y-4">
    <div class="flex items-center justify-between">
      <h2>上下文记忆</h2>
      <div class="flex items-center gap-2">
        <span class="text-12px c-gray-500">每页 {{ limit }} 条（上限 {{ MEMORY_LIMIT_MAX }}）</span>
        <ElButton size="small" :disabled="loading" @click="load()">
          刷新
        </ElButton>
      </div>
    </div>

    <ElAlert
      v-if="state.kind === 'loading'"
      title="正在加载记忆…"
      type="info"
      :closable="false"
      data-testid="memory-loading"
    />

    <!-- 成功态（列表或空）：两者都只可能由 `envelope.code === 200` 到达。
         ⚠️ 这里必须是一个**完整的 v-if / v-else-if / v-else 链**：上一版把"重试"按钮
         插在失败分支与空态分支之间（`<ElButton v-if="canRetry(state)">`），
         **把 v-else 链切断了** —— 于是 `canRetry` 为假时 `v-else` 照样渲染，
         页面会同时出现"失败提示"与"列表骨架"。
         这是浏览器验收（CDP）抓出来的：`testids` 同时含 `memory-auth-expired` 与 `memory-rows`。 -->
    <template v-else-if="state.kind === 'rows' || state.kind === 'empty'">
      <!-- 空态：`data-testid` 是**机器可判**的状态标记，验收脚本按标记判定、不按文案猜
           （第一版脚本用文案匹配，被错误提示里那句「这不是"暂无记忆"」命中 ⇒ 假失败）。 -->
      <ElEmpty
        v-if="state.kind === 'empty'"
        description="当前租户/成员没有有效记忆（服务端返回 200 且列表为空）"
        data-testid="memory-empty"
      />
      <template v-else>
        <div data-testid="memory-rows" />
        <ElCard v-for="row in rows" :key="row.id" class="memory-card">
          <p class="whitespace-pre-wrap">
            {{ row.content }}
          </p>
          <p class="text-12px c-gray-500 break-all">
            id={{ row.id }}
            <span v-if="row.sourcePolicyVersion !== null"> · 策略版本 {{ row.sourcePolicyVersion }}</span>
            <span v-if="row.sourceAclVersion !== null"> · ACL 版本 {{ row.sourceAclVersion }}</span>
          </p>
          <details v-if="row.sourceRefs.length || row.sourceRefsRaw" class="mt-2">
            <summary>来源引用（{{ row.sourceRefs.length }}）</summary>
            <pre v-if="row.sourceRefs.length" class="whitespace-pre-wrap break-all">{{ JSON.stringify(row.sourceRefs, null, 2) }}</pre>
            <p v-if="row.sourceRefsRaw" class="c-orange-6">
              未能解析为 JSON，保留原文：{{ row.sourceRefsRaw }}
            </p>
          </details>
        </ElCard>

        <div class="flex items-center gap-2">
          <ElButton size="small" :disabled="!canPrev" @click="prev">
            上一页
          </ElButton>
          <span class="text-12px">第 {{ page }} 页（offset={{ offset }}）</span>
          <ElButton size="small" :disabled="!canNext" @click="next">
            下一页
          </ElButton>
        </div>
      </template>
    </template>

    <!-- 失败态：各自有文案与"下一步"；**结构上不带 rows**（showsMemoryList 为 false） -->
    <template v-else>
      <ElAlert
        :title="state.message"
        :description="state.hint"
        :type="state.kind === 'auth-expired' ? 'warning' : 'error'"
        :closable="false"
        show-icon
        :data-testid="`memory-${state.kind}`"
      />
      <ElButton v-if="canRetry(state)" size="small" :disabled="loading" @click="load()">
        重试
      </ElButton>
    </template>
  </div>
</template>

<style scoped>
.memory-card {
  margin-bottom: 8px;
}
</style>
