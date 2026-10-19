import type {
  ImageGenerationDTO,
  MediaGenerationResponse,
  MediaTaskDTO,
  MediaTaskListDTO,
  SpeechGenerationDTO,
  VideoGenerationDTO,
} from './types';
import { get, post } from '@/utils/request';
import {
  MEDIA_IMAGE_PATH,
  MEDIA_PREDICTION_PATH,
  MEDIA_SPEECH_PATH,
  MEDIA_TASKS_PATH,
  MEDIA_VIDEO_PATH,
  mediaTaskPath,
  mediaTaskRetryPath,
} from './paths';
import { readMediaApiResult } from './response';

export function generateImage(data: ImageGenerationDTO) {
  return readMediaApiResult<MediaTaskDTO>(post<MediaTaskDTO>(MEDIA_IMAGE_PATH, data, { timeout: 180000 }).json());
}

/** F19:2 video submission is a server task: the response is the task authority view. */
export function generateVideo(data: VideoGenerationDTO) {
  return readMediaApiResult<MediaTaskDTO>(post<MediaTaskDTO>(MEDIA_VIDEO_PATH, data, { timeout: 180000 }).json());
}

/** F19:0 speech submission is synchronous on the provider; the response is still a server task. */
export function generateSpeech(data: SpeechGenerationDTO) {
  return readMediaApiResult<MediaTaskDTO>(post<MediaTaskDTO>(MEDIA_SPEECH_PATH, data, { timeout: 180000 }).json());
}

export function getPrediction(params: { model: string; predictionId: string }) {
  return readMediaApiResult<MediaGenerationResponse>(get<MediaGenerationResponse>(MEDIA_PREDICTION_PATH, params, { timeout: 30000 }).json());
}

export function getVideoResult(params: { model: string; videoId: string }) {
  return readMediaApiResult<MediaGenerationResponse>(get<MediaGenerationResponse>(MEDIA_VIDEO_PATH, params, { timeout: 30000 }).json());
}

/** F19:3 server task list; the page must not treat page memory as the authority. */
export function listMediaTasks(limit = 20) {
  return readMediaApiResult<MediaTaskListDTO>(get<MediaTaskListDTO>(MEDIA_TASKS_PATH, { limit }, { timeout: 30000 }).json());
}

/** Task detail (poll/refresh path). */
export function getMediaTask(taskId: string) {
  return readMediaApiResult<MediaTaskDTO>(get<MediaTaskDTO>(mediaTaskPath(taskId), {}, { timeout: 30000 }).json());
}

/** Explicit retry: new attempt, linked to the previous task id by the server. */
export function retryMediaTask(taskId: string, requestId: string) {
  return readMediaApiResult<MediaTaskDTO>(
    post<MediaTaskDTO>(mediaTaskRetryPath(taskId), { requestId }, { timeout: 60000 }).json(),
  );
}
