<!-- 登录页 -->
<script setup lang="ts">
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthSession } from '@/composables/useAuthSession';
import { HOME_URL } from '@/config';

const route = useRoute();
const router = useRouter();
const { signIn, loading, error } = useAuthSession();

const form = ref({
  username: '',
  password: '',
  tenantId: '',
  code: '',
  uuid: '',
});

async function handleSubmit() {
  const ok = await signIn({
    username: form.value.username,
    password: form.value.password,
    tenantId: form.value.tenantId || undefined,
    code: form.value.code || undefined,
    uuid: form.value.uuid || undefined,
  });
  if (!ok)
    return;
  const redirect = route.query.redirect;
  await router.replace(typeof redirect === 'string' && redirect ? redirect : HOME_URL);
}
</script>

<template>
  <div class="login-page">
    <ElCard class="login-card">
      <template #header>
        登录 Ruoyi AI 管理端
      </template>

      <ElAlert
        type="info"
        :closable="false"
        class="mb-4"
        title="登录请求 POST /auth/login 需要后端 clientId 已配置 grantType=password。当前后端 api-decrypt.enabled=false，登录体为明文 JSON。"
      />

      <ElAlert v-if="error" :title="error" type="error" :closable="false" class="mb-4" />

      <ElForm label-width="80px" @submit.prevent="handleSubmit">
        <ElFormItem label="用户名" required>
          <ElInput v-model="form.username" autocomplete="username" />
        </ElFormItem>
        <ElFormItem label="密码" required>
          <ElInput v-model="form.password" type="password" show-password autocomplete="current-password" />
        </ElFormItem>
        <ElFormItem label="租户 ID">
          <ElInput v-model="form.tenantId" placeholder="可留空（默认租户）" />
        </ElFormItem>
        <ElFormItem>
          <ElButton type="primary" native-type="submit" :loading="loading" @click="handleSubmit">
            登录
          </ElButton>
        </ElFormItem>
      </ElForm>
    </ElCard>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background-color: var(--el-fill-color-light);
}

.login-card {
  width: 460px;
}
</style>
