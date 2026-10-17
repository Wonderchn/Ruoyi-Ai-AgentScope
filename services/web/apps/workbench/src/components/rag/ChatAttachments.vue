<script setup lang="ts">
import type { ChatAttachment } from '../../api/ai/attachments';
import { useUserStore } from '@/stores/modules/user';
import { createAttachmentClient } from '../../api/ai/attachments';
import PrivatePdf from './PrivatePdf.vue';

const props = defineProps<{ attachments?: ChatAttachment[] }>();
const user = useUserStore();
const error = ref('');
const preview = ref('');
const previewMime = ref('');
// F04-HANDOFF 缺口 6：close / 切换附件 / authEpoch 变化都会让在途 download 过期；
// generation 是"当前预览版本"的唯一事实，过期的成功与失败回调一律丢弃。
let generation = 0;
const client = createAttachmentClient({
  baseUrl: import.meta.env.VITE_API_URL,
  clientId: import.meta.env.VITE_CLIENT_ID,
  identity: () => ({ token: user.token, epoch: user.authEpoch }),
  onAuthExpired: () => user.handleAuthExpired(),
});
function close() {
  generation += 1;
  if (preview.value)
    URL.revokeObjectURL(preview.value);
  preview.value = '';
  previewMime.value = '';
}
async function open(attachment: ChatAttachment) {
  close();
  error.value = '';
  const epoch = user.authEpoch;
  const current = generation;
  try {
    const blob = await client.download(attachment);
    if (current !== generation || epoch !== user.authEpoch)
      return;
    previewMime.value = attachment.mimeType;
    preview.value = URL.createObjectURL(blob);
  }
  catch (failure) {
    if (current !== generation || epoch !== user.authEpoch)
      return;
    error.value = failure instanceof Error ? failure.message : '附件不可用';
  }
}
watch(() => user.authEpoch, () => {
  close();
  error.value = '';
}, { flush: 'sync' });
onBeforeUnmount(close);
</script>

<template>
  <div v-if="props.attachments?.length" class="chat-attachments">
    <el-button v-for="attachment in props.attachments" :key="attachment.id" size="small" @click="open(attachment)">
      {{ attachment.filename }}（{{ Math.ceil(attachment.sizeBytes / 1024) }} KB）
    </el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-dialog :model-value="Boolean(preview)" title="私有附件" width="75%" @close="close">
      <PrivatePdf v-if="previewMime === 'application/pdf'" :source-url="preview" />
      <img v-else-if="previewMime.startsWith('image/')" :src="preview" style="max-width: 100%; max-height: 70vh" alt="附件预览">
      <iframe v-else :src="preview" sandbox="" title="附件预览" style="width: 100%; height: 70vh" />
    </el-dialog>
  </div>
</template>
