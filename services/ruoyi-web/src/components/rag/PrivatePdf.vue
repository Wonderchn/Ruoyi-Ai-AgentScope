<script setup lang="ts">
import type { PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask } from 'pdfjs-dist';
import { getDocument, GlobalWorkerOptions } from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import { onBeforeUnmount, ref, watch } from 'vue';
import { useUserStore } from '@/stores';

const props = defineProps<{ sourceUrl: string; initialPage?: number }>();
GlobalWorkerOptions.workerSrc = workerUrl;
const canvas = ref<HTMLCanvasElement>();
const page = ref(1);
const count = ref(0);
const error = ref('');
const loading = ref(false);
let generation = 0;
let paintVersion = 0;
let document: PDFDocumentProxy | null = null;
let task: PDFDocumentLoadingTask | null = null;
let renderTask: RenderTask | null = null;
function dispose() {
  generation++;
  paintVersion++;
  renderTask?.cancel();
  renderTask = null;
  void task?.destroy();
  task = null;
  document = null;
  count.value = 0;
}
async function draw() {
  const epoch = generation;
  const paint = ++paintVersion;
  const doc = document;
  const node = canvas.value;
  if (!doc || !node)
    return;
  const previous = renderTask;
  if (previous) {
    previous.cancel();
    try {
      await previous.promise;
    }
    catch {
      // Cancellation completion releases the canvas before the next render.
    }
  }
  const pdfPage = await doc.getPage(page.value);
  if (epoch !== generation || document !== doc || paint !== paintVersion)
    return;
  const original = pdfPage.getViewport({ scale: 1 });
  const scale = Math.min(1.5, 1000 / original.width, Math.sqrt(4000000 / (original.width * original.height)));
  const viewport = pdfPage.getViewport({ scale });
  node.width = Math.ceil(viewport.width);
  node.height = Math.ceil(viewport.height);
  const context = node.getContext('2d');
  if (!context)
    throw new Error('PDF 画布不可用');
  const current = pdfPage.render({ canvasContext: context, viewport });
  renderTask = current;
  try {
    await current.promise;
  }
  catch (cause) {
    if (epoch === generation && paint === paintVersion && renderTask === current && (cause as Error).name !== 'RenderingCancelledException')
      error.value = 'PDF 页面绘制失败';
  }
}
watch(() => props.sourceUrl, async (url) => {
  dispose();
  error.value = '';
  page.value = 1;
  if (!url)
    return;
  const epoch = generation;
  loading.value = true;
  try {
    // The private source demands a bearer token; pdf.js's own url fetch carries no
    // credentials, so the bytes are fetched authorized first and handed over as data.
    const store = useUserStore();
    const response = await fetch(url, {
      headers: { Authorization: `Bearer ${store.token ?? ''}`, ClientID: import.meta.env.VITE_CLIENT_ID as string },
    });
    if (!response.ok)
      throw new Error(`来源不可访问 (${response.status})`);
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (epoch !== generation)
      return;
    task = getDocument({ data: bytes, isEvalSupported: false, enableXfa: false, useSystemFonts: false });
    const loaded = await task.promise;
    if (epoch !== generation) {
      void loaded.destroy();
      return;
    }
    document = loaded;
    count.value = loaded.numPages;
    page.value = Math.min(count.value, Math.max(1, Number.isInteger(props.initialPage) ? props.initialPage! : 1));
    await draw();
  }
  catch {
    if (epoch === generation)
      error.value = 'PDF 无法显示，请核对文件是否有效';
  }
  finally {
    if (epoch === generation)
      loading.value = false;
  }
}, { immediate: true, flush: 'post' });
watch(page, () => {
  void draw();
});
onBeforeUnmount(dispose);
</script>

<template>
  <div class="private-pdf">
    <p v-if="loading">
      正在显示私有 PDF…
    </p>
    <p v-if="error" role="alert">
      {{ error }}
    </p>
    <div v-if="count" class="pdf-controls">
      <el-button :disabled="page <= 1" @click="page--">
        上一页
      </el-button>
      <span>第 {{ page }} / {{ count }} 页</span>
      <el-button :disabled="page >= count" @click="page++">
        下一页
      </el-button>
    </div>
    <canvas ref="canvas" aria-label="当前私有 PDF 页面" />
  </div>
</template>

<style scoped>
.private-pdf { max-height: 70vh; overflow: auto; }
.pdf-controls { display: flex; align-items: center; gap: 12px; padding-bottom: 12px; }
canvas { max-width: 100%; height: auto; }
</style>
