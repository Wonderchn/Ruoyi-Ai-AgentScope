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
import { createMemoryApi, MEMORY_CLEAR_NOT_RUN_NOTE, MEMORY_LIMIT_DEFAULT, MEMORY_LIMIT_MAX } from '@/api/ai/memories';
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
  <div class="memory-page">
    <header class="memory-head">
      <div class="memory-head-copy">
        <p class="memory-eyebrow">
          上下文
        </p>
        <h1 class="memory-title">
          上下文记忆
        </h1>
        <p class="memory-sub">
          服务端按租户与成员保存的长期上下文；每一条都会在返回前重新核对来源权限。
        </p>
      </div>
      <div class="memory-head-actions">
        <span class="memory-limit">每页 {{ limit }} 条（上限 {{ MEMORY_LIMIT_MAX }}）</span>
        <ElButton size="small" :disabled="loading" @click="load()">
          刷新
        </ElButton>
      </div>
    </header>

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
        class="memory-empty"
        description="当前租户/成员没有有效记忆（服务端返回 200 且列表为空）"
        data-testid="memory-empty"
      />
      <template v-else>
        <div data-testid="memory-rows" />
        <div class="memory-list">
          <article v-for="row in rows" :key="row.id" class="memory-card">
            <p class="memory-content">
              {{ row.content }}
            </p>
            <p class="memory-meta">
              id={{ row.id }}
            </p>
          </article>
        </div>

        <div class="memory-pager">
          <ElButton size="small" :disabled="!canPrev" @click="prev">
            上一页
          </ElButton>
          <span>第 {{ page }} 页（offset={{ offset }}）</span>
          <ElButton size="small" :disabled="!canNext" @click="next">
            下一页
          </ElButton>
        </div>

        <!--
          两条**必须写明**的服务端事实（RW-20 §4.7）：
          1. 条目会因来源撤权而"消失"（服务端逐条复核来源，不合格即不返回）—— 这是设计行为；
             响应里没有 sourceRefs/版本，因此本页**无法解释**"为什么少了某条"。
          2. 记忆清空/范围删除本轮**不做**（D-RW20-1，需新 canonical 动作 + 权限 + 迁移号）。
        -->
        <div class="memory-notes">
          <ElAlert
            type="info"
            :closable="false"
            data-testid="memory-scope-note"
            title="关于条目数量"
            description="服务端逐条复核来源：来源被撤权后该条**不再返回**（设计行为）。响应不含来源引用，因此本页无法解释为什么少了某条。"
          />
          <ElAlert
            type="warning"
            :closable="false"
            data-testid="memory-clear-not-run"
            title="一键清空/范围删除：本轮未提供"
            :description="MEMORY_CLEAR_NOT_RUN_NOTE"
          />
        </div>
      </template>
    </template>

    <!-- 失败态：各自有文案与"下一步"；**结构上不带 rows**（showsMemoryList 为 false） -->
    <template v-else>
      <div class="memory-failure">
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
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.memory-page {
  box-sizing: border-box;
  width: 100%;
  max-width: 900px;
  margin: 0 auto;
  padding: 48px 32px 64px;
  font-family: var(--apple-font);
  color: var(--apple-text);
}

.memory-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 28px;
}

.memory-head-copy {
  min-width: 0;
}

.memory-eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--apple-secondary);
  text-transform: uppercase;
}

.memory-title {
  margin: 0;
  font-size: 34px;
  font-weight: 600;
  line-height: 1.15;
  letter-spacing: -0.015em;
}

.memory-sub {
  max-width: 56ch;
  margin: 10px 0 0;
  font-size: 15px;
  line-height: 1.6;
  color: var(--apple-secondary);
}

.memory-head-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.memory-limit {
  font-size: 12px;
  color: var(--apple-secondary);
}

.memory-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.memory-card {
  padding: 20px 22px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator);
  border-radius: 18px;
}

.memory-content {
  margin: 0;
  font-size: 15px;
  line-height: 1.65;
  color: var(--apple-text);
  white-space: pre-wrap;
  word-break: break-word;
}

.memory-meta {
  margin: 12px 0 0;
  font-family: var(--apple-font-mono);
  font-size: 11px;
  line-height: 1.5;
  color: var(--apple-tertiary);
  word-break: break-all;
}

.memory-empty {
  background: var(--apple-surface);
  border: 1px dashed var(--apple-separator-strong);
  border-radius: 18px;
}

.memory-pager {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: center;
  margin-top: 24px;
  font-size: 13px;
  color: var(--apple-secondary);
}

.memory-notes {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 28px;
}

.memory-failure {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
}

@media (max-width: 768px) {
  .memory-page {
    padding: 28px 16px 40px;
  }

  .memory-title {
    font-size: 28px;
  }

  .memory-head {
    align-items: flex-start;
  }

  .memory-card {
    padding: 16px 18px;
  }
}
</style>
