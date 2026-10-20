<!-- 登录页 -->
<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getLoginTenants } from '@/api';
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

const tenants = ref<{ tenantId: string; companyName: string }[]>([]);
const tenantLoading = ref(false);
const tenantError = ref('');

async function loadTenants() {
  tenantLoading.value = true;
  tenantError.value = '';
  try {
    const data = await getLoginTenants();
    tenants.value = Array.isArray(data?.voList) ? data.voList : [];
    if (!form.value.tenantId && tenants.value.length > 0)
      form.value.tenantId = tenants.value[0].tenantId;
  }
  catch (caught) {
    tenantError.value = caught instanceof Error ? caught.message : '租户列表加载失败';
  }
  finally {
    tenantLoading.value = false;
  }
}

onMounted(loadTenants);

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
    <main class="login-card">
      <header class="login-head">
        <span class="login-mark">AI</span>
        <p class="login-eyebrow">
          RuoYi AI · 管理端
        </p>
        <h1 class="login-title">
          登录管理端
        </h1>
        <p class="login-sub">
          管理租户、用户与 AI 资源。使用平台账号继续。
        </p>
      </header>

      <ElAlert
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        class="login-alert"
      />

      <ElForm label-position="top" class="login-form" @submit.prevent="handleSubmit">
        <ElFormItem label="用户名" required>
          <ElInput v-model="form.username" size="large" autocomplete="username" placeholder="请输入账号" />
        </ElFormItem>
        <ElFormItem label="密码" required>
          <ElInput v-model="form.password" size="large" type="password" show-password autocomplete="current-password" placeholder="请输入登录密码" />
        </ElFormItem>
        <ElFormItem label="租户">
          <ElSelect
            v-if="tenantLoading || tenants.length"
            v-model="form.tenantId"
            size="large"
            placeholder="请选择租户"
            :loading="tenantLoading"
            style="width: 100%"
          >
            <ElOption
              v-for="tenant in tenants"
              :key="tenant.tenantId"
              :label="`${tenant.companyName}（${tenant.tenantId}）`"
              :value="tenant.tenantId"
            />
          </ElSelect>
          <ElInput
            v-else
            v-model="form.tenantId"
            size="large"
            placeholder="请输入租户 ID（默认 000000）"
          />
          <p v-if="tenantError" class="tenant-hint">
            租户列表加载失败：{{ tenantError }}。可手动输入租户 ID。
          </p>
        </ElFormItem>
        <ElFormItem class="login-submit-item">
          <ElButton type="primary" size="large" native-type="submit" class="login-submit" :loading="loading" @click="handleSubmit">
            登录
          </ElButton>
        </ElFormItem>
      </ElForm>

      <details class="login-env">
        <summary>环境说明</summary>
        <p>
          登录请求 POST /auth/login 需要后端 clientId 已配置 grantType=password。当前后端 api-decrypt.enabled=false，登录体为明文 JSON。
        </p>
      </details>

      <p class="login-foot">
        登录即代表你同意平台服务条款。凭据只用于本次会话。
      </p>
    </main>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  min-height: 100vh;
  min-height: 100dvh;
  padding: 32px 20px;
  background: var(--apple-bg);
}

.login-card {
  box-sizing: border-box;
  width: min(420px, 100%);
  padding: 40px 36px 32px;
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator);
  border-radius: 24px;
}

.login-head {
  margin-bottom: 28px;
}

.login-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  margin-bottom: 20px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: #fff;
  background: var(--apple-text);
  border-radius: 12px;
}

.login-eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--apple-secondary);
  text-transform: uppercase;
}

.login-title {
  margin: 0;
  font-size: 32px;
  font-weight: 600;
  line-height: 1.15;
  letter-spacing: -0.015em;
  color: var(--apple-text);
}

.login-sub {
  margin: 10px 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--apple-secondary);
}

.login-alert {
  margin-bottom: 18px;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.login-form :deep(.el-form-item__label) {
  padding-bottom: 6px;
  font-size: 13px;
  color: var(--apple-secondary);
}

.login-submit-item {
  margin-top: 24px;
  margin-bottom: 0 !important;
}

.login-submit {
  width: 100%;
  height: 46px;
  font-size: 15px;
}

.tenant-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--apple-warning);
  overflow-wrap: anywhere;
}

.login-env {
  margin-top: 24px;
  padding-top: 18px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--apple-secondary);
  border-top: 1px solid var(--apple-separator);
}

.login-env summary {
  color: var(--apple-tertiary);
  cursor: pointer;
}

.login-env p {
  margin: 8px 0 0;
}

.login-foot {
  margin: 14px 0 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--apple-tertiary);
}

@media (max-width: 480px) {
  .login-card {
    padding: 30px 22px 26px;
    border-radius: 20px;
  }

  .login-title {
    font-size: 28px;
  }
}
</style>
