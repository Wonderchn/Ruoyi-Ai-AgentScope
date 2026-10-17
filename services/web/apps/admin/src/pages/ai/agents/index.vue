<!--
  Agent 定义 + Prompt 槽位管理（04-page-map「Agent 定义」「Agent 提示词」admin /ai/agents）。

  ## 契约（2026-10-07 更正：本族**已装配且已进网关白名单**，不再 BLOCKED）

  | 用途 | 端点（`AiGatewayController.ROUTES` 逐字） | 动作 / 权限（`AiCanonicalAction`） |
  |---|---|---|
  | 列表 | GET  /api/ai/v1/agent-catalog/agents | agent.list / ai:agent:list |
  | 新建 | POST /api/ai/v1/agent-catalog/agents | agent.write / ai:agent:write |
  | 改名 | PUT  /api/ai/v1/agent-catalog/agents/{id} | agent.write |
  | 删除 | DELETE /api/ai/v1/agent-catalog/agents/{id} | agent.delete / ai:agent:delete |
  | 激活 | POST /api/ai/v1/agent-catalog/agents/{id}/activate | agent.activate / ai:agent:activate |
  | 槽位 | GET  /api/ai/v1/agent-catalog/agents/{id}/prompts | agent.read / ai:agent:read |
  | 存槽位 | PUT /api/ai/v1/agent-catalog/agents/{id}/prompts/{slotKey} | agent.write（体字段名 `content`） |
  | 默认回落 | GET /api/ai/v1/agent-catalog/agents/prompt-slots/{slotKey}/default | agent.read |

  信封是平台 `ApiEnvelope{int code=200,...}`（**不再是** ragent `Result` 的字符串 `code:"0"`），
  由共享 `PlatformClient` 解包；失败抛 `PlatformApiError`（401/403/业务码）。

  ## 权限（两道门都在服务端，前端只做显示）

  ① scope 精确包含（V27 7132-7136）；② 平台管理身份（A2-ter，`PLATFORM_ADMIN_ACTIONS`）。
  前端拿不到 ②，因此 403 一律如实显示服务端结论并**清空已加载的行**，不推断原因。

  ## 状态口径（判据纪律）

  data-testid="agents-{error,loading,idle,empty,rows}" 由 `listViewPhase` 生成；
  权限未到达时不发请求（`agents-no-permission`），权限丢失时丢弃在途响应。

  ## 真实实例 E2E

  本卡只做静态+单元/组件级验证；真实浏览器端到端由 T8 的实例窗口执行（RW-03 报告记 NOT_RUN）。
-->
<script setup lang="ts">
import type { AgentProfileForm } from './agentAdmin';
import type { AgentProfileBindingRef, AgentProfileRow, AgentPromptSlot } from '@/api';
import { computed, ref, watch } from 'vue';
import { aiApi } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { useIdentityStore } from '@/stores/identity';
import { listStateTestId, listViewPhase } from '@/utils';
import {
  AGENT_PERMISSIONS,
  agentFailureHint,
  agentIdOf,
  canEditRow,
  deleteBlockedReason,
  isBuiltin,
  isFallingBack,
  mayActivate,
  mayDelete,
  mayHold,
  mayReadCatalog,
  mayWrite,
  missingPlaceholders,
  normalizeAgentList,
  profileFormOf,
  profileSaveBody,
  promptSaveBody,
  slotLabel,
} from './agentAdmin';

const identity = useIdentityStore();
const { can, canExact } = usePermission();
const checker = { can, canExact };

const readAllowed = computed(() => mayReadCatalog(checker));
const writeAllowed = computed(() => mayWrite(checker));
const deleteAllowed = computed(() => mayDelete(checker));
const activateAllowed = computed(() => mayActivate(checker));
/**
 * 同一个 `ai:agent:read`（V27）同时管三件事：槽位读取、**详情**、**导出**（F09:0）。
 * 三者的后端动作都是 `agent.read`；前端不另立权限，按钮显示共用这一条。
 */
const readDetailAllowed = computed(() => mayHold(checker, AGENT_PERMISSIONS.read));
const readPromptsAllowed = readDetailAllowed;

const rows = ref<AgentProfileRow[]>([]);
const loading = ref(false);
const error = ref('');
const loaded = ref(false);
/** 服务端 `ragent.engine.type`（展示用，前端不推断）。 */
const mode = ref('');
/** 当前架构下生效的槽位总数。 */
const effectiveSlotTotal = ref(0);
/** 操作级错误（新建/改名/删除/激活/存槽位），与列表错误分开显示。 */
const actionError = ref('');
let loadGeneration = 0;

const phase = computed(() => listViewPhase({
  loading: loading.value,
  error: error.value,
  loaded: loaded.value,
  rowCount: rows.value.length,
}));

/**
 * F09:0 列表筛选（客户端）：与导出 `keyword` **同口径**（名称/描述、大小写不敏感）。
 * 导出按钮把同一个 `keyword` 送服务端，因此"页面筛出来的行"与"导出的行"一致。
 */
const keyword = ref('');
const filterNeedle = computed(() => keyword.value.trim().toLowerCase());
const filteredRows = computed(() => {
  const needle = filterNeedle.value;
  if (!needle)
    return rows.value;
  return rows.value.filter((row) => {
    const name = row.name == null ? '' : String(row.name);
    const description = row.description == null ? '' : String(row.description);
    return `${name} ${description}`.toLowerCase().includes(needle);
  });
});

/** 分页（客户端）；筛选变化与重新加载都回到第一页。 */
const page = ref(1);
const pageSize = 10;
const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize;
  return filteredRows.value.slice(start, start + pageSize);
});
watch(filterNeedle, () => {
  page.value = 1;
});

function clearRows() {
  rows.value = [];
  loaded.value = false;
  mode.value = '';
  effectiveSlotTotal.value = 0;
  page.value = 1;
}

async function load() {
  if (!readAllowed.value) {
    // 无 ai:agent:list：不发起请求，也不保留任何服务端数据。
    ++loadGeneration;
    clearRows();
    error.value = '';
    loading.value = false;
    return;
  }
  const generation = ++loadGeneration;
  const capturedAuth = identity.snapshotEpoch();
  const current = () => generation === loadGeneration && identity.isCurrent(capturedAuth) && readAllowed.value;
  loading.value = true;
  error.value = '';
  try {
    const vo = await aiApi.agentProfiles.list();
    if (!current())
      return;
    const normalized = normalizeAgentList(vo);
    rows.value = normalized.agents;
    mode.value = normalized.mode;
    effectiveSlotTotal.value = normalized.effectiveSlotTotal;
    loaded.value = true;
  }
  catch (caught) {
    if (current()) {
      // 失败必须清空：403（缺 scope／非平台管理身份）后不得继续显示上一份数据。
      clearRows();
      error.value = agentFailureHint(caught).message;
    }
  }
  finally {
    if (current())
      loading.value = false;
  }
}

watch([() => readAllowed.value, () => identity.authEpoch], ([allowed]) => {
  ++loadGeneration;
  clearRows();
  error.value = '';
  actionError.value = '';
  loading.value = false;
  if (allowed)
    void load();
}, { immediate: true });

/** 新建（agent.write）。 */
const createVisible = ref(false);
const creating = ref(false);
const createForm = ref<AgentProfileForm>(profileFormOf());

const editBindings = ref<AgentProfileBindingRef[]>([]);
/**
 * 打开编辑时行上是否**携带**绑定数据（`Array.isArray(row.bindings)`）。
 *
 * 安全兜底：老服务端/边界行缺 `bindings`（`undefined`/`null`）⇒ 保存体**不发**该字段，
 * 保持服务端 `null`＝"本次不改绑定"；仅当行确实带回数据（含空数组）才按替换语义发。
 */
const editCarriesBindings = ref(false);

function openCreate() {
  createForm.value = profileFormOf();
  // 创建侧本轮不接绑定（R-4 默认不落）：清空编辑态绑定，避免跨对话框串味。
  editCarriesBindings.value = false;
  editBindings.value = [];
  actionError.value = '';
  createVisible.value = true;
}

async function submitCreate() {
  const body = profileSaveBody(createForm.value);
  if (!body)
    return;
  const capturedAuth = identity.snapshotEpoch();
  creating.value = true;
  actionError.value = '';
  try {
    await aiApi.agentProfiles.create(body);
    if (!identity.isCurrent(capturedAuth))
      return;
    createVisible.value = false;
    createForm.value = profileFormOf();
    await load();
  }
  catch (caught) {
    if (identity.isCurrent(capturedAuth))
      actionError.value = agentFailureHint(caught).message;
  }
  finally {
    if (identity.isCurrent(capturedAuth))
      creating.value = false;
  }
}

/** 改名/改描述（agent.write；内置行服务端拒绝）。 */
const editVisible = ref(false);
const editing = ref(false);
const editTargetId = ref('');
const editForm = ref<AgentProfileForm>(profileFormOf());

function openEdit(row: AgentProfileRow) {
  const id = agentIdOf(row);
  if (!id || !canEditRow(row))
    return;
  editTargetId.value = id;
  editForm.value = profileFormOf(row);
  // 安全兜底：仅当行**携带**绑定数据（数组，含空数组）才进入替换语义；
  // 老服务端/边界行缺 `bindings`（undefined）或为 null ⇒ 保持"本次不改绑定"。
  editCarriesBindings.value = Array.isArray(row.bindings);
  editBindings.value = editCarriesBindings.value ? [...(row.bindings ?? [])] : [];
  actionError.value = '';
  editVisible.value = true;
  void loadBindingOptions();
}

async function submitEdit() {
  const id = editTargetId.value;
  const base = profileSaveBody(editForm.value);
  if (!id || !base)
    return;
  // 携带数据 ⇒ 整体替换（空数组＝解除全部）；未携带 ⇒ 不发该字段（服务端 null＝不改绑定）。
  const body = editCarriesBindings.value ? { ...base, bindings: editBindings.value } : base;
  const capturedAuth = identity.snapshotEpoch();
  editing.value = true;
  actionError.value = '';
  try {
    await aiApi.agentProfiles.update(id, body);
    if (!identity.isCurrent(capturedAuth))
      return;
    editVisible.value = false;
    await load();
  }
  catch (caught) {
    if (identity.isCurrent(capturedAuth))
      actionError.value = agentFailureHint(caught).message;
  }
  finally {
    if (identity.isCurrent(capturedAuth))
      editing.value = false;
  }
}

// ---------------- P3-BIND：绑定选择器与读侧回显（F09:3 / F11:3） ----------------

type BindingType = AgentProfileBindingRef['type'];
/** 编排模式闭集（与服务端 ORCHESTRATION_MODES 对齐；未知码服务端拒绝）。 */
const ORCHESTRATION_MODES = ['REACT', 'PLAN_ACT'] as const;

const modelOptions = ref<Array<{ id: string; label: string }>>([]);
const skillOptions = ref<Array<{ skillCode: string; name: string }>>([]);
const mcpOptions = ref<Array<{ toolId: string; name: string }>>([]);
/** MCP 清单是否可用；不可用走受控文本输入（服务端仍在保存时核验）。 */
const mcpListAvailable = ref(false);
const mcpFallbackInput = ref('');
const bindingLoadError = ref('');
const bindingNotice = ref('');

function bindingsOfType(type: BindingType): string[] {
  return editBindings.value.filter(b => b.type === type).map(b => b.refId);
}
function setBindingsOfType(type: BindingType, ids: string[]): void {
  // 整体替换该类型的引用（其它类型保持不变）。
  editBindings.value = [
    ...editBindings.value.filter(b => b.type !== type),
    ...ids.filter(id => id.trim()).map(id => ({ type, refId: id.trim() })),
  ];
}
function addMcpFallbackId(): void {
  const id = mcpFallbackInput.value.trim();
  if (!id)
    return;
  // 去重（同 id 不重复绑定），保持加入顺序。用 Array.from 而非 `[...new Set(...)]`：
  // 后者在测试的 ES5 transpile 目标下会退化成空数组（Set 无 length），非产品缺陷但会假红。
  setBindingsOfType('MCP', Array.from(new Set([...bindingsOfType('MCP'), id])));
  mcpFallbackInput.value = '';
}

async function loadBindingOptions(): Promise<void> {
  bindingLoadError.value = '';
  bindingNotice.value = '';
  // 模型：运行时目录（config.read）。按 modelId 展示（同 pages/ai/models 的模型列）；
  // 失败/403 ⇒ 禁用选择器并如实提示，不伪造选项。
  if (modelOptions.value.length === 0) {
    try {
      const catalog = await aiApi.runtimeConfig.catalog();
      modelOptions.value = (catalog?.models ?? [])
        .map(m => ({ id: String(m.id ?? ''), label: String(m.modelId ?? m.id ?? '') }))
        .filter(o => o.id);
    }
    catch (caught) {
      bindingLoadError.value = `模型清单不可用：${agentFailureHint(caught).message}（模型绑定暂不可选）`;
    }
  }
  // 技能：既有 agentSkills.list()（skill.list）。
  if (skillOptions.value.length === 0) {
    try {
      const page = await aiApi.agentSkills.list({ current: 1, size: 200 });
      skillOptions.value = (page?.records ?? [])
        .map(r => ({ skillCode: String(r.skillCode ?? ''), name: String(r.name ?? '') }))
        .filter(o => o.skillCode);
    }
    catch (caught) {
      bindingLoadError.value = `技能清单不可用：${agentFailureHint(caught).message}`;
    }
  }
  // MCP：改用 `agentSkills.toolOptions()`——其 `toolId` 正是服务端绑定核验空间
  // （`ai_intent_node.mcp_tool_id`），且是活端点；`mcp.*` 在本形态 G-22 恒 404，不再使用。
  // 不可用（403 / 引擎闭包门 / 清单为空）⇒ 降级受控文本输入，不抛给页面。
  if (mcpOptions.value.length === 0) {
    try {
      const options = await aiApi.agentSkills.toolOptions();
      mcpOptions.value = (Array.isArray(options) ? options : [])
        .map(t => ({ toolId: String(t.toolId ?? ''), name: String(t.name ?? '') }))
        .filter(o => o.toolId);
    }
    catch {
      mcpOptions.value = [];
    }
    mcpListAvailable.value = mcpOptions.value.length > 0;
    if (!mcpListAvailable.value) {
      bindingNotice.value = 'MCP 工具清单不可用（需 ai:skill:list；引擎未启用或 403 时降级）：'
        + '请直接填写 tool id 后点「添加」，保存时由服务端按意图树核验存在与启用。';
    }
  }
}

const modelBindingIds = computed({ get: () => bindingsOfType('MODEL'), set: v => setBindingsOfType('MODEL', v) });
const skillBindingIds = computed({ get: () => bindingsOfType('SKILL'), set: v => setBindingsOfType('SKILL', v) });
const mcpBindingIds = computed({ get: () => bindingsOfType('MCP'), set: v => setBindingsOfType('MCP', v) });
const orchestrationMode = computed({
  get: () => bindingsOfType('ORCHESTRATION')[0] ?? '',
  set: v => setBindingsOfType('ORCHESTRATION', v ? [v] : []),
});

/** 读侧回显汇总文本（分组顺序 = 加入顺序）。 */
const bindingEcho = computed(() => {
  if (editBindings.value.length === 0)
    return '（未绑定任何引用）';
  return editBindings.value.map(b => `${b.type}:${b.refId}`).join('、');
});

/** 删除（agent.delete）。 */
const removing = ref('');

async function removeRow(row: AgentProfileRow) {
  const id = agentIdOf(row);
  if (!id)
    return;
  const blocked = deleteBlockedReason(row);
  if (blocked) {
    // 服务端同样拒绝（mustLoadEditable / 激活中）：前端只做预告，不发出必然失败的请求。
    actionError.value = blocked;
    return;
  }
  const capturedAuth = identity.snapshotEpoch();
  removing.value = id;
  actionError.value = '';
  try {
    await aiApi.agentProfiles.remove(id);
    if (!identity.isCurrent(capturedAuth))
      return;
    await load();
  }
  catch (caught) {
    if (identity.isCurrent(capturedAuth))
      actionError.value = agentFailureHint(caught).message;
  }
  finally {
    if (identity.isCurrent(capturedAuth))
      removing.value = '';
  }
}

/** 激活（agent.activate，唯一激活语义：服务端先清零再置位）。 */
const activating = ref('');

async function activateRow(row: AgentProfileRow) {
  const id = agentIdOf(row);
  if (!id || row.active === true)
    return;
  const capturedAuth = identity.snapshotEpoch();
  activating.value = id;
  actionError.value = '';
  try {
    await aiApi.agentProfiles.activate(id);
    if (!identity.isCurrent(capturedAuth))
      return;
    await load();
  }
  catch (caught) {
    if (identity.isCurrent(capturedAuth))
      actionError.value = agentFailureHint(caught).message;
  }
  finally {
    if (identity.isCurrent(capturedAuth))
      activating.value = '';
  }
}

/** Prompt 槽位抽屉（agent.read 读、agent.write 存）。 */
const promptVisible = ref(false);
const promptAgentId = ref('');
const promptAgentName = ref('');
const promptBuiltin = ref(false);
const promptDefaultAgentName = ref('');
const promptMode = ref('');
const promptSlots = ref<AgentPromptSlot[]>([]);
const promptLoading = ref(false);
const promptError = ref('');
const promptNotice = ref('');
const savingSlot = ref('');
const copyingSlot = ref('');

async function openPrompts(row: AgentProfileRow) {
  const id = agentIdOf(row);
  if (!id)
    return;
  promptAgentId.value = id;
  promptAgentName.value = row.name == null ? '' : String(row.name);
  promptSlots.value = [];
  promptError.value = '';
  promptNotice.value = '';
  promptVisible.value = true;
  await loadPrompts();
}

async function loadPrompts() {
  const id = promptAgentId.value;
  if (!id)
    return;
  const capturedAuth = identity.snapshotEpoch();
  const current = () => identity.isCurrent(capturedAuth) && promptAgentId.value === id;
  promptLoading.value = true;
  promptError.value = '';
  try {
    const config = await aiApi.agentProfiles.prompts(id);
    if (!current())
      return;
    promptSlots.value = Array.isArray(config?.slots) ? config.slots : [];
    promptAgentName.value = config?.agentName ?? promptAgentName.value;
    promptBuiltin.value = config?.builtin === true;
    promptDefaultAgentName.value = config?.defaultAgentName ?? '';
    promptMode.value = config?.mode ?? '';
  }
  catch (caught) {
    if (current()) {
      promptSlots.value = [];
      promptError.value = agentFailureHint(caught).message;
    }
  }
  finally {
    if (current())
      promptLoading.value = false;
  }
}

async function saveSlot(slot: AgentPromptSlot) {
  const slotKey = String(slot?.slotKey ?? '');
  const id = promptAgentId.value;
  if (!slotKey || !id)
    return;
  const missing = missingPlaceholders(slot, slot.content);
  if (missing.length > 0) {
    promptError.value = `「${slotLabel(slot)}」缺少必需占位符：${missing.join('、')}（服务端同样会拒绝保存）`;
    return;
  }
  const capturedAuth = identity.snapshotEpoch();
  const current = () => identity.isCurrent(capturedAuth) && promptAgentId.value === id;
  savingSlot.value = slotKey;
  promptError.value = '';
  promptNotice.value = '';
  try {
    await aiApi.agentProfiles.savePrompt(id, slotKey, promptSaveBody(slot.content));
    if (!current())
      return;
    const restored = isFallingBack(slot);
    await loadPrompts();
    if (!current())
      return;
    promptNotice.value = restored
      ? '已清空该槽位：运行时回落内置智能体（服务端写 null）。'
      : '已保存该槽位。';
  }
  catch (caught) {
    if (current())
      promptError.value = agentFailureHint(caught).message;
  }
  finally {
    if (current())
      savingSlot.value = '';
  }
}

/** 从内置默认复制内容到编辑器（只填编辑框，仍需点保存才写入）。 */
async function copyDefault(slot: AgentPromptSlot) {
  const slotKey = String(slot?.slotKey ?? '');
  if (!slotKey)
    return;
  const capturedAuth = identity.snapshotEpoch();
  copyingSlot.value = slotKey;
  promptError.value = '';
  promptNotice.value = '';
  try {
    const content = await aiApi.agentProfiles.promptDefault(slotKey);
    if (!identity.isCurrent(capturedAuth))
      return;
    slot.content = typeof content === 'string' ? content : '';
    promptNotice.value = '已复制内置默认内容到编辑器（尚未保存：点「保存槽位」才写入）。';
  }
  catch (caught) {
    if (identity.isCurrent(capturedAuth))
      promptError.value = agentFailureHint(caught).message;
  }
  finally {
    if (identity.isCurrent(capturedAuth))
      copyingSlot.value = '';
  }
}

/** F09:0 详情（agent.read）：单条读取，与列表同一投影；失败清空并如实显示服务端结论。 */
const detailVisible = ref(false);
const detailLoading = ref(false);
const detailError = ref('');
const detailRow = ref<AgentProfileRow | null>(null);

async function openDetail(row: AgentProfileRow) {
  const id = agentIdOf(row);
  if (!id)
    return;
  detailVisible.value = true;
  detailRow.value = null;
  detailError.value = '';
  await loadDetail(id);
}

async function loadDetail(id: string) {
  const capturedAuth = identity.snapshotEpoch();
  const current = () => identity.isCurrent(capturedAuth);
  detailLoading.value = true;
  detailError.value = '';
  try {
    const detail = await aiApi.agentProfiles.detail(id);
    if (!current())
      return;
    detailRow.value = detail ?? null;
  }
  catch (caught) {
    if (current()) {
      // 失败必须清空：403（缺 scope／非平台管理身份）后不得继续显示上一条详情。
      detailRow.value = null;
      detailError.value = agentFailureHint(caught).message;
    }
  }
  finally {
    if (current())
      detailLoading.value = false;
  }
}

/** F09:0 导出（agent.read）：服务端回 NDJSON 文本，这里物化成 .ndjson 下载。 */
const exporting = ref(false);
const exportNotice = ref('');

async function runExport() {
  const capturedAuth = identity.snapshotEpoch();
  const needle = keyword.value.trim();
  exporting.value = true;
  actionError.value = '';
  exportNotice.value = '';
  try {
    const ndjson = await aiApi.agentProfiles.exportDefinition(needle || undefined);
    if (!identity.isCurrent(capturedAuth))
      return;
    const text = typeof ndjson === 'string' ? ndjson : '';
    const trimmed = text.trim();
    const lineCount = trimmed === '' ? 0 : trimmed.split('\n').length;
    downloadNdjson(exportFileName(), text);
    exportNotice.value = lineCount === 0
      ? '导出完成：当前筛选没有匹配的智能体（导出内容为空）。'
      : `导出完成：${lineCount} 个智能体${needle ? `（筛选：${needle}）` : ''}；导出只含目录元数据，提示词正文按掩码排除。`;
  }
  catch (caught) {
    if (identity.isCurrent(capturedAuth))
      actionError.value = agentFailureHint(caught).message;
  }
  finally {
    if (identity.isCurrent(capturedAuth))
      exporting.value = false;
  }
}

function exportFileName(): string {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return `agent-catalog-${stamp}.ndjson`;
}

function onPageChange(value: number) {
  page.value = value;
}

/** 下载文本：不引新依赖（Blob + 临时 <a>）；服务端不产二进制，物化只发生在前端。 */
function downloadNdjson(filename: string, text: string) {
  const blob = new Blob([text], { type: 'application/x-ndjson' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

function nameOf(row: AgentProfileRow): string {
  return row.name == null ? '' : String(row.name);
}
</script>

<template>
  <div>
    <ElAlert
      v-if="!readAllowed"
      data-testid="agents-no-permission"
      type="warning"
      :closable="false"
      title="当前主体没有 ai:agent:list（V27-7132），页面数据区不加载，也不保留任何服务端数据。"
      class="mb-4"
    />

    <template v-else>
      <ElCard class="mb-4">
        <div class="toolbar">
          <span data-testid="agents-contract" class="card-header-meta">
            GET /api/ai/v1/agent-catalog/agents · 架构模式 {{ mode || '未知' }} ·
            生效槽位 {{ effectiveSlotTotal }} · 共 {{ rows.length }} 条
          </span>
          <div class="toolbar-actions">
            <ElInput
              v-model="keyword"
              data-testid="agents-search"
              class="agents-search"
              placeholder="按名称/描述筛选（导出同口径）"
              clearable
              @clear="keyword = ''"
            />
            <ElButton
              v-if="readDetailAllowed"
              data-testid="agents-export"
              :loading="exporting"
              @click="runExport()"
            >
              导出 NDJSON
            </ElButton>
            <ElButton
              v-if="writeAllowed"
              type="primary"
              data-testid="agents-create-open"
              @click="openCreate()"
            >
              新建智能体
            </ElButton>
            <ElButton data-testid="agents-reload" :loading="loading" @click="load()">
              刷新
            </ElButton>
          </div>
        </div>
      </ElCard>

      <ElAlert
        v-if="actionError"
        data-testid="agents-action-error"
        :title="actionError"
        type="error"
        :closable="false"
        class="mb-4"
      />

      <ElCard>
        <ElAlert
          v-if="phase === 'error'"
          :data-testid="listStateTestId('agents', phase)"
          :title="error"
          type="error"
          :closable="false"
          class="mb-4"
        />
        <div v-else-if="phase === 'loading'" :data-testid="listStateTestId('agents', phase)" class="state-block">
          正在加载…
        </div>
        <div v-else-if="phase === 'idle'" :data-testid="listStateTestId('agents', phase)" class="state-block">
          尚未加载。
        </div>
        <div v-else-if="phase === 'empty'" :data-testid="listStateTestId('agents', phase)" class="state-block">
          成功响应，当前平台目录没有智能体定义。
        </div>

        <template v-else>
          <ElEmpty
            v-if="filteredRows.length === 0"
            data-testid="agents-filter-empty"
            description="当前筛选没有匹配的智能体（导出同样为空）。"
          />
          <ElTable v-else :data-testid="listStateTestId('agents', phase)" :data="pagedRows" border size="small">
            <ElTableColumn label="ID" width="260">
              <template #default="{ row }">
                {{ agentIdOf(row) }}
              </template>
            </ElTableColumn>
            <ElTableColumn label="名称" min-width="160">
              <template #default="{ row }">
                {{ nameOf(row) }}
                <ElTag v-if="isBuiltin(row)" size="small" type="info" class="ml-1">
                  内置
                </ElTag>
                <ElTag v-if="row.active === true" size="small" type="success" class="ml-1">
                  激活中
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="description" label="描述" min-width="200" show-overflow-tooltip />
            <ElTableColumn label="生效槽位" width="120">
              <template #default="{ row }">
                {{ row.effectiveSlots ?? 0 }} / {{ effectiveSlotTotal }}
                <span v-if="row.inactiveSlots" class="card-header-meta">（另有 {{ row.inactiveSlots }} 个当前架构读不到）</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="300">
              <template #default="{ row }">
                <ElButton
                  v-if="readDetailAllowed"
                  link
                  size="small"
                  :data-testid="`agents-detail-${agentIdOf(row)}`"
                  @click="openDetail(row)"
                >
                  详情
                </ElButton>
                <ElButton
                  v-if="readPromptsAllowed"
                  link
                  size="small"
                  data-testid="agents-open-prompts"
                  @click="openPrompts(row)"
                >
                  提示词槽位
                </ElButton>
                <ElButton
                  v-if="writeAllowed && canEditRow(row)"
                  link
                  size="small"
                  :data-testid="`agents-edit-${agentIdOf(row)}`"
                  @click="openEdit(row)"
                >
                  编辑
                </ElButton>
                <ElButton
                  v-if="activateAllowed && row.active !== true"
                  link
                  size="small"
                  :loading="activating === agentIdOf(row)"
                  :data-testid="`agents-activate-${agentIdOf(row)}`"
                  @click="activateRow(row)"
                >
                  激活
                </ElButton>
                <ElButton
                  v-if="deleteAllowed"
                  link
                  size="small"
                  type="danger"
                  :disabled="!!deleteBlockedReason(row)"
                  :title="deleteBlockedReason(row)"
                  :loading="removing === agentIdOf(row)"
                  :data-testid="`agents-delete-${agentIdOf(row)}`"
                  @click="removeRow(row)"
                >
                  删除
                </ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
          <ElPagination
            v-if="filteredRows.length > pageSize"
            data-testid="agents-pagination"
            class="agents-pagination"
            layout="prev, pager, next"
            :current-page="page"
            :page-size="pageSize"
            :total="filteredRows.length"
            @current-change="onPageChange"
          />
        </template>
      </ElCard>

      <ElDialog v-model="createVisible" title="新建智能体（POST /agent-catalog/agents）" width="520px">
        <ElForm label-width="80px" @submit.prevent>
          <ElFormItem label="名称">
            <ElInput v-model="createForm.name" data-testid="agents-create-name" />
          </ElFormItem>
          <ElFormItem label="描述">
            <ElInput v-model="createForm.description" type="textarea" data-testid="agents-create-description" />
          </ElFormItem>
          <ElFormItem label="头像标识">
            <ElInput v-model="createForm.avatar" data-testid="agents-create-avatar" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="createVisible = false">
            取消
          </ElButton>
          <ElButton
            type="primary"
            :loading="creating"
            :disabled="!createForm.name.trim()"
            data-testid="agents-create-submit"
            @click="submitCreate"
          >
            创建
          </ElButton>
        </template>
      </ElDialog>

      <ElDialog v-model="editVisible" title="编辑智能体（PUT /agent-catalog/agents/{id}）" width="520px">
        <ElAlert
          type="info"
          :closable="false"
          class="mb-4"
          title="名称/描述/头像在服务端是显式覆盖：留空即清空（本页总是把三个字段一起提交，避免只改名字把描述清掉）。"
        />
        <ElForm label-width="80px" @submit.prevent>
          <ElFormItem label="名称">
            <ElInput v-model="editForm.name" data-testid="agents-edit-name" />
          </ElFormItem>
          <ElFormItem label="描述">
            <ElInput v-model="editForm.description" type="textarea" data-testid="agents-edit-description" />
          </ElFormItem>
          <ElFormItem label="头像标识">
            <ElInput v-model="editForm.avatar" data-testid="agents-edit-avatar" />
          </ElFormItem>

          <ElAlert
            data-testid="agents-binding-notice"
            type="warning"
            :closable="false"
            class="mb-4"
            title="绑定为空即禁用：未绑定任何技能 / MCP 工具时，该智能体运行期看不到任何技能，也看不到任何 MCP 工具。"
          />
          <ElAlert
            v-if="bindingLoadError"
            data-testid="agents-binding-error"
            type="error"
            :closable="false"
            class="mb-4"
            :title="bindingLoadError"
          />
          <ElAlert
            v-if="bindingNotice"
            data-testid="agents-binding-fallback-notice"
            type="info"
            :closable="false"
            class="mb-4"
            :title="bindingNotice"
          />

          <ElFormItem label="模型（MODEL）">
            <ElSelect
              v-model="modelBindingIds"
              multiple
              clearable
              :disabled="modelOptions.length === 0"
              data-testid="agents-binding-model"
            >
              <ElOption v-for="o in modelOptions" :key="o.id" :label="o.label" :value="o.id" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="技能（SKILL）">
            <ElSelect v-model="skillBindingIds" multiple clearable data-testid="agents-binding-skill">
              <ElOption
                v-for="o in skillOptions"
                :key="o.skillCode"
                :label="o.name || o.skillCode"
                :value="o.skillCode"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="MCP 工具（MCP）">
            <ElSelect v-if="mcpListAvailable" v-model="mcpBindingIds" multiple clearable data-testid="agents-binding-mcp">
              <ElOption v-for="o in mcpOptions" :key="o.toolId" :label="o.name || o.toolId" :value="o.toolId" />
            </ElSelect>
            <template v-else>
              <ElInput
                v-model="mcpFallbackInput"
                data-testid="agents-binding-mcp-input"
                placeholder="MCP 清单未启用；填写 tool id 后点「添加」，保存时由服务端校验存在/启用"
              />
              <ElButton link size="small" data-testid="agents-binding-mcp-add" @click="addMcpFallbackId()">
                添加
              </ElButton>
            </template>
          </ElFormItem>
          <ElFormItem label="编排模式（ORCHESTRATION）">
            <ElSelect v-model="orchestrationMode" clearable data-testid="agents-binding-orchestration">
              <ElOption v-for="m in ORCHESTRATION_MODES" :key="m" :label="m" :value="m" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="当前绑定（只读）">
            <div data-testid="agents-binding-echo" class="card-header-meta">
              {{ bindingEcho }}
            </div>
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="editVisible = false">
            取消
          </ElButton>
          <ElButton
            type="primary"
            :loading="editing"
            :disabled="!editForm.name.trim()"
            data-testid="agents-edit-submit"
            @click="submitEdit"
          >
            保存
          </ElButton>
        </template>
      </ElDialog>

      <ElAlert
        v-if="exportNotice"
        data-testid="agents-export-notice"
        :title="exportNotice"
        type="success"
        :closable="false"
        class="mb-4"
      />

      <ElDialog v-model="detailVisible" title="智能体详情（GET /agent-catalog/agents/{id}）" width="560px">
        <div v-if="detailLoading" data-testid="agents-detail-loading" class="state-block">
          正在读取详情…
        </div>
        <ElAlert
          v-else-if="detailError"
          data-testid="agents-detail-error"
          :title="detailError"
          type="error"
          :closable="false"
        />
        <ElDescriptions v-else-if="detailRow" :column="1" border data-testid="agents-detail-body">
          <ElDescriptionsItem label="ID">
            {{ agentIdOf(detailRow) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="名称">
            {{ nameOf(detailRow) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="描述">
            {{ detailRow.description ?? '（空）' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="头像标识">
            {{ detailRow.avatar || '（空）' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="内置 / 激活">
            {{ detailRow.builtin ? '内置' : '普通' }} / {{ detailRow.active ? '激活中' : '未激活' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="槽位">
            {{ detailRow.effectiveSlots ?? 0 }} 生效 / {{ detailRow.inactiveSlots ?? 0 }} 当前架构读不到
          </ElDescriptionsItem>
          <ElDescriptionsItem label="创建时间">
            {{ detailRow.createTime ?? '—' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="更新时间">
            {{ detailRow.updateTime ?? '—' }}
          </ElDescriptionsItem>
        </ElDescriptions>
      </ElDialog>

      <ElDrawer v-model="promptVisible" title="Prompt 槽位（GET /agent-catalog/agents/{id}/prompts）" size="55%">
        <div v-if="promptLoading" data-testid="prompt-loading" class="state-block">
          正在加载槽位…
        </div>
        <ElAlert
          v-else-if="promptError"
          data-testid="prompt-error"
          :title="promptError"
          type="error"
          :closable="false"
        />
        <template v-else>
          <ElAlert
            type="info"
            :closable="false"
            class="mb-4"
            :title="`智能体：${promptAgentName || promptAgentId}${promptBuiltin ? '（内置）' : ''} · 架构 ${promptMode || '未知'} · 留空即回落内置${promptDefaultAgentName ? `（${promptDefaultAgentName}）` : ''}`"
          />
          <ElAlert
            v-if="promptNotice"
            data-testid="prompt-notice"
            type="success"
            :closable="false"
            :title="promptNotice"
            class="mb-4"
          />
          <ElAlert
            v-if="!canEditRow({ builtin: promptBuiltin })"
            data-testid="prompt-builtin-readonly"
            type="warning"
            :closable="false"
            class="mb-4"
            title="内置智能体不可保存提示词（服务端 mustLoadEditable 拒绝）；只能查看与复制默认内容。"
          />
          <ElEmpty
            v-if="promptSlots.length === 0"
            data-testid="prompt-empty"
            description="该智能体没有可编辑的槽位（AgentPromptSlot 枚举为空）"
          />
          <div v-for="slot in promptSlots" :key="String(slot.slotKey)" class="slot-block">
            <div class="slot-head">
              <strong>{{ slotLabel(slot) }}</strong>
              <code class="card-header-meta">{{ slot.slotKey }}</code>
              <ElTag size="small" :type="slot.effective ? 'success' : 'info'">
                {{ slot.effective ? '当前架构生效' : '当前架构不生效' }}
              </ElTag>
              <ElTag v-if="isFallingBack(slot)" size="small" type="info">
                未配置，回落内置
              </ElTag>
              <span class="card-header-meta">{{ slot.groupName || slot.group }}</span>
            </div>
            <div v-if="slot.editorHint" class="card-header-meta">
              {{ slot.editorHint }}
            </div>
            <div v-if="!slot.effective && slot.inactiveReason" data-testid="prompt-inactive-reason" class="card-header-meta">
              {{ slot.inactiveReason }}
            </div>
            <div v-if="(slot.requiredPlaceholders ?? []).length" class="card-header-meta">
              必需占位符：{{ (slot.requiredPlaceholders ?? []).join('、') }}
            </div>
            <ElInput
              v-model="slot.content"
              type="textarea"
              :rows="5"
              :data-testid="`prompt-slot-${slot.slotKey}`"
            />
            <div class="slot-actions">
              <ElButton
                size="small"
                type="primary"
                :disabled="!writeAllowed || !canEditRow({ builtin: promptBuiltin })"
                :loading="savingSlot === slot.slotKey"
                :data-testid="`prompt-save-${slot.slotKey}`"
                @click="saveSlot(slot)"
              >
                保存槽位（留空恢复回落）
              </ElButton>
              <ElButton
                size="small"
                :loading="copyingSlot === slot.slotKey"
                :data-testid="`prompt-copy-default-${slot.slotKey}`"
                @click="copyDefault(slot)"
              >
                从默认复制
              </ElButton>
            </div>
          </div>
        </template>
      </ElDrawer>
    </template>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
}

.card-header-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.state-block {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.slot-block {
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.slot-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.slot-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.ml-1 {
  margin-left: 4px;
}

.agents-search {
  width: 260px;
}

.agents-pagination {
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
