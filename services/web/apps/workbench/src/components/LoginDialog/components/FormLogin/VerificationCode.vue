<!-- 手机号验证码登录表单 -->
<script lang="ts" setup>
// 表单逻辑由用户自行实现
</script>

<template>
  <div class="custom-form">
    <!-- 此处放入用户自己的表单组件 -->
    <div class="form-group">
      <label>手机号</label>
      <input type="text" placeholder="请输入手机号">
    </div>
    <div class="form-group">
      <label>验证码</label>
      <input type="text" placeholder="请输入验证码">
      <button>获取验证码</button>
    </div>
    <button class="login-btn">
      登录
    </button>
  </div>
</template>

<style scoped>
.custom-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: stretch;
}

.form-group label {
  font-family: var(--apple-font);
  font-size: 13px;
  font-weight: 500;
  color: var(--apple-secondary);
}

.form-group input {
  box-sizing: border-box;
  width: 100%;
  height: 44px;
  padding: 0 13px;
  font-family: var(--apple-font);
  font-size: 14px;
  color: var(--apple-text);
  background: var(--apple-surface);
  border: 1px solid var(--apple-separator-strong);
  border-radius: 12px;
}

.form-group input:focus {
  outline: none;
  border-color: var(--apple-blue);
  box-shadow: var(--apple-focus-ring);
}

.form-group button {
  height: 36px;
  padding: 0 14px;
  font-family: var(--apple-font);
  font-size: 12px;
  font-weight: 500;
  color: var(--apple-blue);
  cursor: pointer;
  background: var(--apple-blue-soft);
  border: none;
  border-radius: 980px;
}

.login-btn {
  height: 44px;
  padding: 0 20px;
  margin-top: 24px;
  font-family: var(--apple-font);
  font-size: 14px;
  font-weight: 500;
  color: #fff;
  cursor: pointer;
  background: var(--apple-blue);
  border: none;
  border-radius: 980px;
}

.login-btn:hover {
  background: var(--apple-blue-hover);
}
</style>
