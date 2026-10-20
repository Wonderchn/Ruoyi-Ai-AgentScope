/**
 * Provider 花费信封余额。
 *
 * 路由是 `GET /api/ai/v1/runtime-config/spend`（复用已跑通的 JSON 转发分支）。
 * 刻意**不是** `/spend/balance`：那条走字节分支，要求响应带交付 permit 头，
 * 没有就判 `delivery receipt missing` → 503（服务端侧已有注释说明）。
 *
 * 服务端返回 `{enabled, executionId, capCny, reservedCny, remainingCny}`。
 * 读取失败一律返回 `null` —— 界面**不显示假余额**，也不阻塞输入。
 */
import { get } from '@/utils/request';

export const SPEND_BALANCE_PATH = '/api/ai/v1/runtime-config/spend';

export interface SpendBalance {
  enabled: boolean;
  executionId: string | null;
  capCny: number;
  reservedCny: number;
  remainingCny: number;
}

/**
 * 服务端用 BigDecimal 序列化，金额字段是**字符串**（`"0.998640"`），不是 number。
 * 这里显式转数字并校验有限值：拿到非数值宁可返回 null（不显示），也不渲染 NaN。
 */
function toAmount(value: unknown): number | null {
  const num = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(num) ? num : null;
}

export async function fetchSpendBalance(): Promise<SpendBalance | null> {
  try {
    const body = await get<{ data: Record<string, unknown> }>(SPEND_BALANCE_PATH).json();
    const data = (body as { data?: Record<string, unknown> } | null)?.data;
    if (!data)
      return null;
    const capCny = toAmount(data.capCny);
    const reservedCny = toAmount(data.reservedCny);
    const remainingCny = toAmount(data.remainingCny);
    if (capCny === null || remainingCny === null || reservedCny === null)
      return null;
    return {
      enabled: data.enabled === true,
      executionId: typeof data.executionId === 'string' ? data.executionId : null,
      capCny,
      reservedCny,
      remainingCny,
    };
  }
  catch {
    return null;
  }
}
