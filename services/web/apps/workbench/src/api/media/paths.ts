const MEDIA_PREFIX = '/media';

export const MEDIA_IMAGE_PATH = `${MEDIA_PREFIX}/image`;
export const MEDIA_SPEECH_PATH = `${MEDIA_PREFIX}/speech`;
export const MEDIA_VIDEO_PATH = `${MEDIA_PREFIX}/video`;
export const MEDIA_PREDICTION_PATH = `${MEDIA_PREFIX}/prediction`;
export const MEDIA_TASKS_PATH = `${MEDIA_PREFIX}/tasks`;
export const MEDIA_CONTENT_PATH = `${MEDIA_PREFIX}/content`;

/** Server task detail path (task id is opaque; encode it, never build it from user text). */
export function mediaTaskPath(taskId: string): string {
  return `${MEDIA_TASKS_PATH}/${encodeURIComponent(taskId)}`;
}

/** Explicit retry creates a new server attempt; the old task id stays immutable. */
export function mediaTaskRetryPath(taskId: string): string {
  return `${mediaTaskPath(taskId)}/retry`;
}

/** Controlled content query: task id is the only resource reference (no provider URLs). */
export function mediaContentQuery(params: { model?: string; predictionId: string }): string {
  const query = new URLSearchParams();
  if (params.model)
    query.set('model', params.model);
  query.set('predictionId', params.predictionId);
  return `${MEDIA_CONTENT_PATH}?${query}`;
}
