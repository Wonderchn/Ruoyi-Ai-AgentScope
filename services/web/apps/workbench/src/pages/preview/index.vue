<!--
  私有文档预览页（WP-036 / F15，page-map 目标 `/preview/doc/:docId`）。

  **真页面**：来源二进制走共享客户端的 `downloadSource(docId, versionId, signal)`
  （`@ruoyi/events/rag` 的 `createRagApi`）—— **没有第二套二进制客户端**（C7）。
  身份/epoch/401 也由共享传输负责（`identity` 每次调用时读 Pinia store）。

  视图逻辑在 `@/api/ai/document-preview`（纯模块、有单测）：
  - `parsePreviewQuery`：页码必须 ≥1 整数，**不静默回落**；
  - `toPreviewState`：`ready` **只能**由"成功 + 非空二进制"到达（0 字节 → `empty-binary`）；
  - 401/403/404 三个授权失败**可区分**（私有 URL 必须复核授权）。
-->
<script setup lang="ts">
import type { PreviewState } from '@/api/ai/document-preview';
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { parsePreviewQuery, PreviewQueryError, releaseObjectUrl, toPreviewState } from '@/api/ai/document-preview';
import { downloadSource, getDocument } from '@/api/rag';
import PrivatePdf from '@/components/rag/PrivatePdf.vue';
import { useUserStore } from '@/stores';

const user = useUserStore();
const route = useRoute();

const state = ref<PreviewState>({ kind: 'loading' });
const docMeta = ref<Record<string, unknown> | null>(null);
const sourceUrl = ref('');
let controller: AbortController | null = null;
/** 迟到响应隔离：切文档/切租户后旧响应作废。 */
let viewEpoch = 0;

const page = computed(() => (state.value.kind === 'ready' ? state.value.page : 1));

function closeSource() {
  controller?.abort();
  controller = null;
  sourceUrl.value = releaseObjectUrl(sourceUrl.value, url => URL.revokeObjectURL(url));
}

async function load() {
  const captured = ++viewEpoch;
  const epoch = user.authEpoch;
  const valid = () => captured === viewEpoch && epoch === user.authEpoch;

  closeSource();
  state.value = { kind: 'loading' };
  docMeta.value = null;

  let parsed;
  try {
    parsed = parsePreviewQuery({
      docId: route.params.docId,
      versionId: route.query.versionId,
      page: route.query.page,
    });
  }
  catch (error) {
    // 参数非法：**不发请求**，直接如实报错
    state.value = {
      kind: 'error',
      message: error instanceof PreviewQueryError ? error.message : '预览参数不合法。',
      hint: '请检查链接中的文档 id 与页码。',
    };
    return;
  }

  if (!user.token) {
    state.value = { kind: 'auth-expired', message: '尚未登录。', hint: '私有文档需要登录后由服务端复核授权。' };
    return;
  }

  const ctrl = new AbortController();
  controller = ctrl;
  try {
    // 元信息（document.read）与来源二进制（document.download）是**两个不同的动作权限**：
    // 元信息失败不阻塞预览，但失败要如实记录，不能假装拿到了。
    try {
      const meta = await getDocument(parsed.docId);
      if (valid() && controller === ctrl)
        docMeta.value = meta as unknown as Record<string, unknown>;
    }
    catch {
      if (valid() && controller === ctrl)
        docMeta.value = null;
    }

    const blob = await downloadSource(parsed.docId, parsed.versionId, ctrl.signal);
    if (!valid() || controller !== ctrl)
      return;
    state.value = toPreviewState({ kind: 'loaded', blob, page: parsed.page });
    if (state.value.kind === 'ready')
      sourceUrl.value = URL.createObjectURL(blob);
  }
  catch (error) {
    if (!valid() || controller !== ctrl)
      return;
    state.value = toPreviewState({ kind: 'failed', error });
  }
}

watch(
  () => [route.params.docId, route.query.page, route.query.versionId, user.authEpoch],
  () => { void load(); },
  { immediate: true },
);

onBeforeUnmount(() => {
  viewEpoch++;
  closeSource();
});
</script>

<template>
  <div class="p-4 space-y-4">
    <div class="flex items-center justify-between">
      <h2>文档预览</h2>
      <span class="text-12px c-gray-500">
        {{ String(docMeta?.name ?? docMeta?.docName ?? route.params.docId ?? '') }}
        <span v-if="docMeta?.versionId"> · 版本 {{ String(docMeta.versionId) }}</span>
      </span>
    </div>

    <ElAlert v-if="state.kind === 'loading'" title="正在加载来源…" type="info" :closable="false" data-testid="preview-loading" />

    <template v-else-if="state.kind === 'ready'">
      <div data-testid="preview-ready" />
      <PrivatePdf :source-url="sourceUrl" :initial-page="page" />
    </template>

    <template v-else>
      <ElAlert
        :title="state.message"
        :description="state.hint"
        :type="state.kind === 'auth-expired' ? 'warning' : 'error'"
        :closable="false"
        show-icon
        :data-testid="`preview-${state.kind}`"
      />
      <ElButton v-if="state.kind === 'error'" size="small" @click="load()">
        重试
      </ElButton>
    </template>
  </div>
</template>
