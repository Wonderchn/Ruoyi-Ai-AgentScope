<!--
  MCP 工具目录（04-page-map「MCP / 工具目录」admin /ai/mcp，双 Tab：工具 + 市场）。

  ## 分母（02-api-map.json platform_api_kept + V2 种子权限行）

  McpToolController base /mcp/tool（菜单 2001，mcp:tool:list/query/add/edit/remove/test/export）；
  McpMarketController base /mcp/market（菜单 2010，mcp:market:list/query/add/edit/
  remove/refresh/load/export）。表分母：ai_mcp_tool / ai_mcp_market（V9 迁移）。

  ## ⚠️ BLOCKED-BY-G-22

  ruoyi-chat 未打进 admin 应用 ⇒ 本页所有端点本形态 404。
  页面照分母先行（列表 + 连接测试/市场刷新按钮契约形），联调判据 NOT_RUN。

  ## F08 同族边界

  本页不含"图谱"类内容（K2）；市场/工具目录与知识图谱无关，属于本期分母。
-->
<script setup lang="ts">
import type { McpMarketRow, McpToolRow } from '@/api';
import { computed, ref } from 'vue';
import { aiApi } from '@/api';
import BlockedBy from '@/components/BlockedBy.vue';
import { usePermission } from '@/composables/usePermission';
import { errorMessageOf } from '@/utils';

const { can } = usePermission();

/** V2 种子权限行逐字。 */
const PERMISSION_TOOL_LIST = 'mcp:tool:list';
const PERMISSION_MARKET_LIST = 'mcp:market:list';

const activeTab = ref<'tools' | 'markets'>('tools');

// ---------------------------------------------------------------- 工具
const toolFilters = ref({ pageNum: 1, pageSize: 10, name: '' });
const toolRows = ref<McpToolRow[]>([]);
const toolTotal = ref(0);
const toolLoading = ref(false);
const toolError = ref('');
const toolLoaded = ref(false);

const toolPhase = computed(() => {
  if (toolError.value)
    return 'error';
  if (!toolLoaded.value)
    return toolLoading.value ? 'loading' : 'idle';
  return toolRows.value.length === 0 ? 'empty' : 'rows';
});

async function loadTools() {
  toolLoading.value = true;
  toolError.value = '';
  try {
    const result = await aiApi.mcp.toolList({
      pageNum: toolFilters.value.pageNum,
      pageSize: toolFilters.value.pageSize,
      name: toolFilters.value.name || undefined,
    });
    toolRows.value = result.rows;
    toolTotal.value = result.total;
    toolLoaded.value = true;
  }
  catch (e) {
    toolError.value = errorMessageOf(e);
    toolLoaded.value = false;
  }
  finally {
    toolLoading.value = false;
  }
}

/** 连接测试（POST /mcp/tool/test，权限 mcp:tool:test）。 */
const testing = ref('');
const testResult = ref('');

async function testTool(row: McpToolRow) {
  const id = String(row.id ?? '');
  if (!id)
    return;
  testing.value = id;
  testResult.value = '';
  try {
    await aiApi.mcp.toolTest({ id });
    testResult.value = '测试请求已提交';
  }
  catch (e) {
    testResult.value = errorMessageOf(e);
  }
  finally {
    testing.value = '';
  }
}

// ---------------------------------------------------------------- 市场
const marketFilters = ref({ pageNum: 1, pageSize: 10, name: '' });
const marketRows = ref<McpMarketRow[]>([]);
const marketTotal = ref(0);
const marketLoading = ref(false);
const marketError = ref('');
const marketLoaded = ref(false);

const marketPhase = computed(() => {
  if (marketError.value)
    return 'error';
  if (!marketLoaded.value)
    return marketLoading.value ? 'loading' : 'idle';
  return marketRows.value.length === 0 ? 'empty' : 'rows';
});

async function loadMarkets() {
  marketLoading.value = true;
  marketError.value = '';
  try {
    const result = await aiApi.mcp.marketList({
      pageNum: marketFilters.value.pageNum,
      pageSize: marketFilters.value.pageSize,
      name: marketFilters.value.name || undefined,
    });
    marketRows.value = result.rows;
    marketTotal.value = result.total;
    marketLoaded.value = true;
  }
  catch (e) {
    marketError.value = errorMessageOf(e);
    marketLoaded.value = false;
  }
  finally {
    marketLoading.value = false;
  }
}

function switchTab(name: string | number) {
  if (name === 'markets' && !marketLoaded.value && !marketLoading.value)
    void loadMarkets();
  if (name === 'tools' && !toolLoaded.value && !toolLoading.value)
    void loadTools();
}
</script>

<template>
  <div>
    <BlockedBy
      reason="G-22"
      detail="McpToolController/McpMarketController 在 ruoyi-chat 模块，本形态未打包（G-22），/mcp/tool/** 与 /mcp/market/** 均 404。页面按 02-api-map 分母与 V2 种子权限行先行开发；联调判据 NOT_RUN。"
    />

    <ElCard>
      <ElTabs v-model="activeTab" @tab-change="switchTab">
        <ElTabPane label="工具目录（/mcp/tool）" name="tools">
          <template v-if="can(PERMISSION_TOOL_LIST)">
            <ElForm :inline="true" @submit.prevent>
              <ElFormItem label="名称">
                <ElInput v-model="toolFilters.name" clearable data-testid="mcp-tool-filter-name" />
              </ElFormItem>
              <ElFormItem>
                <ElButton type="primary" :loading="toolLoading" data-testid="mcp-tool-search" @click="loadTools()">
                  查询
                </ElButton>
              </ElFormItem>
            </ElForm>

            <ElAlert
              v-if="toolPhase === 'error'"
              data-testid="mcp-tool-error"
              :title="toolError"
              type="error"
              :closable="false"
              class="mb-4"
            />
            <div v-else-if="toolPhase === 'loading'" data-testid="mcp-tool-loading" class="state-block">
              正在加载…
            </div>
            <div v-else-if="toolPhase === 'idle'" data-testid="mcp-tool-idle" class="state-block">
              尚未查询（本形态端点未打包，按 NOT_RUN 口径不自动发起）。
            </div>
            <div v-else-if="toolPhase === 'empty'" data-testid="mcp-tool-empty" class="state-block">
              成功响应，没有 MCP 工具。
            </div>
            <template v-else>
              <ElAlert
                v-if="testResult"
                data-testid="mcp-tool-test-result"
                :title="testResult"
                type="info"
                :closable="false"
                class="mb-4"
              />
              <ElTable data-testid="mcp-tool-rows" :data="toolRows" border size="small">
                <ElTableColumn prop="name" label="工具名" min-width="160" />
                <ElTableColumn prop="description" label="描述" min-width="220" show-overflow-tooltip />
                <ElTableColumn prop="type" label="类型" width="110" />
                <ElTableColumn prop="status" label="状态" width="110" />
                <ElTableColumn label="操作" width="130">
                  <template #default="{ row }">
                    <ElButton
                      link
                      size="small"
                      :loading="testing === String(row.id ?? '')"
                      :data-testid="`mcp-tool-test-${row.id ?? ''}`"
                      @click="testTool(row)"
                    >
                      连接测试
                    </ElButton>
                  </template>
                </ElTableColumn>
              </ElTable>
              <div class="pager">
                <span>共 {{ toolTotal }} 条</span>
              </div>
            </template>
          </template>
          <ElAlert
            v-else
            data-testid="mcp-tool-no-permission"
            type="warning"
            :closable="false"
            title="当前主体没有 mcp:tool:list 权限（V2 种子菜单 2001），工具区不加载。"
          />
        </ElTabPane>

        <ElTabPane label="市场（/mcp/market）" name="markets">
          <template v-if="can(PERMISSION_MARKET_LIST)">
            <ElForm :inline="true" @submit.prevent>
              <ElFormItem label="名称">
                <ElInput v-model="marketFilters.name" clearable data-testid="mcp-market-filter-name" />
              </ElFormItem>
              <ElFormItem>
                <ElButton type="primary" :loading="marketLoading" data-testid="mcp-market-search" @click="loadMarkets()">
                  查询
                </ElButton>
                <ElButton data-testid="mcp-market-refresh" @click="aiApi.mcp.marketRefresh()">
                  刷新市场
                </ElButton>
              </ElFormItem>
            </ElForm>

            <ElAlert
              v-if="marketPhase === 'error'"
              data-testid="mcp-market-error"
              :title="marketError"
              type="error"
              :closable="false"
              class="mb-4"
            />
            <div v-else-if="marketPhase === 'loading'" data-testid="mcp-market-loading" class="state-block">
              正在加载…
            </div>
            <div v-else-if="marketPhase === 'idle'" data-testid="mcp-market-idle" class="state-block">
              尚未查询。
            </div>
            <div v-else-if="marketPhase === 'empty'" data-testid="mcp-market-empty" class="state-block">
              成功响应，没有 MCP 市场。
            </div>
            <template v-else>
              <ElTable data-testid="mcp-market-rows" :data="marketRows" border size="small">
                <ElTableColumn prop="name" label="市场名" min-width="160" />
                <ElTableColumn prop="url" label="URL" min-width="240" show-overflow-tooltip />
                <ElTableColumn prop="status" label="状态" width="110" />
                <ElTableColumn prop="description" label="描述" min-width="200" show-overflow-tooltip />
              </ElTable>
              <div class="pager">
                <span>共 {{ marketTotal }} 条</span>
              </div>
            </template>
          </template>
          <ElAlert
            v-else
            data-testid="mcp-market-no-permission"
            type="warning"
            :closable="false"
            title="当前主体没有 mcp:market:list 权限（V2 种子菜单 2010），市场区不加载。"
          />
        </ElTabPane>
      </ElTabs>
    </ElCard>
  </div>
</template>

<style scoped>
.state-block {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.pager {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  margin-top: 12px;
  color: var(--el-text-color-regular);
}
</style>
