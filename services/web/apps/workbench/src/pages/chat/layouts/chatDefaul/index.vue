<!-- 默认消息列表页：编辑式工作区首页（问候 → 输入框 → 工作区/提示/最近对话） -->
<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { listKnowledgeBases } from '@/api/rag';
import ChatSender from '@/components/ChatSender/index.vue';
import WelecomeText from '@/components/WelecomeText/index.vue';
import { useSessionStore } from '@/stores/modules/session';
import { useUserStore } from '@/stores/modules/user';
import { userFacingErrorMessage } from '@/utils/user-facing-error';

const sessionStore = useSessionStore();
const userStore = useUserStore();
const router = useRouter();

const senderValue = ref('');
const senderRef = ref<InstanceType<typeof ChatSender> | null>(null);
const isLoggedIn = computed(() => !!userStore.token);

/**
 * `rag.chat` 是**知识库问答**：resourceRefs 为空时服务端会以
 * `FAILED/NO_AUTHORIZED_SCOPE` 终止（0 次模型外发）。因此首页必须先让用户
 * 选择问答范围，不能把“直接输入一句话”当成一定能成功的通用聊天。
 */
const kbList = ref<Array<{ kbId: string; name: string }>>([]);
const kbError = ref('');
const kbLoading = ref(false);
const selectedKbIds = ref<string[]>([]);
let kbLoadEpoch = 0;

async function loadKnowledgeBases() {
  const epoch = ++kbLoadEpoch;
  const authEpoch = userStore.authEpoch;
  if (!userStore.token) {
    kbList.value = [];
    selectedKbIds.value = [];
    kbError.value = '';
    kbLoading.value = false;
    return;
  }
  kbLoading.value = true;
  kbError.value = '';
  try {
    const rows = await listKnowledgeBases();
    if (epoch !== kbLoadEpoch || authEpoch !== userStore.authEpoch)
      return;
    kbList.value = (Array.isArray(rows) ? rows : [])
      .filter(row => row && typeof row.kbId === 'string')
      .map(row => ({ kbId: row.kbId, name: row.name || row.kbId }));
    // 只有一个知识库时直接选中，减少一次无意义的选择。
    selectedKbIds.value = kbList.value.length === 1 ? [kbList.value[0].kbId] : [];
  }
  catch (error) {
    if (epoch !== kbLoadEpoch || authEpoch !== userStore.authEpoch)
      return;
    kbList.value = [];
    selectedKbIds.value = [];
    kbError.value = `知识库列表不可用：${userFacingErrorMessage(error, '无法确认可用授权范围')}`;
  }
  finally {
    if (epoch === kbLoadEpoch && authEpoch === userStore.authEpoch)
      kbLoading.value = false;
  }
}

onMounted(loadKnowledgeBases);
watch(() => userStore.authEpoch, loadKnowledgeBases);

const prompts = [
  '帮我总结知识库里关于部署流程的文档',
  '用一张图说明这个方案的整体结构',
  '把这段需求拆成可执行的开发任务',
  '这条报错的根因是什么？',
];

const recent = computed(() =>
  (sessionStore.sessionList ?? [])
    .filter(item => item?.id)
    .slice(0, 4),
);

/**
 * 「最近对话」行右侧的日期。
 *
 * 为什么不能直接写 `item.createTime ? new Date(item.createTime).toLocaleDateString() : ''`：
 * 服务端 `GET /conversations` 的行目前只返回 `{conversationId, title}`（`last_time` 列为 NULL，
 * 序列化时被丢弃），于是 `new Date(undefined)` 得到 **Invalid Date 对象**——它本身是**真值**，
 * 所以那个 falsy 判断根本挡不住，模板会把它渲染成字面量 "Invalid Date"。
 * 这里显式校验时间有效性：拿不到有效时间就不显示，**不猜、不写占位时间**。
 */
function recentDateLabel(value?: Date | string | null): string {
  if (!value)
    return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleDateString('zh-CN');
}

function focusComposer() {
  const el = document.querySelector<HTMLElement>(
    '.chat-sender textarea, .chat-sender input, .chat-sender [contenteditable="true"]',
  );
  el?.focus();
}

function usePrompt(text: string) {
  senderValue.value = text;
  focusComposer();
}

async function handleSubmit(content: string) {
  const text = String(content ?? '').trim();
  if (text === '')
    return;
  if (!isLoggedIn.value) {
    userStore.ensureLogin('/chat', '登录后即可开始对话');
    return;
  }
  if (kbLoading.value) {
    ElMessage.info('正在加载可用知识库，请稍候');
    return;
  }
  if (kbList.value.length === 0) {
    ElMessage.warning('还没有可用的知识库。先到“知识库问答”创建知识库并上传 PDF，再回来提问。');
    await router.push('/rag');
    return;
  }
  if (selectedKbIds.value.length === 0) {
    ElMessage.warning('请先选择本次问答使用的知识库');
    return;
  }
  const outcome = await sessionStore.createSessionList({
    title: text.slice(0, 10),
    initialText: text,
    kbIds: selectedKbIds.value,
  });
  if (outcome.ok)
    senderValue.value = '';
}

function login() {
  userStore.ensureLogin('/chat', '登录后查看最近对话');
}
</script>

<template>
  <div class="home">
    <WelecomeText />

    <div class="composer">
      <ChatSender
        ref="senderRef"
        v-model="senderValue"
        @submit="handleSubmit"
      />
      <div v-if="isLoggedIn" class="kb-scope">
        <span class="kb-scope-label">问答范围</span>
        <el-select
          v-model="selectedKbIds"
          multiple
          collapse-tags
          collapse-tags-tooltip
          :loading="kbLoading"
          :disabled="kbLoading || kbList.length === 0"
          placeholder="选择知识库（可多选）"
          class="kb-scope-select"
          data-testid="home-kb-select"
        >
          <el-option
            v-for="kb in kbList"
            :key="kb.kbId"
            :label="kb.name"
            :value="kb.kbId"
          />
        </el-select>
        <RouterLink to="/rag" class="kb-scope-link">
          管理知识库
        </RouterLink>
      </div>
      <el-alert
        v-if="kbError"
        type="error"
        :closable="false"
        :title="kbError"
        class="kb-scope-alert"
      />
      <el-alert
        v-else-if="isLoggedIn && !kbLoading && kbList.length === 0"
        type="info"
        :closable="false"
        class="kb-scope-alert"
      >
        <template #title>
          还没有可用知识库。先到
          <RouterLink to="/rag" class="kb-scope-link">
            知识库问答
          </RouterLink>
          创建知识库并上传 PDF，再回来提问。
        </template>
      </el-alert>
    </div>

    <div class="home-grid">
      <section class="panel panel-prompts">
        <div class="panel-head">
          <h2>试试这样问</h2>
        </div>
        <ul class="prompt-list">
          <li
            v-for="prompt in prompts"
            :key="prompt"
          >
            <button
              type="button"
              class="prompt-row"
              @click="usePrompt(prompt)"
            >
              {{ prompt }}
            </button>
          </li>
        </ul>
      </section>

      <section class="panel panel-recent">
        <div class="panel-head">
          <h2>最近对话</h2>
          <RouterLink
            v-if="recent.length"
            to="/history"
            class="panel-link"
          >
            全部
          </RouterLink>
        </div>
        <ul
          v-if="recent.length"
          class="recent-list"
        >
          <li
            v-for="item in recent"
            :key="item.id"
          >
            <RouterLink
              :to="`/chat/${item.id}`"
              class="recent-row"
            >
              <span class="recent-title">{{ item.sessionTitle || '未命名对话' }}</span>
              <span
                v-if="recentDateLabel(item.createTime)"
                class="recent-time"
              >{{ recentDateLabel(item.createTime) }}</span>
            </RouterLink>
          </li>
        </ul>
        <div
          v-else
          class="panel-empty"
        >
          <p>{{ isLoggedIn ? '还没有对话。从上面的输入框开始。' : '登录后查看并继续你的对话。' }}</p>
          <button
            v-if="!isLoggedIn"
            type="button"
            class="btn-primary"
            @click="login"
          >
            登录
          </button>
          <dl class="recent-facts">
            <div><dt>历史</dt><dd>保存在服务端</dd></div>
            <div><dt>每页</dt><dd>25 条</dd></div>
          </dl>
        </div>
      </section>

      <section class="panel panel-about">
        <div class="panel-head">
          <h2>这个工作区</h2>
        </div>
        <p class="about-copy">
          智能体、知识库与媒体生成在同一个网关内；每次调用都按你的身份与额度放行，回答带可追溯的引用。
        </p>
        <dl class="about-list">
          <div>
            <dt>模型</dt>
            <dd>DeepSeek · 通义</dd>
          </div>
          <div>
            <dt>检索</dt>
            <dd>已发布文档</dd>
          </div>
          <div>
            <dt>媒体</dt>
            <dd>图片生成 · 私有取流</dd>
          </div>
        </dl>
      </section>
    </div>
  </div>
</template>

<style scoped lang="scss">
.home {
  --ground: transparent;
  --surface: var(--apple-surface);
  --border: var(--apple-separator);
  --control: var(--apple-control);
  --text: var(--apple-text);
  --muted: var(--apple-secondary);
  --accent: var(--apple-blue);
  --radius-panel: 18px;
  --radius-row: 12px;
  --font: -apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Helvetica Neue', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;

  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  max-width: 1120px;
  margin: 0 auto;
  padding: 56px 32px 64px;
  box-sizing: border-box;
  font-family: var(--font);
  color: var(--text);
}

.composer {
  width: 100%;
  max-width: 720px;
  margin: 26px auto 0;
}

.kb-scope {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  padding: 0 4px;
}

.kb-scope-label {
  flex: none;
  font-size: 12px;
  color: var(--apple-secondary, var(--text-secondary));
}

.kb-scope-select {
  flex: 1 1 auto;
  min-width: 0;
}

.kb-scope-link {
  flex: none;
  font-size: 12px;
  color: var(--apple-blue, var(--primary));
  text-decoration: none;
}

.kb-scope-link:hover {
  text-decoration: underline;
}

.kb-scope-alert {
  margin-top: 10px;
}

.home-grid {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 16px;
  width: 100%;
  margin-top: 56px;
}

.panel {
  box-sizing: border-box;
  padding: 22px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
}

.panel-prompts { grid-column: span 4; }
.panel-recent { grid-column: span 4; }
.panel-about { grid-column: span 4; }

.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.panel-head h2 {
  margin: 0;
  font-size: 17px;
  line-height: 1.35;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--text);
}

.panel-link {
  font-size: 12px;
  color: var(--accent);
  text-decoration: none;
}

.prompt-list,
.recent-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.prompt-row,
.recent-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  min-height: 63px;
  padding: 12px 16px;
  box-sizing: border-box;
  background: var(--apple-bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-row);
  color: var(--text);
  font-family: var(--font);
  font-size: 14px;
  text-align: left;
  text-decoration: none;
  cursor: pointer;
  transition: border-color 150ms ease, background-color 150ms ease;
}

.prompt-row:hover,
.recent-row:hover {
  border-color: var(--apple-separator-strong);
  background: var(--apple-fill);
}

.prompt-row {
  min-height: 0;
  padding: 12px 14px;
  line-height: 1.5;
  justify-content: flex-start;
}

.recent-row {
  justify-content: space-between;
}

.recent-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-time {
  flex: none;
  font-size: 12px;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.panel-empty {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 14px;
  padding: 8px 2px 4px;
}

.panel-empty-note {
  margin-top: -6px !important;
  font-size: 12px;
}

.panel-empty p {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--muted);
}

.btn-primary {
  height: 38px;
  padding: 0 20px;
  border: 0;
  border-radius: 999px;
  background: var(--apple-blue);
  color: #ffffff;
  font-family: var(--font);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
}

.btn-primary:hover {
  background: var(--apple-blue-hover);
}

.about-copy {
  margin: 0 0 16px;
  font-size: 14px;
  line-height: 1.65;
  color: var(--muted);
}

.about-list,
.recent-facts {
  margin: 4px 0 0;
  display: flex;
  flex-direction: column;
  width: 100%;
}

.about-list > div,
.recent-facts > div {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 11px 0;
  border-top: 1px solid var(--apple-separator);
}

.about-list > div:first-child,
.recent-facts > div:first-child {
  border-top: 0;
}

.about-list dt,
.recent-facts dt {
  font-size: 13px;
  color: var(--muted);
}

.about-list dd,
.recent-facts dd {
  margin: 0;
  font-size: 13px;
  color: var(--text);
}

@media (max-width: 900px) {
  .home {
    padding: 32px 16px 40px;
  }
  .panel-prompts,
  .panel-recent,
  .panel-about {
    grid-column: span 12;
  }
  .home-grid {
    margin-top: 36px;
  }
}
</style>
