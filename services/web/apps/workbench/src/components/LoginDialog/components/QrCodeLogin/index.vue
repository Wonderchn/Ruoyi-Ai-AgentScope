<script lang="ts" setup>
import type { LoginUser } from '@/api/auth/types';
import { Picture as IconPicture, Loading, Refresh } from '@element-plus/icons-vue';
import { useCountdown } from '@vueuse/core';
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue';
import { useUserStore } from '@/stores';
import { get } from '@/utils/request';

// 微信二维码响应类型
interface WeixinQrCode {
  ticket: string;
  qrCodeUrl: string;
}

// 用户 Store
const userStore = useUserStore();

// 响应式状态
const qrCodeUrl = shallowRef('');
const currentTicket = shallowRef('');
const isExpired = ref(false);
const isLoading = ref(true); // 是否正在加载

// 二维码倒计时实例（10分钟 = 600秒）
const { start: qrStart, stop: qrStop } = useCountdown(shallowRef(600), {
  interval: 1000,
  onComplete: () => {
    isExpired.value = true;
    stopPolling();
  },
});

// 轮询相关
let loginPolling: number | null = null;

/** 获取二维码 */
async function fetchQrCode() {
  try {
    isLoading.value = true;
    isExpired.value = false;

    const response = await get<WeixinQrCode>('/user/qrcode').json();
    console.log('二维码响应:', response);

    // hook-fetch 返回的 data 字段就是后端的 data 内容
    if (response.data) {
      currentTicket.value = response.data.ticket;
      qrCodeUrl.value = response.data.qrCodeUrl;

      // 开始倒计时和轮询
      qrStart(shallowRef(600));
      startLoginPolling();
    }
  }
  catch (error) {
    console.error('获取二维码失败:', error);
    isExpired.value = true;
  }
  finally {
    isLoading.value = false;
  }
}

/** 停止轮询 */
function stopPolling() {
  if (loginPolling) {
    clearInterval(loginPolling);
    loginPolling = null;
  }
}

/** 刷新二维码 */
async function handleRefresh() {
  stopPolling();
  qrStop();
  await fetchQrCode();
}

/** 启动登录状态轮询 */
function startLoginPolling() {
  stopPolling();
  loginPolling = setInterval(async () => {
    if (!currentTicket.value || isExpired.value)
      return;

    try {
      const clientId = import.meta.env.VITE_CLIENT_ID || 'pc';
      const response = await get<LoginUser>('/user/login/qrcode', {
        ticket: currentTicket.value,
        clientId,
      }).json();

      if (response.data) {
        // 登录成功，停止轮询
        loginSuccess(response.data);
      }
    }
    catch (error) {
      // 接口返回非200状态码，继续轮询（等待扫码）
      // 只有网络错误等才打印日志
      if (error && typeof error === 'object' && 'result' in error) {
        // 业务失败（未扫码），静默处理
      }
      else {
        console.error('检查登录状态失败:', error);
      }
    }
  }, 5000); // 每5秒轮询一次
}

/** 登录成功处理 */
function loginSuccess(userInfo: LoginUser) {
  stopPolling();
  qrStop();

  // 设置用户信息
  if (userInfo.token) {
    userStore.setToken(userInfo.token);
  }
  userStore.setUserInfo(userInfo);

  // 关闭登录弹框
  userStore.closeLoginDialog();
}

/** 组件初始化 */
onMounted(async () => {
  await fetchQrCode();
});

/** 组件卸载清理 */
onBeforeUnmount(() => {
  qrStop();
  stopPolling();
});
</script>

<template>
  <div class="qr-wrapper">
    <div class="tip">
      请使用微信扫码登录
    </div>

    <div class="qr-img-wrapper">
      <!-- 加载中 -->
      <div v-if="isLoading" class="loading-wrapper">
        <el-icon class="loading-icon is-loading">
          <Loading />
        </el-icon>
        <span>加载中...</span>
      </div>

      <!-- 二维码图片 -->
      <el-image v-else-if="qrCodeUrl" :src="qrCodeUrl" alt="登录二维码" class="qr-img">
        <template #error>
          <el-icon><IconPicture /></el-icon>
        </template>
      </el-image>

      <!-- 过期/不可用覆盖层：请求失败或未拿到二维码时也要给出可重试的明确状态 -->
      <div v-if="isExpired || (!isLoading && !qrCodeUrl)" class="expired-overlay" @click.stop="handleRefresh">
        <div class="expired-content">
          <p class="expired-text">
            二维码失效
          </p>
          <el-button class="refresh-btn" link>
            <el-icon><Refresh /></el-icon>
            点击刷新
          </el-button>
        </div>
      </div>
    </div>

    <div class="login-tips">
      <p>打开微信扫一扫，快速登录</p>
    </div>
  </div>
</template>

<style scoped lang="scss">
.qr-wrapper {
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;

  .tip {
    font-size: 15px;
    font-weight: 600;
    color: var(--apple-text);
  }

  .qr-img-wrapper {
    position: relative;
    width: 200px;
    height: 200px;
    padding: 12px;
    overflow: hidden;
    background: var(--apple-surface);
    border: 1px solid var(--apple-separator);
    border-radius: 18px;

    .loading-wrapper {
      display: flex;
      flex-direction: column;
      gap: 8px;
      align-items: center;
      justify-content: center;
      width: 100%;
      height: 100%;
      font-size: 13px;
      color: var(--apple-secondary);

      .loading-icon {
        font-size: 22px;
        color: var(--apple-blue);
      }
    }

    .qr-img {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      height: 100%;

      :deep(.el-icon) {
        font-size: 18px;
        color: var(--apple-tertiary);
      }
    }

    .expired-overlay {
      position: absolute;
      inset: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      background: rgba(255, 255, 255, 0.95);
      backdrop-filter: blur(4px);
      -webkit-backdrop-filter: blur(4px);
      border-radius: 18px;

      .expired-content {
        display: flex;
        flex-direction: column;
        gap: 8px;
        text-align: center;

        .expired-text {
          font-size: 13px;
          color: var(--apple-secondary);
        }

        .refresh-btn {
          font-weight: 500;
          color: var(--apple-blue);
        }
      }
    }
  }

  .login-tips {
    font-size: 12px;
    color: var(--apple-tertiary);
    text-align: center;

    p {
      margin: 0;
    }
  }
}
</style>
