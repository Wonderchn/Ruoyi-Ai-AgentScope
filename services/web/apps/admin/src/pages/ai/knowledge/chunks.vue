<!--
  文档分块（04-page-map「文档分块」admin /knowledge/:kbId/docs/:docId）。

  ## 分母与可达性

  - 04-page-map 的 api 写的是 `/knowledge-base/docs/{docId}/chunks*`（ragent 旧路径）。
  - **本形态**（2026-10-06 HEAD f7d5e9f 实核）：`AiEmbedded*Configuration` 显式登记制
    未登记 `KnowledgeChunkController` ⇒ ragent 旧路径 404；`/api/ai/v1` 白名单
    也没有 chunks 路由 ⇒ 分块在本形态**无活端点**。
  - 页面按分母（`GET /knowledge-base/docs/{docId}/chunks`）先行开发：
    挂 BLOCKED-BY-EMBEDDED-REGISTRY 标注；若将来装配，注意信封是 ragent
    `Result`（code:"0" 字符串），需要 `ragResultEnvelopeOf` 解包——
    api 工厂里 chunks 刻意**没有**接进 client（避免把字符串码信封误判成业务失败）。

  ## 状态块

  data-testid="chunks-rows/-empty"（成功响应才有行/空态）；
  blocked 时整页只有 BLOCKED 标注（testid: blocked-EMBEDDED-REGISTRY），不发请求。
-->
<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();
const kbId = computed(() => String(route.params.kbId ?? ''));
const docId = computed(() => String(route.params.docId ?? ''));

/**
 * 刻意不发请求：本形态端点未装配，发了也只能拿到 SPA 404 或 HTTP 404，
 * 把它显示成"错误"会污染成对核对表的判据（NOT_RUN ≠ FAIL）。
 * 若未来装配了 KnowledgeChunkController，把这里改为调
 * `ragResultEnvelopeOf(await client.get(...))` 并去掉 BLOCKED 标注。
 */
const blocked = ref(true);

const rows = ref<Record<string, unknown>[]>([]);
const loaded = ref(false);
const phase = computed(() => {
  if (blocked.value)
    return 'blocked';
  return loaded.value && rows.value.length === 0 ? 'empty' : 'rows';
});
</script>

<template>
  <div>
    <ElCard>
      <template #header>
        文档分块 · kbId={{ kbId }} · docId={{ docId }}
      </template>

      <!-- 契约先行：分母路径与形状写在这里，联调判据 NOT_RUN -->
      <BlockedBy
        reason="EMBEDDED-REGISTRY"
        detail="分块端点（ragent KnowledgeChunkController：GET /knowledge-base/docs/{docId}/chunks 等 6 条）未进本形态内嵌装配（AiEmbedded*Configuration 显式登记制），调用将 404。页面按 02-api-map 分母先行开发；联调判据 NOT_RUN。api 工厂已留 ragResultEnvelopeOf 解包说明（code:'0' 字符串信封）。"
      />

      <div v-if="phase === 'blocked'" data-testid="chunks-not-available" class="state-block">
        分块数据区未启用（端点未装配，不发起注定失败的请求）。
      </div>
      <div v-else-if="phase === 'empty'" data-testid="chunks-empty" class="state-block">
        成功响应，该文档当前没有分块。
      </div>
      <ElTable v-else data-testid="chunks-rows" :data="rows" border size="small">
        <ElTableColumn prop="id" label="chunkId" width="280" />
        <ElTableColumn prop="content" label="内容" min-width="300" show-overflow-tooltip />
        <ElTableColumn prop="enabled" label="启用" width="100" />
      </ElTable>
    </ElCard>
  </div>
</template>

<style scoped>
.state-block {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
</style>
