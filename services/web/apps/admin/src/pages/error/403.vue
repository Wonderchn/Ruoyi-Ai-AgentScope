<script setup lang="ts">
import { useRouter } from 'vue-router';

const router = useRouter();
</script>

<template>
  <ElResult icon="warning" title="403" sub-title="当前账号没有访问该资源的权限。">
    <template #extra>
      <ElButton type="primary" @click="router.replace('/')">
        返回首页
      </ElButton>
    </template>
  </ElResult>
</template>
