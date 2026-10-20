/**
 * 租户 AI 用量面：运行统计、质量、花费信封与最近链路。
 *
 * 端点全部经 `/api/ai/v1` 网关白名单：
 * - `GET /dashboard/overview?window=24h`（run.get）
 * - `GET /dashboard/performance?window=24h`（run.get）
 * - `GET /usage/summary?days=`（run.get）——按模型/按天的调用与 token，外加租户运行配额
 * - `GET /runtime-config/spend`（config.read）
 * - `GET /rag/traces/runs?current=&size=`（run.get）
 *
 * 所有读取都按当前租户限域（内层 service 使用 tenantWideCurrent）。
 */
import type { PlatformClient } from '@ruoyi/platform-client/http';

export interface DashboardKpi {
  value?: number | null;
  delta?: number | null;
  deltaPct?: number | null;
}

export interface DashboardOverview {
  engine?: string;
  window?: string;
  compareWindow?: string;
  updatedAt?: number;
  kpis?: Partial<Record<
    'totalUsers' | 'activeUsers' | 'totalSessions' | 'sessions24h'
    | 'totalMessages' | 'messages24h' | 'activeSessions',
    DashboardKpi
  >>;
}

export interface DashboardPerformance {
  engine?: string;
  window?: string;
  avgLatencyMs?: number | null;
  p95LatencyMs?: number | null;
  successRate?: number | null;
  errorRate?: number | null;
  noDocRate?: number | null;
  slowRate?: number | null;
  sampleCount?: number | null;
}

export interface SpendSnapshot {
  enabled?: boolean;
  executionId?: string | null;
  capCny?: number | string | null;
  reservedCny?: number | string | null;
  remainingCny?: number | string | null;
}

export interface RagTraceRun {
  traceId?: string;
  traceName?: string;
  entryMethod?: string;
  conversationId?: string;
  taskId?: string;
  userId?: string;
  username?: string;
  status?: string;
  errorMessage?: string;
  durationMs?: number | null;
  ttftMs?: number | null;
  question?: string;
  startTime?: string;
  endTime?: string;
  promptTokens?: number | null;
  completionTokens?: number | null;
  totalTokens?: number | null;
}

export interface UsageSummaryRow {
  provider?: string;
  model?: string;
  day?: string;
  calls?: number | string;
  tokens?: number | string;
  reserved_cny?: number | string;
}

/**
 * 租户运行配额（合成单位）。
 *
 * `settledUnits` 是已结算消耗，`heldUnits` 是在途预占，`remainingUnits` 是准入层
 * 还能放行的额度；`configured=false` 表示该租户尚未建 `ai_tenant_budget` 行，
 * 数值来自部署默认值。
 */
export interface TenantBudget {
  unit?: string;
  configured?: boolean;
  limitUnits?: number | string;
  settledUnits?: number | string;
  heldUnits?: number | string;
  remainingUnits?: number | string;
  activeHolds?: number | string;
  utilizationPct?: number | string;
}

export interface UsageSummary {
  windowDays?: number;
  tenantId?: string;
  amountKind?: string;
  totals?: UsageSummaryRow;
  byModel?: UsageSummaryRow[];
  byDay?: UsageSummaryRow[];
  budget?: TenantBudget | null;
}

export interface RagTracePage {
  records?: RagTraceRun[];
  total?: number;
  current?: number;
  size?: number;
}

export function createUsageApi(client: PlatformClient) {
  return {
    overview: (window: string) =>
      client.get<DashboardOverview>('/api/ai/v1/dashboard/overview', { query: { window } }),
    performance: (window: string) =>
      client.get<DashboardPerformance>('/api/ai/v1/dashboard/performance', { query: { window } }),
    spend: () => client.get<SpendSnapshot>('/api/ai/v1/runtime-config/spend'),
    summary: (days: number) =>
      client.get<UsageSummary>('/api/ai/v1/usage/summary', { query: { days } }),
    traces: (query: { current?: number; size?: number } = {}) =>
      client.get<RagTracePage>('/api/ai/v1/rag/traces/runs', {
        query: { current: query.current ?? 1, size: query.size ?? 10 },
      }),
  };
}

export type UsageApi = ReturnType<typeof createUsageApi>;
