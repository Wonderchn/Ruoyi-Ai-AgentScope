<!-- 深度思考按钮 -->
<script setup lang="ts">
import { useChatStore } from '@/stores/modules/chat';

const chatStore = useChatStore();

const isDeepThinking = computed(() => chatStore.isDeepThinking);

// 切换是否深度思考
function setIsDeepThinking() {
  chatStore.setDeepThinking(!chatStore.isDeepThinking);
}
</script>

<template>
  <button
    type="button"
    :class="{ 'is-select': isDeepThinking }"
    class="deep-thinking-btn"
    :aria-pressed="isDeepThinking"
    @click="setIsDeepThinking"
  >
    <el-icon :size="15">
      <ElementPlus />
    </el-icon>
    <span>深度思考</span>
  </button>
</template>

<style scoped lang="scss">
.deep-thinking-btn {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  height: 36px;
  padding: 0 13px;
  font-family: var(--apple-font);
  font-size: 12px;
  font-weight: 500;
  color: var(--apple-secondary);
  cursor: pointer;
  background: transparent;
  border: 1px solid var(--apple-separator-strong);
  border-radius: 980px;
  transition: color 0.18s ease, background-color 0.18s ease, border-color 0.18s ease;
}

.deep-thinking-btn:hover {
  color: var(--apple-text);
  background: var(--apple-bg);
}

.deep-thinking-btn.is-select {
  color: var(--apple-blue);
  background: var(--apple-blue-soft);
  border-color: var(--apple-blue);
}
</style>
