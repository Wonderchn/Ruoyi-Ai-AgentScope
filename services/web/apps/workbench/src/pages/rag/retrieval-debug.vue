<!--
  检索调试页（W3-5 / WP-036 / F05，`POST /api/ai/v1/knowledge-bases/retrievals`，动作 kb.retrieve）。

  **真页面**：请求/状态机全部在 `@/api/ai/retrieval-debug`（纯模块、有单测），
  本文件只做渲染与调用（模式同 `/memory` 页）。

  三类刻意为之的行为：
  1. **失败不画成空态**：404（请求的 KB 未授权/不存在）、403、503、500（rag.vector.type≠pg
     ⇒ 检索器 bean 缺席）各自有文案与"下一步"，结构上不带 hits（showsRetrievalList=false）。
     "空命中"只有 `200 + data:[]` 才可能出现。
  2. **迟到响应丢弃**：切知识库/重跑/退出（user.authEpoch）都会让在途响应作废。
  3. **调试面显式指定 KB**：不传 requestedKbIds 会静默变成"搜整租户授权集"，
     把 404 掩盖成 200+[]；本页始终显式携带勾选的知识库。
-->
<script setup lang="ts">
import type { RetrievalDebugState } from '@/api/ai/retrieval-debug';
import type { KnowledgeBaseView } from '@/api/rag';
import { computed, onMounted, ref, watch } from 'vue';
import { createRetrievalApi, RETRIEVAL_TOP_K_MAX, RETRIEVAL_TOP_K_MIN, toRetrievalDebugState } from '@/api/ai/retrieval-debug';
import { listKnowledgeBases } from '@/api/rag';
import { useUserStore } from '@/stores';

const user = useUserStore();

const retrieval = createRetrievalApi({
  baseUrl: import.meta.env.VITE_API_URL,
  clientId: import.meta.env.VITE_CLIENT_ID,
  identity: () => ({ token: user.token, epoch: user.authEpoch }),
  onAuthExpired: () => user.handleAuthExpired('/rag/debug'),
});

const knowledgeBases = ref<KnowledgeBaseView[]>([]);
const kbsNote = ref('');
const selectedKbIds = ref<string[]>([]);
const query = ref('');
const topK = ref(5);
const state = ref<RetrievalDebugState>({ kind: 'loading' });
const running = ref(false);

/** 迟到响应隔离（同 /memory 页）。 */
let viewEpoch = 0;

async function loadKbs() {
  const captured = ++viewEpoch;
  const currentEpoch = user.authEpoch;
  const valid = () => captured === viewEpoch && currentEpoch === user.authEpoch;
  kbsNote.value = '';
  try {
    const kbs = await listKnowledgeBases();
    if (!valid())
      return;
    knowledgeBases.value = kbs;
    if (!selectedKbIds.value.length)
      selectedKbIds.value = kbs.slice(0, 1).map(kb => kb.kbId);
  }
  catch (error) {
    if (!valid())
      return;
    kbsNote.value = error instanceof Error ? `知识库列表不可访问：${error.message}` : '知识库列表不可访问';
  }
}

async function run() {
  const captured = ++viewEpoch;
  const currentEpoch = user.authEpoch;
  const valid = () => captured === viewEpoch && currentEpoch === user.authEpoch;

  if (!user.token) {
    state.value = { kind: 'auth-expired', message: '尚未登录。', hint: '登录后才能检索已授权的知识库。' };
    return;
  }

  running.value = true;
  state.value = { kind: 'loading' };
  try {
    const hits = await retrieval.debugRetrieval(query.value, topK.value, selectedKbIds.value);
    if (!valid())
      return;
    state.value = toRetrievalDebugState({ kind: 'loaded', hits, query: query.value, topK: topK.value });
  }
  catch (error) {
    if (!valid())
      return;
    state.value = toRetrievalDebugState({ kind: 'failed', error });
  }
  finally {
    if (valid())
      running.value = false;
  }
}

const hits = computed(() => (state.value.kind === 'rows' ? state.value.hits : []));
const resultMeta = computed(() => (state.value.kind === 'rows' || state.value.kind === 'empty'
  ? `query="${state.value.query}" · topK=${state.value.topK}`
  : ''));

onMounted(() => {
  void loadKbs();
});

watch(() => user.authEpoch, () => {
  viewEpoch++;
  state.value = { kind: 'loading' };
  void loadKbs();
});
</script>

<template>
  <div class="p-4 space-y-4">
    <div class="flex items-center justify-between">
      <h2>检索调试</h2>
      <span class="text-12px c-gray-500">POST /api/ai/v1/knowledge-bases/retrievals（kb.retrieve）</span>
    </div>

    <ElCard>
      <template #header>
        <div class="flex items-center justify-between">
          <span>检索请求</span>
          <ElButton size="small" :disabled="running" @click="loadKbs()">
            刷新知识库
          </ElButton>
        </div>
      </template>
      <ElAlert v-if="kbsNote" :title="kbsNote" type="warning" :closable="false" data-testid="retrieval-kbs-error" />
      <ElSelect v-model="selectedKbIds" multiple filterable placeholder="选择要检索的知识库（可多选）" style="width: 100%" data-testid="retrieval-kb-select">
        <ElOption v-for="kb in knowledgeBases" :key="kb.kbId" :label="kb.name" :value="kb.kbId" />
      </ElSelect>
      <div class="mt-3 flex items-start gap-2">
        <ElInput
          v-model="query"
          type="textarea"
          :rows="2"
          maxlength="4096"
          show-word-limit
          placeholder="输入检索查询（服务端 ≤4096 字符，超限 400）"
          data-testid="retrieval-query"
        />
      </div>
      <div class="mt-3 flex items-center gap-3">
        <span class="text-12px c-gray-500">topK（{{ RETRIEVAL_TOP_K_MIN }}..{{ RETRIEVAL_TOP_K_MAX }}）</span>
        <ElInputNumber v-model="topK" :min="RETRIEVAL_TOP_K_MIN" :max="RETRIEVAL_TOP_K_MAX" :step="1" step-strictly data-testid="retrieval-topk" />
        <ElButton type="primary" :loading="running" :disabled="!query.trim() || !selectedKbIds.length" data-testid="retrieval-run" @click="run">
          检索
        </ElButton>
      </div>
    </ElCard>

    <ElAlert
      v-if="state.kind === 'loading'"
      title="正在检索…"
      type="info"
      :closable="false"
      data-testid="retrieval-loading"
    />

    <!-- 成功态（命中或空）：只可能由 envelope code === 200 到达。
         ⚠️ 条件必须内联（`state.kind === 'rows' || state.kind === 'empty'`）而不能调用
         `showsRetrievalList(state)`：模板里函数调用无法让 vue-tsc 收窄，v-else 分支会
         把 rows/empty 也算进来导致 message/hint 类型报错（与 /memory 页同形）。 -->
    <template v-else-if="state.kind === 'rows' || state.kind === 'empty'">
      <ElEmpty
        v-if="state.kind === 'empty'"
        description="授权范围内没有已发布分块命中（服务端返回 200 且列表为空）"
        data-testid="retrieval-empty"
      />
      <template v-else>
        <div data-testid="retrieval-rows" />
        <p class="text-12px c-gray-500">
          命中 {{ hits.length }} 条 · {{ resultMeta }}（服务端已按相关性降序，前端不重排）
        </p>
        <ElCard v-for="(hit, index) in hits" :key="`${hit.id}-${index}`" class="retrieval-card">
          <p class="whitespace-pre-wrap">
            {{ hit.text }}
          </p>
          <p class="text-12px c-gray-500 break-all">
            #{{ index + 1 }} · score={{ hit.score ?? '—' }}
            <span v-if="hit.rerankScore !== null"> · 精排 {{ hit.rerankScore }}</span>
            <span v-else> · 未精排</span>
            <span v-if="hit.docName"> · {{ hit.docName }}</span>
            <span v-if="hit.chunkIndex !== null"> · 分块 {{ hit.chunkIndex }}</span>
            <span v-if="hit.collectionName"> · {{ hit.collectionName }}</span>
          </p>
        </ElCard>
      </template>
    </template>

    <!-- 失败态：结构上不带 hits；500 点明装配口径 -->
    <template v-else>
      <ElAlert
        :title="state.message"
        :description="state.hint"
        :type="state.kind === 'auth-expired' ? 'warning' : 'error'"
        :closable="false"
        show-icon
        :data-testid="`retrieval-${state.kind}`"
      />
      <ElButton v-if="state.kind === 'error'" size="small" :disabled="running" @click="run">
        重试
      </ElButton>
    </template>
  </div>
</template>

<style scoped>
.retrieval-card {
  margin-bottom: 8px;
}
</style>
