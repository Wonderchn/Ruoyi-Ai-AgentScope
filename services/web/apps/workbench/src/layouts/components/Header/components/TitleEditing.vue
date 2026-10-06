<!-- 标题编辑 -->
<script setup lang="ts">
import SvgIcon from '@/components/SvgIcon/index.vue';
import { useSessionStore } from '@/stores/modules/session';

const sessionStore = useSessionStore();

const currentSession = computed(() => sessionStore.currentSession);

function handleClickTitle() {
  ElMessageBox.prompt('', '编辑对话名称', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputErrorMessage: '请输入对话名称',
    confirmButtonClass: 'el-button--primary',
    cancelButtonClass: 'el-button--info',
    roundButton: true,
    inputValue: currentSession.value?.sessionTitle,
    inputValidator: (value) => {
      if (!value) {
        return false;
      }
      return true;
    },
  })
    .then(async ({ value }) => {
      // 成功/失败提示由 `session-rename` 控制器统一发出（那是可单测的一层）。
      // 这里**不再无条件报"修改成功"**：旧实现无论服务端成败都弹成功，
      // 且失败被 console.error 吞掉，用户会看到"改成功了但刷新就变回去"的假象。
      // 当前会话的标题落地由 store 的 applyTitle 完成（服务端确认之后才改）。
      await sessionStore.renameSession(String(currentSession.value!.id), String(value));
    })
    .catch(() => {
      // ElMessage({
      //   type: 'info',
      //   message: '取消修改',
      // });
    });
}
</script>

<template>
  <div v-if="currentSession" class="w-full h-full flex flex-col justify-center">
    <div class="box-border mr-20px">
      <div
        class="title-editing-container p-4px w-fit max-w-full flex items-center justify-start cursor-pointer select-none hover:bg-[rgba(0,0,0,.04)] cursor-pointer rounded-md font-size-14px"
        @click="handleClickTitle"
      >
        <div class="text-overflow select-none pr-8px">
          {{ currentSession.sessionTitle }}
        </div>
        <SvgIcon name="draft-line" size="14" class="flex-none c-gray-500" />
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.title-editing-container {
  transition: all 0.3s ease;
  &:hover {
    .svg-icon {
      display: block;
      opacity: 1;
    }
  }
  .svg-icon {
    display: none;
    opacity: 0.5;
    transition: all 0.3s ease;
  }
}
</style>
