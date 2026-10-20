<script setup lang="ts">
import type { MediaTask } from '../types';
import type { MediaType } from '@/api/media/types';
import { Headset, Picture, VideoCamera } from '@element-plus/icons-vue';
import { computed, shallowRef, watch } from 'vue';
import { canRetryServerTask } from '../server-tasks';
import { useMediaPreview } from '../useMediaPreview';
import { canQueryMedia, mediaLabels, taskLabels } from '../utils';

const props = defineProps<{ kind: MediaType; task?: MediaTask; querying: boolean; busy: boolean }>();
const emit = defineEmits<{ pause: [key: string]; resume: [key: string]; retry: [key: string] }>();
const { source, mimeType, loading, error: contentError, retry } = useMediaPreview(() => props.task);
const icon = computed(() => ({ image: Picture, audio: Headset, video: VideoCamera })[props.task?.kind || props.kind]);
const mediaError = shallowRef(false);
const copied = shallowRef(false);
const copyError = shallowRef('');
const canResume = computed(() => {
  const task = props.task;
  return Boolean(task?.result?.id && task.state !== 'success' && task.state !== 'failed'
    && !task.terminalUnknown && canQueryMedia(task.provider, task.kind));
});
const canRetry = computed(() => canRetryServerTask(props.task) && !props.busy);
const download = computed(() => /^(?:data|blob):/.test(source.value));
const filename = computed(() => {
  const mime = mimeType.value || source.value.match(/^data:([^;]+);/i)?.[1];
  const extension = mime?.split('/')[1].replace('mpeg', 'mp3') || 'bin';
  return `ruoyi-${props.task?.kind || 'media'}.${extension}`;
});
watch([source, () => props.task?.key], () => {
  mediaError.value = false;
  copied.value = false;
  copyError.value = '';
});

async function copyId() {
  if (!props.task?.result?.id)
    return;
  try {
    await navigator.clipboard.writeText(props.task.result.id);
    copied.value = true;
    copyError.value = '';
  }
  catch {
    copyError.value = '复制失败，请手动选择任务 ID 复制。';
  }
}
</script>

<template>
  <section class="preview-panel" aria-label="生成结果">
    <div class="preview-heading">
      <div class="heading-label">
        <span class="step">02</span><h2 class="section-title">
          生成结果
        </h2>
      </div>
      <span v-if="task" class="status-pill" :class="task.state">{{ querying ? '正在查询' : taskLabels[task.state] }}</span>
      <span v-else class="preview-type">{{ mediaLabels[kind] }}预览</span>
    </div>

    <div class="preview-stage" :class="{ 'has-result': source }" aria-live="polite" :aria-busy="loading || task?.state === 'submitting' || querying">
      <template v-if="source && task">
        <img v-if="task.kind === 'image'" :src="source" :alt="task.prompt" class="image-result" @error="mediaError = true">
        <div v-else-if="task.kind === 'audio'" class="audio-result">
          <el-icon class="audio-icon">
            <Headset />
          </el-icon>
          <p class="audio-title">
            语音已生成
          </p>
          <audio :key="source" :src="source" controls preload="metadata" class="audio-player" @error="mediaError = true" />
        </div>
        <video v-else :key="source" :src="source" controls playsinline preload="metadata" class="video-result" @error="mediaError = true" />
      </template>
      <div v-else class="empty-preview">
        <div class="preview-symbol" :class="{ processing: loading || task?.state === 'waiting' || task?.state === 'submitting' }">
          <el-icon><component :is="icon" /></el-icon>
        </div>
        <h3 class="empty-title">
          {{ loading ? '正在加载资源' : contentError ? '资源加载失败' : task ? taskLabels[task.state] : '让想法有一个新模样' }}
        </h3>
        <p class="empty-description">
          {{ loading ? '作品已生成，正在准备预览。' : contentError || (task ? task.message : '选择模型，写下描述。你的创作会出现在这里。') }}
        </p>
      </div>
    </div>

    <div v-if="task" class="result-details">
      <p v-if="mediaError" class="resource-error" role="alert">
        资源暂时无法预览，可能已过期或浏览器不支持此格式。可以保存文件或打开资源检查。
      </p>
      <div class="result-meta">
        <span>{{ mediaLabels[task.kind] }}</span><strong>{{ task.modelLabel }}</strong>
      </div>
      <p v-if="source" class="prompt-summary">
        {{ task.prompt }}
      </p>
      <div v-if="task.result?.id" class="task-identity">
        <span>任务 ID</span><code>{{ task.result.id }}</code>
        <button type="button" class="text-action" @click="copyId">
          {{ copied ? '已复制' : '复制' }}
        </button>
      </div>
      <p v-if="copyError" class="resource-error" role="alert">
        {{ copyError }}
      </p>
      <p v-if="task.result?.status" class="provider-status">
        服务状态：{{ task.result.status }}
      </p>
      <div class="result-actions">
        <el-button v-if="contentError" @click="retry">
          重新加载资源
        </el-button>
        <a v-if="source && download" :href="source" :download="filename" class="resource-link">保存文件</a>
        <a v-else-if="source" :href="source" target="_blank" rel="noopener noreferrer" class="resource-link">打开资源</a>
        <el-button v-if="canRetry" type="primary" plain @click="emit('retry', task.key)">
          重试生成
        </el-button>
        <el-button v-else-if="task.state === 'waiting'" @click="emit('pause', task.key)">
          暂停查询
        </el-button>
        <el-button v-else-if="canResume" :disabled="busy" @click="emit('resume', task.key)">
          继续查询
        </el-button>
      </div>
    </div>
    <p v-else class="preview-footnote">
      支持图片预览、语音播放与视频播放
    </p>
  </section>
</template>

<style scoped lang="scss">
.preview-panel { min-width: 0; overflow: hidden; background: var(--apple-surface); border: 1px solid var(--apple-separator); border-radius: 18px; }
.preview-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; padding: var(--workspace-panel-padding); }
.heading-label { display: flex; align-items: center; gap: 10px; }
.step { font-size: 12px; font-weight: 600; line-height: 24px; letter-spacing: 0.06em; color: var(--apple-secondary); }
.section-title { margin: 0; font-size: 20px; font-weight: 600; letter-spacing: -0.01em; }
.preview-type, .provider-status { font-size: 12px; color: var(--apple-secondary); }
.preview-stage { display: grid; min-height: clamp(240px, 44dvh, 480px); margin: 0 var(--workspace-panel-padding); overflow: hidden; place-items: center; background: var(--apple-bg); border: 1px solid var(--apple-separator); border-radius: 14px; }
.has-result { background: var(--apple-bg); }
.empty-preview { box-sizing: border-box; max-width: 410px; padding: 24px 16px; text-align: center; }
.preview-symbol { display: grid; width: 64px; height: 64px; margin: 0 auto 20px; font-size: 26px; color: var(--apple-blue); background: var(--apple-surface); border: 1px solid var(--apple-separator); border-radius: 18px; }
.preview-symbol .el-icon { place-self: center; }
.empty-title { margin: 0 0 10px; font-size: 19px; font-weight: 600; letter-spacing: -0.01em; }
.empty-description { margin: 0; font-size: 13px; line-height: 1.8; color: var(--apple-secondary); overflow-wrap: anywhere; }
.image-result { display: block; max-width: 100%; max-height: min(56dvh, 560px); object-fit: contain; }
.video-result { display: block; width: 100%; max-height: min(56dvh, 560px); object-fit: contain; }
.audio-result { box-sizing: border-box; width: 100%; min-width: 0; padding: 24px 16px; text-align: center; }
.audio-icon { font-size: 48px; color: var(--apple-blue); }
.audio-title { margin: 20px 0; font-size: 15px; font-weight: 500; }
.audio-player { display: block; width: 100%; max-width: 390px; margin: 0 auto; }
.result-details { padding: var(--workspace-panel-padding); }
.result-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 13px; }
.result-meta span { padding: 3px 8px; font-size: 11px; color: var(--apple-secondary); background: var(--apple-bg); border-radius: 999px; }
.result-meta strong { overflow-wrap: anywhere; font-weight: 500; }
.prompt-summary { margin-top: 12px; font-size: 13px; line-height: 1.7; color: var(--apple-secondary); overflow-wrap: anywhere; }
.task-identity { display: flex; align-items: baseline; gap: 8px; margin-top: 15px; font-size: 12px; color: var(--apple-secondary); }
.task-identity code { flex: 1; min-width: 0; font-family: var(--apple-font-mono); font-size: 12px; color: var(--apple-text); overflow-wrap: anywhere; }
.task-identity > span, .text-action { flex-shrink: 0; }
.text-action { padding: 4px; color: var(--apple-blue); cursor: pointer; background: none; border: 0; }
.provider-status { margin-top: 10px; overflow-wrap: anywhere; }
.result-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 16px; }
.resource-link { display: inline-flex; align-items: center; height: 40px; padding: 0 20px; font-size: 14px; font-weight: 500; color: var(--apple-blue); text-decoration: none; border: 1px solid var(--apple-separator-strong); border-radius: 999px; }
.resource-error { margin-bottom: 14px; font-size: 12px; line-height: 1.7; color: var(--apple-warning); }
.preview-footnote { padding: 16px; margin: 0; font-size: 12px; line-height: 1.6; color: var(--apple-secondary); text-align: center; }
.status-pill { padding: 4px 10px; font-size: 12px; color: var(--apple-secondary); background: var(--apple-bg); border-radius: 999px; }
.status-pill.waiting, .status-pill.submitting { color: var(--apple-blue); background: var(--apple-blue-soft); }
.status-pill.failed { color: var(--apple-danger); background: var(--apple-danger-surface); }
.status-pill.success { color: var(--apple-success); background: var(--apple-success-surface); }
.processing { animation: breathe 1.8s ease-in-out infinite alternate; }
@keyframes breathe { to { opacity: 0.5; } }
@media (prefers-reduced-motion: reduce) { .processing { animation: none; } }
</style>
