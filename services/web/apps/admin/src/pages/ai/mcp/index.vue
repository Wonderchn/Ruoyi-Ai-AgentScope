<!--
  MCP 工具目录（04-page-map「MCP / 工具目录」admin /ai/mcp，双 Tab：工具 + 市场）。

  ## 可达性（F12-M1，2026-10-18）

  18 条网关活族路由 `/api/ai/v1/mcp/**` 由 `AiGatewayController.ROUTES` 逐条放行；
  信封 = 整数 `ApiEnvelope`（code=200），列表 `data` 是 `IPage`（records/total），
  查询参数逐字 `current`/`size`。动作权限（V39 7144-7148）：

  - 页面路由/侧栏/列表：`ai:mcp:list`；
  - 详情与导出：`ai:mcp:read`；启停/市场刷新/单条与批量加载：`ai:mcp:write`；
  - 删除：`ai:mcp:delete`；连接测试：`ai:mcp:test`。

  403 时如实显示服务端信封消息，页面不推断平台管理身份。GET 响应头带
  `X-AI-Delivery-Permit`/`X-AI-Delivery-Operation`（网关自行消费，前端不追加 ACK）。

  ## 本切片范围（F12-M1 前端腿）

  工具 Tab：分页列表 + 详情（inputSchema/requiresConfirm）+ 连接测试 + 启停 + 删除 +
  导出（服务端 JSON 文本，headers 已掩码）。市场 Tab：分页列表 + 详情（authConfig 掩码）
  + 市场工具列表 + 单条/批量加载 + 刷新 + 启停 + 删除 + 导出（auth_config 已掩码）。
-->
<script setup lang="ts">
import type { McpMarketRow, McpMarketToolRow, McpToolRow } from '@/api';
import { computed, ref, watch } from 'vue';
import { aiApi } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { errorMessageOf, listStateTestId, listViewPhase } from '@/utils';

const { can } = usePermission();

/** 动作权限逐字（与 V39 7144-7148 成对；页面路由/侧栏用 list）。 */
const MCP_PERMISSIONS = {
  list: 'ai:mcp:list',
  read: 'ai:mcp:read',
  write: 'ai:mcp:write',
  delete: 'ai:mcp:delete',
  test: 'ai:mcp:test',
} as const;

const listAllowed = computed(() => can(MCP_PERMISSIONS.list));
const readAllowed = computed(() => can(MCP_PERMISSIONS.read));
const writeAllowed = computed(() => can(MCP_PERMISSIONS.write));
const deleteAllowed = computed(() => can(MCP_PERMISSIONS.delete));
const testAllowed = computed(() => can(MCP_PERMISSIONS.test));

const activeTab = ref<'tools' | 'markets'>('tools');
const actionError = ref('');

/** id 是 snowflake：**不做 Number()**（G-46），统一按字符串进 URL/比较。 */
function idOf(row: { id?: number | string } | null | undefined): string {
  const id = row?.id;
  return id === null || id === undefined ? '' : String(id);
}

/** `IPage` 归一化：缺 records/total 的脏数据给安全默认，不把 undefined 塞进表格。 */
function normalizePage<T>(data: unknown): { records: T[]; total: number } {
  const page = (data ?? {}) as { records?: unknown; total?: unknown };
  return {
    records: Array.isArray(page.records) ? (page.records as T[]) : [],
    total: typeof page.total === 'number' && Number.isFinite(page.total) ? page.total : 0,
  };
}

/**
 * 批量加载的 id 形态：契约是 `{ids:number[]}`；能安全转换为 number 的转，
 * 超出 2^53 的雪花 id 保持字符串（Jackson 会按 Long 解析，避免精度丢失）。
 */
function batchIdOf(id: string): number | string {
  const numeric = Number(id);
  return Number.isSafeInteger(numeric) && String(numeric) === id ? numeric : id;
}

function exportTextOf(data: unknown): string {
  if (typeof data === 'string')
    return data;
  try {
    return JSON.stringify(data, null, 2);
  }
  catch {
    return String(data);
  }
}

// ---------------------------------------------------------------- 工具 Tab
const toolFilters = ref({ current: 1, size: 10, keyword: '', status: '', type: '' });
const toolRows = ref<McpToolRow[]>([]);
const toolTotal = ref(0);
const toolLoading = ref(false);
const toolError = ref('');
const toolLoaded = ref(false);

const toolPhase = computed(() => listViewPhase({
  loading: toolLoading.value,
  error: toolError.value,
  loaded: toolLoaded.value,
  rowCount: toolRows.value.length,
}));

async function loadTools() {
  if (!listAllowed.value) {
    toolRows.value = [];
    toolTotal.value = 0;
    toolLoaded.value = false;
    return;
  }
  toolLoading.value = true;
  toolError.value = '';
  try {
    const page = await aiApi.mcp.toolList({
      current: toolFilters.value.current,
      size: toolFilters.value.size,
      keyword: toolFilters.value.keyword || undefined,
      status: toolFilters.value.status || undefined,
      type: toolFilters.value.type || undefined,
    });
    const normalized = normalizePage<McpToolRow>(page);
    toolRows.value = normalized.records;
    toolTotal.value = normalized.total;
    toolLoaded.value = true;
  }
  catch (e) {
    toolError.value = errorMessageOf(e);
    toolRows.value = [];
    toolTotal.value = 0;
    toolLoaded.value = false;
  }
  finally {
    toolLoading.value = false;
  }
}

function searchTools() {
  toolFilters.value.current = 1;
  void loadTools();
}

function turnToolPage(step: number) {
  const next = toolFilters.value.current + step;
  if (next < 1)
    return;
  if (step > 0 && toolFilters.value.current * toolFilters.value.size >= toolTotal.value)
    return;
  toolFilters.value.current = next;
  void loadTools();
}

// 权限异步到达（getInfo 晚于页面挂载）时补一次加载；撤权则清空视图。
void loadTools();
watch(listAllowed, (allowed) => {
  if (!allowed) {
    toolRows.value = [];
    toolTotal.value = 0;
    toolLoaded.value = false;
    return;
  }
  if (!toolLoaded.value && !toolLoading.value)
    void loadTools();
});

// ---- 详情
const toolDetailVisible = ref(false);
const toolDetail = ref<McpToolRow | null>(null);
const toolDetailError = ref('');

function schemaText(schema: unknown): string {
  if (schema === null || schema === undefined || schema === '')
    return '（无 inputSchema）';
  if (typeof schema === 'string')
    return schema;
  try {
    return JSON.stringify(schema, null, 2);
  }
  catch {
    return String(schema);
  }
}

const toolSchemaText = computed(() => schemaText(toolDetail.value?.inputSchema));

async function openToolDetail(row: McpToolRow) {
  const id = idOf(row);
  if (!id || !readAllowed.value)
    return;
  toolDetailError.value = '';
  try {
    toolDetail.value = await aiApi.mcp.toolGet(id) as McpToolRow;
    toolDetailVisible.value = true;
  }
  catch (e) {
    toolDetail.value = null;
    toolDetailVisible.value = false;
    toolDetailError.value = errorMessageOf(e);
    actionError.value = toolDetailError.value;
  }
}

// ---- 连接测试
const toolTesting = ref('');
const toolTestResult = ref('');

function testResultText(result: unknown): string {
  const value = (result ?? {}) as { success?: unknown; toolCount?: unknown; message?: unknown };
  const parts: string[] = [];
  parts.push(value.success === true ? '连接测试通过' : value.success === false ? '连接测试未通过' : '连接测试已返回');
  if (typeof value.toolCount === 'number')
    parts.push(`工具数：${value.toolCount}`);
  if (typeof value.message === 'string' && value.message.trim())
    parts.push(value.message.trim());
  return parts.join('；');
}

async function testTool(row: McpToolRow) {
  const id = idOf(row);
  if (!id || !testAllowed.value)
    return;
  toolTesting.value = id;
  toolTestResult.value = '';
  try {
    toolTestResult.value = testResultText(await aiApi.mcp.toolTest(id));
  }
  catch (e) {
    toolTestResult.value = errorMessageOf(e);
  }
  finally {
    toolTesting.value = '';
  }
}

// ---- 启停 / 删除
const toolToggling = ref('');
const toolRemoving = ref('');

async function toggleToolStatus(row: McpToolRow) {
  const id = idOf(row);
  if (!id || !writeAllowed.value)
    return;
  toolToggling.value = id;
  actionError.value = '';
  try {
    const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    await aiApi.mcp.toolStatus(id, next);
    await loadTools();
  }
  catch (e) {
    actionError.value = errorMessageOf(e);
  }
  finally {
    toolToggling.value = '';
  }
}

async function removeTool(row: McpToolRow) {
  const id = idOf(row);
  if (!id || !deleteAllowed.value)
    return;
  toolRemoving.value = id;
  actionError.value = '';
  try {
    await aiApi.mcp.toolRemove([id]);
    await loadTools();
  }
  catch (e) {
    actionError.value = errorMessageOf(e);
  }
  finally {
    toolRemoving.value = '';
  }
}

// ---- 导出（服务端返回 JSON 文本，headers 已掩码）
const toolExportVisible = ref(false);
const toolExportText = ref('');

async function exportTools() {
  if (!readAllowed.value)
    return;
  actionError.value = '';
  try {
    toolExportText.value = exportTextOf(await aiApi.mcp.toolExport());
    toolExportVisible.value = true;
  }
  catch (e) {
    actionError.value = errorMessageOf(e);
  }
}

// ---------------------------------------------------------------- 市场 Tab
const marketFilters = ref({ current: 1, size: 10, keyword: '', status: '' });
const marketRows = ref<McpMarketRow[]>([]);
const marketTotal = ref(0);
const marketLoading = ref(false);
const marketError = ref('');
const marketLoaded = ref(false);

const marketPhase = computed(() => listViewPhase({
  loading: marketLoading.value,
  error: marketError.value,
  loaded: marketLoaded.value,
  rowCount: marketRows.value.length,
}));

async function loadMarkets() {
  if (!listAllowed.value) {
    marketRows.value = [];
    marketTotal.value = 0;
    marketLoaded.value = false;
    return;
  }
  marketLoading.value = true;
  marketError.value = '';
  try {
    const page = await aiApi.mcp.marketList({
      current: marketFilters.value.current,
      size: marketFilters.value.size,
      keyword: marketFilters.value.keyword || undefined,
      status: marketFilters.value.status || undefined,
    });
    const normalized = normalizePage<McpMarketRow>(page);
    marketRows.value = normalized.records;
    marketTotal.value = normalized.total;
    marketLoaded.value = true;
  }
  catch (e) {
    marketError.value = errorMessageOf(e);
    marketRows.value = [];
    marketTotal.value = 0;
    marketLoaded.value = false;
  }
  finally {
    marketLoading.value = false;
  }
}

function searchMarkets() {
  marketFilters.value.current = 1;
  void loadMarkets();
}

function turnMarketPage(step: number) {
  const next = marketFilters.value.current + step;
  if (next < 1)
    return;
  if (step > 0 && marketFilters.value.current * marketFilters.value.size >= marketTotal.value)
    return;
  marketFilters.value.current = next;
  void loadMarkets();
}

function switchTab(name: string | number) {
  if (name === 'markets' && !marketLoaded.value && !marketLoading.value)
    void loadMarkets();
  if (name === 'tools' && !toolLoaded.value && !toolLoading.value)
    void loadTools();
}

// ---- 详情（authConfig 恒为服务端掩码 "***"/null）
const marketDetailVisible = ref(false);
const marketDetail = ref<McpMarketRow | null>(null);
const marketDetailError = ref('');

async function openMarketDetail(row: McpMarketRow) {
  const id = idOf(row);
  if (!id || !readAllowed.value)
    return;
  marketDetailError.value = '';
  try {
    marketDetail.value = await aiApi.mcp.marketGet(id) as McpMarketRow;
    marketDetailVisible.value = true;
  }
  catch (e) {
    marketDetail.value = null;
    marketDetailVisible.value = false;
    marketDetailError.value = errorMessageOf(e);
    actionError.value = marketDetailError.value;
  }
}

// ---- 刷新
const marketRefreshing = ref('');
const marketRefreshResult = ref('');

async function refreshMarket(row: McpMarketRow) {
  const id = idOf(row);
  if (!id || !writeAllowed.value)
    return;
  marketRefreshing.value = id;
  marketRefreshResult.value = '';
  try {
    const result = (await aiApi.mcp.marketRefresh(id) ?? {}) as {
      success?: unknown;
      addedCount?: unknown;
      updatedCount?: unknown;
      message?: unknown;
    };
    const parts: string[] = [result.success === false ? '刷新失败' : '刷新完成'];
    if (typeof result.addedCount === 'number')
      parts.push(`新增 ${result.addedCount}`);
    if (typeof result.updatedCount === 'number')
      parts.push(`更新 ${result.updatedCount}`);
    if (typeof result.message === 'string' && result.message.trim())
      parts.push(result.message.trim());
    marketRefreshResult.value = parts.join('；');
    await loadMarkets();
  }
  catch (e) {
    marketRefreshResult.value = errorMessageOf(e);
  }
  finally {
    marketRefreshing.value = '';
  }
}

// ---- 启停 / 删除
const marketToggling = ref('');
const marketRemoving = ref('');

async function toggleMarketStatus(row: McpMarketRow) {
  const id = idOf(row);
  if (!id || !writeAllowed.value)
    return;
  marketToggling.value = id;
  actionError.value = '';
  try {
    const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    await aiApi.mcp.marketStatus(id, next);
    await loadMarkets();
  }
  catch (e) {
    actionError.value = errorMessageOf(e);
  }
  finally {
    marketToggling.value = '';
  }
}

async function removeMarket(row: McpMarketRow) {
  const id = idOf(row);
  if (!id || !deleteAllowed.value)
    return;
  marketRemoving.value = id;
  actionError.value = '';
  try {
    await aiApi.mcp.marketRemove([id]);
    await loadMarkets();
  }
  catch (e) {
    actionError.value = errorMessageOf(e);
  }
  finally {
    marketRemoving.value = '';
  }
}

// ---- 导出（服务端返回 JSON 文本，auth_config 已掩码）
const marketExportVisible = ref(false);
const marketExportText = ref('');

async function exportMarkets() {
  if (!readAllowed.value)
    return;
  actionError.value = '';
  try {
    marketExportText.value = exportTextOf(await aiApi.mcp.marketExport());
    marketExportVisible.value = true;
  }
  catch (e) {
    actionError.value = errorMessageOf(e);
  }
}

// ---- 市场工具：列表 / 单条加载 / 批量加载
const marketToolsVisible = ref(false);
const marketToolsTarget = ref<McpMarketRow | null>(null);
const marketToolRows = ref<McpMarketToolRow[]>([]);
const marketToolTotal = ref(0);
const marketToolLoading = ref(false);
const marketToolError = ref('');
const marketToolNotice = ref('');
const loadingMarketToolId = ref('');

async function loadMarketTools() {
  const marketId = idOf(marketToolsTarget.value);
  if (!marketId || !listAllowed.value)
    return;
  marketToolLoading.value = true;
  marketToolError.value = '';
  try {
    const page = await aiApi.mcp.marketTools(marketId, {
      current: marketFilters.value.current,
      size: marketFilters.value.size,
    });
    const normalized = normalizePage<McpMarketToolRow>(page);
    marketToolRows.value = normalized.records;
    marketToolTotal.value = normalized.total;
  }
  catch (e) {
    marketToolError.value = errorMessageOf(e);
    marketToolRows.value = [];
    marketToolTotal.value = 0;
  }
  finally {
    marketToolLoading.value = false;
  }
}

async function openMarketTools(row: McpMarketRow) {
  const id = idOf(row);
  if (!id || !listAllowed.value)
    return;
  marketToolsTarget.value = row;
  marketToolNotice.value = '';
  marketToolError.value = '';
  marketToolsVisible.value = true;
  await loadMarketTools();
}

async function loadMarketTool(row: McpMarketToolRow) {
  const id = idOf(row);
  if (!id || !writeAllowed.value)
    return;
  loadingMarketToolId.value = id;
  marketToolNotice.value = '';
  marketToolError.value = '';
  try {
    await aiApi.mcp.marketToolLoad(id);
    marketToolNotice.value = `已加载市场工具 ${row.toolName ?? id}`;
    await loadMarketTools();
  }
  catch (e) {
    // 重复加载时服务端回 409，如实显示，不重试。
    marketToolError.value = errorMessageOf(e);
  }
  finally {
    loadingMarketToolId.value = '';
  }
}

async function batchLoadMarketTools() {
  if (!writeAllowed.value)
    return;
  // 只提交未加载行：已加载行再加载会 409（契约）。
  const ids = marketToolRows.value
    .filter(row => row.isLoaded !== true)
    .map(row => idOf(row))
    .filter(id => id !== '')
    .map(batchIdOf);
  if (ids.length === 0) {
    marketToolNotice.value = '没有待加载的市场工具（已加载行不重复提交，避免 409）';
    return;
  }
  marketToolLoading.value = true;
  marketToolNotice.value = '';
  marketToolError.value = '';
  try {
    const result = (await aiApi.mcp.marketToolBatchLoad(ids) ?? {}) as { loaded?: unknown };
    const loaded = typeof result.loaded === 'number' ? result.loaded : ids.length;
    marketToolNotice.value = `批量加载完成：${loaded} 条`;
    await loadMarketTools();
  }
  catch (e) {
    marketToolError.value = errorMessageOf(e);
  }
  finally {
    marketToolLoading.value = false;
  }
}
</script>

<template>
  <div>
    <ElCard>
      <!-- 共享动作错误放在 Tabs 外：市场 Tab 的导出/启停/删除失败同样可见。 -->
      <ElAlert
        v-if="actionError"
        data-testid="mcp-action-error"
        :title="actionError"
        type="warning"
        :closable="false"
        class="mb-4"
      />
      <ElTabs v-model="activeTab" @tab-change="switchTab">
        <ElTabPane label="工具目录" name="tools">
          <template v-if="listAllowed">
            <ElForm :inline="true" @submit.prevent>
              <ElFormItem label="关键字">
                <ElInput v-model="toolFilters.keyword" clearable data-testid="mcp-tool-filter-keyword" />
              </ElFormItem>
              <ElFormItem label="状态">
                <ElSelect v-model="toolFilters.status" clearable style="width: 140px" data-testid="mcp-tool-filter-status">
                  <ElOption label="ENABLED" value="ENABLED" />
                  <ElOption label="DISABLED" value="DISABLED" />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="类型">
                <ElSelect v-model="toolFilters.type" clearable style="width: 140px" data-testid="mcp-tool-filter-type">
                  <ElOption label="LOCAL" value="LOCAL" />
                  <ElOption label="REMOTE" value="REMOTE" />
                  <ElOption label="BUILTIN" value="BUILTIN" />
                </ElSelect>
              </ElFormItem>
              <ElFormItem>
                <ElButton type="primary" :loading="toolLoading" data-testid="mcp-tool-search" @click="searchTools()">
                  查询
                </ElButton>
                <ElButton v-if="readAllowed" data-testid="mcp-tool-export" @click="exportTools()">
                  导出 JSON
                </ElButton>
              </ElFormItem>
            </ElForm>

            <ElAlert
              v-if="toolDetailError"
              data-testid="mcp-tool-detail-error"
              :title="toolDetailError"
              type="error"
              :closable="false"
              class="mb-4"
            />
            <ElAlert
              v-if="toolTestResult"
              data-testid="mcp-tool-test-result"
              :title="toolTestResult"
              type="info"
              :closable="false"
              class="mb-4"
            />

            <ElAlert
              v-if="toolPhase === 'error'"
              :data-testid="listStateTestId('mcp-tool', toolPhase)"
              :title="toolError"
              type="error"
              :closable="false"
              class="mb-4"
            />
            <div
              v-else-if="toolPhase === 'loading'"
              :data-testid="listStateTestId('mcp-tool', toolPhase)"
              class="state-block"
            >
              正在加载…
            </div>
            <div
              v-else-if="toolPhase === 'idle'"
              :data-testid="listStateTestId('mcp-tool', toolPhase)"
              class="state-block"
            >
              尚未加载（点击「查询」读取工具目录）。
            </div>
            <div
              v-else-if="toolPhase === 'empty'"
              :data-testid="listStateTestId('mcp-tool', toolPhase)"
              class="state-block"
            >
              成功响应，没有 MCP 工具。
            </div>
            <template v-else>
              <ElTable
                :data-testid="listStateTestId('mcp-tool', 'rows')"
                :data="toolRows"
                border
                size="small"
              >
                <ElTableColumn prop="name" label="工具名" min-width="170" />
                <ElTableColumn prop="description" label="描述" min-width="220" show-overflow-tooltip />
                <ElTableColumn prop="type" label="类型" width="110" />
                <ElTableColumn prop="status" label="状态" width="110" />
                <ElTableColumn label="操作" width="320">
                  <template #default="{ row }">
                    <ElButton
                      v-if="readAllowed"
                      link
                      size="small"
                      data-testid="mcp-tool-detail"
                      @click="openToolDetail(row)"
                    >
                      详情
                    </ElButton>
                    <ElButton
                      v-if="testAllowed"
                      link
                      size="small"
                      :loading="toolTesting === idOf(row)"
                      data-testid="mcp-tool-test"
                      @click="testTool(row)"
                    >
                      连接测试
                    </ElButton>
                    <ElButton
                      v-if="writeAllowed"
                      link
                      size="small"
                      :loading="toolToggling === idOf(row)"
                      data-testid="mcp-tool-status"
                      @click="toggleToolStatus(row)"
                    >
                      {{ row.status === 'ENABLED' ? '停用' : '启用' }}
                    </ElButton>
                    <ElButton
                      v-if="deleteAllowed"
                      link
                      size="small"
                      type="danger"
                      :loading="toolRemoving === idOf(row)"
                      data-testid="mcp-tool-delete"
                      @click="removeTool(row)"
                    >
                      删除
                    </ElButton>
                  </template>
                </ElTableColumn>
              </ElTable>
              <div class="pager">
                <span>共 {{ toolTotal }} 条 · 第 {{ toolFilters.current }} 页</span>
                <ElButton size="small" :disabled="toolFilters.current <= 1" data-testid="mcp-tool-prev" @click="turnToolPage(-1)">
                  上一页
                </ElButton>
                <ElButton
                  size="small"
                  :disabled="toolFilters.current * toolFilters.size >= toolTotal"
                  data-testid="mcp-tool-next"
                  @click="turnToolPage(1)"
                >
                  下一页
                </ElButton>
              </div>
            </template>
          </template>
          <ElAlert
            v-else
            data-testid="mcp-tool-no-permission"
            type="warning"
            :closable="false"
            title="当前主体没有 ai:mcp:list 权限（V39 7144），工具区不加载。"
          />
        </ElTabPane>

        <ElTabPane label="市场" name="markets">
          <template v-if="listAllowed">
            <ElForm :inline="true" @submit.prevent>
              <ElFormItem label="关键字">
                <ElInput v-model="marketFilters.keyword" clearable data-testid="mcp-market-filter-keyword" />
              </ElFormItem>
              <ElFormItem label="状态">
                <ElSelect v-model="marketFilters.status" clearable style="width: 140px" data-testid="mcp-market-filter-status">
                  <ElOption label="ENABLED" value="ENABLED" />
                  <ElOption label="DISABLED" value="DISABLED" />
                </ElSelect>
              </ElFormItem>
              <ElFormItem>
                <ElButton type="primary" :loading="marketLoading" data-testid="mcp-market-search" @click="searchMarkets()">
                  查询
                </ElButton>
                <ElButton v-if="readAllowed" data-testid="mcp-market-export" @click="exportMarkets()">
                  导出 JSON
                </ElButton>
              </ElFormItem>
            </ElForm>

            <ElAlert
              v-if="marketDetailError"
              data-testid="mcp-market-detail-error"
              :title="marketDetailError"
              type="error"
              :closable="false"
              class="mb-4"
            />
            <ElAlert
              v-if="marketRefreshResult"
              data-testid="mcp-market-refresh-result"
              :title="marketRefreshResult"
              type="success"
              :closable="false"
              class="mb-4"
            />

            <ElAlert
              v-if="marketPhase === 'error'"
              :data-testid="listStateTestId('mcp-market', marketPhase)"
              :title="marketError"
              type="error"
              :closable="false"
              class="mb-4"
            />
            <div
              v-else-if="marketPhase === 'loading'"
              :data-testid="listStateTestId('mcp-market', marketPhase)"
              class="state-block"
            >
              正在加载…
            </div>
            <div
              v-else-if="marketPhase === 'idle'"
              :data-testid="listStateTestId('mcp-market', marketPhase)"
              class="state-block"
            >
              尚未加载（切换到市场 Tab 或点击「查询」）。
            </div>
            <div
              v-else-if="marketPhase === 'empty'"
              :data-testid="listStateTestId('mcp-market', marketPhase)"
              class="state-block"
            >
              成功响应，没有 MCP 市场。
            </div>
            <template v-else>
              <ElTable
                :data-testid="listStateTestId('mcp-market', 'rows')"
                :data="marketRows"
                border
                size="small"
              >
                <ElTableColumn prop="name" label="市场名" min-width="170" />
                <ElTableColumn prop="url" label="URL" min-width="240" show-overflow-tooltip />
                <ElTableColumn prop="description" label="描述" min-width="200" show-overflow-tooltip />
                <ElTableColumn prop="status" label="状态" width="110" />
                <ElTableColumn label="操作" width="380">
                  <template #default="{ row }">
                    <ElButton
                      v-if="readAllowed"
                      link
                      size="small"
                      data-testid="mcp-market-detail"
                      @click="openMarketDetail(row)"
                    >
                      详情
                    </ElButton>
                    <ElButton
                      link
                      size="small"
                      data-testid="mcp-market-tools"
                      @click="openMarketTools(row)"
                    >
                      市场工具
                    </ElButton>
                    <ElButton
                      v-if="writeAllowed"
                      link
                      size="small"
                      :loading="marketRefreshing === idOf(row)"
                      data-testid="mcp-market-refresh"
                      @click="refreshMarket(row)"
                    >
                      刷新
                    </ElButton>
                    <ElButton
                      v-if="writeAllowed"
                      link
                      size="small"
                      :loading="marketToggling === idOf(row)"
                      data-testid="mcp-market-status"
                      @click="toggleMarketStatus(row)"
                    >
                      {{ row.status === 'ENABLED' ? '停用' : '启用' }}
                    </ElButton>
                    <ElButton
                      v-if="deleteAllowed"
                      link
                      size="small"
                      type="danger"
                      :loading="marketRemoving === idOf(row)"
                      data-testid="mcp-market-delete"
                      @click="removeMarket(row)"
                    >
                      删除
                    </ElButton>
                  </template>
                </ElTableColumn>
              </ElTable>
              <div class="pager">
                <span>共 {{ marketTotal }} 条 · 第 {{ marketFilters.current }} 页</span>
                <ElButton size="small" :disabled="marketFilters.current <= 1" data-testid="mcp-market-prev" @click="turnMarketPage(-1)">
                  上一页
                </ElButton>
                <ElButton
                  size="small"
                  :disabled="marketFilters.current * marketFilters.size >= marketTotal"
                  data-testid="mcp-market-next"
                  @click="turnMarketPage(1)"
                >
                  下一页
                </ElButton>
              </div>
            </template>
          </template>
          <ElAlert
            v-else
            data-testid="mcp-market-no-permission"
            type="warning"
            :closable="false"
            title="当前主体没有 ai:mcp:list 权限（V39 7144），市场区不加载。"
          />
        </ElTabPane>
      </ElTabs>
    </ElCard>

    <ElDialog v-model="toolDetailVisible" title="工具详情（mcp.read）" width="720">
      <ElDescriptions v-if="toolDetail" :column="2" border>
        <ElDescriptionsItem label="ID" :span="2">
          {{ idOf(toolDetail) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="名称">
          {{ toolDetail.name }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="类型">
          {{ toolDetail.type }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="状态">
          {{ toolDetail.status }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="需要确认（requiresConfirm）">
          {{ toolDetail.requiresConfirm === true ? '是' : '否' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="Base URL" :span="2">
          {{ toolDetail.baseUrl }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="描述" :span="2">
          {{ toolDetail.description }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="inputSchema（参数）" :span="2">
          <pre data-testid="mcp-tool-detail-schema" class="code">{{ toolSchemaText }}</pre>
        </ElDescriptionsItem>
      </ElDescriptions>
    </ElDialog>

    <ElDialog v-model="toolExportVisible" title="工具导出（JSON 文本，headers 已掩码）" width="720">
      <pre data-testid="mcp-tool-export-text" class="code">{{ toolExportText }}</pre>
    </ElDialog>

    <ElDialog v-model="marketDetailVisible" title="市场详情（mcp.read）" width="720">
      <ElDescriptions v-if="marketDetail" :column="2" border>
        <ElDescriptionsItem label="ID" :span="2">
          {{ idOf(marketDetail) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="名称">
          {{ marketDetail.name }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="状态">
          {{ marketDetail.status }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="URL" :span="2">
          {{ marketDetail.url }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="描述" :span="2">
          {{ marketDetail.description }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="鉴权配置（authConfig，服务端掩码）" :span="2">
          {{ marketDetail.authConfig ?? '***' }}
        </ElDescriptionsItem>
      </ElDescriptions>
    </ElDialog>

    <ElDialog v-model="marketExportVisible" title="市场导出（JSON 文本，auth_config 已掩码）" width="720">
      <pre data-testid="mcp-market-export-text" class="code">{{ marketExportText }}</pre>
    </ElDialog>

    <ElDialog
      v-model="marketToolsVisible"
      :title="`市场工具（${marketToolsTarget?.name ?? ''}）`"
      width="860"
    >
      <ElAlert
        v-if="marketToolError"
        data-testid="mcp-market-tools-error"
        :title="marketToolError"
        type="error"
        :closable="false"
        class="mb-4"
      />
      <ElAlert
        v-if="marketToolNotice"
        data-testid="mcp-market-tools-notice"
        :title="marketToolNotice"
        type="success"
        :closable="false"
        class="mb-4"
      />
      <div class="toolbar">
        <span>共 {{ marketToolTotal }} 条（重复加载会返回 409）</span>
        <ElButton
          v-if="writeAllowed"
          type="primary"
          :loading="marketToolLoading"
          data-testid="mcp-market-tools-batch-load"
          @click="batchLoadMarketTools()"
        >
          批量加载未加载项
        </ElButton>
      </div>
      <div v-if="marketToolLoading" class="state-block">
        正在加载…
      </div>
      <ElTable v-else data-testid="mcp-market-tool-rows" :data="marketToolRows" border size="small">
        <ElTableColumn prop="toolName" label="工具名" min-width="180" />
        <ElTableColumn prop="toolVersion" label="版本" width="110" />
        <ElTableColumn prop="toolDescription" label="描述" min-width="220" show-overflow-tooltip />
        <ElTableColumn label="已加载" width="100">
          <template #default="{ row }">
            {{ row.isLoaded === true ? '是' : '否' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="140">
          <template #default="{ row }">
            <ElButton
              v-if="writeAllowed"
              link
              size="small"
              :disabled="row.isLoaded === true"
              :loading="loadingMarketToolId === idOf(row)"
              data-testid="mcp-market-tool-load"
              @click="loadMarketTool(row)"
            >
              加载
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElDialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.state-block {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.pager {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: flex-end;
  margin-top: 12px;
  color: var(--el-text-color-regular);
}

.code {
  max-height: 320px;
  margin: 0;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
