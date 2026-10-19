/**
 * F19:3/F19:4 前端接线判据（服务端任务权威 + 受控取流）。
 *
 * 关键纪律：
 * - 任务列表/刷新只映射服务端 DTO；服务端没有的任务不会从内存"复活"；
 * - `unknown` 是终态：只能显式重试，不能被当成 success；
 * - 结果视图不含 provider URL；成功图片只能按任务 ID 走受控 content 端点；
 * - 任务/重试路径由同一组纯函数生成，测试钉住形状。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  MEDIA_CONTENT_PATH,
  MEDIA_IMAGE_PATH,
  MEDIA_PREDICTION_PATH,
  MEDIA_SPEECH_PATH,
  MEDIA_TASKS_PATH,
  MEDIA_VIDEO_PATH,
  mediaContentQuery,
  mediaTaskPath,
  mediaTaskRetryPath,
} from '../src/api/media/paths.ts';
import {
  applyServerOutcome,
  canRetryServerTask,
  mergeServerTasks,
  needsControlledContent,
  toMediaTask,
  wireStatusToState,
} from '../src/pages/media/server-tasks.ts';
import { supportsMedia } from '../src/pages/media/utils.ts';

const BASE_DTO = {
  id: 'mt-1',
  type: 'image',
  provider: 'Tongyiwanx',
  status: 'completed',
  model: 'wanx-test',
  prompt: 'a red fox',
  mimeType: 'image/png',
  message: null,
  errorCode: null,
  attempt: 1,
  previousTaskId: null,
  sizeBytes: 11,
  createdAt: '2026-10-19T00:00:00Z',
  updatedAt: '2026-10-19T00:00:01Z',
};

describe('服务端状态映射（不把未知当成功）', () => {
  it('completed 映射为 success 且只保留任务 id，不带 provider URL', () => {
    const task = toMediaTask(BASE_DTO);
    assert.equal(task.state, 'success');
    assert.equal(task.serverId, 'mt-1');
    assert.equal(task.key, 'mt-1');
    assert.equal(task.result?.id, 'mt-1');
    assert.equal(task.result?.mimeType, 'image/png');
    assert.equal(task.result?.url, undefined);
    assert.equal(task.terminalUnknown, false);
  });

  it('pending/processing 正在生成；failed 带原因；unknown 是终态且只可重试', () => {
    assert.equal(wireStatusToState('pending'), 'waiting');
    assert.equal(wireStatusToState('processing'), 'waiting');
    assert.equal(wireStatusToState('failed'), 'failed');
    assert.equal(wireStatusToState('unknown'), 'paused');
    assert.equal(wireStatusToState('some-new-status'), 'paused');

    const failed = toMediaTask({ ...BASE_DTO, status: 'failed', errorCode: 'PROVIDER_FAILED', message: 'content policy' });
    assert.equal(failed.state, 'failed');
    assert.equal(failed.message, 'content policy');
    assert.equal(canRetryServerTask(failed), true);

    const unknown = toMediaTask({ ...BASE_DTO, status: 'unknown', message: '结果未知' });
    assert.equal(unknown.state, 'paused');
    assert.equal(unknown.terminalUnknown, true);
    assert.equal(canRetryServerTask(unknown), true);

    const waiting = toMediaTask({ ...BASE_DTO, status: 'processing' });
    assert.equal(canRetryServerTask(waiting), false);
  });
});

describe('轮询结果合并', () => {
  it('unknown 轮询结果覆盖旧 success，不能被旧结果掩盖', () => {
    const task = toMediaTask(BASE_DTO);
    const merged = applyServerOutcome(task, { id: 'mt-1', status: 'unknown', message: '结果未知' });
    assert.equal(merged.state, 'paused');
    assert.equal(merged.terminalUnknown, true);
    assert.equal(merged.result?.id, 'mt-1');
  });

  it('processing 保留任务 id，后续轮询仍可查询', () => {
    const task = toMediaTask({ ...BASE_DTO, status: 'pending' });
    const merged = applyServerOutcome(task, { id: 'mt-1', status: 'processing' });
    assert.equal(merged.state, 'waiting');
    assert.equal(merged.result?.id, 'mt-1');
    assert.equal(canRetryServerTask(merged), false);
  });
});

describe('任务列表以服务端为准', () => {
  it('刷新替换服务端任务，保留尚未提交完成的本地任务', () => {
    const server = toMediaTask(BASE_DTO);
    const removed = toMediaTask({ ...BASE_DTO, id: 'mt-old', status: 'failed' });
    const local = {
      key: 'local-audio',
      kind: 'audio',
      model: 'legacy',
      modelLabel: 'legacy',
      provider: 'openai',
      prompt: 'hello',
      createdAt: 1,
      state: 'failed',
      message: 'legacy local task',
    };
    const merged = mergeServerTasks([server, removed, local], [BASE_DTO]);
    assert.deepEqual(merged.map(task => task.key), ['mt-1', 'local-audio']);
  });
});

describe('受控取流（按任务 ID，不接受 provider 裸 URL）', () => {
  it('仅成功的受控 provider 任务需要走 content 端点', () => {
    const success = toMediaTask(BASE_DTO);
    assert.equal(needsControlledContent(success), true);
    assert.equal(needsControlledContent({ ...success, state: 'waiting' }), false);
    assert.equal(needsControlledContent({ ...success, provider: 'openai' }), false);
    assert.equal(needsControlledContent({ ...success, result: undefined }), false);
  });

  it('音频/视频任务同样只经受控 content 端点取字节', () => {
    const audio = toMediaTask({ ...BASE_DTO, id: 'mt-a1', type: 'audio', mimeType: 'audio/wav', prompt: 'hello' });
    const video = toMediaTask({ ...BASE_DTO, id: 'mt-v1', type: 'video', mimeType: 'video/mp4', prompt: 'a cat' });
    assert.equal(audio.state, 'success');
    assert.equal(audio.kind, 'audio');
    assert.equal(audio.result?.url, undefined);
    assert.equal(needsControlledContent(audio), true);
    assert.equal(video.state, 'success');
    assert.equal(video.kind, 'video');
    assert.equal(needsControlledContent(video), true);
  });

  it('DashScope 媒体模型（Tongyiwanx）在图片/语音/视频三种类型均可提交', () => {
    assert.equal(supportsMedia('Tongyiwanx', 'image'), true);
    assert.equal(supportsMedia('Tongyiwanx', 'audio'), true);
    assert.equal(supportsMedia('Tongyiwanx', 'video'), true);
    assert.equal(supportsMedia('unknown-provider', 'audio'), false);
  });

  it('任务/重试/取流路径形状固定且 task id 被编码', () => {
    assert.equal(MEDIA_IMAGE_PATH, '/media/image');
    assert.equal(MEDIA_PREDICTION_PATH, '/media/prediction');
    assert.equal(MEDIA_SPEECH_PATH, '/media/speech');
    assert.equal(MEDIA_VIDEO_PATH, '/media/video');
    assert.equal(MEDIA_TASKS_PATH, '/media/tasks');
    assert.equal(MEDIA_CONTENT_PATH, '/media/content');
    assert.equal(mediaTaskPath('mt-1'), '/media/tasks/mt-1');
    assert.equal(mediaTaskPath('mt/1'), '/media/tasks/mt%2F1');
    assert.equal(mediaTaskRetryPath('mt-1'), '/media/tasks/mt-1/retry');
    assert.equal(
      mediaContentQuery({ model: 'wanx-test', predictionId: 'mt-1' }),
      '/media/content?model=wanx-test&predictionId=mt-1',
    );
    assert.equal(mediaContentQuery({ predictionId: 'mt-1' }), '/media/content?predictionId=mt-1');
  });
});
