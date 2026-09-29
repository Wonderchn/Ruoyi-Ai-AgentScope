<!-- 文件上传 -->
<script setup lang="ts">
import type { FilesCardProps } from 'vue-element-plus-x/types/FilesCard';
import { useFileDialog } from '@vueuse/core';
import { ElMessage } from 'element-plus';
import Popover from '@/components/Popover/index.vue';
import { useFilesStore } from '@/stores/modules/files';

type FilesList = FilesCardProps & {
  file: File;
};

const filesStore = useFilesStore();

/* 弹出面板 开始 */
const popoverStyle = ref({
  padding: '4px',
  height: 'fit-content',
  background: 'var(--el-bg-color, #fff)',
  border: '1px solid var(--el-border-color-light)',
  borderRadius: '8px',
  boxShadow: '0 2px 12px 0 rgba(0, 0, 0, 0.1)',
});
const popoverRef = ref();
/* 弹出面板 结束 */

const { reset, open, onChange } = useFileDialog({
  // 允许所有图片文件，文档文件，音视频文件
  accept: 'image/*,video/*,audio/*,application/*',
  directory: false, // 是否允许选择文件夹
  multiple: true, // 是否允许多选
});

onChange((files) => {
  if (!files)
    return;
  console.log('files', files);
  const arr = [] as FilesList[];
  for (let i = 0; i < files!.length; i++) {
    const file = files![i];
    arr.push({
      uid: crypto.randomUUID(), // 不写 uid，文件列表展示不出来，elx 1.2.0 bug 待修复
      name: file.name,
      fileSize: file.size,
      file,
      maxWidth: '200px',
      showDelIcon: true, // 显示删除图标
      imgPreview: true, // 显示图片预览
      imgVariant: 'square', // 图片预览的形状
      url: URL.createObjectURL(file), // 图片预览地址
    });
  }
  filesStore.setFilesList([...filesStore.filesList, ...arr]);
  // 重置文件选择器
  nextTick(() => reset());
});

function handleUploadFiles() {
  open();
  popoverRef.value.hide();
}
</script>

<template>
  <div class="files-select">
    <Popover
      ref="popoverRef"
      placement="top-start"
      :offset="[4, 0]"
      popover-class="popover-content"
      :popover-style="popoverStyle"
      trigger="clickTarget"
    >
      <template #trigger>
        <div class="files-select-btn">
          <el-icon class="files-icon">
            <Paperclip />
          </el-icon>
        </div>
      </template>

      <div class="popover-content-box">
        <div
          class="popover-content-item flex items-center gap-4px p-10px rounded-10px cursor-pointer font-size-14px hover:bg-[rgba(0,0,0,.04)]"
          @click="handleUploadFiles"
        >
          <el-icon>
            <Upload />
          </el-icon>
          <div class="font-size-14px">
            上传文件或图片
          </div>
        </div>

        <Popover
          placement="right-end"
          :offset="[8, 4]"
          popover-class="popover-content"
          :popover-style="popoverStyle"
          trigger="hover"
          :hover-delay="100"
        >
          <!--          <template #trigger> -->
          <!--            <div -->
          <!--              class="popover-content-item flex items-center gap-4px p-10px rounded-10px cursor-pointer font-size-14px hover:bg-[rgba(0,0,0,.04)]" -->
          <!--            > -->
          <!--              <SvgIcon name="code" size="16" /> -->
          <!--              <div class="font-size-14px"> -->
          <!--                上传代码 -->
          <!--              </div> -->

          <!--              <el-icon class="ml-auto"> -->
          <!--                <ArrowRight /> -->
          <!--              </el-icon> -->
          <!--            </div> -->
          <!--          </template> -->

          <div class="popover-content-box">
            <div
              class="popover-content-item flex items-center gap-4px p-10px rounded-10px cursor-pointer font-size-14px hover:bg-[rgba(0,0,0,.04)]"
              @click="
                () => {
                  ElMessage.warning('暂未开放');
                }
              "
            >
              代码文件
            </div>

            <div
              class="popover-content-item flex items-center gap-4px p-10px rounded-10px cursor-pointer font-size-14px hover:bg-[rgba(0,0,0,.04)]"
              @click="
                () => {
                  ElMessage.warning('暂未开放');
                }
              "
            >
              代码文件夹
            </div>
          </div>
        </Popover>
      </div>
    </Popover>
  </div>
</template>

<style scoped lang="scss">
.files-select-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  cursor: pointer;
  user-select: none;
  background-color: #fff;
  border: 1px solid rgb(0 0 0 / 10%);
  border-radius: 50%;
  transition: all 0.2s ease;

  &:hover {
    background-color: rgb(0 0 0 / 4%);
    border-color: rgb(0 0 0 / 15%);
  }

  .files-icon {
    width: 14px;
    height: 14px;
    font-size: 14px;
    color: rgb(0 0 0 / 65%);
  }
}
</style>
