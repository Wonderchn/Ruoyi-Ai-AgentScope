export type MediaType = 'audio' | 'image' | 'video';

export interface MediaGenerationResponse {
  type?: MediaType;
  mimeType?: string;
  url?: string;
  b64Json?: string;
  dataUrl?: string;
  id?: string;
  status?: string;
  lastFrameUrl?: string;
  rawResponse?: string;
  /** Server task failure/transient message (already sanitized by the server). */
  message?: string;
  errorCode?: string;
  attempt?: number;
  previousTaskId?: string;
  sizeBytes?: number;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * F19 服务端媒体任务视图（`GET /media/tasks` / `GET /media/prediction`）。
 *
 * `status` is the stable wire status: `pending | processing | completed | failed | unknown`.
 * No provider URL is ever part of this contract: content is fetched through the
 * authenticated `/media/content` endpoint by task id.
 */
export interface MediaTaskDTO {
  id: string;
  type: MediaType;
  provider?: string;
  status: string;
  model: string;
  prompt: string;
  mimeType?: string | null;
  message?: string | null;
  errorCode?: string | null;
  attempt?: number;
  previousTaskId?: string | null;
  sizeBytes?: number | null;
  createdAt: string;
  updatedAt?: string;
}

export interface MediaTaskListDTO {
  items: MediaTaskDTO[];
}

export interface ImageGenerationDTO {
  model: string;
  prompt: string;
  size?: string;
  seed?: number;
  /** Client-generated idempotency key; the server never submits the same key twice. */
  requestId?: string;
}

export interface VideoGenerationDTO {
  model: string;
  prompt: string;
  size?: string;
  seconds?: number;
  quality?: string;
}

export interface SpeechGenerationDTO {
  model: string;
  input: string;
  voice?: string;
  responseFormat?: string;
  speed?: number;
  instructions?: string;
}
