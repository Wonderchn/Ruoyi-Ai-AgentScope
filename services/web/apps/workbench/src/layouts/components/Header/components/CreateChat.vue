<!-- 添加新会话按钮 -->
<script setup lang="ts">
import { useSessionStore } from '@/stores/modules/session';

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
  <div
    class="create-chat-container flex-center flex-none p-6px pl-8px pr-8px c-#0057ff b-#0057ff b-rounded-12px border-1px hover:bg-#0057ff hover:c-#fff hover:b-#fff hover:cursor-pointer border-solid select-none"
    @click="handleCreatChat"
  >
    <el-icon size="12" class="flex-center flex-none w-14px h-14px">
      <Plus />
    </el-icon>
    <span class="ml-4px font-size-14px font-700">新对话</span>
  </div>
</template>
