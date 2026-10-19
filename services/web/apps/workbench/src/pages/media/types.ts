import type { MediaGenerationResponse, MediaType } from '@/api/media/types';

export interface MediaDraft {
  model: string;
  prompt: string;
  size: string;
  seed?: number;
  voice: string;
  responseFormat: string;
  speed?: number;
  instructions: string;
  seconds?: number;
  quality: string;
}

export interface MediaModel {
  modelName: string;
  label: string;
  providerCode: string;
  supported: boolean;
}

export type TaskState = 'submitting' | 'waiting' | 'success' | 'failed' | 'paused';

export interface MediaTask {
  key: string;
  /** Server task authority id; present for every task read from or submitted to the server. */
  serverId?: string;
  kind: MediaType;
  model: string;
  modelLabel: string;
  provider: string;
  prompt: string;
  createdAt: number;
  state: TaskState;
  message: string;
  errorCode?: string;
  attempt?: number;
  previousTaskId?: string;
  /** Server status is UNKNOWN (or unrecognized): terminal for polling, explicit retry only. */
  terminalUnknown?: boolean;
  result?: MediaGenerationResponse;
}
