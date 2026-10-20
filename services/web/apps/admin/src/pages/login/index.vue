<!-- 登录页：左侧品牌叙述 + 右侧表单的 HIG 分栏卡 -->
<script setup lang="ts">
import { Lock, User } from '@element-plus/icons-vue';
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
    <div class="login-shell">
      <aside class="login-brand" aria-label="产品介绍">
        <div class="brand-top">
          <span class="brand-mark">AI</span>
          <span class="brand-name">RuoYi AI</span>
        </div>

        <div class="brand-copy">
          <p class="brand-eyebrow">
            AI WORKSPACE
          </p>
          <h2 class="brand-title">
            把租户、知识库与 Agent 放进同一个工作空间。
          </h2>
          <p class="brand-desc">
            统一身份、权限与运行数据。登录后进入你的管理端。
          </p>
        </div>

        <ul class="brand-points">
          <li>
            <span class="brand-dot" />
            租户与成员边界
          </li>
          <li>
            <span class="brand-dot" />
            知识库 / RAG / Agent
          </li>
          <li>
            <span class="brand-dot" />
            运行配置与审计
          </li>
        </ul>

        <p class="brand-foot">
          RuoYi AI · 管理端
        </p>
      </aside>

      <main class="login-card">
        <header class="login-head">
          <p class="login-eyebrow">
            管理端登录
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
            <ElInput
              v-model="form.username"
              size="large"
              :prefix-icon="User"
              autocomplete="username"
              placeholder="请输入账号"
            />
          </ElFormItem>
          <ElFormItem label="密码" required>
            <ElInput
              v-model="form.password"
              size="large"
              type="password"
              show-password
              :prefix-icon="Lock"
              autocomplete="current-password"
              placeholder="请输入登录密码"
            />
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
  background: transparent;
}

.login-shell {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  box-sizing: border-box;
  width: min(980px, 100%);
  overflow: hidden;
  background: var(--apple-surface);
  border: 1px solid var(--apple-hairline);
  border-radius: 32px;
  box-shadow: var(--apple-shadow-float);
}

.login-brand {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  box-sizing: border-box;
  min-height: 560px;
  padding: 44px 42px;
  overflow: hidden;
  color: #f5f5f7;
  background:
    radial-gradient(520px 320px at 10% 0%, rgba(0, 113, 227, 0.42), transparent 64%),
    radial-gradient(420px 280px at 100% 100%, rgba(94, 92, 230, 0.34), transparent 62%),
    linear-gradient(160deg, #0b0b0d 0%, #16161a 52%, #0b1220 100%);
}

.login-brand::after {
  position: absolute;
  inset: 0;
  pointer-events: none;
  content: '';
  background:
    linear-gradient(rgba(255, 255, 255, 0.035) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.035) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: linear-gradient(180deg, rgba(0, 0, 0, 0.7), transparent 78%);
}

.brand-top {
  position: relative;
  z-index: 1;
  display: flex;
  gap: 10px;
  align-items: center;
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: #0b0b0d;
  background: #f5f5f7;
  border-radius: 12px;
}

.brand-name {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.brand-copy {
  position: relative;
  z-index: 1;
  max-width: 34ch;
}

.brand-eyebrow {
  margin: 0 0 12px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.16em;
  color: rgba(245, 245, 247, 0.62);
}

.brand-title {
  margin: 0;
  font-size: 34px;
  font-weight: 600;
  line-height: 1.18;
  letter-spacing: -0.03em;
  color: #f5f5f7;
}

.brand-desc {
  margin: 16px 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: rgba(245, 245, 247, 0.68);
}

.brand-points {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 0;
  margin: 26px 0;
  list-style: none;
}

.brand-points li {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 13px;
  color: rgba(245, 245, 247, 0.82);
}

.brand-dot {
  width: 6px;
  height: 6px;
  background: #64d2ff;
  border-radius: 50%;
  box-shadow: 0 0 0 4px rgba(100, 210, 255, 0.14);
}

.brand-foot {
  position: relative;
  z-index: 1;
  margin: 0;
  font-size: 11px;
  color: rgba(245, 245, 247, 0.45);
}

.login-card {
  display: flex;
  flex-direction: column;
  justify-content: center;
  box-sizing: border-box;
  padding: 46px 44px;
  background: var(--apple-surface);
}

.login-head {
  margin-bottom: 26px;
}

.login-eyebrow {
  margin: 0 0 8px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.12em;
  color: var(--apple-secondary);
  text-transform: uppercase;
}

.login-title {
  margin: 0;
  font-size: 34px;
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -0.025em;
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
  font-size: 12px;
  font-weight: 500;
  color: var(--apple-secondary);
}

.login-submit-item {
  margin-top: 24px;
  margin-bottom: 0 !important;
}

.login-submit {
  width: 100%;
  height: 48px;
  font-size: 15px;
  font-weight: 500;
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
  border-top: 1px solid var(--apple-hairline);
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

@media (max-width: 860px) {
  .login-shell {
    grid-template-columns: minmax(0, 1fr);
    max-width: 520px;
  }

  .login-brand {
    min-height: 0;
    padding: 26px 28px;
  }

  .brand-copy {
    max-width: none;
    margin-top: 22px;
  }

  .brand-title {
    font-size: 26px;
  }

  .brand-points,
  .brand-foot {
    display: none;
  }

  .login-card {
    padding: 30px 28px 32px;
  }
}

@media (max-width: 480px) {
  .login-page {
    padding: 16px 12px;
  }

  .login-shell {
    border-radius: 24px;
  }

  .login-brand {
    padding: 22px;
  }

  .login-title {
    font-size: 28px;
  }

  .login-card {
    padding: 26px 20px 28px;
  }
}
</style>
