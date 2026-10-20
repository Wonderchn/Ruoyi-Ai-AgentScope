<!--
  知识图谱管理页（F08:0–3）。

  - 路由 `/ai/knowledge-graph`，权限 `ai:kb:read`（V4-7102，复用既有行，零新增权限/菜单）。
  - 数据源：`GET /api/ai/v1/admin/kg/{status,graph,labels}`（整数 ApiEnvelope；GET 回执由网关消费）。
  - 可视化零新增依赖：SVG 自绘（半径按度数 13–30、确定性布局、top-45 标签）。
  - 溯源：节点/边都显示来源文档，docName 链接到 `/ai/knowledge/:kbId/docs/:docId`。
  - 重建按钮仅 `ai:kb:write` 可见；409/503 由共享客户端抛 PlatformApiError，页面按 msg 原样提示。
-->
<script setup lang="ts">
import type { GraphEdgeRow, GraphNodeRow, GraphSourceRow, GraphStatusPayload } from '@/api/ai/knowledgeGraph';
import { computed, onScopeDispose, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { aiApi } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';
import { errorMessageOf } from '@/utils';
import {
  clampDepth,
  clampLimit,
  layoutNodes,
  normalizeGraph,
  rebuildSummary,
  sourceLink,
  statusMeta,
  topLabels,
  visibleEdges,
} from './graphAdmin';

const router = useRouter();
const identity = useIdentityStore();
const { can } = usePermission();

const PERM_READ = 'ai:kb:read';
const PERM_WRITE = 'ai:kb:write';
const PERM_CONFIG = 'ai:config:read';

const status = ref<GraphStatusPayload>({});
const statusError = ref('');
const statusLoading = ref(false);

const graph = ref(normalizeGraph(null));
const graphError = ref('');
const graphLoading = ref(false);

const kbId = ref('');
const docId = ref('');
const entity = ref('');
const keyword = ref('');
const depth = ref(2);
const limit = ref(200);
const labelOptions = ref<string[]>([]);
const knowledgeBases = ref<Array<{ kbId?: string; id?: string; name?: string }>>([]);
const documents = ref<Array<{ id?: string; docId?: string; docName?: string; name?: string }>>([]);

const rebuilding = ref(false);
const rebuildMessage = ref('');
const selected = ref<GraphNodeRow | GraphEdgeRow | null>(null);
const selectedKind = ref<'node' | 'edge' | ''>('');

const nodes = computed(() => graph.value.nodes);
const edges = computed(() => graph.value.edges);
const truncated = computed(() => graph.value.truncated);
const points = computed(() => layoutNodes(nodes.value, 800, 600));
const lines = computed(() => visibleEdges(edges.value, points.value));
const labelNodes = computed(() => topLabels(nodes.value, 45));
const labelIds = computed(() => new Set(labelNodes.value.map(node => String(node.id))));
const statusView = computed(() => statusMeta(status.value.status));
const selectedNode = computed<GraphNodeRow | null>(() =>
  selectedKind.value === 'node' ? selected.value as GraphNodeRow : null);
const selectedEdge = computed<GraphEdgeRow | null>(() =>
  selectedKind.value === 'edge' ? selected.value as GraphEdgeRow : null);
const selectedSources = computed<GraphSourceRow[]>(() => {
  const sources = selected.value?.sources;
  return Array.isArray(sources) ? sources : [];
});
const selectedDescription = computed(() => selected.value?.description || '（无描述）');
const selectedTitle = computed(() => selectedKind.value === 'node'
  ? selectedNode.value?.name || ''
  : selectedEdge.value?.label || '');

let labelTimer: ReturnType<typeof setTimeout> | null = null;
let graphEpoch = 0;

function kbIdOf(row: { kbId?: string; id?: string }): string {
  return String(row.kbId ?? row.id ?? '');
}

function docIdOf(row: { id?: string; docId?: string }): string {
  return String(row.docId ?? row.id ?? '');
}

function docNameOf(row: { docName?: string; name?: string }): string {
  return String(row.docName ?? row.name ?? '');
}

function nameOf(id: string): string {
  return nodes.value.find(node => String(node.id) === id)?.name || id;
}

async function loadStatus() {
  if (!can(PERM_CONFIG)) {
    statusError.value = '当前主体没有 ai:config:read 权限，无法查询图谱状态。';
    return;
  }
  const epoch = identity.snapshotEpoch();
  statusLoading.value = true;
  statusError.value = '';
  try {
    const payload = await aiApi.knowledgeGraph.status();
    if (!identity.isCurrent(epoch))
      return;
    status.value = payload ?? {};
  }
  catch (error) {
    if (identity.isCurrent(epoch))
      statusError.value = errorMessageOf(error);
  }
  finally {
    if (identity.isCurrent(epoch))
      statusLoading.value = false;
  }
}

async function loadKnowledgeBases() {
  if (!can(PERM_READ))
    return;
  try {
    const rows = await aiApi.knowledgeBases.list();
    knowledgeBases.value = Array.isArray(rows) ? rows : [];
  }
  catch {
    knowledgeBases.value = [];
  }
}

async function loadDocuments() {
  documents.value = [];
  docId.value = '';
  if (!kbId.value || !can(PERM_READ))
    return;
  try {
    const rows = await aiApi.knowledgeBases.documents(kbId.value);
    documents.value = Array.isArray(rows)
      ? rows.map(row => ({
          id: String(row.id ?? ''),
          docId: String(row.docId ?? ''),
          docName: String(row.docName ?? row.name ?? ''),
        }))
      : [];
  }
  catch {
    documents.value = [];
  }
}

async function loadGraph() {
  if (!can(PERM_READ)) {
    graphError.value = '当前主体没有 ai:kb:read 权限，图谱数据区不加载。';
    return;
  }
  const epoch = ++graphEpoch;
  const auth = identity.snapshotEpoch();
  graphLoading.value = true;
  graphError.value = '';
  try {
    const payload = await aiApi.knowledgeGraph.graph({
      entity: entity.value || undefined,
      kbId: kbId.value || undefined,
      doc: docId.value || undefined,
      depth: clampDepth(depth.value),
      limit: clampLimit(limit.value),
    });
    if (epoch !== graphEpoch || !identity.isCurrent(auth))
      return;
    graph.value = normalizeGraph(payload);
    selected.value = null;
    selectedKind.value = '';
  }
  catch (error) {
    if (epoch !== graphEpoch || !identity.isCurrent(auth))
      return;
    graph.value = normalizeGraph(null);
    graphError.value = errorMessageOf(error);
  }
  finally {
    if (epoch === graphEpoch && identity.isCurrent(auth))
      graphLoading.value = false;
  }
}

async function loadLabels() {
  if (!can(PERM_READ))
    return;
  const epoch = identity.snapshotEpoch();
  try {
    const values = await aiApi.knowledgeGraph.labels({
      keyword: keyword.value.trim() || undefined,
      limit: 50,
    });
    if (!identity.isCurrent(epoch))
      return;
    labelOptions.value = Array.isArray(values) ? values : [];
  }
  catch {
    if (identity.isCurrent(epoch))
      labelOptions.value = [];
  }
}

function scheduleLabels() {
  if (labelTimer)
    clearTimeout(labelTimer);
  labelTimer = setTimeout(() => {
    labelTimer = null;
    void loadLabels();
  }, 200);
}

function selectNode(id: string) {
  selected.value = nodes.value.find(node => String(node.id) === id) || null;
  selectedKind.value = 'node';
}

function selectEdge(edge: GraphEdgeRow) {
  selected.value = edge;
  selectedKind.value = 'edge';
}

function openSource(source: GraphSourceRow) {
  const link = sourceLink(source);
  if (link)
    void router.push(link);
}

async function rebuild() {
  if (!can(PERM_WRITE))
    return;
  const query = { kbId: kbId.value || undefined, docId: docId.value || undefined };
  rebuilding.value = true;
  rebuildMessage.value = '';
  try {
    const result = await aiApi.knowledgeGraph.rebuild(query);
    rebuildMessage.value = rebuildSummary(result);
    // 不阻塞按钮：重建成功后异步刷新，失败由 loadGraph 自己呈现。
    void loadGraph();
  }
  catch (error) {
    rebuildMessage.value = errorMessageOf(error);
  }
  finally {
    rebuilding.value = false;
  }
}

watch(
  [() => can(PERM_READ), () => identity.authEpoch],
  ([allowed]) => {
    if (!allowed) {
      graph.value = normalizeGraph(null);
      graphError.value = '';
    }
    else {
      void loadKnowledgeBases();
      void loadGraph();
    }
  },
  { immediate: true },
);

watch(
  [() => can(PERM_CONFIG), () => identity.authEpoch],
  ([allowed]) => {
    if (allowed)
      void loadStatus();
  },
  { immediate: true },
);

watch(kbId, () => {
  void loadDocuments();
  void loadGraph();
});

watch(docId, () => {
  void loadGraph();
});

watch(keyword, scheduleLabels);

onScopeDispose(() => {
  if (labelTimer)
    clearTimeout(labelTimer);
});
</script>

<template>
  <div>
    <ElAlert
      v-if="!can(PERM_READ)"
      data-testid="kg-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 ai:kb:read 权限（V4-7102），图谱数据区不加载。"
    />

    <template v-else>
      <ElCard data-testid="kg-status" class="mb-4">
        <template #header>
          <div class="kg-status-header">
            <span>图谱后端状态</span>
            <ElTag :type="statusView.tone" size="small">
              {{ status.status || 'UNKNOWN' }}
            </ElTag>
          </div>
        </template>
        <ElAlert
          v-if="statusError"
          data-testid="kg-status-error"
          :title="statusError"
          type="warning"
          :closable="false"
          class="mb-3"
        />
        <div v-else class="kg-status-grid" :data-loading="statusLoading">
          <div><span>结论</span><strong>{{ statusView.text }}</strong></div>
          <div><span>后端</span><strong>{{ status.backendType || 'none' }}</strong></div>
          <div><span>可达</span><strong>{{ status.reachable ? '是' : '否' }}</strong></div>
          <div><span>延迟</span><strong>{{ status.latencyMs ?? 0 }} ms</strong></div>
          <div><span>缓存</span><strong>{{ status.cached ? 'TTL 命中' : '实时探测' }}</strong></div>
          <div><span>投影</span><strong>实体 {{ status.projection?.entities ?? 0 }} / 关系 {{ status.projection?.relations ?? 0 }} / 文档 {{ status.projection?.docs ?? 0 }}</strong></div>
          <div><span>Base URL</span><strong>{{ status.baseUrl || '—' }}</strong></div>
          <div><span>Query Mode</span><strong>{{ status.queryMode || '—' }}</strong></div>
          <div><span>API Key</span><strong>{{ status.apiKeyConfigured ? '已配置（不回显）' : '未配置' }}</strong></div>
          <div><span>原因</span><strong>{{ status.reason || '—' }}</strong></div>
        </div>
      </ElCard>

      <ElCard class="mb-4">
        <div class="kg-toolbar">
          <ElSelect v-model="kbId" data-testid="kg-filter-kb" placeholder="全部授权知识库" clearable class="kg-filter">
            <ElOption v-for="row in knowledgeBases" :key="kbIdOf(row)" :label="row.name || kbIdOf(row)" :value="kbIdOf(row)" />
          </ElSelect>
          <ElSelect v-model="docId" data-testid="kg-filter-doc" placeholder="全部文档" clearable class="kg-filter">
            <ElOption v-for="row in documents" :key="docIdOf(row)" :label="docNameOf(row) || docIdOf(row)" :value="docIdOf(row)" />
          </ElSelect>
          <ElInput v-model="entity" data-testid="kg-filter-entity" placeholder="实体名（精确优先）" clearable class="kg-filter" />
          <ElSelect v-model="depth" data-testid="kg-filter-depth" class="kg-filter-narrow">
            <ElOption :value="1" label="深度 1" />
            <ElOption :value="2" label="深度 2" />
            <ElOption :value="3" label="深度 3" />
          </ElSelect>
          <ElSelect v-model="limit" data-testid="kg-filter-limit" class="kg-filter-narrow">
            <ElOption :value="50" label="50 节点" />
            <ElOption :value="200" label="200 节点" />
            <ElOption :value="500" label="500 节点" />
            <ElOption :value="1000" label="1000 节点" />
          </ElSelect>
          <ElButton data-testid="kg-reload" :loading="graphLoading" @click="loadGraph()">
            刷新
          </ElButton>
          <ElButton
            v-if="can(PERM_WRITE)"
            data-testid="kg-rebuild"
            type="primary"
            :loading="rebuilding"
            @click="rebuild()"
          >
            重建投影
          </ElButton>
        </div>
        <ElAlert
          v-if="rebuildMessage"
          data-testid="kg-rebuild-message"
          :title="rebuildMessage"
          type="info"
          :closable="false"
          class="mt-3"
        />
        <div v-if="labelOptions.length" class="kg-label-options">
          <ElTag
            v-for="label in labelOptions"
            :key="label"
            size="small"
            class="kg-label-chip"
            @click="entity = label"
          >
            {{ label }}
          </ElTag>
        </div>
      </ElCard>

      <ElCard>
        <ElAlert
          v-if="graphError"
          data-testid="kg-error"
          :title="graphError"
          type="error"
          :closable="false"
          class="mb-3"
        />
        <div v-else-if="graphLoading" data-testid="kg-loading" class="kg-state">
          正在加载图谱…
        </div>
        <div v-else-if="nodes.length === 0" data-testid="kg-empty" class="kg-state">
          当前范围没有投影数据。可先重建投影，或确认文档已完成图谱抽取。
        </div>
        <template v-else>
          <ElAlert
            v-if="truncated"
            data-testid="kg-truncated"
            type="warning"
            :closable="false"
            title="图谱过大，仅展示当前上限内的节点；边只保留两端可见的部分。"
            class="mb-3"
          />
          <div class="kg-canvas" data-testid="kg-nodes">
            <svg viewBox="0 0 800 600" role="img" aria-label="知识图谱">
              <line
                v-for="line in lines"
                :key="line.id"
                :x1="line.x1"
                :y1="line.y1"
                :x2="line.x2"
                :y2="line.y2"
                class="kg-edge"
                @click="selectEdge(line.edge)"
              />
              <g v-for="point in points" :key="point.id" class="kg-node" @click="selectNode(point.id)">
                <circle :cx="point.x" :cy="point.y" :r="point.r" />
                <text
                  v-if="labelIds.has(point.id)"
                  :x="point.x"
                  :y="point.y - point.r - 4"
                  text-anchor="middle"
                >
                  {{ nameOf(point.id) }}
                </text>
              </g>
            </svg>
            <div class="kg-meta">
              节点 {{ nodes.length }} · 边 {{ edges.length }} · 丢弃 {{ graph.droppedUnattributed }} · 读源 {{ graph.readSource }}
            </div>
          </div>
        </template>
      </ElCard>

      <ElCard v-if="selected" data-testid="kg-source-panel" class="mt-4">
        <template #header>
          <div class="kg-status-header">
            <span>{{ selectedKind === 'node' ? '实体溯源' : '关系溯源' }}</span>
            <span class="kg-muted">{{ selectedTitle }}</span>
          </div>
        </template>
        <div class="kg-detail">
          <p v-if="selectedNode">
            类型：{{ selectedNode.type || '—' }} ·
            度数：{{ selectedNode.degree ?? 0 }}
          </p>
          <p v-else-if="selectedEdge">
            两端：{{ selectedEdge.source }} → {{ selectedEdge.target }} ·
            来源派生：{{ selectedEdge.sourcesDerived ? '是（继承两端）' : '否' }}
          </p>
          <p class="kg-muted">
            {{ selectedDescription }}
          </p>
          <ElTable :data="selectedSources" size="small" border>
            <ElTableColumn label="知识库" width="220">
              <template #default="{ row }">
                {{ row.kbId }}
              </template>
            </ElTableColumn>
            <ElTableColumn label="文档" min-width="240">
              <template #default="{ row }">
                <ElButton
                  v-if="sourceLink(row)"
                  link
                  type="primary"
                  data-testid="kg-source-link"
                  @click="openSource(row)"
                >
                  {{ row.docName || row.docId }}
                </ElButton>
                <span v-else>{{ row.docName || row.docId }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="collectionName" label="Collection" min-width="180" />
          </ElTable>
        </div>
      </ElCard>
    </template>
  </div>
</template>

<style scoped>
.kg-status-header {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.kg-status-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 8px 16px;
  font-size: 13px;
}

.kg-status-grid span {
  margin-right: 8px;
  color: var(--el-text-color-secondary);
}

.kg-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.kg-filter {
  width: 200px;
}

.kg-filter-narrow {
  width: 120px;
}

.kg-label-options {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.kg-label-chip {
  cursor: pointer;
}

.kg-canvas svg {
  width: 100%;
  min-height: 480px;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
}

.kg-edge {
  stroke: var(--el-border-color);
  stroke-width: 1.2;
  cursor: pointer;
}

.kg-node circle {
  fill: var(--el-color-primary-light-5);
  stroke: var(--el-color-primary);
  cursor: pointer;
}

.kg-node text {
  font-size: 11px;
  fill: var(--el-text-color-primary);
  pointer-events: none;
}

.kg-state {
  padding: 32px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.kg-meta {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.kg-detail p {
  margin: 4px 0;
}

.kg-muted {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
