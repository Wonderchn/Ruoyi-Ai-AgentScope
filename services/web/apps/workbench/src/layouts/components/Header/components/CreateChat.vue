<!-- 添加新会话按钮 -->
<script setup lang="ts">
import { useSessionStore } from '@/stores/modules/session';

defineProps<{ compact?: boolean }>();

const sessionStore = useSessionStore();

/* 创建会话 开始 */
function handleCreatChat() {
  // 新建会话 = 回到默认聊天页，由 `chatDefaul` 的输入框真正发起创建
  // （`createSessionList` 会 POST /system/session 并跳到新会话）。
  //
  // 这里**不再要求 currentSession 存在**：原来的 `if (!sessionStore.currentSession) return;`
  // 让"还没有任何会话"时按钮不可点，恰好把最需要新建入口的场景锁死了；
  // 而侧栏的"新对话"入口调的是同一个方法且从未禁用——同一动作两种行为。
  sessionStore.createSessionBtn();
}
/* 创建会话 结束 */
</script>

<template>
  <button
    type="button"
    class="create-chat"
    :class="{ 'create-chat--compact': compact }"
    :aria-label="compact ? '新对话' : undefined"
    @click="handleCreatChat"
  >
    <el-icon :size="compact ? 18 : 14">
      <Plus />
    </el-icon>
    <span v-if="!compact">新对话</span>
  </button>
</template>

<style scoped lang="scss">
.create-chat {
  display: inline-flex;
  flex: none;
  gap: 6px;
  align-items: center;
  justify-content: center;
  height: 38px;
  padding: 0 18px;
  font-family: var(--apple-font);
  font-size: 14px;
  font-weight: 500;
  color: #fff;
  cursor: pointer;
  background: var(--apple-blue);
  border: 0;
  border-radius: var(--apple-radius-pill);
  transition: background-color 0.18s ease;
}

.create-chat:hover {
  background: var(--apple-blue-hover);
}

.create-chat--compact {
  width: 40px;
  height: 40px;
  padding: 0;
  border-radius: 12px;
}
</style>
