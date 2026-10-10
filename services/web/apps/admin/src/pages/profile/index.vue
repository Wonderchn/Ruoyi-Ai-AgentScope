<!--
  个人中心 + 社交关系（F01 **op13**：社交列表；个人中心改密/头像上传）—— RW-15 补齐。

  ## 端点（`SysProfileController` `/system/user/profile`、`SysSocialController` `/system/social`）

  | 用途 | 端点 | 权限 |
  |---|---|---|
  | 本人资料 | GET /system/user/profile → `{user, roleGroup, postGroup}` | **无** `@SaCheckPermission`（只读本人） |
  | 改资料 | PUT /system/user/profile（`SysUserProfileBo`） | 同上 |
  | 改口令 | PUT /system/user/profile/updatePwd（`{oldPassword,newPassword}`） | 同上 |
  | 社交关系 | GET /system/social/list | **无** `@SaCheckPermission` |

  ## 头像上传（S2-F01/op12：页面本地 fetch 最小实现）

  `POST /system/user/profile/avatar` 是 **multipart**（`avatarfile`），响应是 `R<AvatarVo>`。
  共享 `PlatformClient` 只处理 JSON ⇒ **不动共享包**：本页用页面本地 `fetch` 直发 FormData
  （baseURL/token/clientId 经 `@/api` 的 `uploadContext()` 取数，与平台客户端同配置）；
  服务端上传成功后**尽力回收旧头像
  对象**（op12 服务端修复，失败仅留痕）。浏览器级回归随 F01 包级切片（登记为后续债务）。

  ## 口令纪律

  `updatePwd` 只把旧/新口令放进**请求体**，不写日志、不持久化、不回显；成功后清空输入框。
  两次新口令必须一致（前端预检），服务端仍独立校验旧口令与强度。
-->
<script setup lang="ts">
import type { SysProfileVo, SysSocialVo } from '@/api';
import { computed, ref } from 'vue';
import { systemApi, uploadContext } from '@/api';

const loading = ref(false);
const error = ref('');
const loaded = ref(false);
const notice = ref('');
const profile = ref<SysProfileVo | null>(null);

const socials = ref<SysSocialVo[]>([]);
const socialLoading = ref(false);
const socialError = ref('');
const socialLoaded = ref(false);

const user = computed<Record<string, unknown>>(() => (profile.value?.user ?? {}) as Record<string, unknown>);

function textOf(target: Record<string, unknown>, key: string): string {
  const value = target[key];
  return value === null || value === undefined ? '' : String(value);
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    profile.value = await systemApi.profile.get();
    loaded.value = true;
  }
  catch (caught) {
    profile.value = null;
    loaded.value = false;
    error.value = caught instanceof Error ? caught.message : String(caught);
  }
  finally {
    loading.value = false;
  }
}

async function loadSocials() {
  socialLoading.value = true;
  socialError.value = '';
  try {
    socials.value = await systemApi.socials.list();
    socialLoaded.value = true;
  }
  catch (caught) {
    socials.value = [];
    socialLoaded.value = false;
    socialError.value = caught instanceof Error ? caught.message : String(caught);
  }
  finally {
    socialLoading.value = false;
  }
}

/** 改资料（只提交四个可改字段）。 */
const form = ref({ nickName: '', email: '', phonenumber: '', sex: '' });
const savingProfile = ref(false);

function syncForm() {
  form.value = {
    nickName: textOf(user.value, 'nickName'),
    email: textOf(user.value, 'email'),
    phonenumber: textOf(user.value, 'phonenumber'),
    sex: textOf(user.value, 'sex'),
  };
}

async function submitProfile() {
  savingProfile.value = true;
  error.value = '';
  notice.value = '';
  try {
    await systemApi.profile.update({ ...form.value });
    notice.value = '资料已保存。';
    await load();
    syncForm();
  }
  catch (caught) {
    error.value = caught instanceof Error ? caught.message : String(caught);
  }
  finally {
    savingProfile.value = false;
  }
}

/** 改口令。 */
const pwdVisible = ref(false);
const pwdSaving = ref(false);
const pwdError = ref('');
const pwdForm = ref({ oldPassword: '', newPassword: '', confirmPassword: '' });

function openPwd() {
  pwdForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' };
  pwdError.value = '';
  pwdVisible.value = true;
}

async function submitPwd() {
  pwdError.value = '';
  if (!pwdForm.value.oldPassword || !pwdForm.value.newPassword) {
    pwdError.value = '旧口令与新口令都不能为空';
    return;
  }
  if (pwdForm.value.newPassword !== pwdForm.value.confirmPassword) {
    pwdError.value = '两次输入的新口令不一致';
    return;
  }
  pwdSaving.value = true;
  try {
    await systemApi.profile.updatePwd({
      oldPassword: pwdForm.value.oldPassword,
      newPassword: pwdForm.value.newPassword,
    });
    // 成功后立刻清空内存里的口令，不留驻。
    pwdForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' };
    pwdVisible.value = false;
    notice.value = '口令已修改。';
  }
  catch (caught) {
    pwdError.value = caught instanceof Error ? caught.message : String(caught);
  }
  finally {
    pwdSaving.value = false;
  }
}

/** 头像（op12）：页面本地 fetch 直发 multipart（共享 JSON 客户端不支持）。 */
const avatarFile = ref<File | null>(null);
const avatarUploading = ref(false);
const avatarError = ref('');
const avatarPreview = ref('');
/** 预览用 blob URL：替换/成功后必须 revoke（复核 P3 收口）。 */
let avatarBlobUrl = '';

function onAvatarPicked(event: Event) {
  const input = event.target as HTMLInputElement;
  const picked = input.files && input.files.length > 0 ? input.files[0] : null;
  avatarFile.value = picked;
  avatarError.value = '';
  if (avatarBlobUrl) {
    URL.revokeObjectURL(avatarBlobUrl);
    avatarBlobUrl = '';
  }
  if (picked) {
    avatarBlobUrl = URL.createObjectURL(picked);
  }
  avatarPreview.value = avatarBlobUrl;
}

async function uploadAvatar() {
  if (!avatarFile.value) {
    avatarError.value = '请先选择图片文件';
    return;
  }
  avatarUploading.value = true;
  avatarError.value = '';
  notice.value = '';
  try {
    const formData = new FormData();
    formData.append('avatarfile', avatarFile.value);
    const { baseUrl, token, clientId } = uploadContext();
    // 归一尾斜杠（与共享客户端 joinUrl 同语义；复核 P3 收口）。
    const base = baseUrl.replace(/\/+$/, '');
    // 空身份不发空头（与共享客户端 buildAuthHeaders"空则不写"同语义）。
    const headers: Record<string, string> = {};
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }
    if (clientId) {
      headers.ClientID = clientId;
    }
    const response = await fetch(`${base}/system/user/profile/avatar`, {
      method: 'POST',
      headers,
      body: formData,
    });
    const envelope = await response.json().catch(() => null) as {
      code?: number;
      msg?: string;
      data?: { imgUrl?: string };
    } | null;
    if (!envelope || envelope.code !== 200) {
      throw new Error(envelope?.msg || `HTTP ${response.status}`);
    }
    if (avatarBlobUrl) {
      URL.revokeObjectURL(avatarBlobUrl);
      avatarBlobUrl = '';
    }
    avatarPreview.value = envelope.data?.imgUrl || avatarPreview.value;
    avatarFile.value = null;
    notice.value = '头像已更新。';
    await load();
    syncForm();
  }
  catch (caught) {
    avatarError.value = caught instanceof Error ? caught.message : String(caught);
  }
  finally {
    avatarUploading.value = false;
  }
}

void load();
void loadSocials();
</script>

<template>
  <div>
    <ElAlert
      v-if="notice"
      data-testid="profile-notice"
      type="success"
      :closable="false"
      class="mb-4"
      :title="notice"
    />

    <ElCard class="mb-4">
      <template #header>
        <div class="toolbar">
          <span data-testid="profile-contract" class="card-header-meta">
            GET /system/user/profile（只读本人，无 @SaCheckPermission）
          </span>
          <div class="toolbar-actions">
            <ElButton data-testid="profile-reload" :loading="loading" @click="load().then(syncForm)">
              刷新
            </ElButton>
            <ElButton type="primary" data-testid="profile-pwd-open" @click="openPwd()">
              修改口令
            </ElButton>
          </div>
        </div>
      </template>

      <ElAlert
        v-if="error"
        data-testid="profile-error"
        :title="error"
        type="error"
        :closable="false"
        class="mb-4"
      />
      <div v-else-if="loading" data-testid="profile-loading" class="state-block">
        正在加载…
      </div>
      <div v-else-if="!loaded" data-testid="profile-idle" class="state-block">
        尚未加载。
      </div>
      <template v-else>
        <ElDescriptions data-testid="profile-facts" border :column="2" size="small" class="mb-4">
          <ElDescriptionsItem label="userName">
            {{ textOf(user, 'userName') || '—' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="昵称">
            {{ textOf(user, 'nickName') || '—' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="角色">
            {{ profile?.roleGroup || '—' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="岗位">
            {{ profile?.postGroup || '—' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="头像 URL">
            {{ textOf(user, 'avatar') || '—' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="手机">
            {{ textOf(user, 'phonenumber') || '—' }}
          </ElDescriptionsItem>
        </ElDescriptions>

        <div class="avatar-row" data-testid="profile-avatar">
          <img
            v-if="avatarPreview"
            data-testid="profile-avatar-preview"
            :src="avatarPreview"
            alt="头像预览"
            class="avatar-preview"
          >
          <input
            data-testid="profile-avatar-file"
            type="file"
            accept="image/png,image/jpeg,image/gif,image/webp"
            @change="onAvatarPicked"
          >
          <ElButton
            type="primary"
            :loading="avatarUploading"
            data-testid="profile-avatar-upload"
            @click="uploadAvatar()"
          >
            上传头像
          </ElButton>
        </div>
        <ElAlert
          v-if="avatarError"
          data-testid="profile-avatar-error"
          :title="avatarError"
          type="error"
          :closable="false"
          class="mb-4"
        />

        <ElForm label-width="90px" @submit.prevent>
          <ElFormItem label="昵称">
            <ElInput v-model="form.nickName" data-testid="profile-form-nickname" />
          </ElFormItem>
          <ElFormItem label="邮箱">
            <ElInput v-model="form.email" data-testid="profile-form-email" />
          </ElFormItem>
          <ElFormItem label="手机">
            <ElInput v-model="form.phonenumber" data-testid="profile-form-phone" />
          </ElFormItem>
          <ElFormItem label="性别">
            <ElSelect v-model="form.sex" style="width: 140px" data-testid="profile-form-sex">
              <ElOption label="男" value="0" />
              <ElOption label="女" value="1" />
              <ElOption label="未知" value="2" />
            </ElSelect>
          </ElFormItem>
        </ElForm>
        <ElButton type="primary" :loading="savingProfile" data-testid="profile-form-submit" @click="submitProfile">
          保存资料
        </ElButton>
      </template>
    </ElCard>

    <ElCard>
      <template #header>
        <span data-testid="social-contract" class="card-header-meta">
          GET /system/social/list（无 @SaCheckPermission）· 共 {{ socials.length }} 条
        </span>
      </template>
      <ElAlert
        v-if="socialError"
        data-testid="social-error"
        :title="socialError"
        type="error"
        :closable="false"
        class="mb-4"
      />
      <div v-else-if="socialLoading" data-testid="social-loading" class="state-block">
        正在加载社交关系…
      </div>
      <div v-else-if="!socialLoaded" data-testid="social-idle" class="state-block">
        尚未加载。
      </div>
      <div v-else-if="socials.length === 0" data-testid="social-empty" class="state-block">
        成功响应，没有绑定第三方账号。
      </div>
      <ElTable v-else data-testid="social-rows" :data="socials" border size="small">
        <ElTableColumn prop="source" label="平台" width="140" />
        <ElTableColumn prop="nickName" label="昵称" min-width="160" />
        <ElTableColumn prop="userName" label="账号" min-width="160" />
        <ElTableColumn prop="createTime" label="绑定时间" width="180" />
      </ElTable>
    </ElCard>

    <ElDialog v-model="pwdVisible" title="修改口令（PUT /system/user/profile/updatePwd）" width="460px">
      <ElAlert
        v-if="pwdError"
        data-testid="profile-pwd-error"
        type="error"
        :closable="false"
        class="mb-4"
        :title="pwdError"
      />
      <ElForm label-width="90px" @submit.prevent>
        <ElFormItem label="旧口令">
          <ElInput v-model="pwdForm.oldPassword" type="password" data-testid="profile-pwd-old" />
        </ElFormItem>
        <ElFormItem label="新口令">
          <ElInput v-model="pwdForm.newPassword" type="password" data-testid="profile-pwd-new" />
        </ElFormItem>
        <ElFormItem label="确认新口令">
          <ElInput v-model="pwdForm.confirmPassword" type="password" data-testid="profile-pwd-confirm" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="pwdVisible = false">
          取消
        </ElButton>
        <ElButton type="primary" :loading="pwdSaving" data-testid="profile-pwd-submit" @click="submitPwd">
          提交
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
}

.card-header-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.state-block {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.avatar-row {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.avatar-preview {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
}
</style>
