<script setup lang="ts">
import type { MediaType } from '@/api/media/types';
import { Headset, Picture, Refresh, VideoCamera } from '@element-plus/icons-vue';
import MediaForm from './components/MediaForm.vue';
import MediaPreview from './components/MediaPreview.vue';
import MediaTasks from './components/MediaTasks.vue';
import { useMediaWorkbench } from './useMediaWorkbench';

const {
  kind,
  draft,
  taskId,
  models,
  modelsLoading,
  modelsError,
  formError,
  loggedIn,
  busy,
  selectedTask,
  selectedKey,
  tasks,
  queryingKey,
  canQuerySelected,
  login,
  loadModels,
  selectModel,
  submit,
  pause,
  resume,
  retry,
  queryExisting,
  selectTask,
} = useMediaWorkbench();
const modes = [
  { value: 'image' as MediaType, label: '图片生成', icon: Picture, hint: '把描述变成画面' },
  { value: 'audio' as MediaType, label: '语音合成', icon: Headset, hint: '让文字拥有声音' },
  { value: 'video' as MediaType, label: '视频生成', icon: VideoCamera, hint: '让场景动起来' },
];
</script>

<template>
  <main class="media-workbench">
    <header class="workbench-header">
      <div>
        <p class="eyebrow">
          MEDIA STUDIO
        </p><h1 class="workbench-title">
          媒体工作台
        </h1><p class="workbench-description">
          从一句描述开始，创作图片、语音与视频。
        </p>
      </div>
      <el-button :icon="Refresh" :loading="modelsLoading" :disabled="!loggedIn || busy" @click="loadModels">
        刷新模型
      </el-button>
    </header>

    <nav class="media-modes" aria-label="媒体类型">
      <button v-for="mode in modes" :key="mode.value" type="button" class="mode-button" :class="{ active: kind === mode.value }" :aria-pressed="kind === mode.value" @click="kind = mode.value">
        <el-icon class="mode-icon">
          <component :is="mode.icon" />
        </el-icon><span><strong>{{ mode.label }}</strong><small>{{ mode.hint }}</small></span>
      </button>
    </nav>

    <div class="workbench-grid">
      <MediaForm
        v-model="draft" v-model:task-id="taskId" :kind="kind" :models="models" :loading="modelsLoading"
        :model-error="modelsError" :error="formError" :busy="busy" :logged-in="loggedIn" :can-query="canQuerySelected"
        @submit="submit" @refresh="loadModels" @login="login" @select-model="selectModel" @query="queryExisting"
      />
      <MediaPreview :kind="kind" :task="selectedTask" :querying="queryingKey === selectedKey && Boolean(queryingKey)" :busy="busy" @pause="pause" @resume="resume" @retry="retry" />
    </div>
    <MediaTasks :tasks="tasks" :selected-key="selectedKey" @select="selectTask" @retry="retry" />
  </main>
</template>

<style scoped lang="scss">
.media-workbench { box-sizing: border-box; width: 100%; min-height: 100%; padding: var(--workspace-top-gap) var(--workspace-padding) 40px; font-family: var(--apple-font); color: var(--apple-text); }
.workbench-header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: var(--workspace-section-gap); }
.eyebrow { margin: 0 0 8px; font-size: 12px; font-weight: 600; letter-spacing: 0.08em; color: var(--apple-secondary); }
.workbench-title { margin: 0; font-size: 34px; font-weight: 600; line-height: 1.15; letter-spacing: -0.015em; }
.workbench-description { margin: 10px 0 0; font-size: 15px; line-height: 1.6; color: var(--apple-secondary); }
.media-modes { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; max-width: 720px; margin-bottom: var(--workspace-section-gap); }
.mode-button { display: flex; align-items: center; gap: 12px; min-width: 0; min-height: 64px; padding: 12px 16px; font-family: inherit; text-align: left; cursor: pointer; background: var(--apple-surface); border: 1px solid var(--apple-separator); border-radius: 14px; transition: border-color 0.15s ease, background 0.15s ease; }
.mode-button:hover { border-color: var(--apple-separator-strong); }
.mode-button.active { background: var(--apple-blue-soft); border-color: var(--apple-blue); }
.mode-icon { font-size: 21px; color: var(--apple-secondary); }
.mode-button.active .mode-icon { color: var(--apple-blue); }
.mode-button strong { display: block; margin-bottom: 3px; font-size: 14px; font-weight: 600; color: var(--apple-text); }
.mode-button small { font-size: 12px; color: var(--apple-secondary); }
.workbench-grid { display: grid; grid-template-columns: minmax(300px, 380px) minmax(0, 1fr); align-items: start; gap: 20px; }
@container workspace (max-width: 1000px) { .workbench-grid { grid-template-columns: minmax(300px, .9fr) minmax(0, 1.1fr); gap: 16px; } }
@container workspace (max-width: 780px) { .workbench-grid { grid-template-columns: minmax(0, 1fr); } }
@container workspace (max-width: 560px) { .media-modes { gap: 8px; } .mode-button { flex-direction: column; gap: 6px; min-height: 78px; padding: 12px 6px; text-align: center; } .mode-button small { display: none; } .mode-button strong { margin: 0; font-size: 12px; } }
@media (prefers-reduced-motion: reduce) { .mode-button { transition: none; } }
</style>
