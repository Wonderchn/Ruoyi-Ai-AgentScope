import type { EChartsOption } from 'echarts';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isJsonValue(value: unknown): boolean {
  if (value === null || typeof value === 'string' || typeof value === 'boolean')
    return true;
  if (typeof value === 'number')
    return Number.isFinite(value);
  if (Array.isArray(value))
    return value.every(isJsonValue);
  if (isRecord(value)) {
    return Object.entries(value).every(([key, item]) =>
      !['__proto__', 'prototype', 'constructor'].includes(key) && isJsonValue(item));
  }
  return false;
}

/** Parse model output as data only. Never evaluate generated JavaScript/formatter functions. */
export function parseEChartsOption(input: unknown): EChartsOption | null {
  try {
    let value = input;
    if (typeof input === 'string') {
      const text = input.trim();
      const block = /^```(?:echarts|json)?[^\S\r\n]*\r?\n([\s\S]*?)\r?\n```$/i.exec(text);
      value = JSON.parse(block ? block[1] : text);
    }
    if (!isRecord(value) || !isJsonValue(value))
      return null;
    const series = Array.isArray(value.series) ? value.series : [value.series];
    if (!series.length || !series.every(item => isRecord(item) && typeof item.type === 'string'))
      return null;
    return value as EChartsOption;
  }
  catch {
    return null;
  }
}
