import type { Citation, DocumentView, KnowledgeBaseView, RunSnapshot, UploadResult } from '@/api/rag';
/**
 * P2 RAG 运行态 store：知识库/文档/摄入任务/问答运行与引用。
 *
 * 切租户/退出时由 user store 的 clearRuntimeState 调用 resetRag，旧答案/进度不得写入新租户页面。
 */
import { defineStore } from 'pinia';
import { ref } from 'vue';

export const useRagStore = defineStore('rag', () => {
  const epoch = ref(0);
  const knowledgeBases = ref<KnowledgeBaseView[]>([]);
  const currentKbId = ref('');
  const documents = ref<DocumentView[]>([]);
  const upload = ref<UploadResult | null>(null);
  const uploadPercent = ref(0);
  const ingestRun = ref<RunSnapshot | null>(null);
  const chatRunId = ref('');
  const chatStatus = ref('');
  const answer = ref('');
  const citations = ref<Citation[]>([]);
  const steps = ref<Array<{ stepId: string; stepName: string; state: string }>>([]);
  const lastSeq = ref(0);
  const streamNote = ref('');
  const errorCode = ref('');

  function resetRag() {
    epoch.value++;
    knowledgeBases.value = [];
    currentKbId.value = '';
    documents.value = [];
    upload.value = null;
    uploadPercent.value = 0;
    ingestRun.value = null;
    chatRunId.value = '';
    chatStatus.value = '';
    answer.value = '';
    citations.value = [];
    steps.value = [];
    lastSeq.value = 0;
    streamNote.value = '';
    errorCode.value = '';
  }

  return {
    epoch,
    knowledgeBases,
    currentKbId,
    documents,
    upload,
    uploadPercent,
    ingestRun,
    chatRunId,
    chatStatus,
    answer,
    citations,
    steps,
    lastSeq,
    streamNote,
    errorCode,
    resetRag,
  };
});
