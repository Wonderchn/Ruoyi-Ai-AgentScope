<script setup lang="ts">
import type { ChatAttachment } from '../../api/ai/attachments';
import { createAttachmentClient } from '../../api/ai/attachments';
import { useUserStore } from '@/stores/modules/user';

const props = defineProps<{ attachments?: ChatAttachment[] }>();
const user = useUserStore();
const error = ref('');
const preview = ref('');
const previewMime = ref('');
const client = createAttachmentClient({
  baseUrl: import.meta.env.VITE_API_URL,
  clientId: import.meta.env.VITE_CLIENT_ID,
  identity: () => ({ token: user.token, epoch: user.authEpoch }),
  onAuthExpired: () => user.handleAuthExpired(),
});
function close() {
  if (preview.value)
    URL.revokeObjectURL(preview.value);
  preview.value = '';
  previewMime.value = '';
}
async function open(attachment: ChatAttachment) {
  close();
  error.value = '';
  try {
    const blob = await client.download(attachment);
    previewMime.value = attachment.mimeType;
    preview.value = URL.createObjectURL(blob);
  }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '附件不可用'; }
}
watch(() => user.authEpoch, () => { close(); error.value = ''; }, { flush: 'sync' });
onBeforeUnmount(close);
</script>

<template>
  <div v-if="props.attachments?.length" class="chat-attachments">
    <el-button v-for="attachment in props.attachments" :key="attachment.id" size="small" @click="open(attachment)">
      {{ attachment.filename }}（{{ Math.ceil(attachment.sizeBytes / 1024) }} KB）
    </el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-dialog :model-value="Boolean(preview)" title="私有附件" width="75%" @close="close">
      <img v-if="previewMime.startsWith('image/')" :src="preview" style="max-width: 100%; max-height: 70vh" alt="附件预览">
      <iframe v-else :src="preview" sandbox="" title="附件预览" style="width: 100%; height: 70vh" />
    </el-dialog>
  </div>
</template>
