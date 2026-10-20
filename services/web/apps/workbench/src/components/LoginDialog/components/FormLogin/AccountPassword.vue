<!-- 账号密码登录表单 -->
<script lang="ts" setup>
import type { FormInstance, FormRules } from 'element-plus';
import type { LoginDTO } from '@/api/auth/types';
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { login } from '@/api';
import { useUserStore } from '@/stores';
import { useLoginFormStore } from '@/stores/modules/loginForm';
import { useSessionStore } from '@/stores/modules/session';
import { userFacingErrorMessage } from '@/utils/user-facing-error';

const userStore = useUserStore();
const sessionStore = useSessionStore();
const loginFromStore = useLoginFormStore();

const formRef = ref<FormInstance>();
const isSubmitting = ref(false);

const formModel = reactive<LoginDTO>({
  username: '',
  password: '',
  clientId: import.meta.env.VITE_CLIENT_ID,
  grantType: 'password',
});

const rules = reactive<FormRules<LoginDTO>>({
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入登录密码', trigger: 'blur' }],
});

const router = useRouter();
onMounted(() => loginFromStore.loadTenants());
async function handleSubmit() {
  if (isSubmitting.value)
    return;

  try {
    isSubmitting.value = true;
    await formRef.value?.validate();
    const res = await login({ ...formModel, tenantId: loginFromStore.requireTenant() });
    const loginData = res?.data ?? res;
    const token = loginData?.access_token || loginData?.token;
    if (!token) {
      // 后端认证失败时可能返回 200 信封 + 空 data；把服务端 msg 带出来，
      // 不要用“缺少访问令牌”掩盖真正的 401 原因（账号/密码/租户错误）。
      const backendMessage = loginData?.msg || res?.msg;
      throw new Error(backendMessage || '登录失败：后端未返回访问令牌');
    }
    userStore.setToken(token);
    // res.data.userInfo && userStore.setUserInfo(res.data.userInfo);
    userStore.resetAuthExpiredHandling();
    ElMessage.success('登录成功');
    userStore.closeLoginDialog();
    // 立刻获取回话列表
    await sessionStore.requestSessionList(1, true);
    const redirectPath = userStore.consumeLoginRedirectPath();
    router.replace(redirectPath || '/');
  }
  catch (error) {
    console.error('请求错误:', error);
    ElMessage.error(userFacingErrorMessage(error, '登录失败，请稍后重试'));
  }
  finally {
    isSubmitting.value = false;
  }
}
</script>

<template>
  <div class="custom-form">
    <el-form
      ref="formRef"
      :model="formModel"
      :rules="rules"
      class="login-form"
      label-position="top"
      @submit.prevent="handleSubmit"
    >
      <el-form-item v-if="loginFromStore.tenantEnabled" label="所属租户">
        <el-select v-model="loginFromStore.tenantId" placeholder="请选择所属租户" style="width: 100%">
          <el-option v-for="tenant in loginFromStore.tenants" :key="tenant.tenantId" :label="tenant.companyName" :value="tenant.tenantId" />
        </el-select>
      </el-form-item>
      <el-alert v-if="loginFromStore.tenantError" :title="loginFromStore.tenantError" type="error" :closable="false">
        <el-button link @click="loginFromStore.loadTenants()">
          重新加载
        </el-button>
      </el-alert>
      <el-form-item prop="username" label="账号">
        <el-input v-model="formModel.username" placeholder="请输入账号">
          <template #prefix>
            <el-icon>
              <User />
            </el-icon>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item prop="password" label="登录密码">
        <el-input
          v-model="formModel.password"
          placeholder="请输入登录密码"
          type="password"
          show-password
        >
          <template #prefix>
            <el-icon>
              <Lock />
            </el-icon>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item>
        <el-button
          class="submit-btn"
          type="primary"
          native-type="submit"
          :loading="isSubmitting"
          :disabled="isSubmitting || !loginFromStore.tenantsReady || (loginFromStore.tenantEnabled && !loginFromStore.tenantId)"
        >
          立即登录
        </el-button>
      </el-form-item>
    </el-form>

    <!-- 注册登录 -->
    <div class="form-tip font-size-12px flex items-center">
      <span>没有账号，</span>
      <span class="link-action" @click="loginFromStore.setLoginFormType('RegistrationForm')">
        立即注册
      </span>
    </div>
    <p class="security-tip">
      登录即代表你同意平台服务条款，我们会妥善保护你的账号信息。
    </p>
  </div>
</template>

<style scoped lang="scss">
.custom-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.login-form {
  width: 100%;
}

.submit-btn {
  width: 100%;
  height: 44px;
  font-size: 14px;
  font-weight: 500;
  background: var(--apple-blue);
  border: none;
  border-radius: 999px;
  box-shadow: none;
  font-family: var(--apple-font);
}

.submit-btn:hover {
  background: var(--apple-blue-hover);
}

.link-action {
  color: var(--apple-blue);
  cursor: pointer;
  font-weight: 500;
}

.security-tip {
  margin: 0;
  color: var(--apple-secondary);
  font-size: 12px;
  line-height: 1.6;
}

:deep(.el-input__wrapper),
:deep(.el-select__wrapper) {
  min-height: 44px;
  border-radius: 12px;
  box-shadow: 0 0 0 1px var(--apple-separator-strong);
}

:deep(.el-input__inner) {
  font-size: 14px;
}

:deep(.el-input__wrapper.is-focus),
:deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px var(--apple-blue), var(--apple-focus-ring);
}

:deep(.el-form-item__label) {
  font-size: 13px;
  font-weight: 500;
  color: var(--apple-secondary);
  padding-bottom: 6px;
}

:deep(.el-form-item) {
  margin-bottom: 18px;
}

:deep(.el-form-item:last-child) {
  margin-bottom: 0;
}

:deep(.el-form-item__error) {
  padding-top: 5px;
  font-size: 12px;
  line-height: 1.25;
}
</style>
