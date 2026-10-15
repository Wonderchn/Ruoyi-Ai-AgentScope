<!--
  Skills（04-page-map「Skills」admin /ai/skills）。

  ## 可达性（S2-F11-A1）

  7 条白名单路由已随 F11-A1 接线：`AiEmbeddedAgentSkillConfiguration` 登记
  `AgentSkillController`（三件套：类级 /internal/ai/v1/agent-catalog + 整数
  `ApiEnvelope` + GET 交付回执），`AiGatewayController.ROUTES` 逐条放行
  `/api/ai/v1/agent-catalog/agent-skills**`（读=skill.list/skill.read，
  写=skill.write/skill.delete；四条动作全部要求平台管理身份）。
  **装配随 S1 引擎闭包门（ragent.engine.type=agent）**：引擎关的形态下内层未命中
  fail-closed 503（页面如实显示服务端结论，不假装成功）。
  列表 `data` 是 `IPage`（records/total）——页面按此解析（整数信封，非 ragent Result）。

  ## 本切片范围（F11:0）

  列表 + 详情 + 新增/编辑表单 + 删除 + 列表导出（字段白名单 + 外部凭据脱敏）+
  单条启停。导出**复用列表读动作**（`skill.list`）在浏览器侧生成 CSV：
  零新端点、零新 canonical 动作、零新权限行、零迁移。
  纯逻辑（校验/守卫/脱敏/CSV）在 `./skillAdmin.ts`，本页只做接线与状态。
-->
<script setup lang="ts">
import { computed, ref } from 'vue';
import { aiApi } from '@/api';
import { usePermission } from '@/composables/usePermission';
import { errorMessageOf } from '@/utils';
import {
  deleteBlockedReason,
  isBuiltin,
  normalizeSkillList,
  SKILL_PERMISSIONS,
  skillCreateBody,
  skillIdOf,
  skillUpdateBody,
  toSkillsCsv,
  type SkillRow,
} from './skillAdmin';

const { can } = usePermission();

const rows = ref<SkillRow[]>([]);
const total = ref(0);
const loading = ref(false);
const error = ref('');
const loaded = ref(false);
const actionError = ref('');
const formError = ref('');
const exportNotice = ref('');

/** 四条动作与网关 canonical 动作逐字对应；缺权不发请求、不显示入口。 */
const readAllowed = computed(() => can(SKILL_PERMISSIONS.list));
const writeAllowed = computed(() => can(SKILL_PERMISSIONS.write));
const deleteAllowed = computed(() => can(SKILL_PERMISSIONS.delete));
/** 导出复用 `skill.list`：没有列表权就没有可导出的数据，也不提供入口。 */
const canExport = computed(() => readAllowed.value);

async function load() {
  if (!readAllowed.value) {
    rows.value = [];
    loaded.value = false;
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const page = await aiApi.agentSkills.list({ current: 1, size: 20 });
    const normalized = normalizeSkillList(page);
    rows.value = normalized.records;
    total.value = normalized.total;
    loaded.value = true;
  }
  catch (e) {
    error.value = errorMessageOf(e);
    rows.value = [];
    loaded.value = false;
  }
  finally {
    loading.value = false;
  }
}

void load();

/** 启停（POST /agent-catalog/agent-skills/{id}/enabled + query `enabled`）。 */
const toggling = ref('');

async function toggleEnabled(row: SkillRow) {
  const id = skillIdOf(row);
  if (!id || !writeAllowed.value) {
    return;
  }
  toggling.value = id;
  try {
    await aiApi.agentSkills.setEnabled(id, row.enabled !== true);
    await load();
  }
  catch (e) {
    actionError.value = errorMessageOf(e);
  }
  finally {
    toggling.value = '';
  }
}

// ------------------------------------------------------------------ 工具引用选项

const toolOptions = ref<Array<{ toolId: string; name: string }>>([]);
const toolOptionsLoaded = ref(false);

async function loadToolOptions() {
  if (toolOptionsLoaded.value || !writeAllowed.value) {
    return;
  }
  try {
    const options = await aiApi.agentSkills.toolOptions();
    toolOptions.value = (Array.isArray(options) ? options : []).map(option => ({
      toolId: String((option as Record<string, unknown>)?.toolId ?? ''),
      name: String((option as Record<string, unknown>)?.name ?? ''),
    })).filter(option => option.toolId);
    toolOptionsLoaded.value = true;
  }
  catch {
    // 工具选项取不到不阻塞表单：仍可提交空工具集，由服务端做权威校验
  }
}

// ------------------------------------------------------------------ 新增

const createVisible = ref(false);
const createForm = ref<SkillRow>({ skillCode: '', name: '', description: '', content: '', toolIds: [] });

function openCreate() {
  if (!writeAllowed.value) {
    return;
  }
  createForm.value = { skillCode: '', name: '', description: '', content: '', toolIds: [] };
  formError.value = '';
  createVisible.value = true;
  void loadToolOptions();
}

async function submitCreate() {
  if (!writeAllowed.value) {
    return;
  }
  const body = skillCreateBody(createForm.value);
  if (!body) {
    // 错误提交零请求、零部分保存：校验不过就不发，别让服务端去拒绝一次必然失败的写
    formError.value = '技能标识/名称/描述/正文不合法：请检查后重试（未提交、未落库）';
    return;
  }
  formError.value = '';
  try {
    await aiApi.agentSkills.create(body);
    createVisible.value = false;
    await load();
  }
  catch (e) {
    formError.value = errorMessageOf(e);
  }
}

// ------------------------------------------------------------------ 详情

const detailVisible = ref(false);
const detail = ref<SkillRow | null>(null);

async function openDetail(row: SkillRow) {
  const id = skillIdOf(row);
  if (!id || !readAllowed.value) {
    return;
  }
  try {
    detail.value = (await aiApi.agentSkills.get(id)) as SkillRow;
    detailVisible.value = true;
  }
  catch (e) {
    detail.value = null;
    detailVisible.value = false;
    actionError.value = errorMessageOf(e);
  }
}

// ------------------------------------------------------------------ 编辑

const editVisible = ref(false);
const editForm = ref<SkillRow>({ id: '', skillCode: '', name: '', description: '', content: '', toolIds: [] });

function openEdit(row: SkillRow) {
  if (!writeAllowed.value || isBuiltin(row)) {
    return;
  }
  editForm.value = {
    id: skillIdOf(row),
    skillCode: String(row?.skillCode ?? ''),
    name: String(row?.name ?? ''),
    description: String(row?.description ?? ''),
    content: String(row?.content ?? ''),
    toolIds: Array.isArray(row?.toolIds) ? [...(row.toolIds as string[])] : [],
  };
  formError.value = '';
  editVisible.value = true;
  void loadToolOptions();
}

async function submitEdit() {
  if (!writeAllowed.value) {
    return;
  }
  const id = String(editForm.value.id ?? '');
  const body = skillUpdateBody(editForm.value);
  if (!id || !body) {
    formError.value = '名称/描述/正文不合法：请检查后重试（未提交、未落库）';
    return;
  }
  formError.value = '';
  try {
    await aiApi.agentSkills.update(id, body);
    editVisible.value = false;
    await load();
  }
  catch (e) {
    formError.value = errorMessageOf(e);
  }
}

// ------------------------------------------------------------------ 删除

async function removeRow(row: SkillRow) {
  if (!deleteAllowed.value) {
    return;
  }
  const blocked = deleteBlockedReason(row);
  if (blocked) {
    actionError.value = blocked;
    return;
  }
  const id = skillIdOf(row);
  if (!id) {
    return;
  }
  actionError.value = '';
  try {
    await aiApi.agentSkills.remove(id);
    await load();
  }
  catch (e) {
    actionError.value = errorMessageOf(e);
  }
}

// ------------------------------------------------------------------ 导出

/**
 * 列表导出：**零新端点**——复用已加载列表行（`skill.list`）在浏览器侧生成 CSV，
 * 字段白名单 + 外部凭据脱敏见 `./skillAdmin.ts`。
 */
function buildExport(): string {
  const csv = toSkillsCsv(rows.value);
  exportNotice.value = `已导出 ${rows.value.length} 行（字段白名单 + 外部凭据脱敏；复用列表读动作，零新端点）`;
  return csv;
}

function str(row: SkillRow, key: string): string {
  const value = row?.[key];
  return value === null || value === undefined ? '' : String(value);
}

function toolsOf(row: SkillRow): string {
  const value = row?.toolIds;
  return Array.isArray(value) ? value.map(item => String(item)).join(', ') : '';
}
</script>

<template>
  <div>
    <ElCard>
      <template #header>
        <div class="toolbar">
          <span>Skills（GET /api/ai/v1/agent-catalog/agent-skills）</span>
          <div class="toolbar-actions">
            <ElButton data-testid="skills-reload" :loading="loading" @click="load()">
              加载
            </ElButton>
            <ElButton
              v-if="writeAllowed"
              type="primary"
              data-testid="skills-create"
              @click="openCreate()"
            >
              新增
            </ElButton>
            <ElButton v-if="canExport" data-testid="skills-export" @click="buildExport()">
              导出 CSV
            </ElButton>
          </div>
        </div>
      </template>

      <ElAlert
        v-if="error"
        data-testid="skills-error"
        :title="error"
        type="error"
        :closable="false"
        class="mb-4"
      />
      <ElAlert
        v-if="actionError"
        data-testid="skills-action-error"
        :title="actionError"
        type="warning"
        :closable="false"
        class="mb-4"
      />
      <ElAlert
        v-if="exportNotice"
        data-testid="skills-export-notice"
        :title="exportNotice"
        type="success"
        :closable="false"
        class="mb-4"
      />
      <div v-if="loading" data-testid="skills-loading" class="state-block">
        正在加载…
      </div>
      <div v-else-if="!loaded" data-testid="skills-idle" class="state-block">
        尚未加载（点击「加载」读取技能列表）。
      </div>
      <div v-else-if="rows.length === 0" data-testid="skills-empty" class="state-block">
        成功响应，没有 Skill。
      </div>
      <ElTable v-else data-testid="skills-rows" :data="rows" border size="small">
        <ElTableColumn label="ID" width="280">
          <template #default="{ row }">
            {{ str(row, 'id') }}
          </template>
        </ElTableColumn>
        <ElTableColumn prop="skillCode" label="标识" min-width="140" />
        <ElTableColumn prop="name" label="名称" min-width="160" />
        <ElTableColumn prop="description" label="描述" min-width="240" show-overflow-tooltip />
        <ElTableColumn label="工具引用" min-width="160">
          <template #default="{ row }">
            {{ toolsOf(row) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="启用" width="110">
          <template #default="{ row }">
            <ElTag size="small" :type="row.enabled === true ? 'success' : 'info'">
              {{ row.enabled === true ? '开' : '关' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="300">
          <template #default="{ row }">
            <ElButton link size="small" data-testid="skills-detail" @click="openDetail(row)">
              详情
            </ElButton>
            <ElButton
              v-if="writeAllowed"
              link
              size="small"
              data-testid="skills-edit"
              @click="openEdit(row)"
            >
              编辑
            </ElButton>
            <ElButton
              v-if="writeAllowed"
              link
              size="small"
              :loading="toggling === str(row, 'id')"
              data-testid="skills-toggle"
              @click="toggleEnabled(row)"
            >
              {{ row.enabled === true ? '停用' : '启用' }}
            </ElButton>
            <ElButton
              v-if="deleteAllowed"
              link
              size="small"
              type="danger"
              data-testid="skills-delete"
              @click="removeRow(row)"
            >
              删除
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog v-model="detailVisible" title="技能详情（skill.read）" width="720">
      <ElDescriptions v-if="detail" :column="2" border>
        <ElDescriptionsItem label="标识">
          {{ str(detail, 'skillCode') }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="名称">
          {{ str(detail, 'name') }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="描述" :span="2">
          {{ str(detail, 'description') }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="工具引用" :span="2">
          {{ toolsOf(detail) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="启用">
          {{ detail.enabled === true ? '开' : '关' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="排序">
          {{ str(detail, 'sortOrder') }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="正文（content）" :span="2">
          <pre class="content">{{ str(detail, 'content') }}</pre>
        </ElDescriptionsItem>
      </ElDescriptions>
    </ElDialog>

    <ElDialog v-model="createVisible" title="新增技能（skill.write）" width="720">
      <ElAlert v-if="formError" :title="formError" type="error" :closable="false" class="mb-4" />
      <ElForm label-width="110px">
        <ElFormItem label="技能标识">
          <ElInput v-model="createForm.skillCode" data-testid="skills-create-code" placeholder="小写字母开头，2~64 位（如 f11_hello）" />
        </ElFormItem>
        <ElFormItem label="名称">
          <ElInput v-model="createForm.name" data-testid="skills-create-name" />
        </ElFormItem>
        <ElFormItem label="描述">
          <ElInput v-model="createForm.description" data-testid="skills-create-description" />
        </ElFormItem>
        <ElFormItem label="正文">
          <ElInput v-model="createForm.content" type="textarea" :rows="6" data-testid="skills-create-content" />
        </ElFormItem>
        <ElFormItem label="工具引用">
          <ElSelect v-model="createForm.toolIds" multiple clearable data-testid="skills-create-tools">
            <ElOption v-for="option in toolOptions" :key="option.toolId" :label="option.name" :value="option.toolId" />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="createVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" data-testid="skills-create-submit" @click="submitCreate()">
          提交
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="editVisible" title="编辑技能（skill.write；标识不可改）" width="720">
      <ElAlert v-if="formError" :title="formError" type="error" :closable="false" class="mb-4" />
      <ElForm label-width="110px">
        <ElFormItem label="技能标识">
          <ElInput :model-value="str(editForm, 'skillCode')" disabled data-testid="skills-edit-code" />
        </ElFormItem>
        <ElFormItem label="名称">
          <ElInput v-model="editForm.name" data-testid="skills-edit-name" />
        </ElFormItem>
        <ElFormItem label="描述">
          <ElInput v-model="editForm.description" data-testid="skills-edit-description" />
        </ElFormItem>
        <ElFormItem label="正文">
          <ElInput v-model="editForm.content" type="textarea" :rows="6" data-testid="skills-edit-content" />
        </ElFormItem>
        <ElFormItem label="工具引用">
          <ElSelect v-model="editForm.toolIds" multiple clearable data-testid="skills-edit-tools">
            <ElOption v-for="option in toolOptions" :key="option.toolId" :label="option.name" :value="option.toolId" />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="editVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" data-testid="skills-edit-submit" @click="submitEdit()">
          保存
        </ElButton>
      </template>
    </ElDialog>
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

.state-block {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.content {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
